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

    @PostConstruct
    public void recoverFromBackup() {
        try {
            log.info("🔄 [Shutdown Recovery] 백업 데이터 복구 시작");

            List<Path> backupFiles = persistenceService.findAllBackupFiles();
            if (backupFiles.isEmpty()) {
                log.info("✅ [Shutdown Recovery] 복구할 백업 파일 없음");
                return;
            }

            for (Path backupFile : backupFiles) {
                try {
                    // 🚀 [이슈 #123] 성공 시에만 아카이브 수행
                    boolean success = processBackupFile(backupFile);
                    if (success) {
                        persistenceService.archiveFile(backupFile);
                    } else {
                        log.warn("⏭️ [Recovery Skip] 복구 미완료로 파일을 보존합니다: {}", backupFile.getFileName());
                    }
                } catch (Exception e) {
                    log.error("❌ [Shutdown Recovery] 백업 파일 처리 실패: {}", backupFile.getFileName(), e);
                    // 🔥 [Issue #77] 개별 파일 실패는 전체 복구를 중단시키지 않음
                }
            }
            log.info("✅ [Shutdown Recovery] 백업 데이터 복구 완료");
        } catch (Exception e) {
            // 🔥 [Issue #77] Redis 연결 실패 등으로 복구가 불가능해도 애플리케이션은 시작됨
            log.error("❌ [Shutdown Recovery] 복구 프로세스 실패 - 애플리케이션은 계속 시작됩니다", e);
            log.warn("⚠️ [Shutdown Recovery] 백업 파일은 보존되며, 수동 복구가 필요할 수 있습니다");
        }
    }

    private boolean processBackupFile(Path backupFile) {
        Optional<ShutdownData> dataOpt = persistenceService.readBackupFile(backupFile);
        if (dataOpt.isEmpty()) return false;

        ShutdownData data = dataOpt.get();
        log.info("📝 [Shutdown Recovery] 처리 중: {} (항목: {}개)", backupFile.getFileName(), data.getTotalItems());

        // 🚀 모든 복구 로직이 성공해야 true 반환
        boolean likesRecovered = recoverLikeBuffer(data);
        recoverEquipmentPending(data); // Equipment는 로그 기록용이므로 결과에 영향 없음

        return likesRecovered;
    }

    private boolean recoverLikeBuffer(ShutdownData data) {
        Map<String, Long> likeBuffer = data.likeBuffer();
        if (likeBuffer == null || likeBuffer.isEmpty()) return true;

        boolean allSuccess = true;
        for (Map.Entry<String, Long> entry : likeBuffer.entrySet()) {
            String userIgn = entry.getKey();
            Long count = entry.getValue();

            try {
                redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                log.debug("✅ [Shutdown Recovery] Redis 복구 성공: {} ({}건)", userIgn, count);
            } catch (Exception e) {
                // 🔥 [Issue #77] Redis 장애 시 즉시 DB Fallback (CircuitBreaker 패턴)
                log.warn("⚠️ [Shutdown Recovery] Redis 복구 실패 ({}), DB 직접 반영: {} ({}건)",
                    e.getClass().getSimpleName(), userIgn, count);
                try {
                    syncExecutor.executeIncrement(userIgn, count);
                    log.info("✅ [Shutdown Recovery] DB 직접 반영 성공: {} ({}건)", userIgn, count);
                } catch (Exception dbEx) {
                    log.error("❌ [Shutdown Recovery] 최종 복구 실패 - 수동 처리 필요: {} ({}건)", userIgn, count, dbEx);
                    allSuccess = false; // 하나라도 실패하면 false
                }
            }
        }

        if (!allSuccess) {
            log.error("❌ [Shutdown Recovery] 일부 데이터 복구 실패 - 백업 파일 보존됨");
        }

        return allSuccess;
    }

    private void recoverEquipmentPending(ShutdownData data) {
        List<String> equipmentPending = data.equipmentPending();
        if (equipmentPending == null || equipmentPending.isEmpty()) return;

        log.warn("⚠️ [Shutdown Recovery] Equipment 미완료 항목: {}건", equipmentPending.size());
        log.warn("   → OCID 목록: {}", equipmentPending);
        log.warn("   → 운영자 수동 처리 권장: 해당 OCID의 Equipment 데이터 재조회");
    }
}