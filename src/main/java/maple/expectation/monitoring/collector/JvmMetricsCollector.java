package maple.expectation.monitoring.collector;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM 메트릭 수집기 (Issue #251)
 *
 * <h3>수집 항목</h3>
 * <ul>
 *   <li>Heap Memory (used, max, committed)</li>
 *   <li>GC 통계 (count, time)</li>
 *   <li>Thread Count (live, peak, daemon)</li>
 *   <li>Class Loading</li>
 * </ul>
 *
 * @see MetricsCollectorStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JvmMetricsCollector implements MetricsCollectorStrategy {

    private final MeterRegistry meterRegistry;

    @Override
    public String getCategoryName() {
        return MetricCategory.JVM.getKey();
    }

    @Override
    public Map<String, Object> collect() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // 1. Heap Memory
        collectHeapMetrics(metrics);

        // 2. GC Statistics
        collectGcMetrics(metrics);

        // 3. Thread Count
        collectThreadMetrics(metrics);

        return metrics;
    }

    @Override
    public boolean supports(MetricCategory category) {
        return MetricCategory.JVM == category;
    }

    @Override
    public int getOrder() {
        return 2;
    }

    private void collectHeapMetrics(Map<String, Object> metrics) {
        Gauge heapUsed = meterRegistry.find("jvm.memory.used")
                .tag("area", "heap")
                .gauge();
        Gauge heapMax = meterRegistry.find("jvm.memory.max")
                .tag("area", "heap")
                .gauge();
        Gauge heapCommitted = meterRegistry.find("jvm.memory.committed")
                .tag("area", "heap")
                .gauge();

        if (heapUsed != null) {
            metrics.put("heap_used_mb", toMb(heapUsed.value()));
        }
        if (heapMax != null && heapMax.value() > 0) {
            metrics.put("heap_max_mb", toMb(heapMax.value()));
            if (heapUsed != null) {
                double usagePercent = (heapUsed.value() / heapMax.value()) * 100;
                metrics.put("heap_usage_percent", formatDouble(usagePercent));
            }
        }
        if (heapCommitted != null) {
            metrics.put("heap_committed_mb", toMb(heapCommitted.value()));
        }
    }

    private void collectGcMetrics(Map<String, Object> metrics) {
        // GC Pause Time
        var gcPauseTime = meterRegistry.find("jvm.gc.pause")
                .timer();

        if (gcPauseTime != null) {
            metrics.put("gc_pause_count", gcPauseTime.count());
            metrics.put("gc_pause_total_ms", formatDouble(gcPauseTime.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)));
        }

        // GC Memory Allocated
        var gcAlloc = meterRegistry.find("jvm.gc.memory.allocated")
                .counter();

        if (gcAlloc != null) {
            metrics.put("gc_memory_allocated_mb", toMb(gcAlloc.count()));
        }
    }

    private void collectThreadMetrics(Map<String, Object> metrics) {
        Gauge liveThreads = meterRegistry.find("jvm.threads.live")
                .gauge();
        Gauge peakThreads = meterRegistry.find("jvm.threads.peak")
                .gauge();
        Gauge daemonThreads = meterRegistry.find("jvm.threads.daemon")
                .gauge();

        if (liveThreads != null) {
            metrics.put("threads_live", (int) liveThreads.value());
        }
        if (peakThreads != null) {
            metrics.put("threads_peak", (int) peakThreads.value());
        }
        if (daemonThreads != null) {
            metrics.put("threads_daemon", (int) daemonThreads.value());
        }
    }

    private long toMb(double bytes) {
        return (long) (bytes / (1024 * 1024));
    }

    private double formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
