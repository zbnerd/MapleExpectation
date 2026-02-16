package maple.expectation.infrastructure.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.Collections;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring Scheduler Thread Pool Configuration
 *
 * <h2>Issue #344: Connection Pool Exhaustion from fixedRate Overlap</h2>
 *
 * <h3>문제 상황</h3>
 *
 * <p>Spring Boot의 기본 {@code TaskScheduler}는 단일 스레드({@code poolSize=1})로 생성됩니다. 10개 이상의
 * {@code @Scheduled} 메서드가 있는 상황에서:
 *
 * <ul>
 *   <li><b>fixedRate</b>: 이전 작업 종료와 무관하게 주기적 실행 → 중복 실행 가능
 *   <li>poolSize=1</b>: 모든 스케줄 작업이 단일 큐에서 대기 → 지연 누적
 * </ul>
 *
 * <p>이로 인해 다음과 같은 문제가 발생합니다:
 *
 * <ul>
 *   <li><b>Connection Pool 고갈</b>: 겹친 스케줄 작업이 DB 커넥션을 동시 점유
 *   <li><b>Deadlock</b>: Scheduler가 다른 스케줄 작업의 완료를 기다리는 순환 의존성 발생 가능
 * </ul>
 *
 * <h3>해결 방안</h3>
 *
 * <p>명시적인 {@link ThreadPoolTaskScheduler} 빈을 생성하여:
 *
 * <ul>
 *   <li><b>적절한 poolSize</b>: 3-4 스레드로 10개 스케줄러 병렬 처리
 *   <li><b>Metric 가시성</b>: Micrometer로 스케줄러 상태 모니터링
 *   <li><b>Graceful Shutdown</b>: 앱 종료 시 진행 중인 작업 완료 보장
 * </ul>
 *
 * <h2>왜 fixedDelay에서도 명시적 poolSize가 필요한가?</h2>
 *
 * <p>대부분의 스케줄러가 {@code fixedDelay}라 하더라도 명시적 설정이 필요합니다:
 *
 * <ul>
 *   <li><b>동시성 요구</b>: 여러 독립적인 작업을 동시에 실행하여 전체 처리량 향상
 *   <li><b>장애 격리</b>: 단일 작업의 장애가 다른 작업의 실행을 차단하지 않음
 *   <li><b>가시성</b>: Micrometer 메트릭으로 스케줄러 상태 모니터링
 * </ul>
 *
 * <h2>Graceful Shutdown的重要性</h2>
 *
 * <p>스케줄러는 장기 실행 작업(DB 백업, 배치 작업 등)을 수행할 수 있으므로:
 *
 * <ul>
 *   <li><b>waitForTasksToCompleteOnShutdown=true</b>: 앱 종료 시 진행 중인 작업 완료 대기
 *   <li><b>awaitTerminationSeconds=60</b>: 최대 60초 대기 후 강제 종료
 * </ul>
 *
 * <p>이를 통해:
 *
 * <ul>
 *   <li>DB 트랜잭션 중단으로 인한 데이터 불일치 방지
 *   <li>파일 쓰기 중단으로 인한 손상 방지
 *   <li>안전한 배포 롤아웃 가능
 * </ul>
 *
 * <h2>설정</h2>
 *
 * <pre>{@code
 * scheduler:
 *   task-scheduler:
 *     pool-size: 3  # 기본값: 3
 * }</pre>
 *
 * @see <a href="https://github.com/spring-projects/spring-framework/issues/28241">Spring Issue
 *     #28241</a>
 * @see <a
 *     href="https://docs.spring.io/spring-framework/reference/integration/scheduling.html">Spring
 *     Scheduling Documentation</a>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({SchedulerProperties.class, ShutdownProperties.class})
public class SchedulerConfig {

  private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

  /** 로그 샘플링 간격: 1초에 1회만 WARN 로그 (log storm 방지) */
  private static final long REJECT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  /**
   * 인스턴스별 로그 샘플링 카운터
   *
   * <p>이 카운터들은 <b>로그 샘플링 용도</b>로, 정확한 클러스터 집계가 필요 없습니다:
   *
   * <ul>
   *   <li>목적: log storm 방지 (1초에 1회만 WARN)
   *   <li>영향: 인스턴스별 독립 샘플링 → 정상 동작
   *   <li>결론: Micrometer Counter로 교체 불필요 (이미 rejected Counter 별도 존재)
   * </ul>
   *
   * <p>실제 rejected 메트릭은 {@code scheduler.rejected} Counter로 Micrometer에 집계됩니다.
   */
  private static final AtomicLong lastRejectLogNanos = new AtomicLong(0);

