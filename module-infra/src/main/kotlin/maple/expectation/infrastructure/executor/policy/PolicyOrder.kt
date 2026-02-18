package maple.expectation.infrastructure.executor.policy

/**
 * ExecutionPolicy 실행 순서 상수 정의 (Spring @Order 기준)
 *
 * Spring의 {@code @Order} 애노테이션은 낮은 값이 높은 우선순위를 가집니다.
 * 즉, 100이 200보다 먼저 실행됩니다.
 *
 * ## 실행 순서 규칙
 * - **BEFORE**: ORDER 값이 작을수록 먼저 실행 (0 → N)
 * - **AFTER**: entered 역순(LIFO, N → 0)
 *
 * ## 권장 순서
 * ```
 * 1. CONTEXT(100)    - 컨텍스트 설정 (TaskContext 초기화 등)
 * 2. TRACE(200)      - 분산 추적 (예: OpenTelemetry, Zipkin)
 * 3. LOGGING(300)    - 로깅 (실행 전후 로그 기록)
 * 4. METRICS(400)    - 메트릭 수집 (예: Prometheus, Micrometer)
 * 5. LOCK(5000)      - 분산 락 (BEFORE에서 늦게 획득 / AFTER에서 빨리 해제)
 * 6. FINALLY(9000)   - 자원 정리 (BEFORE에서 가장 늦게 / AFTER에서 가장 먼저 실행, early cleanup)
 * ```
 *
 * ## 사용 예시
 * ```kotlin
 * @Bean
 * @Order(PolicyOrder.LOGGING)
 * fun loggingPolicy(props: ExecutorLoggingProperties): LoggingPolicy {
 *     return LoggingPolicy(props.slowMs)
 * }
 * ```
 */
object PolicyOrder {
    /** 컨텍스트 설정 정책 (TaskContext 초기화 등) */
    const val CONTEXT = 100

    /** 분산 추적 정책 (예: OpenTelemetry, Zipkin) */
    const val TRACE = 200

    /** 로깅 정책 (실행 전후 로그 기록) */
    const val LOGGING = 300

    /** 메트릭 수집 정책 (예: Prometheus, Micrometer) */
    const val METRICS = 400

    /**
     * 분산 락 정책
     *
     * ORDER를 크게 두어 BEFORE에서 늦게 획득하고, AFTER에서 LIFO 특성상 빠르게 해제하여
     * 락 점유 시간을 최소화합니다.
     */
    const val LOCK = 5_000

    /**
     * 자원 정리 정책
     *
     * ORDER가 큰 정책은 BEFORE에서 늦게 실행되고(entered의 뒤쪽),
     * AFTER에서는 LIFO 특성상 먼저 실행됩니다.
     *
     * 즉, FINALLY를 크게 두면 AFTER 초기에 cleanup이 수행되어
     * 리소스 해제(락/ThreadLocal 정리 등)를 빠르게 할 수 있습니다.
     */
    const val FINALLY = 9_000
}
