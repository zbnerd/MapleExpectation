package maple.expectation.monitoring.context;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.collector.MetricCategory;
import maple.expectation.monitoring.collector.MetricsCollectorStrategy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시스템 컨텍스트 제공자 (Facade 패턴)
 *
 * <h3>Issue #251: AI SRE 모니터링</h3>
 * <p>여러 MetricsCollectorStrategy를 통합하여 시스템 상태를 종합적으로 제공합니다.</p>
 *
 * <h4>Facade 패턴 적용</h4>
 * <ul>
 *   <li>복잡한 메트릭 수집 로직을 단순 인터페이스로 제공</li>
 *   <li>AI SRE Analyzer에 필요한 컨텍스트 통합</li>
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 * <ul>
 *   <li>Section 4 (SOLID): Facade로 복잡성 캡슐화</li>
 *   <li>Section 6 (Design Patterns): Factory + Strategy 조합</li>
 *   <li>Section 12 (LogicExecutor): 수집 실패 시 안전한 폴백</li>
 * </ul>
 *
 * @see MetricsCollectorStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemContextProvider {

    private final List<MetricsCollectorStrategy> collectors;
    private final LogicExecutor executor;

    /**
     * 전체 시스템 컨텍스트 수집
     *
     * @return 카테고리별 메트릭 맵
     */
    public Map<MetricCategory, Map<String, Object>> collectAllMetrics() {
        Map<MetricCategory, Map<String, Object>> result = new EnumMap<>(MetricCategory.class);

        // 우선순위 순으로 정렬하여 수집
        collectors.stream()
                .sorted(Comparator.comparingInt(MetricsCollectorStrategy::getOrder))
                .forEach(collector -> collectSafely(collector, result));

        return result;
    }

    /**
     * 특정 카테고리 메트릭만 수집
     *
     * @param categories 수집할 카테고리들
     * @return 요청된 카테고리의 메트릭 맵
     */
    public Map<MetricCategory, Map<String, Object>> collectMetrics(MetricCategory... categories) {
        Map<MetricCategory, Map<String, Object>> result = new EnumMap<>(MetricCategory.class);

        for (MetricCategory category : categories) {
            collectors.stream()
                    .filter(c -> c.supports(category))
                    .findFirst()
                    .ifPresent(collector -> collectSafely(collector, result));
        }

        return result;
    }

    /**
     * AI 분석용 시스템 컨텍스트 문자열 생성
     *
     * @return AI 분석에 적합한 형식의 시스템 상태 문자열
     */
    public String buildContextForAi() {
        Map<MetricCategory, Map<String, Object>> allMetrics = collectAllMetrics();
        StringBuilder sb = new StringBuilder();

        sb.append("=== System Context at ").append(Instant.now()).append(" ===\n\n");

        allMetrics.forEach((category, metrics) -> {
            sb.append("[").append(category.getDisplayName()).append("]\n");
            metrics.forEach((key, value) -> {
                // 중첩 맵 처리 (Circuit Breaker 상세 등)
                if (value instanceof Map) {
                    sb.append("  ").append(key).append(":\n");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) value;
                    nested.forEach((k, v) -> sb.append("    ").append(k).append(": ").append(v).append("\n"));
                } else {
                    sb.append("  ").append(key).append(": ").append(value).append("\n");
                }
            });
            sb.append("\n");
        });

        return sb.toString();
    }

    /**
     * 핵심 지표 요약 생성 (Discord 알림용)
     *
     * @return 핵심 지표 요약 문자열
     */
    public String buildSummary() {
        Map<MetricCategory, Map<String, Object>> metrics = collectMetrics(
                MetricCategory.GOLDEN_SIGNALS,
                MetricCategory.CIRCUIT_BREAKER
        );

        Map<String, Object> summary = new LinkedHashMap<>();

        // Golden Signals 요약
        metrics.getOrDefault(MetricCategory.GOLDEN_SIGNALS, Map.of()).forEach((key, value) -> {
            if (key.contains("latency_p95") || key.contains("error_rate") || key.contains("saturation")) {
                summary.put(key, value);
            }
        });

        // Circuit Breaker 요약
        Map<String, Object> cbMetrics = metrics.getOrDefault(MetricCategory.CIRCUIT_BREAKER, Map.of());
        summary.put("cb_open_count", cbMetrics.getOrDefault("summary_open_count", 0L));

        StringBuilder sb = new StringBuilder();
        summary.forEach((k, v) -> sb.append(k).append(": ").append(v).append(" | "));

        return sb.toString();
    }

    /**
     * 안전하게 메트릭 수집 (실패 시 빈 맵 반환)
     */
    private void collectSafely(MetricsCollectorStrategy collector, Map<MetricCategory, Map<String, Object>> result) {
        TaskContext context = TaskContext.of("Monitoring", "Collect", collector.getCategoryName());

        Map<String, Object> metrics = executor.executeOrDefault(
                collector::collect,
                Map.of("error", "Collection failed"),
                context
        );

        // 해당 카테고리 찾기
        for (MetricCategory category : MetricCategory.values()) {
            if (collector.supports(category)) {
                result.put(category, metrics);
                break;
            }
        }
    }
}
