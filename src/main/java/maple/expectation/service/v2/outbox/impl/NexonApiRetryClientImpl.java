package maple.expectation.service.v2.outbox.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterBasicResponse;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.executor.CheckedLogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.util.ExceptionUtils;
import maple.expectation.service.v2.outbox.NexonApiOutboxMetrics;
import maple.expectation.service.v2.outbox.NexonApiRetryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nexon API 재시도 클라이언트 구현 (N19)
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>Outbox에 적재된 실패한 API 호출을 재시도</li>
 *   <li>Event Type에 따라 적절한 NexonApiClient 메서드 호출</li>
 *   <li>성공/실패 결과를 반환하여 Processor가 상태 업데이트</li>
 * </ul>
 *
 * <h3>예외 처리 정책</h3>
 * <ul>
 *   <li>4xx 오류: 비즈니스 예외로 간주, 재시도 무의미 → 실패 반환</li>
 *   <li>5xx/네트워크 오류: 일시적 장애로 간주, 재시도 의미 있음 → 실패 반환 (Processor가 재시도)</li>
 *   <li>타임아웃: 10초 타임아웃 적용</li>
 * </ul>
 *
 * @see maple.expectation.service.v2.outbox.NexonApiOutboxProcessor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NexonApiRetryClientImpl implements NexonApiRetryClient {

    private final NexonApiClient nexonApiClient;
    private final CheckedLogicExecutor checkedExecutor;
    private final NexonApiOutboxMetrics metrics;
    private final ObjectMapper objectMapper;

    /** API 호출 타임아웃 (초) */
    private static final long API_TIMEOUT_SECONDS = 10L;

    @Override
    public boolean processOutboxEntry(NexonApiOutbox outbox) {
        TaskContext context = TaskContext.of("NexonApiRetry", "ProcessEntry", outbox.getRequestId());

        return checkedExecutor.executeUnchecked(
                () -> doRetry(outbox),
                context,
                e -> new RuntimeException("Outbox retry failed", e)
        );
    }

    /**
     * Outbox 항목 재시도 로직
     *
     * <p>Event Type에 따라 적절한 API 메서드 호출</p>
     */
    private boolean doRetry(NexonApiOutbox outbox) {
        NexonApiOutbox.NexonApiEventType eventType = outbox.getEventType();
        String payload = outbox.getPayload();

        log.info("[Retry] Outbox 항목 재시도: requestId={}, eventType={}",
                outbox.getRequestId(), eventType);

        try {
            return switch (eventType) {
                case GET_OCID -> retryGetOcid(payload);
                case GET_CHARACTER_BASIC -> retryGetCharacterBasic(payload);
                case GET_ITEM_DATA -> retryGetItemData(payload);
                case GET_CUBES -> retryGetCubes(payload);
            };
        } catch (Exception e) {
            log.error("[Retry] 재시도 실패: requestId={}, eventType={}",
                    outbox.getRequestId(), eventType, e);
            metrics.incrementApiCallRetry();
            return false;
        }
    }

    /**
     * OCID 조회 재시도
     */
    private boolean retryGetOcid(String characterName) {
        try {
            CharacterOcidResponse response = nexonApiClient.getOcidByCharacterName(characterName)
                    .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();

            log.info("[Retry] OCID 조회 성공: name={}, ocid={}", characterName, response.getOcid());
            metrics.incrementApiCallSuccess();
            return true;
        } catch (Exception e) {
            return handleRetryFailure("GET_OCID", characterName, e);
        }
    }

    /**
     * 캐릭터 기본 정보 조회 재시도
     */
    private boolean retryGetCharacterBasic(String ocid) {
        try {
            CharacterBasicResponse response = nexonApiClient.getCharacterBasic(ocid)
                    .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();

            log.info("[Retry] Character Basic 조회 성공: ocid={}, world={}",
                    ocid, response.getWorldName());
            metrics.incrementApiCallSuccess();
            return true;
        } catch (Exception e) {
            return handleRetryFailure("GET_CHARACTER_BASIC", ocid, e);
        }
    }

    /**
     * 장비 데이터 조회 재시도
     */
    private boolean retryGetItemData(String ocid) {
        try {
            EquipmentResponse response = nexonApiClient.getItemDataByOcid(ocid)
                    .orTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();

            log.info("[Retry] Item Data 조회 성공: ocid={}", ocid);
            metrics.incrementApiCallSuccess();
            return true;
        } catch (Exception e) {
            return handleRetryFailure("GET_ITEM_DATA", ocid, e);
        }
    }

    /**
     * 큐브 데이터 조회 재시도 (TODO: 추후 구현)
     */
    private boolean retryGetCubes(String ocid) {
        log.warn("[Retry] GET_CUBES 아직 미구현: ocid={}", ocid);
        return false;
    }

    /**
     * 재시도 실패 처리
     *
     * <p>4xx 오류: 재시도 무의미 → 실패 반환</p>
     * <p>5xx/네트워크 오류: 일시적 장애 → 실패 반환 (Processor가 재시도)</p>
     */
    private boolean handleRetryFailure(String eventType, String payload, Exception e) {
        Throwable root = ExceptionUtils.unwrapAsyncException(e);

        // 4xx 클라이언트 오류: 재시도 무의미
        if (root instanceof WebClientResponseException wce && wce.getStatusCode().is4xxClientError()) {
            log.warn("[Retry] 4xx 오류로 재시도 중단: eventType={}, status={}, payload={}",
                    eventType, wce.getStatusCode(), maskPayload(payload));
            return false;
        }

        // 5xx/네트워크/타임아웃: 일시적 장애로 간주, 실패 반환 (Processor가 계속 재시도)
        log.warn("[Retry] 일시적 장애 발생: eventType={}, payload={}, error={}",
                eventType, maskPayload(payload), root.getMessage());
        metrics.incrementApiCallRetry();
        return false;
    }

    /**
     * PII 마스킹 (로그 안전성 확보)
     */
    private String maskPayload(String payload) {
        if (payload == null || payload.length() <= 4) {
            return "***";
        }
        return payload.substring(0, 4) + "***";
    }
}
