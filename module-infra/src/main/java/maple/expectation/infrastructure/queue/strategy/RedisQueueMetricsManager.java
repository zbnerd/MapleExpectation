package maple.expectation.infrastructure.queue.strategy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import maple.expectation.infrastructure.queue.QueueType;

/**
 * Redis Queue Metrics Manager - 메트릭 등록 및 카운터 관리 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>Gauge 등록 (pending, inflight, retry, dlq)
 *   <li>AtomicLong 카운터 래핑
 *   <li>메트릭 태그 설정 (strategy 태그)
 * </ul>
 */
@RequiredArgsConstructor
public class RedisQueueMetricsManager {

  private final MeterRegistry meterRegistry;

  /** 카운터 캐시 (성능 최적화) */
  private final AtomicLong cachedPendingCount = new AtomicLong(0);

  private final AtomicLong cachedInflightCount = new AtomicLong(0);
  private final AtomicLong cachedRetryCount = new AtomicLong(0);
  private final AtomicLong cachedDlqCount = new AtomicLong(0);

  /**
   * 메트릭 등록
   *
   * @param strategyType 큐 전략 타입 (메트릭 태그용)
   */
  public void registerMetrics(QueueType strategyType) {
    String strategyTag = strategyType.name();

    Gauge.builder("queue.pending", cachedPendingCount, AtomicLong::get)
        .tag("strategy", strategyTag)
        .description("대기 중인 메시지 수")
        .register(meterRegistry);

    Gauge.builder("queue.inflight", cachedInflightCount, AtomicLong::get)
        .tag("strategy", strategyTag)
        .description("처리 중인 메시지 수")
        .register(meterRegistry);

    Gauge.builder("queue.retry", cachedRetryCount, AtomicLong::get)
        .tag("strategy", strategyTag)
        .description("재시도 대기 중인 메시지 수")
        .register(meterRegistry);

    Gauge.builder("queue.dlq", cachedDlqCount, AtomicLong::get)
        .tag("strategy", strategyTag)
        .description("DLQ 메시지 수")
        .register(meterRegistry);
  }

  // ==================== Counter Accessors ====================

  public AtomicLong getCachedPendingCount() {
    return cachedPendingCount;
  }

  public AtomicLong getCachedInflightCount() {
    return cachedInflightCount;
  }

  public AtomicLong getCachedRetryCount() {
    return cachedRetryCount;
  }

  public AtomicLong getCachedDlqCount() {
    return cachedDlqCount;
  }
}
