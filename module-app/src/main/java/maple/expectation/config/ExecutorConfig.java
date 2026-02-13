package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.DefaultCheckedLogicExecutor;
import maple.expectation.infrastructure.executor.DefaultLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.policy.ExecutionPipeline;
import maple.expectation.infrastructure.executor.policy.ExecutionPolicy;
import maple.expectation.infrastructure.executor.policy.LoggingPolicy;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor Configuration - 비동기 실행 및 Thread Pool 관리 설정
 *
 * <h4>책임 (Refactoring 후)</h4>
 *
 * <ul>
 *   <li><b>LogicExecutor Beans</b>: logicExecutor, checkedLogicExecutor, executionPipeline
 *   <li><b>ThreadPoolTaskExecutor Beans</b>: alertTaskExecutor, aiTaskExecutor,
 *       expectationComputeExecutor
 *   <li><b>조정</b>: RejectionPolicyFactory, ExecutorMetricsConfigurator, TaskDecoratorFactory를 활용
 * </ul>
 *
 * <h4>분리된 책임 (별도 클래스)</h4>
 *
 * <ul>
 *   <li>{@link RejectionPolicyFactory}: Rejection Policy 생성 (LOGGING_ABORT_POLICY,
 *       EXPECTATION_ABORT_POLICY)
 *   <li>{@link ExecutorMetricsConfigurator}: Micrometer 메트릭 등록
 *   <li>{@link TaskDecoratorFactory}: MDC + Cache Context 전파용 TaskDecorator 생성
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ExecutorLoggingProperties.class)
public class ExecutorConfig {

  private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

  private final MeterRegistry meterRegistry;

  public ExecutorConfig(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  // ==================== LogicExecutor Beans ====================

  @Bean
  public ExceptionTranslator exceptionTranslator() {
    // DefaultLogicExecutor가 기본적으로 사용할 번역기를 지정합니다.
    return ExceptionTranslator.defaultTranslator();
  }

  @Bean
  public LoggingPolicy loggingPolicy(ExecutorLoggingProperties props) {
    return new LoggingPolicy(props.slowMs());
  }

  @Bean
  @ConditionalOnMissingBean(ExecutionPipeline.class)
  public ExecutionPipeline executionPipeline(List<ExecutionPolicy> policies) {
    List<ExecutionPolicy> ordered = new ArrayList<>(policies);
    AnnotationAwareOrderComparator.sort(ordered);
    return new ExecutionPipeline(ordered);
  }

  /**
   * 비즈니스 레이어 기본 Executor (Primary)
   *
   * <p>서비스/도메인 내부에서 기본으로 주입되는 Executor입니다. IO 경계에서는 {@link CheckedLogicExecutor}를
   * {@code @Qualifier("checkedLogicExecutor")}로 opt-in합니다.
   */
  @Bean
  @Primary
  @ConditionalOnMissingBean(LogicExecutor.class)
  public LogicExecutor logicExecutor(ExecutionPipeline pipeline, ExceptionTranslator translator) {
    return new DefaultLogicExecutor(pipeline, translator);
  }

  /**
   * IO 경계 전용 CheckedLogicExecutor 빈 등록
   *
   * <p>파일 I/O, 네트워크 통신, 분산 락 등 checked 예외가 발생하는 IO 경계에서 try-catch 없이 예외를 처리합니다.
   *
   * <h4>주입 패턴 (Qualifier 명시 권장)</h4>
   *
   * <p>Lombok {@code @RequiredArgsConstructor}는 {@code @Qualifier}를 생성자 파라미터로 전파하지 않을 수 있으므로, 명시적
   * 생성자를 권장합니다:
   *
   * <pre>{@code
   * class ResilientNexonApiClient {
   *     private final CheckedLogicExecutor checkedExecutor;
   *
   *     ResilientNexonApiClient(
   *         @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor
   *     ) {
   *         this.checkedExecutor = checkedExecutor;
   *     }
   * }
   * }</pre>
   */
  @Bean(name = "checkedLogicExecutor")
  @ConditionalOnMissingBean(CheckedLogicExecutor.class)
  public CheckedLogicExecutor checkedLogicExecutor(ExecutionPipeline pipeline) {
    return new DefaultCheckedLogicExecutor(pipeline);
  }

  // ==================== TaskDecorator Bean ====================

  /**
   * MDC + SkipEquipmentL2CacheContext 전파용 TaskDecorator (위임)
   *
   * @return TaskDecorator 인스턴스
   * @see TaskDecoratorFactory#createContextPropagatingDecorator()
   */
  @Bean
  public TaskDecorator contextPropagatingDecorator() {
    return taskDecoratorFactory().createContextPropagatingDecorator();
  }

  // ==================== ThreadPoolTaskExecutor Beans ====================

  /**
   * 외부 알림(Discord/Slack 등) 전용 비동기 Executor
   *
   * <h4>설계 의도</h4>
   *
   * <ul>
   *   <li><b>commonPool 분리</b>: 외부 I/O 지연이 앱 전반의 CompletableFuture에 전파되는 것을 방지
   *   <li><b>Best-effort 알림</b>: 알림은 부가 기능이므로, 폭주 시 드롭/종료 시 즉시 종료
   * </ul>
   *
   * <h4>운영 정책</h4>
   *
   * <ul>
   *   <li><b>RejectedExecution</b>: AbortPolicy + 샘플링 로깅 + rejected 메트릭
   *   <li><b>Shutdown</b>: 대기 없이 즉시 종료 (알림은 flush 불필요)
   * </ul>
   */
  @Bean(name = "alertTaskExecutor")
  @ConditionalOnMissingBean(name = "alertTaskExecutor")
  public Executor alertTaskExecutor(TaskDecorator contextPropagatingDecorator) {

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("alert-");
    executor.setAllowCoreThreadTimeOut(true);
    executor.setKeepAliveSeconds(30);

    // 불변식 3: ThreadLocal 전파 (P0-4/B2)
    executor.setTaskDecorator(contextPropagatingDecorator);

    // Best-effort 정책: 드롭 허용 + Future 완료 보장 + 메트릭 기록
    executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAlertAbortPolicy());

    // Shutdown 정책: 대기 없이 즉시 종료 (알림은 flush 불필요)
    executor.setWaitForTasksToCompleteOnShutdown(false);

    executor.initialize();

    // Micrometer ExecutorServiceMetrics 등록
    executorMetricsConfigurator().registerExecutorMetrics(executor, "alert");

    return executor;
  }

