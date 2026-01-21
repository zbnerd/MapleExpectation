package maple.expectation.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.InsufficientResourceException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import maple.expectation.service.v2.starforce.StarforceLookupTable;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lookup Table 초기화 ApplicationRunner (#240)
 *
 * <h3>5-Agent Council SRE 요구사항 (Red Agent)</h3>
 * <ul>
 *   <li>@Order(HIGHEST_PRECEDENCE): 다른 Runner보다 먼저 실행</li>
 *   <li>Pre-flight Memory Validation: OOM 방지</li>
 *   <li>Health Check Gating: 초기화 완료 전 트래픽 차단</li>
 *   <li>Startup 메트릭 노출: 초기화 시간 기록</li>
 * </ul>
 *
 * <h3>Failure Mode</h3>
 * <ul>
 *   <li>메모리 부족: InsufficientResourceException → 애플리케이션 시작 실패</li>
 *   <li>초기화 실패: 로그 기록 후 Graceful Degradation (fallback to on-the-fly 계산)</li>
 * </ul>
 *
 * @see StarforceLookupTable Starforce 기대값 Lookup Table
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class LookupTableInitializer implements ApplicationRunner {

    private static final long REQUIRED_HEAP_BYTES = 2 * 1024 * 1024; // 2MB for lookup tables
    private static final double SAFETY_MARGIN = 1.5;

    private final StarforceLookupTable starforceLookupTable;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Override
    public void run(ApplicationArguments args) {
        TaskContext context = TaskContext.of("Startup", "LookupTableInit");

        log.info("[LookupTableInitializer] Starting initialization...");
        long startTime = System.nanoTime();

        executor.executeWithTranslation(
                () -> {
                    validateHeapAvailability();
                    initializeTables();
                    initialized.set(true);
                    recordInitializationTime(startTime);
                    return null;
                },
                ExceptionTranslator.forStartup("LookupTableInitializer"),
                context
        );
    }

    /**
     * Pre-flight 메모리 검증 (Red Agent 요구사항)
     *
     * <p>Lookup Table 로딩에 필요한 최소 메모리 확보 여부 확인</p>
     *
     * @throws InsufficientResourceException 메모리 부족 시
     */
    private void validateHeapAvailability() {
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        long requiredWithMargin = (long) (REQUIRED_HEAP_BYTES * SAFETY_MARGIN);

        if (freeMemory < requiredWithMargin) {
            String message = String.format(
                    "Insufficient heap for lookup tables. Required: %dMB, Available: %dMB",
                    requiredWithMargin / (1024 * 1024),
                    freeMemory / (1024 * 1024)
            );
            log.error("[LookupTableInitializer] {}", message);
            throw new InsufficientResourceException(message);
        }

        log.info("[LookupTableInitializer] Memory check passed. Available: {}MB, Required: {}MB",
                freeMemory / (1024 * 1024), requiredWithMargin / (1024 * 1024));
    }

    /**
     * Lookup Table 초기화
     */
    private void initializeTables() {
        log.info("[LookupTableInitializer] Initializing Starforce Lookup Table...");
        starforceLookupTable.initialize();
        log.info("[LookupTableInitializer] Starforce Lookup Table initialized successfully");
    }

    /**
     * 초기화 시간 메트릭 기록 (Red Agent 요구사항)
     *
     * <h4>Prometheus Alert 권장 임계값</h4>
     * <ul>
     *   <li>>30s: WARNING (startup delay)</li>
     *   <li>>60s: CRITICAL (investigate OOM)</li>
     * </ul>
     */
    private void recordInitializationTime(long startNanos) {
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        Timer.builder("startup.lookup.table.init")
                .description("Lookup Table 초기화 시간")
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);

        log.info("[LookupTableInitializer] Initialization completed in {}ms", elapsedMillis);
    }

    /**
     * Health Check용 초기화 상태 확인
     *
     * <p>Kubernetes readiness probe 등에서 사용</p>
     *
     * @return true if all lookup tables are initialized
     */
    public boolean isReady() {
        return initialized.get() && starforceLookupTable.isInitialized();
    }
}
