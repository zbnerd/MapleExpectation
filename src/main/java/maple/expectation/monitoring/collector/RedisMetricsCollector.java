package maple.expectation.monitoring.collector;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.repository.RedisBufferRepository;
import org.springframework.stereotype.Component;

/**
 * Redis 메트릭 수집기 (Issue #251)
 *
 * <h3>수집 항목</h3>
 *
 * <ul>
 *   <li>버퍼 대기 항목 수
 *   <li>캐시 히트율
 *   <li>커넥션 상태
 * </ul>
 *
 * @see MetricsCollectorStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMetricsCollector implements MetricsCollectorStrategy {

  private final MeterRegistry meterRegistry;
  private final RedisBufferRepository redisBufferRepository;
  private final maple.expectation.config.MonitoringThresholdProperties thresholdProperties;

  @Override
  public String getCategoryName() {
    return MetricCategory.REDIS.getKey();
  }

  @Override
  public Map<String, Object> collect() {
    Map<String, Object> metrics = new LinkedHashMap<>();

    // 버퍼 상태
    collectBufferMetrics(metrics);

    // 캐시 히트율
    collectCacheMetrics(metrics);

    return metrics;
  }

  @Override
  public boolean supports(MetricCategory category) {
    return MetricCategory.REDIS == category;
  }

  @Override
  public int getOrder() {
    return 5;
  }

  private void collectBufferMetrics(Map<String, Object> metrics) {
    // Redis 버퍼 대기 항목 수 (직접 조회)
    long pendingCount = redisBufferRepository.getTotalPendingCount();
    metrics.put("buffer_pending_count", pendingCount);

    // 버퍼 포화도 (임계값 기준 - 설정에서 가져옴)
    double saturation = (pendingCount / thresholdProperties.bufferSaturationDouble()) * 100;
    metrics.put("buffer_saturation_percent", Math.min(formatDouble(saturation), 100.0));

    // Micrometer 메트릭에서 추가 정보
    Gauge bufferGauge = meterRegistry.find("redis.buffer.pending").gauge();
    if (bufferGauge != null) {
      metrics.put("buffer_gauge_value", (long) bufferGauge.value());
    }
  }

  private void collectCacheMetrics(Map<String, Object> metrics) {
    // L1 캐시 (Caffeine) 히트율
    Gauge caffeineHits = meterRegistry.find("cache.gets").tag("result", "hit").gauge();
    Gauge caffeineMisses = meterRegistry.find("cache.gets").tag("result", "miss").gauge();

    if (caffeineHits != null && caffeineMisses != null) {
      double hits = caffeineHits.value();
      double misses = caffeineMisses.value();
      double total = hits + misses;
      if (total > 0) {
        metrics.put("l1_cache_hit_rate", formatDouble((hits / total) * 100));
      }
    }

    // L2 캐시 (Redis) 관련 메트릭
    var l2HitCounter = meterRegistry.find("tiered.cache.hit").tag("layer", "L2").counter();
    var l2MissCounter = meterRegistry.find("tiered.cache.miss").tag("layer", "L2").counter();

    if (l2HitCounter != null && l2MissCounter != null) {
      double l2Hits = l2HitCounter.count();
      double l2Misses = l2MissCounter.count();
      double l2Total = l2Hits + l2Misses;
      if (l2Total > 0) {
        metrics.put("l2_cache_hit_rate", formatDouble((l2Hits / l2Total) * 100));
      }
    }
  }

  private double formatDouble(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return 0.0;
    }
    return Math.round(value * 100.0) / 100.0;
  }
}
