package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Nightmare 10: CallerRunsPolicy Betrayal - 실제 운영 Executor 검증
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - ThreadPool 큐 포화로 CallerRunsPolicy 발동
 *   <li>🔵 Blue (Architect): 흐름 검증 - HTTP 요청 스레드가 백그라운드 작업 수행
 *   <li>🟢 Green (Performance): 메트릭 검증 - 응답 시간 증가, 타임아웃 발생
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 작업 완료 여부 확인
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 실제 운영 Executor 설정 검증
 * </ul>
 *
 * <h4>테스트 목적</h4>
 *
 * <p>실제 운영 환경의 {@code expectationComputeExecutor}와 {@code alertTaskExecutor}가 CallerRunsPolicy를
 * 사용하지 않고 AbortPolicy 기반 정책을 사용하는지 검증한다.
 *
 * <h4>운영 Executor 설정 (ExecutorConfig.java)</h4>
 *
 * <ul>
 *   <li><b>expectationComputeExecutor</b>: EXPECTATION_ABORT_POLICY (Issue #168)
 *   <li><b>alertTaskExecutor</b>: LOGGING_ABORT_POLICY
 * </ul>
 *
 * <h4>예상 결과: PASS</h4>
 *
 * <p>운영 환경은 CallerRunsPolicy를 사용하지 않으므로 HTTP 스레드 블로킹이 발생하지 않음.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>CallerRunsPolicy: 큐가 가득 차면 제출자 스레드에서 직접 실행
 *   <li>Thread Pool Exhaustion: 모든 스레드가 작업 중일 때 발생
 *   <li>Backpressure Leak: 비동기→동기 전환으로 backpressure 전파
 *   <li>Cascading Timeout: 한 컴포넌트 지연이 전체 요청 타임아웃 유발
 * </ul>
 *
 * @see maple.expectation.config.ExecutorConfig
 * @see java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
 */
@Slf4j
@Tag("nightmare")
@DisplayName("Nightmare 10: CallerRunsPolicy Betrayal - 실제 운영 Executor 검증")
class CallerRunsPolicyNightmareTest extends IntegrationTestSupport {

  /** 실제 운영 환경의 expectationComputeExecutor 주입 */
  @Autowired
  @Qualifier("expectationComputeExecutor") private Executor expectationComputeExecutor;

  /** 실제 운영 환경의 alertTaskExecutor 주입 */
  @Autowired
  @Qualifier("alertTaskExecutor") private Executor alertTaskExecutor;

  private static final int TASK_DURATION_MS = 1000;
  private static final int CALLER_THREAD_THRESHOLD_MS = 500;

  /**
   * 🔴 Red's Test 1: 운영 expectationComputeExecutor가 CallerRunsPolicy를 사용하지 않음을 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>실제 운영 Executor의 설정 확인
   *   <li>큐 포화 상황 유도
   *   <li>CallerRunsPolicy 발동 여부 검증
   * </ol>
   *
   * <p><b>성공 기준</b>: CallerRunsPolicy 발동 0회
   *
   * <p><b>실패 조건</b>: 호출자 스레드에서 작업 실행됨
   */
  @Test
  @DisplayName("expectationComputeExecutor: CallerRunsPolicy 미사용 검증")
  void shouldNotUseCallerRunsPolicy_expectationComputeExecutor() throws Exception {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) expectationComputeExecutor;
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

    String rejectionPolicy = pool.getRejectedExecutionHandler().getClass().getSimpleName();
    int corePoolSize = pool.getCorePoolSize();
    int maxPoolSize = pool.getMaximumPoolSize();
    int queueCapacity = pool.getQueue().remainingCapacity() + pool.getQueue().size();

    boolean isCallerRunsPolicy = rejectionPolicy.contains("CallerRuns");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│  Nightmare 10: expectationComputeExecutor Config Check     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Core Pool Size: {}                                         │", corePoolSize);
    log.info("│ Max Pool Size: {}                                          │", maxPoolSize);
    log.info("│ Queue Capacity: {}                                         │", queueCapacity);
    log.info("│ Rejection Policy: {}                                       │", rejectionPolicy);
    log.info("├────────────────────────────────────────────────────────────┤");

    if (isCallerRunsPolicy) {
      log.info("│ ❌ DANGER: CallerRunsPolicy detected!                      │");
      log.info("│ 🔧 This can block HTTP threads under load                  │");
    } else {
      log.info("│ ✅ Safe: {} is not CallerRunsPolicy                        │", rejectionPolicy);
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 실제 동작 검증: 큐 포화 시 CallerRunsPolicy 발동 여부
    AtomicInteger callerThreadCount = new AtomicInteger(0);
    String mainThread = Thread.currentThread().getName();

    int taskCount = maxPoolSize + queueCapacity + 50;

    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      try {
        executor.execute(
            () -> {
              if (Thread.currentThread().getName().equals(mainThread)) {
                callerThreadCount.incrementAndGet();
                log.warn("[Task-{}] ⚠️ EXECUTED IN CALLER THREAD!", taskId);
              }
              try {
                Thread.sleep(100); // 짧은 작업
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      } catch (RejectedExecutionException e) {
        // AbortPolicy: 정상적으로 거부됨
        log.debug("[Task-{}] Rejected by AbortPolicy (expected)", taskId);
      }
    }

    // 잠시 대기
    Thread.sleep(500);

    assertThat(isCallerRunsPolicy)
        .as("[Nightmare] expectationComputeExecutor should NOT use CallerRunsPolicy")
        .isFalse();

    assertThat(callerThreadCount.get())
        .as("[Nightmare] No tasks should execute in caller thread")
        .isZero();
  }

  /** 🔵 Blue's Test 2: 운영 alertTaskExecutor가 CallerRunsPolicy를 사용하지 않음을 검증 */
  @Test
  @DisplayName("alertTaskExecutor: CallerRunsPolicy 미사용 검증")
  void shouldNotUseCallerRunsPolicy_alertTaskExecutor() throws Exception {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) alertTaskExecutor;
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

    String rejectionPolicy = pool.getRejectedExecutionHandler().getClass().getSimpleName();
    int corePoolSize = pool.getCorePoolSize();
    int maxPoolSize = pool.getMaximumPoolSize();

    boolean isCallerRunsPolicy = rejectionPolicy.contains("CallerRuns");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│       Nightmare 10: alertTaskExecutor Config Check         │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Core Pool Size: {}                                         │", corePoolSize);
    log.info("│ Max Pool Size: {}                                          │", maxPoolSize);
    log.info("│ Rejection Policy: {}                                       │", rejectionPolicy);
    log.info("├────────────────────────────────────────────────────────────┤");

    if (isCallerRunsPolicy) {
      log.info("│ ❌ DANGER: CallerRunsPolicy detected!                      │");
    } else {
      log.info("│ ✅ Safe: {} is not CallerRunsPolicy                        │", rejectionPolicy);
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(isCallerRunsPolicy)
        .as("[Nightmare] alertTaskExecutor should NOT use CallerRunsPolicy")
        .isFalse();
  }

  /**
   * 🟢 Green's Test 3: HTTP 요청 시뮬레이션 - 타임아웃 위험 검증 (개념 비교)
   *
   * <p>CallerRunsPolicy vs AbortPolicy 동작 차이를 명확히 비교
   */
  @Test
  @DisplayName("[개념 비교] HTTP 요청 타임아웃 시뮬레이션")
  void shouldSimulateHttpTimeout_CallerRunsPolicyVsAbortPolicy() throws Exception {
    // CallerRunsPolicy Executor (위험한 설정)
    ThreadPoolExecutor callerRunsExecutor =
        new ThreadPoolExecutor(
            1,
            1,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2),
            new ThreadPoolExecutor.CallerRunsPolicy());

    // AbortPolicy Executor (운영 권장)
    ThreadPoolExecutor abortExecutor =
        new ThreadPoolExecutor(
            1,
            1,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2),
            new ThreadPoolExecutor.AbortPolicy());

    // Pre-saturate both pools
    log.info("[Green] Pre-saturating thread pools...");
    for (int i = 0; i < 3; i++) {
      final int id = i;
      callerRunsExecutor.execute(
          () -> {
            try {
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });

      try {
        abortExecutor.execute(
            () -> {
              try {
                Thread.sleep(2000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      } catch (RejectedExecutionException e) {
        // Expected for AbortPolicy
      }
    }

    Thread.sleep(100); // Ensure pools are saturated

    // HTTP Request simulation
    AtomicLong callerRunsResponseTime = new AtomicLong(0);
    AtomicLong abortResponseTime = new AtomicLong(0);

    // CallerRunsPolicy HTTP request
    long start1 = System.currentTimeMillis();
    callerRunsExecutor.execute(
        () -> {
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    callerRunsResponseTime.set(System.currentTimeMillis() - start1);

    // AbortPolicy HTTP request
    long start2 = System.currentTimeMillis();
    try {
      abortExecutor.execute(
          () -> {
            try {
              Thread.sleep(500);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    } catch (RejectedExecutionException e) {
      // Fast fail
    }
    abortResponseTime.set(System.currentTimeMillis() - start2);

    callerRunsExecutor.shutdown();
    abortExecutor.shutdown();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│     HTTP Timeout Simulation: Policy Comparison             │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ CallerRunsPolicy:                                          │");
    log.info(
        "│   - Response Time: {}ms                                    │",
        callerRunsResponseTime.get());
    log.info(
        "│   - Blocked HTTP thread: {}                                │",
        callerRunsResponseTime.get() > CALLER_THREAD_THRESHOLD_MS ? "YES ❌" : "NO ✅");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ AbortPolicy:                                               │");
    log.info(
        "│   - Response Time: {}ms                                    │", abortResponseTime.get());
    log.info(
        "│   - Fast Fail: {}                                          │",
        abortResponseTime.get() < 100 ? "YES ✅" : "NO ❌");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🎯 Production should use AbortPolicy + 503 response        │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // CallerRunsPolicy는 블로킹 발생
    assertThat(callerRunsResponseTime.get())
        .as("CallerRunsPolicy blocks caller thread")
        .isGreaterThan(100);

    // AbortPolicy는 빠른 실패
    assertThat(abortResponseTime.get()).as("AbortPolicy provides fast fail").isLessThan(100);
  }

  /**
   * 🟣 Purple's Test 4: CallerRunsPolicy의 위험성 시연 (교육용)
   *
   * <p>테스트 전용 Executor로 CallerRunsPolicy가 얼마나 위험한지 시연
   */
  @Test
  @DisplayName("[개념 시연] CallerRunsPolicy의 Cascading Timeout 위험")
  void shouldDemonstrateCascadingTimeoutRisk_withCallerRunsPolicy() throws Exception {
    ThreadPoolExecutor dangerousExecutor =
        new ThreadPoolExecutor(
            2,
            2,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2),
            new ThreadPoolExecutor.CallerRunsPolicy());

    AtomicInteger executorThreadCount = new AtomicInteger(0);
    AtomicInteger callerThreadCount = new AtomicInteger(0);
    AtomicLong maxBlockTime = new AtomicLong(0);

    String callerThread = Thread.currentThread().getName();

    int totalTasks = 10;
    log.info("[Purple] Submitting {} tasks to saturate pool (capacity: 4)...", totalTasks);

    long totalStart = System.currentTimeMillis();

    for (int i = 0; i < totalTasks; i++) {
      final int taskId = i;
      long submitStart = System.currentTimeMillis();

      dangerousExecutor.execute(
          () -> {
            String executingThread = Thread.currentThread().getName();
            if (executingThread.equals(callerThread)) {
              callerThreadCount.incrementAndGet();
              log.warn("[Task-{}] ⚠️ CallerRunsPolicy: executing in {}", taskId, executingThread);
            } else {
              executorThreadCount.incrementAndGet();
              log.info("[Task-{}] Pool thread: {}", taskId, executingThread);
            }

            try {
              Thread.sleep(TASK_DURATION_MS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });

      long submitTime = System.currentTimeMillis() - submitStart;
      if (submitTime > maxBlockTime.get()) {
        maxBlockTime.set(submitTime);
      }

      if (submitTime > CALLER_THREAD_THRESHOLD_MS) {
        log.warn("[Red] Submit #{} blocked for {}ms (CallerRunsPolicy!)", taskId, submitTime);
      }
    }

    long totalTime = System.currentTimeMillis() - totalStart;

    dangerousExecutor.shutdown();
    dangerousExecutor.awaitTermination(30, TimeUnit.SECONDS);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    CallerRunsPolicy Cascading Timeout Demonstration        │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Tasks: {}                                            │", totalTasks);
    log.info(
        "│ Executor Thread Executions: {}                             │",
        executorThreadCount.get());
    log.info(
        "│ Caller Thread Executions: {}                               │", callerThreadCount.get());
    log.info("│ Max Block Time: {}ms                                       │", maxBlockTime.get());
    log.info("│ Total Submit Time: {}ms                                    │", totalTime);
    log.info("├────────────────────────────────────────────────────────────┤");

    if (callerThreadCount.get() > 0) {
      log.info(
          "│ ❌ CallerRunsPolicy blocked caller thread {} times         │", callerThreadCount.get());
      log.info("│                                                            │");
      log.info("│ Impact in Production:                                      │");
      log.info(
          "│   - HTTP request takes {}ms instead of ~0ms                │", maxBlockTime.get());
      log.info("│   - Tomcat thread occupied, unavailable for other requests │");
      log.info("│   - Under load: Cascading timeout across all APIs          │");
    }
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔧 Solution: Use AbortPolicy + Return 503                   │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // CallerRunsPolicy는 호출자 스레드에서 작업 실행
    assertThat(callerThreadCount.get())
        .as("CallerRunsPolicy executes tasks in caller thread")
        .isGreaterThan(0);
  }
}
