package maple.expectation.monitoring.collector;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Circuit Breaker 메트릭 수집기 (Issue #251)
 *
 * <h3>수집 항목</h3>
 * <ul>
 *   <li>각 Circuit Breaker 상태 (CLOSED, OPEN, HALF_OPEN)</li>
 *   <li>실패율, 호출 수</li>
 *   <li>느린 호출 비율</li>
 * </ul>
 *
 * @see MetricsCollectorStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerMetricsCollector implements MetricsCollectorStrategy {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public String getCategoryName() {
        return MetricCategory.CIRCUIT_BREAKER.getKey();
    }

    @Override
    public Map<String, Object> collect() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            String name = cb.getName();
            CircuitBreaker.Metrics cbMetrics = cb.getMetrics();

            Map<String, Object> cbData = new LinkedHashMap<>();
            cbData.put("state", cb.getState().name());
            cbData.put("failure_rate", formatDouble(cbMetrics.getFailureRate()));
            cbData.put("slow_call_rate", formatDouble(cbMetrics.getSlowCallRate()));
            cbData.put("buffered_calls", cbMetrics.getNumberOfBufferedCalls());
            cbData.put("failed_calls", cbMetrics.getNumberOfFailedCalls());
            cbData.put("successful_calls", cbMetrics.getNumberOfSuccessfulCalls());
            cbData.put("not_permitted_calls", cbMetrics.getNumberOfNotPermittedCalls());

            metrics.put(name, cbData);
        });

        // 요약 통계
        long openCount = circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .filter(cb -> cb.getState() == CircuitBreaker.State.OPEN)
                .count();
        long halfOpenCount = circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .filter(cb -> cb.getState() == CircuitBreaker.State.HALF_OPEN)
                .count();

        metrics.put("summary_open_count", openCount);
        metrics.put("summary_half_open_count", halfOpenCount);
        metrics.put("summary_total_count", (long) circuitBreakerRegistry.getAllCircuitBreakers().size());

        return metrics;
    }

    @Override
    public boolean supports(MetricCategory category) {
        return MetricCategory.CIRCUIT_BREAKER == category;
    }

    @Override
    public int getOrder() {
        return 3;
    }

    private double formatDouble(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return -1.0; // 데이터 없음 표시
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
