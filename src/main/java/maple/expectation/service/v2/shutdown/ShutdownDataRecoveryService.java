package maple.expectation.service.v2.shutdown;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.service.v2.LikeSyncExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shutdown 시 백업된 데이터를 복구하는 서비스
 * <p>
 * 애플리케이션 재시작 시 {@link PostConstruct}를 통해 자동으로 실행되며,
 * 백업 디렉토리에서 백업 파일을 읽어 데이터를 복구합니다.
 * <p>
 * <b>복구 절차:</b>
 * <ol>
 *   <li>백업 파일 스캔 (최신순 정렬)</li>
 *   <li>좋아요 버퍼 → Redis로 복구</li>
 *   <li>Equipment 미완료 → 로그 기록 (수동 처리 필요)</li>
 *   <li>처리 완료된 백업 파일 아카이브</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShutdownDataRecoveryService {

    private final ShutdownDataPersistenceService persistenceService;
    private final LikeSyncExecutor syncExecutor;
    private final StringRedisTemplate redisTemplate;

    private static final String REDIS_HASH_KEY = "buffer:likes";

    /**
     * 애플리케이션 시작 시 백업 파일 복구 실행
     * <p>
     * 백업 파일이 없으면 아무 작업도 수행하지 않습니다.
     */
    @PostConstruct
    public void recoverFromBackup() {
        log.info("🔄 [Shutdown Recovery] 백업 데이터 복구 시작");

        List<Path> backupFiles = persistenceService.findAllBackupFiles();

        if (backupFiles.isEmpty()) {
            log.info("✅ [Shutdown Recovery] 복구할 백업 파일 없음");
            return;
        }

        log.info("📂 [Shutdown Recovery] {}개의 백업 파일 발견", backupFiles.size());

        for (Path backupFile : backupFiles) {
            try {
                processBackupFile(backupFile);
                persistenceService.archiveFile(backupFile);

            } catch (Exception e) {
                log.error("❌ [Shutdown Recovery] 백업 파일 처리 실패: {}", backupFile.getFileName(), e);
            }
        }

        log.info("✅ [Shutdown Recovery] 백업 데이터 복구 완료");
    }

    /**
     * 백업 파일을 읽어서 데이터 복구
     *
     * @param backupFile 백업 파일 경로
     */
    private void processBackupFile(Path backupFile) {
        Optional<ShutdownData> dataOpt = persistenceService.readBackupFile(backupFile);

        if (dataOpt.isEmpty()) {
            log.warn("⚠️ [Shutdown Recovery] 백업 파일 읽기 실패: {}", backupFile.getFileName());
            return;
        }

        ShutdownData data = dataOpt.get();

        log.info("📝 [Shutdown Recovery] 처리 중: {} (인스턴스: {}, 항목: {}개)",
                backupFile.getFileName(), data.instanceId(), data.getTotalItems());

        // 1. 좋아요 버퍼 복구
        recoverLikeBuffer(data);

        // 2. Equipment 미완료 처리
        recoverEquipmentPending(data);
    }

    /**
     * 좋아요 버퍼 데이터를 Redis로 복구
     * <p>
     * Redis 장애 시 DB로 직접 반영합니다.
     *
     * @param data Shutdown 백업 데이터
     */
    private void recoverLikeBuffer(ShutdownData data) {
        Map<String, Long> likeBuffer = data.likeBuffer();

        if (likeBuffer == null || likeBuffer.isEmpty()) {
            return;
        }

        int successCount = 0;
        int failureCount = 0;

        for (Map.Entry<String, Long> entry : likeBuffer.entrySet()) {
            String userIgn = entry.getKey();
            Long count = entry.getValue();

            try {
                // Redis로 복구 시도
                redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                successCount++;

            } catch (Exception e) {
                // Redis 실패 시 DB로 직접 반영
                log.warn("⚠️ [Shutdown Recovery] Redis 복구 실패, DB 직접 반영: {} ({}건)", userIgn, count);
                try {
                    syncExecutor.executeIncrement(userIgn, count);
                    successCount++;
                } catch (Exception dbEx) {
                    log.error("❌ [Shutdown Recovery] DB 반영 실패: {}", userIgn, dbEx);
                    failureCount++;
                }
            }
        }

        log.info("✅ [Shutdown Recovery] 좋아요 복구 완료: 성공 {}건, 실패 {}건", successCount, failureCount);
    }

    /**
     * Equipment 미완료 항목 처리
     * <p>
     * Equipment 데이터는 Nexon API 재호출이 필요하므로 자동 복구 불가.
     * 로그로 기록하여 운영자가 수동으로 처리할 수 있도록 합니다.
     *
     * @param data Shutdown 백업 데이터
     */
    private void recoverEquipmentPending(ShutdownData data) {
        List<String> equipmentPending = data.equipmentPending();

        if (equipmentPending == null || equipmentPending.isEmpty()) {
            return;
        }

        log.warn("⚠️ [Shutdown Recovery] Equipment 미완료 항목: {}건", equipmentPending.size());
        log.warn("   → OCID 목록: {}", equipmentPending);
        log.warn("   → 자동 복구 불가: Nexon API 재호출 필요");
        log.warn("   → 운영자 수동 처리 권장: 해당 OCID의 Equipment 데이터 재조회");

        // TODO: 자동 복구 로직 추가 가능 (Nexon API 재호출 + 캐시 갱신)
        // 현재는 로그만 남기고 수동 처리로 유도
    }
}
