package maple.expectation.service.v2.shutdown;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.service.v2.LikeSyncExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShutdownDataRecoveryService {

    private final ShutdownDataPersistenceService persistenceService;
    private final LikeSyncExecutor syncExecutor;
    private final StringRedisTemplate redisTemplate;
    private final LogicExecutor executor; // ✅ 지능형 실행기 주입

    private static final String REDIS_HASH_KEY = "buffer:likes";

    @PostConstruct
    public void recoverFromBackup() {
        TaskContext context = TaskContext.of("Recovery", "MainProcess");

        //  전체 복구 프로세스를 실행기로 보호 (Issue #77 대응)
        executor.executeVoid(() -> {
            log.info("🔄 [Shutdown Recovery] 백업 데이터 복구 시작");

            List<Path> backupFiles = persistenceService.findAllBackupFiles();
            if (backupFiles.isEmpty()) {
                log.info("✅ [Shutdown Recovery] 복구할 백업 파일 없음");
                return;
            }

            // 루프 내부의 try-catch를 메서드 참조와 실행기로 평탄화
            backupFiles.forEach(file -> processFileWithSafety(file, context));

            log.info("✅ [Shutdown Recovery] 백업 데이터 복구 완료");
        }, context);
    }

    /**
     * 개별 파일 처리 (관측성 격리)
     */
    private void processFileWithSafety(Path backupFile, TaskContext parentContext) {
        TaskContext fileContext = TaskContext.of("Recovery", "ProcessFile", backupFile.getFileName().toString());

        executor.executeVoid(() -> {
            boolean success = processBackupFile(backupFile);
            if (success) {
                persistenceService.archiveFile(backupFile); // [이슈 #123] 성공 시에만 아카이브
            } else {
                log.warn("⏭️ [Recovery Skip] 복구 미완료로 파일을 보존합니다: {}", backupFile.getFileName());
            }
        }, fileContext);
    }

    private boolean processBackupFile(Path backupFile) {
        Optional<ShutdownData> dataOpt = persistenceService.readBackupFile(backupFile);
        if (dataOpt.isEmpty()) return false;

        ShutdownData data = dataOpt.get();
        log.info("📝 [Shutdown Recovery] 처리 중: {} (항목: {}개)", backupFile.getFileName(), data.getTotalItems());

        boolean likesRecovered = recoverLikeBuffer(data);
        recoverEquipmentPending(data);

        return likesRecovered;
    }

    /**
     * ✅  Redis -> DB Fallback 로직 평탄화
     */
    private boolean recoverLikeBuffer(ShutdownData data) {
        Map<String, Long> likeBuffer = data.likeBuffer();
        if (likeBuffer == null || likeBuffer.isEmpty()) return true;

        AtomicBoolean allSuccess = new AtomicBoolean(true);

        likeBuffer.forEach((userIgn, count) -> {
            TaskContext entryContext = TaskContext.of("Recovery", "LikeEntry", userIgn);

            // [패턴 5] executeWithRecovery: Redis 시도 -> 실패 시 DB 시도 (Issue #77)
            executor.executeWithRecovery(
                    () -> {
                        redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                        log.debug("✅ [Shutdown Recovery] Redis 복구 성공: {} ({}건)", userIgn, count);
                        return null;
                    },
                    (redisEx) -> {
                        // Redis 실패 시 DB Fallback 로직 수행
                        return recoverToDbFallback(userIgn, count, redisEx, allSuccess, entryContext);
                    },
                    entryContext
            );
        });

        return allSuccess.get();
    }

    /**
     * 헬퍼: DB로 직접 복구 시도 (복구 시나리오 격리)
     */
    private Void recoverToDbFallback(String userIgn, Long count, Throwable redisEx, AtomicBoolean allSuccess, TaskContext context) {
        log.warn("⚠️ [Shutdown Recovery] Redis 복구 실패, DB 직접 반영 시도: {} ({}건)", userIgn, count);

        return executor.executeWithRecovery(
                () -> {
                    syncExecutor.executeIncrement(userIgn, count);
                    log.info("✅ [Shutdown Recovery] DB 직접 반영 성공: {} ({}건)", userIgn, count);
                    return null;
                },
                (dbEx) -> {
                    log.error("❌ [Shutdown Recovery] 최종 복구 실패 - 수동 처리 필요: {} ({}건)", userIgn, count);
                    allSuccess.set(false); // 최종 실패 시 파일 보존을 위해 상태 변경
                    return null;
                },
                context
        );
    }

    private void recoverEquipmentPending(ShutdownData data) {
        List<String> equipmentPending = data.equipmentPending();
        if (equipmentPending == null || equipmentPending.isEmpty()) return;

        log.warn("⚠️ [Shutdown Recovery] Equipment 미완료 항목: {}건", equipmentPending.size());
        log.warn("   → OCID 목록: {}", equipmentPending);
    }
}