  /**
   * AI LLM 호출 전용 Executor (Issue #283 P0-5: Semaphore 제한 외부화)
   *
   * <h4>문제</h4>
   *
   * <p>AiSreService에서 Executors.newVirtualThreadPerTaskExecutor()를 인스턴스 필드로 직접 생성. Bean이 아니므로 관리
   * 불가, 동시성 무제한으로 대량 에러 시 수백 LLM 호출 → OOM 위험.
   *
   * <h4>해결</h4>
   *
   * <ul>
   *   <li>Semaphore로 동시 LLM 호출 제한 (기본값 10, YAML 외부화)
   *   <li>Virtual Thread 사용으로 I/O 대기 시 효율적
   *   <li>Spring Bean으로 관리하여 라이프사이클 추적 가능
   * </ul>
   */
  @Bean(name = "aiTaskExecutor")
  public Executor aiTaskExecutor(
      @org.springframework.beans.factory.annotation.Value("${ai.sre.max-concurrent-threads:10}")
          int maxConcurrent) {

    Semaphore semaphore = new Semaphore(maxConcurrent);
    Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    return runnable ->
        virtualThreadExecutor.execute(
            () -> {
              boolean acquired = false;
              try {
                acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS);
                if (!acquired) {
                  log.warn(
                      "[AiTaskExecutor] Semaphore timeout - LLM 호출 동시성 한도 초과 (limit={})",
                      maxConcurrent);
                  throw new RejectedExecutionException("AI task executor semaphore timeout");
                }
                runnable.run();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("AI task executor interrupted", e);
              } finally {
                if (acquired) {
                  semaphore.release();
                }
              }
            });
  }

  /**
   * Expectation compute(파싱/계산/외부 호출 포함) 데드라인 강제를 위한 전용 Executor
   *
   * <h4>설계 의도</h4>
   *
   * <ul>
   *   <li><b>30초 데드라인 강제</b>: CompletableFuture.orTimeout()과 함께 사용하여 leader compute가 30초를 초과하면
   *       TimeoutException으로 정리
   *   <li><b>inFlight 누수 방지</b>: @Scheduled 백그라운드 정리 대신 실제 데드라인 강제
   * </ul>
   */
  @Bean(name = "expectationComputeExecutor")
  public Executor expectationComputeExecutor(TaskDecorator contextPropagatingDecorator) {

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("expectation-");
    executor.setAllowCoreThreadTimeOut(true);
    executor.setKeepAliveSeconds(30);

    // 불변식 3: ThreadLocal 전파 (P0-4/B2)
    executor.setTaskDecorator(contextPropagatingDecorator);

    // Issue #168: CallerRunsPolicy → AbortPolicy + rejected 메트릭 기록
    executor.setRejectedExecutionHandler(rejectionPolicyFactory().createExpectationAbortPolicy());

    // Graceful Shutdown: 진행 중인 계산 작업 완료 대기
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);

    executor.initialize();

    // Micrometer ExecutorServiceMetrics 등록
    executorMetricsConfigurator().registerExecutorMetrics(executor, "expectation.compute");

    return executor;
  }

  // ==================== Helper Factory Beans ====================

  /**
   * Rejection Policy Factory Bean
   *
   * @return RejectionPolicyFactory 인스턴스
   */
  @Bean
  public RejectionPolicyFactory rejectionPolicyFactory() {
    return new RejectionPolicyFactory(meterRegistry);
  }

  /**
   * Executor Metrics Configurator Bean
   *
   * @return ExecutorMetricsConfigurator 인스턴스
   */
  @Bean
  public ExecutorMetricsConfigurator executorMetricsConfigurator() {
    return new ExecutorMetricsConfigurator(meterRegistry);
  }

  /**
   * Task Decorator Factory Bean
   *
   * @return TaskDecoratorFactory 인스턴스
   */
  @Bean
  public TaskDecoratorFactory taskDecoratorFactory() {
    return new TaskDecoratorFactory();
  }
}
