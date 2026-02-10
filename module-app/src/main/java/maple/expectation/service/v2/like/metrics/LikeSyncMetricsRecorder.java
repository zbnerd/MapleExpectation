package maple.expectation.service.v2.like.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 좋아요 동기화 메트릭 기록기 (LikeSyncService에서 분리)
 *
 * <h3>책임: Micrometer 메트릭 기록</h3>
 *
 * <ul>
 *   <li>동기화 메트릭: like.sync.duration, like.sync.entries, like.sync.total.count,
 *       like.sync.failed.entries
 *   <li>복구 메트릭: like.sync.restore.count, like.sync.restore.likes
 *   <li>청크 메트릭: like.sync.chunk.processed, like.sync.chunk.failed
 * </ul>
 *
 * <h3>분해 근거</h3>
 *
 * <p>LikeSyncService(440줄)의 메트릭 관련 코드를 분리하여 SRP 준수 (CLAUDE.md Section 4)
 */
@Component
@RequiredArgsConstructor
public class LikeSyncMetricsRecorder {

  private final MeterRegistry meterRegistry;

  /**
   * LikeSync 동기화 메트릭 기록 (SRE 모니터링)
   *
   * @param entries 배치당 엔트리 수
   * @param totalCount 배치당 총 좋아요 수
   * @param failedEntries 실패 엔트리 수
   * @param startNanos 시작 시간 (System.nanoTime)
   * @param result 결과 태그 (success, empty)
   */
  public void recordSyncMetrics(
      int entries, long totalCount, long failedEntries, long startNanos, String result) {
    long durationNanos = System.nanoTime() - startNanos;

    meterRegistry
        .timer("like.sync.duration", "result", result)
        .record(durationNanos, TimeUnit.NANOSECONDS);

    meterRegistry.summary("like.sync.entries").record(entries);

    if (totalCount > 0) {
      meterRegistry.counter("like.sync.total.count", "result", result).increment(totalCount);
    }

    if (failedEntries > 0) {
      meterRegistry.counter("like.sync.failed.entries").increment(failedEntries);
    }
  }

  /**
   * 개별 복구 메트릭 기록
   *
   * @param result success | failure
   * @param count 복구된/실패한 좋아요 수
   */
  public void recordRestoreMetrics(String result, long count) {
    meterRegistry.counter("like.sync.restore.count", "result", result).increment();
    meterRegistry.counter("like.sync.restore.likes", "result", result).increment(count);
  }

  /** 청크 처리 성공 메트릭 기록 */
  public void recordChunkProcessed() {
    meterRegistry.counter("like.sync.chunk.processed").increment();
  }

  /** 청크 처리 실패 메트릭 기록 */
  public void recordChunkFailed() {
    meterRegistry.counter("like.sync.chunk.failed").increment();
  }
}
