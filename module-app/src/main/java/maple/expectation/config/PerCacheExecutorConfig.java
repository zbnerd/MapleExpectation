package maple.expectation.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * PER (Probabilistic Early Recomputation) 전용 Thread Pool (#219)
 *
 * <h3>SRE 요구사항 (Red Agent)</h3>
 *
 * <ul>
 *   <li>기존 비즈니스 Thread Pool과 분리하여 자원 격리
 *   <li>DiscardPolicy: 큐 포화 시 갱신 작업 버림 (기존 Stale 데이터 유지)
 *   <li>보수적인 Pool 크기: Core 2, Max 4
 * </ul>
 *
 * <h3>장애 격리</h3>
 *
 * <p>PER 갱신 작업이 폭증해도 메인 비즈니스 로직에 영향을 주지 않음.
 *
 * @see maple.expectation.infrastructure.cache.per.ProbabilisticCacheAspect
 */
@Configuration
@RequiredArgsConstructor
public class PerCacheExecutorConfig {

  private final MeterRegistry meterRegistry;

  /**
   * PER 전용 Executor
   *
   * <h4>설정 근거</h4>
   *
   * <ul>
   *   <li>Core 2: 평상시 백그라운드 갱신 처리
   *   <li>Max 4: 트래픽 증가 시 탄력적 확장
   *   <li>Queue 100: Burst 대응, 초과 시 버림
   *   <li>DiscardPolicy: Stale 데이터가 이미 있으므로 안전하게 버림
   * </ul>
   *
   * <h4>메트릭 노출 (#238 5-Agent Council P2-B)</h4>
   *
   * <ul>
   *   <li>per.cache.executor.queue.size: 큐 대기 작업 수
   *   <li>per.cache.executor.active.count: 활성 스레드 수
   *   <li>per.cache.executor.pool.size: 현재 풀 크기
   *   <li>per.cache.executor.completed.tasks: 완료된 작업 수
   * </ul>
   */
  @Bean("perCacheExecutor")
  public Executor perCacheExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("per-cache-");

    // DiscardPolicy: 큐 포화 시 새 작업 버림
    // 기존 Stale 데이터가 있으므로 버려도 서비스 영향 없음
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());

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
   * <p>Prometheus/Grafana에서 PER 전용 Thread Pool 상태를 모니터링 가능
   */
  private void registerMetrics(ThreadPoolTaskExecutor executor) {
    Gauge.builder(
            "per.cache.executor.queue.size",
            executor,
            e -> e.getThreadPoolExecutor().getQueue().size())
        .description("PER 캐시 갱신 대기 큐 크기")
        .register(meterRegistry);

    Gauge.builder(
            "per.cache.executor.active.count", executor, ThreadPoolTaskExecutor::getActiveCount)
        .description("PER 캐시 갱신 활성 스레드 수")
        .register(meterRegistry);

    Gauge.builder("per.cache.executor.pool.size", executor, ThreadPoolTaskExecutor::getPoolSize)
        .description("PER 캐시 갱신 현재 풀 크기")
        .register(meterRegistry);

    Gauge.builder(
            "per.cache.executor.completed.tasks",
            executor,
            e -> e.getThreadPoolExecutor().getCompletedTaskCount())
        .description("PER 캐시 갱신 완료된 작업 수")
        .register(meterRegistry);
  }
}
