package maple.expectation.global.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.global.error.exception.CompensationSyncException;
import maple.expectation.global.event.MySQLUpEvent;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.redisson.api.StreamMessageId;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Compensation Sync Scheduler (Issue #218)
 *
 * <p>MySQL 복구 시 Compensation Log를 DB에 동기화합니다.</p>
 *
 * <h4>동기화 흐름</h4>
 * <ol>
 *   <li>MySQLUpEvent 수신</li>
 *   <li>Consumer Group으로 XREAD (batch-size: 100)</li>
 *   <li>배치로 DB에 저장</li>
 *   <li>성공: XACK + XDEL</li>
 *   <li>실패: 3회 재시도 → DLQ 이동</li>
 * </ol>
 *
 * <h4>재시도 정책 (P1-4)</h4>
 * <ul>
 *   <li>Exponential backoff: 1s, 2s, 4s</li>
 *   <li>3회 실패 시 DLQ로 이동</li>
 *   <li>DLQ 이동 시 Discord 알림</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationSyncScheduler {

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_RETRY_COUNT = "retryCount";

    private static final String TYPE_EQUIPMENT = "equipment";

    private final CompensationLogService compensationLogService;
    private final CharacterEquipmentRepository equipmentRepository;
    private final MySQLHealthEventPublisher healthEventPublisher;
    private final MySQLFallbackProperties properties;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;
    private final DiscordAlertService discordAlertService;

    /**
     * MySQL UP 이벤트 처리 (P0-N4: @Async + @EventListener)
     */
    @Async
    @EventListener
    public void onMySQLUp(MySQLUpEvent event) {
        executor.executeVoid(() -> {
            log.info("[CompensationSync] MySQL UP 이벤트 수신 - 동기화 시작: {}", event);

            String consumerId = generateConsumerId();
            int totalSynced = 0;
            int totalFailed = 0;

            // 배치 단위로 동기화 반복
            while (true) {
                Map<StreamMessageId, Map<String, String>> messages =
                        compensationLogService.readLogs(consumerId, properties.getSyncBatchSize());

                if (messages == null || messages.isEmpty()) {
                    log.info("[CompensationSync] 동기화 완료 - 더 이상 메시지 없음");
                    break;
                }

                SyncResult result = syncBatch(messages);
                totalSynced += result.successCount();
                totalFailed += result.failedCount();

                log.info("[CompensationSync] 배치 동기화: success={}, failed={}", result.successCount(), result.failedCount());
            }

            // 동기화 완료 후 HEALTHY 전이
            if (totalFailed == 0) {
                healthEventPublisher.markRecoveryComplete();
            } else {
                log.warn("[CompensationSync] 일부 실패 항목 존재 - DLQ 확인 필요: failed={}", totalFailed);
                sendDlqAlert(totalFailed);
            }

            log.info("[CompensationSync] 전체 동기화 완료: synced={}, failed={}", totalSynced, totalFailed);

        }, TaskContext.of("Compensation", "OnMySQLUp", event.circuitBreakerName()));
    }

    /**
     * 배치 동기화 수행
     */
    @Transactional
    public SyncResult syncBatch(Map<StreamMessageId, Map<String, String>> messages) {
        List<StreamMessageId> successIds = new ArrayList<>();
        int failedCount = 0;

        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            StreamMessageId messageId = entry.getKey();
            Map<String, String> data = entry.getValue();

            boolean success = syncSingleEntry(messageId, data);
            if (success) {
                successIds.add(messageId);
            } else {
                failedCount++;
            }
        }

        // 성공한 메시지 ACK
        if (!successIds.isEmpty()) {
            compensationLogService.ackLogs(successIds);
        }

        return new SyncResult(successIds.size(), failedCount);
    }

    /**
     * 단일 엔트리 동기화 (재시도 포함)
     */
    private boolean syncSingleEntry(StreamMessageId messageId, Map<String, String> data) {
        int retryCount = Integer.parseInt(data.getOrDefault(FIELD_RETRY_COUNT, "0"));
        String type = data.get(FIELD_TYPE);
        String key = data.get(FIELD_KEY);

        for (int attempt = retryCount; attempt < properties.getSyncMaxRetries(); attempt++) {
            boolean success = executor.executeOrDefault(() -> {
                performSync(type, key, data.get(FIELD_DATA));
                return true;
            }, false, TaskContext.of("Compensation", "SyncEntry", messageId.toString()));

            if (success) {
                log.debug("[CompensationSync] 동기화 성공: messageId={}, type={}, key={}", messageId, type, key);
                return true;
            }

            // 재시도 전 대기 (exponential backoff: 1s, 2s, 4s)
            waitBeforeRetry(attempt);
            log.warn("[CompensationSync] 동기화 재시도: messageId={}, attempt={}/{}", messageId, attempt + 1, properties.getSyncMaxRetries());
        }

        // 최대 재시도 초과 → DLQ 이동
        String errorMessage = String.format("Max retries exceeded (%d)", properties.getSyncMaxRetries());
        compensationLogService.moveToDlq(messageId, data, errorMessage);
        log.error("[CompensationSync] DLQ 이동: messageId={}, reason={}", messageId, errorMessage);

        return false;
    }

    /**
     * 실제 DB 동기화 수행
     */
    private void performSync(String type, String key, String jsonData) throws Exception {
        if (TYPE_EQUIPMENT.equals(type)) {
            syncEquipment(key, jsonData);
        } else {
            log.warn("[CompensationSync] 알 수 없는 타입: type={}, key={}", type, key);
        }
    }

    /**
     * Equipment 데이터 동기화
     */
    private void syncEquipment(String ocid, String jsonData) {
        CharacterEquipment equipment = equipmentRepository.findById(ocid)
                .orElse(null);

        if (equipment != null) {
            equipment.updateData(jsonData);
        } else {
            equipment = CharacterEquipment.builder()
                    .ocid(ocid)
                    .jsonContent(jsonData)
                    .build();
        }

        equipmentRepository.save(equipment);
        log.debug("[CompensationSync] Equipment 저장 완료: ocid={}", ocid);
    }

    /**
     * 재시도 전 대기 (exponential backoff)
     */
    private void waitBeforeRetry(int attempt) {
        executor.executeVoid(() -> {
            long waitMs = (long) Math.pow(2, attempt) * 1000; // 1s, 2s, 4s
            TimeUnit.MILLISECONDS.sleep(waitMs);
        }, TaskContext.of("Compensation", "WaitBackoff", String.valueOf(attempt)));
    }

    /**
     * Consumer ID 생성
     */
    private String generateConsumerId() {
        return "sync-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * DLQ 알림 발송 (P1-N6)
     */
    private void sendDlqAlert(int failedCount) {
        executor.executeOrDefault(() -> {
            String title = "⚠️ Compensation Sync DLQ 발생";
            String description = String.format(
                    "동기화 실패 항목이 DLQ로 이동되었습니다.\n실패 건수: %d\nTimestamp: %s",
                    failedCount, Instant.now()
            );
            discordAlertService.sendCriticalAlert(title, description, new CompensationSyncException("DLQ Alert"));
            return null;
        }, null, TaskContext.of("Compensation", "SendDlqAlert", String.valueOf(failedCount)));
    }

    /**
     * 동기화 결과 레코드
     */
    public record SyncResult(int successCount, int failedCount) {}
}
