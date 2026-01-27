package maple.expectation.monitoring.collector;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Database 메트릭 수집기 (Issue #251)
 *
 * <h3>수집 항목</h3>
 * <ul>
 *   <li>HikariCP 커넥션 풀 상태</li>
 *   <li>커넥션 획득 대기 시간</li>
 *   <li>활성/유휴 커넥션 수</li>
 * </ul>
 *
 * @see MetricsCollectorStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMetricsCollector implements MetricsCollectorStrategy {

    private final MeterRegistry meterRegistry;

    @Override
    public String getCategoryName() {
        return MetricCategory.DATABASE.getKey();
    }

    @Override
    public Map<String, Object> collect() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // HikariCP Connection Pool
        collectHikariMetrics(metrics);

        return metrics;
    }

    @Override
    public boolean supports(MetricCategory category) {
        return MetricCategory.DATABASE == category;
    }

    @Override
    public int getOrder() {
        return 4;
    }

    private void collectHikariMetrics(Map<String, Object> metrics) {
        // 활성 커넥션
        Gauge active = meterRegistry.find("hikaricp.connections.active")
                .gauge();
        if (active != null) {
            metrics.put("connections_active", (int) active.value());
        }

        // 유휴 커넥션
        Gauge idle = meterRegistry.find("hikaricp.connections.idle")
                .gauge();
        if (idle != null) {
            metrics.put("connections_idle", (int) idle.value());
        }

        // 최대 커넥션
        Gauge max = meterRegistry.find("hikaricp.connections.max")
                .gauge();
        if (max != null) {
            metrics.put("connections_max", (int) max.value());
        }

        // 대기 중인 스레드
        Gauge pending = meterRegistry.find("hikaricp.connections.pending")
                .gauge();
        if (pending != null) {
            metrics.put("connections_pending", (int) pending.value());
        }

        // 커넥션 획득 시간
        var acquireTimer = meterRegistry.find("hikaricp.connections.acquire")
                .timer();
        if (acquireTimer != null) {
            metrics.put("acquire_mean_ms", formatDouble(acquireTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS)));
            metrics.put("acquire_max_ms", formatDouble(acquireTimer.max(java.util.concurrent.TimeUnit.MILLISECONDS)));
        }

        // 커넥션 사용 시간
        var usageTimer = meterRegistry.find("hikaricp.connections.usage")
                .timer();
        if (usageTimer != null) {
            metrics.put("usage_mean_ms", formatDouble(usageTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS)));
            metrics.put("usage_max_ms", formatDouble(usageTimer.max(java.util.concurrent.TimeUnit.MILLISECONDS)));
        }

        // 타임아웃 카운트
        var timeoutCounter = meterRegistry.find("hikaricp.connections.timeout")
                .counter();
        if (timeoutCounter != null) {
            metrics.put("timeout_count", (long) timeoutCounter.count());
        }

        // 포화도 계산
        if (active != null && max != null && max.value() > 0) {
            double saturation = (active.value() / max.value()) * 100;
            metrics.put("saturation_percent", formatDouble(saturation));
        }
    }

    private double formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
