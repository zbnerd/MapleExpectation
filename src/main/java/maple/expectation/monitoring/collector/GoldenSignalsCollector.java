package maple.expectation.monitoring.collector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Golden Signals 메트릭 수집기 (Issue #251)
 *
 * <h3>4대 핵심 지표 (Google SRE Book)</h3>
 * <ul>
 *   <li><b>Latency</b>: 요청 처리 시간 (p50, p95, p99)</li>
 *   <li><b>Traffic</b>: 초당 요청 수 (RPS)</li>
 *   <li><b>Errors</b>: 에러율 (5xx / total)</li>
 *   <li><b>Saturation</b>: 자원 포화도 (버퍼, 커넥션 풀)</li>
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 * <ul>
 *   <li>Section 4 (SOLID): Strategy 패턴 구현</li>
 *   <li>Section 12 (LogicExecutor): 예외 없는 조회 로직</li>
 * </ul>
 *
 * @see MetricsCollectorStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoldenSignalsCollector implements MetricsCollectorStrategy {

    private final MeterRegistry meterRegistry;

    @Override
    public String getCategoryName() {
        return MetricCategory.GOLDEN_SIGNALS.getKey();
    }

    @Override
    public Map<String, Object> collect() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // 1. Latency (HTTP 요청 처리 시간)
        collectLatencyMetrics(metrics);

        // 2. Traffic (RPS)
        collectTrafficMetrics(metrics);

        // 3. Errors (에러율)
        collectErrorMetrics(metrics);

        // 4. Saturation (버퍼 포화도)
        collectSaturationMetrics(metrics);

        return metrics;
    }

    @Override
    public boolean supports(MetricCategory category) {
        return MetricCategory.GOLDEN_SIGNALS == category;
    }

    @Override
    public int getOrder() {
        return 1; // 최우선 수집
    }

    /**
     * Latency 메트릭 수집 (p50, p95, p99)
     */
    private void collectLatencyMetrics(Map<String, Object> metrics) {
        Timer httpTimer = meterRegistry.find("http.server.requests")
                .timer();

        if (httpTimer != null) {
            metrics.put("latency_p50_ms", formatDouble(httpTimer.percentile(0.5, TimeUnit.MILLISECONDS)));
            metrics.put("latency_p95_ms", formatDouble(httpTimer.percentile(0.95, TimeUnit.MILLISECONDS)));
            metrics.put("latency_p99_ms", formatDouble(httpTimer.percentile(0.99, TimeUnit.MILLISECONDS)));
            metrics.put("latency_mean_ms", formatDouble(httpTimer.mean(TimeUnit.MILLISECONDS)));
            metrics.put("latency_max_ms", formatDouble(httpTimer.max(TimeUnit.MILLISECONDS)));
        } else {
            metrics.put("latency_status", "NO_DATA");
        }
    }

    /**
     * Traffic 메트릭 수집 (RPS)
     */
    private void collectTrafficMetrics(Map<String, Object> metrics) {
        Counter requestCounter = meterRegistry.find("http.server.requests")
                .counter();

        if (requestCounter != null) {
            metrics.put("total_requests", (long) requestCounter.count());
        }

        // Nexon API 호출 통계
        Timer nexonTimer = meterRegistry.find("nexon.api.performance")
                .timer();

        if (nexonTimer != null) {
            metrics.put("nexon_api_calls", nexonTimer.count());
            metrics.put("nexon_api_mean_ms", formatDouble(nexonTimer.mean(TimeUnit.MILLISECONDS)));
        }
    }

    /**
     * Error 메트릭 수집
     */
    private void collectErrorMetrics(Map<String, Object> metrics) {
        // 5xx 에러 카운트
        Counter errorCounter = meterRegistry.find("http.server.requests")
                .tag("status", "5xx")
                .counter();

        Counter totalCounter = meterRegistry.find("http.server.requests")
                .counter();

        if (errorCounter != null && totalCounter != null && totalCounter.count() > 0) {
            double errorRate = (errorCounter.count() / totalCounter.count()) * 100;
            metrics.put("error_rate_percent", formatDouble(errorRate));
            metrics.put("error_count_5xx", (long) errorCounter.count());
        } else {
            metrics.put("error_rate_percent", 0.0);
        }
    }

    /**
     * Saturation 메트릭 수집 (버퍼, 커넥션 풀)
     */
    private void collectSaturationMetrics(Map<String, Object> metrics) {
        // HikariCP 커넥션 풀 포화도
        var activeConnections = meterRegistry.find("hikaricp.connections.active")
                .gauge();
        var maxConnections = meterRegistry.find("hikaricp.connections.max")
                .gauge();

        if (activeConnections != null && maxConnections != null && maxConnections.value() > 0) {
            double saturation = (activeConnections.value() / maxConnections.value()) * 100;
            metrics.put("db_pool_saturation_percent", formatDouble(saturation));
            metrics.put("db_active_connections", (int) activeConnections.value());
            metrics.put("db_max_connections", (int) maxConnections.value());
        }

        // 버퍼 대기 항목 수
        var bufferPending = meterRegistry.find("buffer.pending")
                .gauge();

        if (bufferPending != null) {
            metrics.put("buffer_pending_count", (long) bufferPending.value());
        }
    }

    /**
     * Double 값 포맷팅 (NaN/Infinity 처리)
     */
    private double formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
