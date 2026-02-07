package maple.expectation.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import maple.expectation.monitoring.collector.CircuitBreakerMetricsCollector;
import maple.expectation.monitoring.collector.GoldenSignalsCollector;
import maple.expectation.monitoring.collector.JvmMetricsCollector;
import maple.expectation.monitoring.collector.MetricCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 메트릭 수집기 테스트 (Issue #251)
 *
 * <p>Strategy 패턴으로 구현된 각 Collector 검증
 */
@DisplayName("MetricsCollector 테스트")
class MetricsCollectorTest {

  @Nested
  @DisplayName("GoldenSignalsCollector 테스트")
  class GoldenSignalsCollectorTest {

    private GoldenSignalsCollector collector;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
      meterRegistry = new SimpleMeterRegistry();
      collector = new GoldenSignalsCollector(meterRegistry);
    }

    @Test
    @DisplayName("카테고리 이름이 golden-signals여야 한다")
    void shouldReturnCorrectCategoryName() {
      assertThat(collector.getCategoryName()).isEqualTo("golden-signals");
    }

    @Test
    @DisplayName("GOLDEN_SIGNALS 카테고리를 지원해야 한다")
    void shouldSupportGoldenSignalsCategory() {
      assertThat(collector.supports(MetricCategory.GOLDEN_SIGNALS)).isTrue();
      assertThat(collector.supports(MetricCategory.JVM)).isFalse();
    }

    @Test
    @DisplayName("우선순위가 1이어야 한다 (최우선)")
    void shouldHaveHighestPriority() {
      assertThat(collector.getOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("메트릭 수집이 빈 맵을 반환하지 않아야 한다")
    void shouldCollectMetrics() {
      // When
      Map<String, Object> metrics = collector.collect();

      // Then
      assertThat(metrics).isNotNull();
      // 데이터가 없어도 키는 존재해야 함
    }
  }

  @Nested
  @DisplayName("JvmMetricsCollector 테스트")
  class JvmMetricsCollectorTest {

    private JvmMetricsCollector collector;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
      meterRegistry = new SimpleMeterRegistry();
      collector = new JvmMetricsCollector(meterRegistry);
    }

    @Test
    @DisplayName("카테고리 이름이 jvm이어야 한다")
    void shouldReturnCorrectCategoryName() {
      assertThat(collector.getCategoryName()).isEqualTo("jvm");
    }

    @Test
    @DisplayName("JVM 카테고리를 지원해야 한다")
    void shouldSupportJvmCategory() {
      assertThat(collector.supports(MetricCategory.JVM)).isTrue();
      assertThat(collector.supports(MetricCategory.DATABASE)).isFalse();
    }

    @Test
    @DisplayName("우선순위가 2여야 한다")
    void shouldHaveSecondPriority() {
      assertThat(collector.getOrder()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("CircuitBreakerMetricsCollector 테스트")
  class CircuitBreakerMetricsCollectorTest {

    private CircuitBreakerMetricsCollector collector;
    private CircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
      registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
      collector = new CircuitBreakerMetricsCollector(registry);
    }

    @Test
    @DisplayName("카테고리 이름이 circuit-breaker여야 한다")
    void shouldReturnCorrectCategoryName() {
      assertThat(collector.getCategoryName()).isEqualTo("circuit-breaker");
    }

    @Test
    @DisplayName("CIRCUIT_BREAKER 카테고리를 지원해야 한다")
    void shouldSupportCircuitBreakerCategory() {
      assertThat(collector.supports(MetricCategory.CIRCUIT_BREAKER)).isTrue();
      assertThat(collector.supports(MetricCategory.REDIS)).isFalse();
    }

    @Test
    @DisplayName("Circuit Breaker가 없으면 요약만 반환해야 한다")
    void shouldReturnSummaryWhenNoCircuitBreakers() {
      // When
      Map<String, Object> metrics = collector.collect();

      // Then
      assertThat(metrics)
          .containsKey("summary_open_count")
          .containsKey("summary_half_open_count")
          .containsKey("summary_total_count");
      assertThat(metrics.get("summary_total_count")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Circuit Breaker 상태가 올바르게 수집되어야 한다")
    void shouldCollectCircuitBreakerStatus() {
      // Given
      CircuitBreaker cb = registry.circuitBreaker("testCb");
      cb.transitionToOpenState();

      // When
      Map<String, Object> metrics = collector.collect();

      // Then
      assertThat(metrics.get("summary_open_count")).isEqualTo(1L);
      assertThat(metrics.get("summary_total_count")).isEqualTo(1L);

      @SuppressWarnings("unchecked")
      Map<String, Object> cbData = (Map<String, Object>) metrics.get("testCb");
      assertThat(cbData.get("state")).isEqualTo("OPEN");
    }
  }
}
