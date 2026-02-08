package maple.expectation.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 프리셋 병렬 계산 전용 Executor (#266 P1 Deadlock 방지)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Red (SRE): equipmentExecutor와 분리하여 Deadlock 방지
 *   <li>Green (Performance): AbortPolicy로 큐 포화 시 빠른 실패 및 메트릭 기록
 * </ul>
 *
 * <h3>P1 Deadlock 문제 해결</h3>
 *
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
 *
 * <ul>
 *   <li>Core 12: 3 프리셋 × 4 동시 요청 = 12 스레드
 *   <li>Max 24: 피크 시 2배 확장 여유
 *   <li>Queue 100: 스파이크 흡수 버퍼
 *   <li>AbortPolicy: 큐 포화 시 빠른 실패 및 rejected 메트릭 기록 (CLAUDE.md Section 22)
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
   * <h4>CLAUDE.md Section 22 준수</h4>
   *
   * <p>AbortPolicy 사용 - 큐 포화 시 빠른 실패 및 rejected 메트릭 기록. CallerRunsPolicy는 큐 포화 시 호출 스레드에서
   * 실행하여 Backpressure 신호를 손실하므로 금지됨.
   *
   * <h4>메트릭 노출 (Issue #284: Micrometer 표준)</h4>
   *
   * <ul>
   *   <li>executor.completed / executor.active / executor.queued: Micrometer ExecutorServiceMetrics
   *   <li>executor.rejected: 거부된 작업 수 (AbortPolicy 메트릭)
   *   <li>preset.calculation.queue.size: 큐 대기 작업 수 (레거시 호환)
   *   <li>preset.calculation.active.count: 활성 스레드 수 (레거시 호환)
   * </ul>
   *
   * <h4>Issue #284 P1-NEW-1: TaskDecorator 주입</h4>
   *
   * <p>MDC/ThreadLocal 전파를 위해 contextPropagatingDecorator 적용
   */
  @Bean("presetCalculationExecutor")
  public Executor presetCalculationExecutor(TaskDecorator contextPropagatingDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    ExecutorProperties.PoolConfig config = executorProperties.preset();
    executor.setCorePoolSize(config.corePoolSize());
    executor.setMaxPoolSize(config.maxPoolSize());
    executor.setQueueCapacity(config.queueCapacity());
    executor.setThreadNamePrefix("preset-calc-");

    // Issue #284 P1-NEW-1: MDC/ThreadLocal 전파
    executor.setTaskDecorator(contextPropagatingDecorator);

    // CLAUDE.md Section 22: AbortPolicy - 큐 포화 시 빠른 실패 및 rejected 메트릭 기록
    executor.setRejectedExecutionHandler(
        new ThreadPoolExecutor.AbortPolicy() {
          @Override
          public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            // rejected 메트릭 기록 (Micrometer ExecutorServiceMetrics가 자동 기록)
            super.rejectedExecution(r, e);
            log.warn(
                "[PresetCalculationExecutor] Task rejected - queue saturated: active={}, poolSize={}, queueSize={}",
                e.getActiveCount(),
                e.getPoolSize(),
                e.getQueue().size());
          }
        });

    // Graceful Shutdown
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);

    executor.initialize();

    // Issue #284: Micrometer ExecutorServiceMetrics 등록 (표준 네이밍)
    new ExecutorServiceMetrics(
            executor.getThreadPoolExecutor(), "preset.calculation", Collections.emptyList())
        .bindTo(meterRegistry);

    // 레거시 메트릭 호환 (기존 대시보드 유지)
    registerMetrics(executor);

    log.info(
        "[PresetCalculationExecutor] Initialized: core={}, max={}, queue={}",
        config.corePoolSize(),
        config.maxPoolSize(),
        config.queueCapacity());

    return executor;
  }

  /**
   * Thread Pool 메트릭 등록
   *
   * <h4>Prometheus Alert 권장 임계값 (Red Agent)</h4>
   *
   * <ul>
   *   <li>queue.size > 80: WARNING (80% capacity)
   *   <li>active.count >= 22: WARNING (90%+ pool saturated)
   * </ul>
   */
  private void registerMetrics(ThreadPoolTaskExecutor executor) {
    Gauge.builder(
            "preset.calculation.queue.size",
            executor,
            e -> e.getThreadPoolExecutor().getQueue().size())
        .description("프리셋 계산 대기 큐 크기")
        .register(meterRegistry);

    Gauge.builder(
            "preset.calculation.active.count", executor, ThreadPoolTaskExecutor::getActiveCount)
        .description("프리셋 계산 활성 스레드 수")
        .register(meterRegistry);

    Gauge.builder("preset.calculation.pool.size", executor, ThreadPoolTaskExecutor::getPoolSize)
        .description("프리셋 계산 현재 풀 크기")
        .register(meterRegistry);

    Gauge.builder(
            "preset.calculation.completed.tasks",
            executor,
            e -> e.getThreadPoolExecutor().getCompletedTaskCount())
        .description("프리셋 계산 완료된 작업 수")
        .register(meterRegistry);
  }
}
