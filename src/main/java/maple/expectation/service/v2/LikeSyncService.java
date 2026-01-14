package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.like.compensation.CompensationCommand;
import maple.expectation.service.v2.like.compensation.RedisCompensationCommand;
import maple.expectation.service.v2.like.dto.FetchResult;
import maple.expectation.service.v2.like.strategy.AtomicFetchStrategy;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 좋아요 동기화 서비스 (리팩토링: 금융수준 원자성)
 *
 * <p>이슈 #147: Redis → DB 동기화 중 데이터 유실 방지
 *
 * <p>금융수준 안전 설계:
 * <ul>
 *   <li><b>원자적 Fetch</b>: Lua Script로 RENAME + HGETALL 원자적 실행</li>
 *   <li><b>보상 트랜잭션</b>: DB 실패 시 임시 키 → 원본 키 복원</li>
 *   <li><b>JVM 크래시 대응</b>: 임시 키 보존 + OrphanKeyRecoveryService</li>
 *   <li><b>TTL 안전장치</b>: 임시 키 1시간 TTL로 메모리 누수 방지</li>
 * </ul>
 * </p>
 *
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeSyncService {

    private final LikeBufferStorage likeBufferStorage;
    private final LikeSyncExecutor syncExecutor;
    private final StringRedisTemplate redisTemplate;
    private final RedisBufferRepository redisBufferRepository;
    private final Retry likeSyncRetry;
    private final ShutdownDataPersistenceService shutdownDataPersistenceService;
    private final LogicExecutor executor;
    private final AtomicFetchStrategy atomicFetchStrategy;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Hash Tag 패턴 적용 (Redis Cluster 호환)
     *
     * <p>Context7 Best Practice: {prefix}:suffix 패턴으로 같은 슬롯 보장</p>
     */
    private static final String SOURCE_KEY = "{buffer:likes}";
    private static final String TEMP_KEY_PREFIX = "{buffer:likes}:sync:";

    // ========== L1 -> L2 Flush (변경 없음) ==========

    /**
     * L1 -> L2 전송
     */
    public void flushLocalToRedis() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return;
        snapshot.forEach(this::processLocalBufferEntry);
    }

    /**
     * Graceful Shutdown용 전송
     */
    public FlushResult flushLocalToRedisWithFallback() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return FlushResult.empty();

        AtomicInteger redisSuccessCount = new AtomicInteger(0);
        AtomicInteger fileBackupCount = new AtomicInteger(0);

        snapshot.forEach((userIgn, atomicCount) ->
                processShutdownFlushEntry(userIgn, atomicCount, redisSuccessCount, fileBackupCount)
        );

        return new FlushResult(redisSuccessCount.get(), fileBackupCount.get());
    }

    // ========== L2 -> L3 Sync (금융수준 리팩토링) ==========

    /**
     * Redis -> DB 동기화 (금융수준 원자성)
     *
     * <p>변경 사항:
     * <ul>
     *   <li>기존: rename → forEach → 개별 복구</li>
     *   <li>변경: Lua Script → 일괄 처리 → 보상 트랜잭션</li>
     * </ul>
     * </p>
     */
    @ObservedTransaction("scheduler.like.redis_to_db")
    public void syncRedisToDatabase() {
        String tempKey = generateTempKey();
        TaskContext context = TaskContext.of("LikeSync", "RedisToDb", tempKey);

        // 보상 명령 생성 (DLQ 패턴 적용)
        CompensationCommand compensation = new RedisCompensationCommand(
                SOURCE_KEY, atomicFetchStrategy, executor, meterRegistry, eventPublisher);

        // executeWithFinally: 성공/실패 여부와 관계없이 finally 블록 실행 보장
        executor.executeWithFinally(
                () -> doAtomicSyncProcess(tempKey, compensation),
                () -> executeCompensationIfNeeded(compensation),
                context
        );
    }

    // ========== Private Methods (3-Line Rule 준수) ==========

    /**
     * 원자적 동기화 프로세스 (메인 로직)
     *
     * <p>P1 Enhancement: Micrometer 메트릭 기록 (SRE 모니터링)</p>
     */
    private Void doAtomicSyncProcess(String tempKey, CompensationCommand compensation) {
        long startTime = System.nanoTime();

        // Step 1: 원자적 Fetch (Lua Script)
        FetchResult fetchResult = atomicFetchStrategy.fetchAndMove(SOURCE_KEY, tempKey);
        if (fetchResult.isEmpty()) {
            log.debug("No data to sync from Redis");
            recordSyncMetrics(0, 0, 0, startTime, "empty");
            return null;
        }

        // Step 2: 보상 명령에 상태 저장 (실패 시 복구용)
        compensation.save(fetchResult);

        // Step 3: DB 저장 처리
        long successTotal = processDatabaseSync(fetchResult);
        long failedEntries = fetchResult.size() - countSuccessfulEntries(fetchResult, successTotal);

        // Step 4: GlobalCount 차감 (성공분만)
        if (successTotal > 0) {
            redisBufferRepository.decrementGlobalCount(successTotal);
        }

        // Step 5: 커밋 (임시 키 삭제)
        compensation.commit();

        // Step 6: 메트릭 기록 (P1 Enhancement)
        recordSyncMetrics(fetchResult.size(), successTotal, failedEntries, startTime, "success");

        log.info("Redis → DB sync completed: entries={}, totalCount={}",
                fetchResult.size(), successTotal);

        return null;
    }

    /**
     * 성공 엔트리 수 계산 (근사치)
     */
    private long countSuccessfulEntries(FetchResult fetchResult, long successTotal) {
        if (fetchResult.isEmpty() || successTotal == 0) return 0;
        // 전체 count 대비 성공 count 비율로 엔트리 수 추정
        long totalCount = fetchResult.data().values().stream().mapToLong(Long::longValue).sum();
        return totalCount > 0 ? (long) Math.ceil((double) successTotal / totalCount * fetchResult.size()) : 0;
    }

    /**
     * DB 동기화 처리 (Retry 포함)
     *
     * @return 성공적으로 동기화된 총 count
     */
    private long processDatabaseSync(FetchResult fetchResult) {
        AtomicLong successTotal = new AtomicLong(0);

        fetchResult.data().forEach((userIgn, count) -> {
            boolean success = syncWithRetry(userIgn, count);
            if (success) {
                successTotal.addAndGet(count);
            } else {
                // 실패 시 개별 복구 (원본 버퍼로)
                restoreSingleEntry(userIgn, count);
            }
        });

        return successTotal.get();
    }

    /**
     * 단일 엔트리 복구 (DB 동기화 실패 시)
     *
     * <p>P1 Enhancement: 복구 메트릭 기록</p>
     */
    private void restoreSingleEntry(String userIgn, long count) {
        executor.executeOrCatch(
                () -> {
                    redisTemplate.opsForHash().increment(SOURCE_KEY, userIgn, count);
                    recordRestoreMetrics("success", count);
                    log.warn("♻️ [Sync Recovery] DB 반영 실패로 Redis 복구: {} ({}건)", userIgn, count);
                    return null;
                },
                e -> {
                    recordRestoreMetrics("failure", count);
                    log.error("‼️ [Sync Recovery] Redis 복구 실패: {} ({}건)", userIgn, count, e);
                    return null;
                },
                TaskContext.of("LikeSync", "RestoreSingleEntry", userIgn)
        );
    }

    /**
     * 보상 트랜잭션 실행 (finally에서 호출)
     */
    private void executeCompensationIfNeeded(CompensationCommand compensation) {
        if (compensation.isPending()) {
            log.warn("Compensation triggered due to abnormal termination");
            compensation.compensate();
        }
    }

    /**
     * 임시 키 생성 (Hash Tag 패턴)
     */
    private String generateTempKey() {
        return TEMP_KEY_PREFIX + UUID.randomUUID();
    }

    // ========== L1 -> L2 Helper Methods ==========

    private void processLocalBufferEntry(String userIgn, AtomicLong atomicCount) {
        long count = atomicCount.getAndSet(0);
        if (count <= 0) return;

        executor.executeOrCatch(
                () -> {
                    redisTemplate.opsForHash().increment(SOURCE_KEY, userIgn, count);
                    redisBufferRepository.incrementGlobalCount(count);
                    return null;
                },
                e -> {
                    handleRedisFailure(userIgn, count, e);
                    return null;
                },
                TaskContext.of("LikeSync", "L1toL2", userIgn)
        );
    }

    private void processShutdownFlushEntry(String userIgn, AtomicLong atomicCount,
                                           AtomicInteger redisSuccessCount, AtomicInteger fileBackupCount) {
        long count = atomicCount.getAndSet(0);
        if (count <= 0) return;

        executor.executeOrCatch(
                () -> {
                    redisTemplate.opsForHash().increment(SOURCE_KEY, userIgn, count);
                    redisBufferRepository.incrementGlobalCount(count);
                    redisSuccessCount.incrementAndGet();
                    return null;
                },
                e -> {
                    log.warn("⚠️ [Shutdown Flush] Redis 전송 실패, 파일 백업: {} ({}건)", userIgn, count);
                    shutdownDataPersistenceService.appendLikeEntry(userIgn, count);
                    fileBackupCount.incrementAndGet();
                    return null;
                },
                TaskContext.of("LikeSync", "ShutdownFlush", userIgn)
        );
    }

    private void handleRedisFailure(String userIgn, long count, Throwable e) {
        log.error("🚑 [Redis Down] L2 전송 실패. DB 직접 반영 시도: {}", userIgn);
        executor.executeOrCatch(
                () -> {
                    syncExecutor.executeIncrement(userIgn, count);
                    return null;
                },
                dbEx -> {
                    likeBufferStorage.getCounter(userIgn).addAndGet(count);
                    log.error("‼️ [Critical] Redis/DB 동시 장애. 로컬 롤백 완료.");
                    return null;
                },
                TaskContext.of("LikeSync", "RedisFailureRecovery", userIgn)
        );
    }

    private boolean syncWithRetry(String userIgn, long count) {
        return executor.executeOrDefault(() -> {
            Retry.decorateRunnable(likeSyncRetry, () ->
                    syncExecutor.executeIncrement(userIgn, count)
            ).run();
            return true;
        }, false, TaskContext.of("LikeSync", "DbSyncWithRetry", userIgn));
    }

    // ========== Metrics (Micrometer) - P1 Enhancement ==========

    /**
     * LikeSync 메트릭 기록 (SRE 모니터링)
     *
     * <p>기록 항목:
     * <ul>
     *   <li><b>like.sync.duration</b>: 동기화 소요 시간 (Timer)</li>
     *   <li><b>like.sync.entries</b>: 배치당 엔트리 수 (Distribution)</li>
     *   <li><b>like.sync.total.count</b>: 배치당 총 좋아요 수</li>
     *   <li><b>like.sync.failed.entries</b>: 실패 엔트리 수</li>
     * </ul>
     * </p>
     */
    private void recordSyncMetrics(int entries, long totalCount, long failedEntries, long startNanos, String result) {
        long durationNanos = System.nanoTime() - startNanos;

        // Timer: 동기화 소요 시간
        meterRegistry.timer("like.sync.duration", "result", result)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);

        // Distribution Summary: 배치당 엔트리 수
        meterRegistry.summary("like.sync.entries").record(entries);

        // Counter: 총 좋아요 수
        if (totalCount > 0) {
            meterRegistry.counter("like.sync.total.count", "result", result).increment(totalCount);
        }

        // Counter: 실패 엔트리 수
        if (failedEntries > 0) {
            meterRegistry.counter("like.sync.failed.entries").increment(failedEntries);
        }
    }

    /**
     * 개별 복구 메트릭 기록
     *
     * @param result  success | failure
     * @param count   복구된/실패한 좋아요 수
     */
    private void recordRestoreMetrics(String result, long count) {
        meterRegistry.counter("like.sync.restore.count", "result", result).increment();
        meterRegistry.counter("like.sync.restore.likes", "result", result).increment(count);
    }
}
