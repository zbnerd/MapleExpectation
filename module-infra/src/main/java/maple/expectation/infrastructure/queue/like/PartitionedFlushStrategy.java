package maple.expectation.infrastructure.queue.like;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.queue.RedisKey;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 파티션 기반 분산 Flush 전략 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 *
 * <p>분산 환경에서 Like 버퍼를 안전하게 DB로 플러시합니다. 분산 락으로 동일 파티션의 중복 처리를 방지합니다.
 *
 * <h3>분산 락 전략</h3>
 *
 * <ul>
 *   <li>파티션별 독립 락: 한 파티션 실패가 다른 파티션에 영향 없음
 *   <li>tryLock 사용: 이미 처리 중인 파티션은 스킵
 *   <li>Hash-based Partitioning: userIgn.hashCode() % partitionCount
 * </ul>
 *
 * <h3>파티션 구조</h3>
 *
 * <pre>
 * {likes}:flush:partition:0 (Lock)
 * {likes}:flush:partition:1 (Lock)
 * ...
 * {likes}:flush:partition:N-1 (Lock)
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): 전략 패턴으로 Flush 로직 교체 가능
 *   <li>Green (Performance): 파티션별 병렬 처리로 처리량 향상
 *   <li>Red (SRE): 분산 락으로 중복 처리 방지
 *   <li>Purple (Auditor): 락 획득 실패 시 Skip으로 데이터 유실 없음
 * </ul>
 *
 * @see RedisLikeBufferStorage Redis 버퍼 스토리지
 * @see RedisKey#LIKE_FLUSH_PARTITION 파티션 락 키
 */
@Slf4j
public class PartitionedFlushStrategy {

  private final RedissonClient redissonClient;
  private final RedisLikeBufferStorage bufferStorage;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;
  private final BiConsumer<String, Long> syncProcessor;

  /** 파티션 수 (기본값: 4) */
  private final int partitionCount;

  /** 락 대기 시간 (기본값: 100ms) */
  private final long lockWaitMs;

  /** 락 유지 시간 (기본값: 30초) */
  private final long lockLeaseMs;

  /** 배치당 최대 처리 수 (기본값: 1000) */
  private final int batchSize;

  public PartitionedFlushStrategy(
      RedissonClient redissonClient,
      RedisLikeBufferStorage bufferStorage,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      BiConsumer<String, Long> syncProcessor) {
    this(
        redissonClient,
        bufferStorage,
        executor,
        meterRegistry,
        syncProcessor,
        4,
        100L,
        30000L,
        1000);
  }

  public PartitionedFlushStrategy(
      RedissonClient redissonClient,
      RedisLikeBufferStorage bufferStorage,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      BiConsumer<String, Long> syncProcessor,
      int partitionCount,
      long lockWaitMs,
      long lockLeaseMs,
      int batchSize) {
    this.redissonClient = redissonClient;
    this.bufferStorage = bufferStorage;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
    this.syncProcessor = syncProcessor;
    this.partitionCount = partitionCount;
    this.lockWaitMs = lockWaitMs;
    this.lockLeaseMs = lockLeaseMs;
    this.batchSize = batchSize;

    log.info("[PartitionedFlushStrategy] Initialized with {} partitions", partitionCount);
  }

  /**
   * 담당 파티션 Flush 실행 (P0-10: Flush Race 해결)
   *
   * <h4>용도</h4>
   *
   * <p>LikeSyncScheduler에서 호출하여 Redis 버퍼를 DB로 동기화합니다. 내부적으로 {@link
   * #flushWithPartitions(BiConsumer)}를 호출하면서 DB 저장 로직({@link LikeSyncExecutor#executeIncrement})을
   * processor로 전달합니다.
   *
   * <h4>5-Agent Council 합의</h4>
   *
   * <ul>
   *   <li>Blue (Architect): Scheduler는 호출만, DB 저장 로직은 캡슐화
   *   <li>Green (Performance): 파티션별 병렬 처리로 throughput 향상
   *   <li>Red (SRE): 분산 락으로 중복 Flush 방지
   * </ul>
   *
   * @return 처리 결과 (acquiredPartitions, processedEntries, totalDelta)
   */
  public FlushResult flushAssignedPartitions() {
    return flushWithPartitions(syncProcessor);
  }

