package maple.expectation.global.shutdown;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Graceful Shutdown 조정자
 * <p>
 * Spring Boot 3.x의 {@link SmartLifecycle}을 구현하여 애플리케이션 종료 시
 * 체계적인 cleanup 절차를 수행합니다.
 * <p>
 * <b>종료 절차:</b>
 * <ol>
 *   <li>Equipment 비동기 저장 작업 완료 대기 (최대 20초)</li>
 *   <li>미완료 Equipment 작업을 로컬 파일로 백업</li>
 *   <li>로컬 좋아요 버퍼를 Redis로 Flush</li>
 *   <li>리더 인스턴스가 Redis 데이터를 DB로 최종 동기화</li>
 *   <li>백업 데이터를 로컬 파일로 저장</li>
 * </ol>
 * <p>
 * <b>Phase 설정:</b> {@code Integer.MAX_VALUE - 1000}을 사용하여
 * 가장 마지막 단계에서 종료되도록 보장합니다.
 * (낮은 phase는 먼저 시작, 높은 phase는 나중에 종료)
 *
 * @see SmartLifecycle
 * @see ShutdownDataPersistenceService
 * @see EquipmentPersistenceTracker
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownCoordinator implements SmartLifecycle {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;
    private final EquipmentPersistenceTracker equipmentPersistenceTracker;
    private final ShutdownDataPersistenceService shutdownDataPersistenceService;

    private volatile boolean running = false;

    @Override
    public void start() {
        // SmartLifecycle 계약을 위해 구현하지만, 시작 시 특별한 작업 없음
        this.running = true;
        log.debug("✅ [Graceful Shutdown Coordinator] Started");
    }

    /**
     * Graceful Shutdown 시 호출되는 메인 로직
     * <p>
     * Spring Boot의 {@code spring.lifecycle.timeout-per-shutdown-phase} 설정값
     * (기본 30초) 내에 모든 cleanup 작업을 완료해야 합니다.
     */
    @Override
    public void stop() {
        log.warn("========= [System Shutdown] 종료 절차 시작 =========");

        ShutdownData backupData = null;

        try {
            // 1. Equipment 비동기 저장 작업 완료 대기
            backupData = waitForEquipmentPersistence();

            // 2. 로컬 좋아요 버퍼 Flush (모든 인스턴스 수행)
            backupData = flushLikeBuffer(backupData);

            // 3. 리더 서버인 경우 DB 최종 동기화 (단일 인스턴스 수행)
            syncRedisToDatabase();

            // 4. 백업 데이터 최종 저장 (Equipment 미완료 포함)
            // appendLikeEntry()로 이미 저장된 좋아요 데이터와 Equipment 데이터를 통합
            if (backupData != null && !backupData.isEmpty()) {
                shutdownDataPersistenceService.saveShutdownData(backupData);
            }

        } catch (Exception e) {
            log.error("❌ [System Shutdown] 종료 절차 중 예외 발생", e);
        } finally {
            this.running = false;
            log.warn("========= [System Shutdown] 종료 완료 =========");
        }
    }

    /**
     * Equipment 비동기 저장 작업이 완료될 때까지 대기
     * <p>
     * timeout 발생 시 미완료 OCID를 백업 데이터에 포함합니다.
     *
     * @return 백업 데이터 (미완료 Equipment 포함)
     */
    private ShutdownData waitForEquipmentPersistence() {
        log.info("▶️ [1/4] Equipment 비동기 저장 작업 완료 대기 중...");

        boolean allCompleted = equipmentPersistenceTracker.awaitAllCompletion(Duration.ofSeconds(20));

        if (!allCompleted) {
            List<String> pendingOcids = equipmentPersistenceTracker.getPendingOcids();
            log.warn("⚠️ Equipment 저장 미완료 항목: {}건", pendingOcids.size());

            // 미완료 작업을 백업 데이터에 포함
            return new ShutdownData(
                    LocalDateTime.now(),
                    shutdownDataPersistenceService.getInstanceId(),
                    Map.of(),
                    pendingOcids
            );
        }

        log.info("✅ 모든 Equipment 저장 작업 완료.");
        return ShutdownData.empty(shutdownDataPersistenceService.getInstanceId());
    }

    /**
     * 로컬 좋아요 버퍼를 Redis로 Flush
     * <p>
     * Redis 장애 시 로컬 파일로 백업합니다.
     * 파일 백업된 데이터는 기존 백업 데이터와 병합하여 최종 저장합니다.
     *
     * @param backupData 백업 데이터 (Equipment 미완료 항목 포함 가능)
     * @return 병합된 최종 백업 데이터
     */
    private ShutdownData flushLikeBuffer(ShutdownData backupData) {
        try {
            log.info("▶️ [2/4] 로컬 좋아요 버퍼 Flush 중...");

            FlushResult result = likeSyncService.flushLocalToRedisWithFallback();

            // 파일 백업이 발생한 경우 백업 데이터 업데이트
            if (result.fileBackupCount() > 0) {
                log.warn("⚠️ Redis 장애로 {}건의 좋아요 데이터가 파일로 백업되었습니다.", result.fileBackupCount());
                // ShutdownDataPersistenceService.appendLikeEntry()가 이미 처리했으므로 추가 작업 불필요
            }

            log.info("✅ 로컬 좋아요 버퍼 Flush 완료: Redis {}건, 파일 백업 {}건",
                    result.redisSuccessCount(), result.fileBackupCount());

            return backupData; // 기존 백업 데이터 유지 (Equipment 정보 포함)

        } catch (Exception e) {
            log.error("❌ 로컬 Flush 중 예상치 못한 오류 발생", e);
            return backupData;
        }
    }

    /**
     * 리더 서버인 경우 Redis 데이터를 DB로 최종 동기화
     * <p>
     * 분산 락을 사용하여 단일 인스턴스만 수행하도록 보장합니다.
     * Redis 장애 시 빠르게 로컬 백업으로 넘어가기 위해 락 획득 타임아웃을 3초로 설정합니다.
     * <p>
     * 락 획득 실패는 정상 시나리오이므로 (다른 서버가 이미 처리 중),
     * 예외를 던지지 않고 로그만 남깁니다.
     */
    private void syncRedisToDatabase() {
        try {
            log.info("▶️ [3/4] DB 최종 동기화 시도 중...");

            lockStrategy.executeWithLock("like-db-sync-lock", 3, 10, () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
            });

            log.info("✅ DB 최종 동기화 완료.");

        } catch (maple.expectation.global.error.exception.DistributedLockException e) {
            // 락 획득 실패 - 다른 서버가 이미 처리 중
            log.info("ℹ️ [Shutdown Sync] DB 동기화 스킵: 다른 서버가 처리 중입니다.");

        } catch (Exception e) {
            // Redis 장애 등 일반 예외 - 로그만 남기고 계속 진행
            log.warn("⚠️ [Shutdown Sync] DB 동기화 실패: {}", e.getMessage());
            log.debug("상세 오류:", e);

        } catch (Throwable t) {
            // Error 등 심각한 예외 - 로그 남기고 계속 진행 (Shutdown은 중단하지 않음)
            log.error("❌ [Shutdown Sync] 심각한 오류 발생: {}", t.getMessage(), t);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Phase를 높게 설정하여 가장 마지막에 종료되도록 보장
     * <p>
     * 다른 Bean들(Web Server, DataSource 등)이 먼저 종료되기 전에
     * 데이터 정합성을 보장하는 작업을 수행합니다.
     *
     * @return Integer.MAX_VALUE - 1000
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    /**
     * Spring Boot Graceful Shutdown과 통합하기 위해 false 반환
     * <p>
     * true를 반환하면 자동으로 시작되지만, 우리는 수동 시작을 원합니다.
     *
     * @return false
     */
    @Override
    public boolean isAutoStartup() {
        return true; // ApplicationContext 시작 시 자동으로 start() 호출
    }
}