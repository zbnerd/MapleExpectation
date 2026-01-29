package maple.expectation.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Equipment Processing 전용 Thread Pool (#240, #264)
 *
 * <h3>5-Agent Council 합의사항</h3>
 * <ul>
 *   <li>🔴 Red (SRE): AbortPolicy 적용 - 큐 포화 시 503 반환</li>
 *   <li>🟢 Green (Performance): parallelStream 금지 - 전용 Executor 사용</li>
 *   <li>🔵 Blue (Architect): 비즈니스 Thread Pool과 격리</li>
 * </ul>
 *
 * <h3>Issue #264: Thread Pool 병목 해결</h3>
 * <ul>
 *   <li>문제: Core 2, Max 4 → L1 캐시 미스 시 RPS 120 병목</li>
 *   <li>해결: Core 8, Max 16, Queue 200으로 확장</li>
 *   <li>참고: L1 Fast Path로 캐시 히트 시 스레드풀 우회</li>
 * </ul>
 *
 * <h3>설정 근거</h3>
 * <ul>
 *   <li>Core 8: I/O 바운드 작업 (API 호출, DB) 고려</li>
 *   <li>Max 16: 피크 시 2배 확장 여유</li>
 *   <li>Queue 200: 스파이크 흡수 버퍼</li>
 *   <li>AbortPolicy: 읽기 작업이므로 재시도 가능 (DiscardPolicy와 달리 503 반환)</li>
 * </ul>
 *
 * <h3>Failure Mode (Red Agent)</h3>
 * <p>큐 포화 시 RejectedExecutionException → GlobalExceptionHandler가 503 반환</p>
 *
 * @see maple.expectation.config.PerCacheExecutorConfig PER 전용 Executor (DiscardPolicy)
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ExecutorProperties.class)
public class EquipmentProcessingExecutorConfig {

    private final MeterRegistry meterRegistry;
    private final ExecutorProperties executorProperties;

    /**
     * Equipment Processing 전용 Executor (#264 확장)
     *
     * <h4>CLAUDE.md Section 22 준수</h4>
     * <ul>
     *   <li>AbortPolicy: 읽기 작업에서 큐 포화 시 즉시 실패</li>
     *   <li>CallerRunsPolicy 금지: 호출 스레드 블로킹 방지</li>
     * </ul>
     *
     * <h4>메트릭 노출</h4>
     * <ul>
     *   <li>equipment.executor.queue.size: 큐 대기 작업 수</li>
     *   <li>equipment.executor.active.count: 활성 스레드 수</li>
     *   <li>equipment.executor.pool.size: 현재 풀 크기</li>
     *   <li>equipment.executor.completed.tasks: 완료된 작업 수</li>
     * </ul>
     */
    @Bean("equipmentProcessingExecutor")
    public Executor equipmentProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        ExecutorProperties.PoolConfig config = executorProperties.equipment();
        executor.setCorePoolSize(config.corePoolSize());
        executor.setMaxPoolSize(config.maxPoolSize());
        executor.setQueueCapacity(config.queueCapacity());
        executor.setThreadNamePrefix("equip-proc-");

        // AbortPolicy: 큐 포화 시 RejectedExecutionException 발생
        // GlobalExceptionHandler에서 503 Service Unavailable 반환
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        // 메트릭 노출 (SRE Red Agent 요구사항)
        registerMetrics(executor);

        return executor;
    }

    /**
     * Thread Pool 메트릭 등록
     *
     * <h4>Prometheus Alert 권장 임계값 (Red Agent) - #264 업데이트</h4>
     * <ul>
     *   <li>queue.size > 160: WARNING (80% capacity of 200)</li>
     *   <li>queue.size == 200: CRITICAL (requests rejected)</li>
     *   <li>active.count >= 14: WARNING (87.5% pool saturated)</li>
     * </ul>
     */
    private void registerMetrics(ThreadPoolTaskExecutor executor) {
        Gauge.builder("equipment.executor.queue.size", executor,
                        e -> e.getThreadPoolExecutor().getQueue().size())
                .description("Equipment 처리 대기 큐 크기")
                .register(meterRegistry);

        Gauge.builder("equipment.executor.active.count", executor,
                        ThreadPoolTaskExecutor::getActiveCount)
                .description("Equipment 처리 활성 스레드 수")
                .register(meterRegistry);

        Gauge.builder("equipment.executor.pool.size", executor,
                        ThreadPoolTaskExecutor::getPoolSize)
                .description("Equipment 처리 현재 풀 크기")
                .register(meterRegistry);

        Gauge.builder("equipment.executor.completed.tasks", executor,
                        e -> e.getThreadPoolExecutor().getCompletedTaskCount())
                .description("Equipment 처리 완료된 작업 수")
                .register(meterRegistry);
    }
}
