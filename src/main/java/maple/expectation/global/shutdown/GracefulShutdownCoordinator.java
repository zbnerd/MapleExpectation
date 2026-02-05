package maple.expectation.global.shutdown;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.ShutdownProperties;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.shutdown.PersistenceTrackerStrategy;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot Graceful Shutdown 조정자 (P0/P1 리팩토링 완료)
 *
 * <h3>4단계 순차 종료</h3>
 * <ol>
 *   <li>Equipment 비동기 저장 완료 대기</li>
 *   <li>로컬 좋아요 버퍼 Flush</li>
 *   <li>DB 최종 동기화 (분산 락)</li>
 *   <li>백업 데이터 최종 저장</li>
 * </ol>
 *
 * <h3>P1-2 Fix: 하드코딩 제거 -> ShutdownProperties 외부화</h3>
 * <h3>P1-6 Fix: Shutdown 메트릭 추가 (Micrometer)</h3>
 *
 * @see ShutdownProperties 외부 설정
 * @see maple.expectation.service.v4.buffer.ExpectationBatchShutdownHandler 버퍼 Drain 핸들러
 */
@Slf4j
@Component
@EnableConfigurationProperties(ShutdownProperties.class)
public class GracefulShutdownCoordinator implements SmartLifecycle {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;
    private final PersistenceTrackerStrategy persistenceTracker;
    private final ShutdownDataPersistenceService shutdownDataPersistenceService;
    private final LogicExecutor executor;
    private final ShutdownProperties properties;

    // P1-6 Fix: Shutdown 메트릭
    private final Timer shutdownTimer;
    private final Counter shutdownSuccessCounter;
    private final Counter shutdownFailureCounter;

    /**
     * SmartLifecycle 실행 상태 플래그
     *
     * <h4>Issue #283 P1-14: Scale-out 분산 안전성</h4>
     * <p>이 플래그는 Spring {@link SmartLifecycle} 계약의 일부로,
     * <b>인스턴스 로컬 lifecycle</b>을 관리합니다:</p>
     * <ul>
     *   <li>start(): 이 인스턴스의 Coordinator가 활성화되었음을 표시</li>
     *   <li>stop(): 이 인스턴스의 4단계 순차 종료를 실행 후 false로 전환</li>
     *   <li>isRunning(): Spring이 이 인스턴스의 lifecycle 상태를 확인</li>
     * </ul>
     * <p>각 인스턴스는 독립적으로 SIGTERM을 수신하고 자신의 종료 절차를 실행합니다.
     * DB 동기화 단계에서는 {@link LockStrategy} 분산 락으로 중복 실행을 방지합니다.</p>
     * <p><b>결론: SmartLifecycle 계약에 의한 인스턴스 로컬 상태. Redis 전환 불필요.</b></p>
     */
    private volatile boolean running = false;

    public GracefulShutdownCoordinator(
            LikeSyncService likeSyncService,
            LockStrategy lockStrategy,
            PersistenceTrackerStrategy persistenceTracker,
            ShutdownDataPersistenceService shutdownDataPersistenceService,
            LogicExecutor executor,
            ShutdownProperties properties,
            MeterRegistry meterRegistry) {
        this.likeSyncService = likeSyncService;
        this.lockStrategy = lockStrategy;
        this.persistenceTracker = persistenceTracker;
        this.shutdownDataPersistenceService = shutdownDataPersistenceService;
        this.executor = executor;
        this.properties = properties;
        this.shutdownTimer = Timer.builder("shutdown.coordinator.duration")
                .description("Graceful Shutdown 총 소요 시간")
                .register(meterRegistry);
        this.shutdownSuccessCounter = Counter.builder("shutdown.coordinator.result")
                .tag("status", "success")
                .description("Shutdown 성공 횟수")
                .register(meterRegistry);
        this.shutdownFailureCounter = Counter.builder("shutdown.coordinator.result")
                .tag("status", "failure")
                .description("Shutdown 실패 횟수")
                .register(meterRegistry);
    }

    @Override
    public void start() {
        this.running = true;
        log.debug("[Graceful Shutdown Coordinator] Started");
    }