  private static final AtomicLong rejectedSinceLastLog = new AtomicLong(0);

  /**
   * Scheduler용 AbortPolicy
   *
   * <h4>왜 AbortPolicy인가?</h4>
   *
   * <ul>
   *   <li><b>즉시 거부</b>: 큐 포화 시 즉시 예외 발생 → 모니터링 가능
   *   <li><b>메트릭 가시성</b>: rejected Counter로 Micrometer에 집계
   *   <li><b>CallerRuns 부적합</b>: 스케줄러는 호출자가 톰캣 스레드가 아니므로 무의미
   * </ul>
   *
   * <h4>Log storm 방지</h4>
   *
   * <p>1초에 1회만 WARN 로그를 출력하여 로그 폭주를 방지합니다.
   */
  private static final RejectedExecutionHandler SCHEDULER_ABORT_POLICY =
      (r, executor) -> {
        // 종료 중 거절은 정상 시나리오
        if (executor.isShutdown() || executor.isTerminating()) {
          throw new RejectedExecutionException("Scheduler rejected (shutdown in progress)");
        }

        // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
        long dropped = rejectedSinceLastLog.incrementAndGet();
        long now = System.nanoTime();
        long prev = lastRejectLogNanos.get();

        if (now - prev >= REJECT_LOG_INTERVAL_NANOS
            && lastRejectLogNanos.compareAndSet(prev, now)) {
          long count = rejectedSinceLastLog.getAndSet(0);
          log.warn(
              "[TaskScheduler] Task rejected (queue full). "
                  + "droppedInLastWindow={}, poolSize={}, activeCount={}, queueSize={}",
              count,
              executor.getPoolSize(),
              executor.getActiveCount(),
              executor.getQueue().size());
        }

        throw new RejectedExecutionException("TaskScheduler queue full (capacity exceeded)");
      };

  /**
   * Spring TaskScheduler 빈
   *
   * <h4>설정</h4>
   *
   * <ul>
   *   <li><b>poolSize</b>: 3-4 스레드 (기본값 3, YAML 외부화)
   *   <li><b>threadNamePrefix</b>: "scheduler-"로 시작하는 명명된 스레드
   *   <li><b>waitForTasksToCompleteOnShutdown</b>: true (Graceful Shutdown)
   *   <li><b>awaitTerminationSeconds</b>: 60 (최대 대기 시간)
   * </ul>
   *
   * <h4>Micrometer 메트릭</h4>
   *
   * <ul>
   *   <li>{@code scheduler.completed} - 완료된 작업 수
   *   <li>{@code scheduler.active} - 현재 활성 스레드 수
   *   <li>{@code scheduler.queued} - 큐에 대기 중인 작업 수
   *   <li>{@code scheduler.rejected} - 거부된 작업 수 (커스텀)
   * </ul>
   *
   * @return ThreadPoolTaskScheduler 인스턴스
   */
  @Bean
  @ConditionalOnMissingBean(name = "taskScheduler")
  public ThreadPoolTaskScheduler taskScheduler(
      SchedulerProperties properties, MeterRegistry meterRegistry) {

    // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
    Counter schedulerRejectedCounter =
        Counter.builder("scheduler.rejected")
            .description("Number of scheduled tasks rejected due to queue full")
            .register(meterRegistry);

    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(properties.poolSize());
    scheduler.setThreadNamePrefix("scheduler-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(properties.awaitTerminationSeconds());

    // RejectedExecution 정책: AbortPolicy + 메트릭 기록
    scheduler.setRejectedExecutionHandler(
        (r, e) -> {
          schedulerRejectedCounter.increment();
          SCHEDULER_ABORT_POLICY.rejectedExecution(r, e);
        });

    scheduler.initialize();

    // Context7 Best Practice: Micrometer ExecutorServiceMetrics 등록
    // 제공 메트릭: executor.completed, executor.active, executor.queued, executor.pool.size
    new ExecutorServiceMetrics(
            scheduler.getScheduledExecutor(), "task.scheduler", Collections.emptyList())
        .bindTo(meterRegistry);

    log.info("[TaskScheduler] Initialized with poolSize={}", properties.poolSize());

    return scheduler;
  }
}
