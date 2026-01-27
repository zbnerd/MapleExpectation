package maple.expectation.monitoring.collector;

/**
 * 메트릭 수집 카테고리 (Issue #251)
 *
 * <p>11대 카테고리로 시스템 관측성을 체계화합니다.</p>
 *
 * @see MetricsCollectorStrategy
 */
public enum MetricCategory {

    /**
     * Golden Signals (4대 핵심 지표)
     * - Latency, Error Rate, Traffic (RPS), Saturation
     */
    GOLDEN_SIGNALS("golden-signals", "4대 핵심 신호"),

    /**
     * JVM 메트릭
     * - Heap, GC, Thread Count
     */
    JVM("jvm", "JVM 상태"),

    /**
     * 데이터베이스 메트릭
     * - Connection Pool, Query Latency
     */
    DATABASE("database", "데이터베이스"),

    /**
     * Redis 메트릭
     * - Connection, Memory, Hit Rate
     */
    REDIS("redis", "Redis 캐시"),

    /**
     * 외부 API 메트릭
     * - Nexon API Latency, Error Count
     */
    EXTERNAL_API("external-api", "외부 API"),

    /**
     * Circuit Breaker 메트릭
     * - State, Failure Rate
     */
    CIRCUIT_BREAKER("circuit-breaker", "서킷브레이커"),

    /**
     * 보안 메트릭
     * - Auth Failure, Rate Limit
     */
    SECURITY("security", "보안"),

    /**
     * 비즈니스 메트릭
     * - Active Users, Calculations
     */
    BUSINESS("business", "비즈니스"),

    /**
     * Batch 메트릭
     * - Job Status, Duration
     */
    BATCH("batch", "배치 작업"),

    /**
     * 로깅 메트릭
     * - Error Count, Warning Count
     */
    LOGGING("logging", "로깅"),

    /**
     * 인프라 메트릭
     * - CPU, Memory, Disk
     */
    INFRA("infra", "인프라");

    private final String key;
    private final String displayName;

    MetricCategory(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }
}