    /**
     * 4단계 순차 종료 프로세스
     *
     * <h4>CLAUDE.md Section 12 준수</h4>
     * <p>try-catch 금지 -> LogicExecutor.executeWithFinally() 사용</p>
     */
    @Override
    public void stop() {
        TaskContext context = TaskContext.of("Shutdown", "MainProcess");

        long startNanos = System.nanoTime();

        executor.executeWithFinally(
                () -> {
                    log.warn("========= [System Shutdown] 종료 절차 시작 =========");

                    // 1. Equipment 비동기 저장 작업 완료 대기
                    ShutdownData backupData = waitForEquipmentPersistence();

                    // 2. 로컬 좋아요 버퍼 Flush
                    backupData = flushLikeBuffer(backupData);

                    // 3. 리더 서버인 경우 DB 최종 동기화
                    syncRedisToDatabase();

                    // 4. 백업 데이터 최종 저장
                    if (backupData != null && !backupData.isEmpty()) {
                        shutdownDataPersistenceService.saveShutdownData(backupData);
                    }

                    shutdownSuccessCounter.increment();
                    return null;
                },
                () -> {
                    this.running = false;
                    shutdownTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                    log.warn("========= [System Shutdown] 종료 완료 =========");
                },
                context
        );
    }

    /**
     * Equipment 저장 대기 (P1-2 Fix: 타임아웃 외부화)
     */
    private ShutdownData waitForEquipmentPersistence() {
        return executor.execute(() -> {
            log.info("[1/4] Equipment 비동기 저장 작업 완료 대기 중...");
            boolean allCompleted = persistenceTracker.awaitAllCompletion(properties.getEquipmentAwaitTimeout());

            if (!allCompleted) {
                List<String> pendingOcids = persistenceTracker.getPendingOcids();
                log.warn("[1/4] Equipment 저장 미완료 항목: {}건", pendingOcids.size());
                return new ShutdownData(LocalDateTime.now(), properties.getInstanceId(), Map.of(), pendingOcids);
            }

            log.info("[1/4] 모든 Equipment 저장 작업 완료.");
            return ShutdownData.empty(properties.getInstanceId());
        }, TaskContext.of("Shutdown", "WaitEquipment"));
    }

    /**
     * 로컬 좋아요 버퍼 Flush
     */
    private ShutdownData flushLikeBuffer(ShutdownData backupData) {
        return executor.executeOrCatch(
                () -> {
                    log.info("[2/4] 로컬 좋아요 버퍼 Flush 중...");
                    FlushResult result = likeSyncService.flushLocalToRedisWithFallback();
                    log.info("[2/4] 로컬 좋아요 버퍼 Flush 완료: Redis {}건, 파일 백업 {}건",
                            result.redisSuccessCount(), result.fileBackupCount());
                    return backupData;
                },
                (e) -> {
                    log.error("[2/4] 로컬 Flush 중 예상치 못한 오류 발생", e);
                    shutdownFailureCounter.increment();
                    return backupData;
                },
                TaskContext.of("Shutdown", "FlushLikes")
        );
    }

    /**
     * DB 최종 동기화 (P1-2 Fix: 락 설정 외부화)
     */
    private void syncRedisToDatabase() {
        executor.executeOrCatch(
                () -> {
                    log.info("[3/4] DB 최종 동기화 시도 중...");
                    lockStrategy.executeWithLock(
                            "like-db-sync-lock",
                            properties.getLockWaitSeconds(),
                            properties.getLockLeaseSeconds(),
                            () -> {
                                likeSyncService.syncRedisToDatabase();
                                return null;
                            });
                    log.info("[3/4] DB 최종 동기화 완료.");
                    return null;
                },
                (e) -> {
                    if (e instanceof maple.expectation.global.error.exception.DistributedLockException) {
                        log.info("[Shutdown Sync] DB 동기화 스킵: 다른 서버가 처리 중입니다.");
                    } else {
                        log.warn("[Shutdown Sync] DB 동기화 실패: {}", e.getMessage());
                        shutdownFailureCounter.increment();
                    }
                    return null;
                },
                TaskContext.of("Shutdown", "SyncToDb")
        );
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 1000; }

    @Override
    public boolean isAutoStartup() { return true; }
}