  /**
   * 파티션별 분산 Flush 실행
   *
   * <p>각 파티션에 대해 분산 락을 시도하고, 획득한 파티션만 처리합니다. 이미 다른 인스턴스가 처리 중인 파티션은 스킵됩니다.
   *
   * @param processor 각 (userIgn, delta) 쌍을 처리할 함수
   * @return 처리 결과 (acquiredPartitions, processedEntries, totalDelta)
   */
  public FlushResult flushWithPartitions(BiConsumer<String, Long> processor) {
    // 1. 버퍼에서 데이터 원자적 추출
    Map<String, Long> allEntries = bufferStorage.fetchAndClear(batchSize);

    if (allEntries.isEmpty()) {
      log.debug("[Flush] No entries to process");
      return FlushResult.empty();
    }

    // 2. 파티션별 분류
    Map<Integer, Map<String, Long>> partitioned = partitionEntries(allEntries);

    // 3. 각 파티션 처리 (분산 락)
    int acquiredPartitions = 0;
    int processedEntries = 0;
    long totalDelta = 0;
    int failedPartitions = 0;

    for (Map.Entry<Integer, Map<String, Long>> partitionEntry : partitioned.entrySet()) {
      int partitionId = partitionEntry.getKey();
      Map<String, Long> entries = partitionEntry.getValue();

      PartitionResult result = processPartition(partitionId, entries, processor);

      if (result.acquired()) {
        acquiredPartitions++;
        processedEntries += result.processedCount();
        totalDelta += result.totalDelta();

        if (!result.success()) {
          failedPartitions++;
        }
      }
    }

    // 4. 메트릭 기록
    recordFlushMetrics(acquiredPartitions, processedEntries, totalDelta, failedPartitions);

    log.info(
        "[Flush] Completed: partitions={}/{}, entries={}, delta={}",
        acquiredPartitions,
        partitioned.size(),
        processedEntries,
        totalDelta);

    return new FlushResult(acquiredPartitions, processedEntries, totalDelta, failedPartitions);
  }

  /**
   * 단일 파티션 처리
   *
   * @param partitionId 파티션 ID
   * @param entries 처리할 데이터
   * @param processor 처리 함수
   * @return 처리 결과
   */
  private PartitionResult processPartition(
      int partitionId, Map<String, Long> entries, BiConsumer<String, Long> processor) {

    String lockKey = RedisKey.LIKE_FLUSH_PARTITION.withSuffix(String.valueOf(partitionId));
    RLock lock = redissonClient.getLock(lockKey);

    // tryLock: 이미 처리 중이면 스킵
    boolean acquired =
        executor.executeOrDefault(
            () -> lock.tryLock(lockWaitMs, lockLeaseMs, TimeUnit.MILLISECONDS),
            false,
            TaskContext.of("Flush", "TryLock", String.valueOf(partitionId)));

    if (!acquired) {
      log.debug("[Flush] Partition {} locked by another instance, skipping", partitionId);
      // 획득 실패한 데이터는 버퍼로 복원
      restoreEntries(entries);
      return PartitionResult.notAcquired();
    }

    // Section 12 준수: try-finally → executeWithFinally
    return executor.executeWithFinally(
        () -> doProcessPartition(partitionId, entries, processor),
        () -> unlockSafely(lock),
        TaskContext.of("Flush", "ProcessPartition", String.valueOf(partitionId)));
  }

