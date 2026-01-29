package maple.expectation.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 프리셋 병렬 계산 전용 Executor (#266 P1 Deadlock 방지)
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Red (SRE): equipmentExecutor와 분리하여 Deadlock 방지</li>
 *   <li>Green (Performance): CallerRunsPolicy로 큐 포화 시 직접 실행</li>
 * </ul>
 *
 * <h3>P1 Deadlock 문제 해결</h3>
 * <pre>
 * 문제 상황:
 * 요청 스레드 (equipmentExecutor) → calculateAllPresetsParallel()
 *     └── CompletableFuture.supplyAsync(equipmentExecutor) × 3
 *         └── 부모가 자식의 .join() 대기
 *             └── 자식이 큐에서 부모 스레드 대기 → Deadlock!
 *
 * 해결:
 * 별도 presetCalculationExecutor 사용으로 스레드 풀 분리
 * </pre>
 *
 * <h3>설정 근거</h3>
 * <ul>
 *   <li>Core 12: 3 프리셋 × 4 동시 요청 = 12 스레드</li>
 *   <li>Max 24: 피크 시 2배 확장 여유</li>
 *   <li>Queue 100: 스파이크 흡수 버퍼</li>
 *   <li>CallerRunsPolicy: 큐 포화 시 호출 스레드에서 직접 실행 (Deadlock 방지)</li>
 * </ul>
 *
 * @see EquipmentProcessingExecutorConfig 요청 처리용 Executor (AbortPolicy)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PresetCalculationExecutorConfig {

    private final MeterRegistry meterRegistry;
    private final ExecutorProperties executorProperties;

    /**
     * 프리셋 병렬 계산 전용 Executor
     *
     * <h4>CLAUDE.md Section 22 예외 적용</h4>
     * <p>CallerRunsPolicy 사용 - 프리셋 계산은 I/O가 없는 CPU 바운드 작업이므로
     * 호출 스레드에서 직접 실행해도 안전함</p>
     *
     * <h4>메트릭 노출</h4>
     * <ul>
     *   <li>preset.calculation.queue.size: 큐 대기 작업 수</li>
     *   <li>preset.calculation.active.count: 활성 스레드 수</li>
     *   <li>preset.calculation.pool.size: 현재 풀 크기</li>
     * </ul>
     */
    @Bean("presetCalculationExecutor")
    public Executor presetCalculationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        ExecutorProperties.PoolConfig config = executorProperties.preset();
        executor.setCorePoolSize(config.corePoolSize());
        executor.setMaxPoolSize(config.maxPoolSize());
        executor.setQueueCapacity(config.queueCapacity());
        executor.setThreadNamePrefix("preset-calc-");

        // CallerRunsPolicy: 큐 포화 시 호출 스레드에서 직접 실행 (Deadlock 방지)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Graceful Shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();

        // 메트릭 등록
        registerMetrics(executor);

        log.info("[PresetCalculationExecutor] Initialized: core={}, max={}, queue={}",
                config.corePoolSize(), config.maxPoolSize(), config.queueCapacity());

        return executor;
    }

    /**
     * Thread Pool 메트릭 등록
     *
     * <h4>Prometheus Alert 권장 임계값 (Red Agent)</h4>
     * <ul>
     *   <li>queue.size > 80: WARNING (80% capacity)</li>
     *   <li>active.count >= 22: WARNING (90%+ pool saturated)</li>
     * </ul>
     */
    private void registerMetrics(ThreadPoolTaskExecutor executor) {
        Gauge.builder("preset.calculation.queue.size", executor,
                        e -> e.getThreadPoolExecutor().getQueue().size())
                .description("프리셋 계산 대기 큐 크기")
                .register(meterRegistry);

        Gauge.builder("preset.calculation.active.count", executor,
                        ThreadPoolTaskExecutor::getActiveCount)
                .description("프리셋 계산 활성 스레드 수")
                .register(meterRegistry);

        Gauge.builder("preset.calculation.pool.size", executor,
                        ThreadPoolTaskExecutor::getPoolSize)
                .description("프리셋 계산 현재 풀 크기")
                .register(meterRegistry);

        Gauge.builder("preset.calculation.completed.tasks", executor,
                        e -> e.getThreadPoolExecutor().getCompletedTaskCount())
                .description("프리셋 계산 완료된 작업 수")
                .register(meterRegistry);
    }
}