  /** 파티션 데이터 처리 (락 획득 후) */
  private PartitionResult doProcessPartition(
      int partitionId, Map<String, Long> entries, BiConsumer<String, Long> processor) {

    int processedCount = 0;
    long totalDelta = 0;
    List<Map.Entry<String, Long>> failedEntries = new ArrayList<>();

    for (Map.Entry<String, Long> entry : entries.entrySet()) {
      String userIgn = entry.getKey();
      Long delta = entry.getValue();

      Boolean success =
          executor.executeOrDefault(
              () -> {
                processor.accept(userIgn, delta);
                return true;
              },
              false,
              TaskContext.of("Flush", "Process", userIgn));

      if (Boolean.TRUE.equals(success)) {
        processedCount++;
        totalDelta += delta;
      } else {
        failedEntries.add(entry);
      }
    }

    // 실패한 엔트리는 버퍼로 복원
    if (!failedEntries.isEmpty()) {
      restoreEntries(failedEntries);
      log.warn(
          "[Flush] Partition {} had {} failed entries, restored to buffer",
          partitionId,
          failedEntries.size());
    }

    return new PartitionResult(true, failedEntries.isEmpty(), processedCount, totalDelta);
  }

  /**
   * 엔트리를 파티션별로 분류
   *
   * @param entries 전체 엔트리
   * @return partitionId → entries 맵
   */
  private Map<Integer, Map<String, Long>> partitionEntries(Map<String, Long> entries) {
    Map<Integer, Map<String, Long>> partitioned = new HashMap<>();

    for (Map.Entry<String, Long> entry : entries.entrySet()) {
      int partitionId = getPartitionId(entry.getKey());
      partitioned
          .computeIfAbsent(partitionId, k -> new HashMap<>())
          .put(entry.getKey(), entry.getValue());
    }

    return partitioned;
  }

  /**
   * userIgn의 파티션 ID 계산
   *
   * @param userIgn 사용자 IGN
   * @return 0 ~ partitionCount-1
   */
  private int getPartitionId(String userIgn) {
    return Math.abs(userIgn.hashCode() % partitionCount);
  }

  /** 실패한 엔트리를 버퍼로 복원 */
  private void restoreEntries(Map<String, Long> entries) {
    entries.forEach((userIgn, delta) -> bufferStorage.increment(userIgn, delta));
    meterRegistry.counter("like.flush.restore.entries").increment(entries.size());
  }

  /** 실패한 엔트리를 버퍼로 복원 (List 버전) */
  private void restoreEntries(List<Map.Entry<String, Long>> entries) {
    entries.forEach(entry -> bufferStorage.increment(entry.getKey(), entry.getValue()));
    meterRegistry.counter("like.flush.restore.entries").increment(entries.size());
  }

  /** Flush 메트릭 기록 */
  private void recordFlushMetrics(int partitions, int entries, long delta, int failed) {
    meterRegistry.counter("like.flush.partitions.acquired").increment(partitions);
    meterRegistry.counter("like.flush.entries.processed").increment(entries);
    meterRegistry.counter("like.flush.delta.total").increment(delta);
    if (failed > 0) {
      meterRegistry.counter("like.flush.partitions.failed").increment(failed);
    }
  }

  /**
   * 락 안전 해제 (Section 15: 메서드 참조 분리)
   *
   * @param lock 해제할 락
   */
  private void unlockSafely(RLock lock) {
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }

  /**
   * Flush 결과
   *
   * @param acquiredPartitions 획득한 파티션 수
   * @param processedEntries 처리된 엔트리 수
   * @param totalDelta 처리된 총 delta
   * @param failedPartitions 실패한 파티션 수
   */
  public record FlushResult(
      int acquiredPartitions, int processedEntries, long totalDelta, int failedPartitions) {

    public static FlushResult empty() {
      return new FlushResult(0, 0, 0, 0);
    }

    public boolean hasProcessed() {
      return processedEntries > 0;
    }

    public boolean hasFailures() {
      return failedPartitions > 0;
    }
  }

  /** 개별 파티션 처리 결과 */
  private record PartitionResult(
      boolean acquired, boolean success, int processedCount, long totalDelta) {

    static PartitionResult notAcquired() {
      return new PartitionResult(false, false, 0, 0);
    }
  }
}
