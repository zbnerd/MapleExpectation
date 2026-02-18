package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * Nightmare 03: Thread Pool Exhaustion - 실제 운영 Executor 검증
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 대량 비동기 작업으로 Thread Pool 포화
 *   <li>🔵 Blue (Architect): 흐름 검증 - AbortPolicy vs CallerRunsPolicy 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - 작업 제출 시간, 블로킹 여부
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 작업 거부 시 Future 완료 보장
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 실제 운영 Executor 설정 검증
 * </ul>
 *
 * <h4>테스트 목적</h4>
 *
 * <p>실제 운영 환경의 {@code expectationComputeExecutor}와 {@code alertTaskExecutor}가 Thread Pool 포화 시
 * CallerRunsPolicy로 인한 메인 스레드 블로킹 없이 적절히 작업을 거부(AbortPolicy)하는지 검증한다.
 *
 * <h4>운영 Executor 설정 (ExecutorConfig.java)</h4>
 *
 * <ul>
 *   <li><b>expectationComputeExecutor</b>: core=4, max=8, queue=200, EXPECTATION_ABORT_POLICY
 *   <li><b>alertTaskExecutor</b>: core=2, max=4, queue=200, LOGGING_ABORT_POLICY
 * </ul>
 *
 * <h4>예상 결과: PASS</h4>
 *
 * <p>운영 환경은 AbortPolicy 기반 정책을 사용하므로 메인 스레드 블로킹이 발생하지 않음. 큐 포화 시 RejectedExecutionException 발생하여 빠른
 * 실패 보장.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Thread Pool Saturation: 풀 포화로 인한 병목
 *   <li>Backpressure: 과부하 시 제어 흐름
 *   <li>RejectedExecutionHandler 전략:
 *       <ul>
 *         <li>CallerRunsPolicy: 호출자 스레드에서 실행 (블로킹) - <b>사용 금지</b>
 *         <li>AbortPolicy: RejectedExecutionException 발생 (빠른 실패)
 *       </ul>
 *   <li>Little's Law: L = λW (대기열 길이 = 도착률 × 대기 시간)
 * </ul>
 *
 * @see maple.expectation.config.ExecutorConfig
 * @see java.util.concurrent.ThreadPoolExecutor
 */
@Slf4j
@Tag("nightmare")
@DisplayName("Nightmare 03: Thread Pool Exhaustion - 실제 운영 Executor 검증")
class ThreadPoolExhaustionNightmareTest extends IntegrationTestSupport {

  private static final long TASK_DURATION_MS = 1000; // 각 작업 1초 소요

  /**
   * 실제 운영 환경의 expectationComputeExecutor 주입 - corePoolSize: 4 - maxPoolSize: 8 - queueCapacity: 200
   * - rejectedExecutionHandler: EXPECTATION_ABORT_POLICY (AbortPolicy 기반)
   */
  @Autowired
  @Qualifier("expectationComputeExecutor") private Executor expectationComputeExecutor;

  /**
   * 실제 운영 환경의 alertTaskExecutor 주입 - corePoolSize: 2 - maxPoolSize: 4 - queueCapacity: 200 -
   * rejectedExecutionHandler: LOGGING_ABORT_POLICY (AbortPolicy 기반)
   */
  @Autowired
  @Qualifier("alertTaskExecutor") private Executor alertTaskExecutor;

  /**
   * 🔴 Red's Test 1: 운영 expectationComputeExecutor의 AbortPolicy 동작 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>Pool Size(4~8) + Queue Size(200) = 최대 208개 동시 처리
   *   <li>250개 작업 제출 (용량 초과)
   *   <li>큐 포화 시 EXPECTATION_ABORT_POLICY 발동 → RejectedExecutionException
   *   <li>메인 스레드 블로킹 없이 빠르게 거부
   * </ol>
   *
   * <p><b>성공 기준</b>: 작업 제출 시간 < 500ms (비블로킹)
   *
   * <p><b>검증 포인트</b>: CallerRunsPolicy와 달리 메인 스레드가 블로킹되지 않음
   */
  @Test
  @DisplayName("expectationComputeExecutor: AbortPolicy로 메인 스레드 블로킹 방지")
  void shouldNotBlockMainThread_withExpectationComputeExecutor() throws Exception {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) expectationComputeExecutor;
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

    int corePoolSize = pool.getCorePoolSize();
    int maxPoolSize = pool.getMaximumPoolSize();
    int queueCapacity = pool.getQueue().remainingCapacity() + pool.getQueue().size();

    // 용량 초과 작업 수 계산 (max + queue + 50 초과)
    int taskCount = maxPoolSize + queueCapacity + 50;

    AtomicInteger submittedCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);
    AtomicInteger callerRunsCount = new AtomicInteger(0);
    List<Long> submitTimes = new CopyOnWriteArrayList<>();

    String mainThreadName = Thread.currentThread().getName();

    log.info("[Red] Starting expectationComputeExecutor exhaustion test...");
    log.info(
        "[Red] Pool Config: core={}, max={}, queue={}", corePoolSize, maxPoolSize, queueCapacity);
    log.info("[Red] Task Count: {} (capacity + 50)", taskCount);
    log.info("[Red] Main Thread: {}", mainThreadName);

    long totalStartTime = System.nanoTime();

    // When: 대량 작업 제출
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      long submitStart = System.nanoTime();

      try {
        executor.execute(
            () -> {
              String currentThread = Thread.currentThread().getName();
              // CallerRunsPolicy 감지: 메인 스레드 또는 expectation- 접두사가 아닌 스레드
              if (currentThread.equals(mainThreadName)
                  || !currentThread.startsWith("expectation-")) {
                callerRunsCount.incrementAndGet();
                log.warn(
                    "[Red] Task {}: CallerRunsPolicy detected! (Thread: {})",
                    taskId,
                    currentThread);
              }

              try {
                Thread.sleep(TASK_DURATION_MS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
        submittedCount.incrementAndGet();
      } catch (RejectedExecutionException e) {
        rejectedCount.incrementAndGet();
        // AbortPolicy 정상 동작 - 로그 샘플링 (10개마다)
        if (rejectedCount.get() % 10 == 1) {
          log.info("[Red] Task {} rejected (AbortPolicy): {}", taskId, e.getMessage());
        }
      }

      long submitTime = (System.nanoTime() - submitStart) / 1_000_000;
      submitTimes.add(submitTime);

      // 블로킹 감지: 100ms 이상 소요 시 경고
      if (submitTime > 100) {
        log.warn(
            "[Red] Task {}: Submit blocked for {}ms! (CallerRunsPolicy 의심)", taskId, submitTime);
      }
    }

    long totalSubmitTime = (System.nanoTime() - totalStartTime) / 1_000_000;

    // 모든 작업 완료 대기 (최대 60초)
    executor.getThreadPoolExecutor().awaitTermination(1, TimeUnit.SECONDS);

    // Then: 분석
    long maxSubmitTime = submitTimes.stream().mapToLong(Long::longValue).max().orElse(0);
    long avgSubmitTime = submitTimes.stream().mapToLong(Long::longValue).sum() / submitTimes.size();
    long blockedSubmits = submitTimes.stream().filter(t -> t > 100).count();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│  Nightmare 03: expectationComputeExecutor Results          │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Pool Config: core={}, max={}, queue={}                     │",
        corePoolSize,
        maxPoolSize,
        queueCapacity);
    log.info("│ Tasks Attempted: {}                                        │", taskCount);
    log.info(
        "│ Tasks Submitted: {}                                        │", submittedCount.get());
    log.info("│ Tasks Rejected: {}                                         │", rejectedCount.get());
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Submit Time: {}ms                                    │", totalSubmitTime);
    log.info("│ Avg Submit Time: {}ms                                      │", avgSubmitTime);
    log.info("│ Max Submit Time: {}ms                                      │", maxSubmitTime);
    log.info("│ Blocked Submits (>100ms): {}                               │", blockedSubmits);
    log.info(
        "│ CallerRunsPolicy Triggered: {} times                       │", callerRunsCount.get());
    log.info("├────────────────────────────────────────────────────────────┤");

    if (callerRunsCount.get() > 0) {
      log.info("│ ❌ CRITICAL: CallerRunsPolicy detected in production!      │");
      log.info("│ 🔧 Fix: Change to AbortPolicy in ExecutorConfig            │");
    } else if (rejectedCount.get() > 0) {
      log.info("│ ✅ AbortPolicy working correctly                           │");
      log.info("│ ✅ No main thread blocking                                 │");
    } else {
      log.info("│ ⚠️ No rejections - pool capacity was sufficient            │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증 1: CallerRunsPolicy로 인한 메인 스레드 실행이 없어야 함
    assertThat(callerRunsCount.get())
        .as("[Nightmare] 운영 환경은 CallerRunsPolicy를 사용하지 않아야 함")
        .isZero();

    // 검증 2: 작업 제출이 빠르게 완료되어야 함 (비블로킹)
    assertThat(maxSubmitTime)
        .as("[Nightmare] AbortPolicy는 메인 스레드를 블로킹하지 않아야 함 (≤500ms)")
        .isLessThanOrEqualTo(500L);

    // 검증 3: 용량 초과 시 작업이 거부되어야 함
    assertThat(rejectedCount.get())
        .as("[Nightmare] 용량 초과 시 RejectedExecutionException 발생해야 함")
        .isGreaterThan(0);
  }

  /**
   * 🔵 Blue's Test 2: 운영 alertTaskExecutor의 LOGGING_ABORT_POLICY 동작 검증
   *
   * <p>Alert 전용 Executor도 AbortPolicy 기반으로 메인 스레드 블로킹을 방지하는지 검증
   */
  @Test
  @DisplayName("alertTaskExecutor: LOGGING_ABORT_POLICY로 메인 스레드 블로킹 방지")
  void shouldNotBlockMainThread_withAlertTaskExecutor() throws Exception {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) alertTaskExecutor;
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

    int corePoolSize = pool.getCorePoolSize();
    int maxPoolSize = pool.getMaximumPoolSize();
    int queueCapacity = pool.getQueue().remainingCapacity() + pool.getQueue().size();

    // 용량 초과 작업 수 계산
    int taskCount = maxPoolSize + queueCapacity + 50;

    AtomicInteger submittedCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);
    AtomicInteger callerRunsCount = new AtomicInteger(0);

    String mainThreadName = Thread.currentThread().getName();

    log.info("[Blue] Starting alertTaskExecutor exhaustion test...");
    log.info(
        "[Blue] Pool Config: core={}, max={}, queue={}", corePoolSize, maxPoolSize, queueCapacity);
    log.info("[Blue] Task Count: {} (capacity + 50)", taskCount);

    long totalStartTime = System.nanoTime();

    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;

      try {
        executor.execute(
            () -> {
              String currentThread = Thread.currentThread().getName();
              if (currentThread.equals(mainThreadName) || !currentThread.startsWith("alert-")) {
                callerRunsCount.incrementAndGet();
                log.warn(
                    "[Blue] Task {}: CallerRunsPolicy detected! (Thread: {})",
                    taskId,
                    currentThread);
              }

              try {
                Thread.sleep(TASK_DURATION_MS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
        submittedCount.incrementAndGet();
      } catch (RejectedExecutionException e) {
        rejectedCount.incrementAndGet();
      }
    }

    long totalSubmitTime = (System.nanoTime() - totalStartTime) / 1_000_000;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      Nightmare 03: alertTaskExecutor Results               │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Pool Config: core={}, max={}, queue={}                     │",
        corePoolSize,
        maxPoolSize,
        queueCapacity);
    log.info("│ Tasks Attempted: {}                                        │", taskCount);
    log.info(
        "│ Tasks Submitted: {}                                        │", submittedCount.get());
    log.info("│ Tasks Rejected: {}                                         │", rejectedCount.get());
    log.info("│ Total Submit Time: {}ms                                    │", totalSubmitTime);
    log.info(
        "│ CallerRunsPolicy Triggered: {} times                       │", callerRunsCount.get());
    log.info("├────────────────────────────────────────────────────────────┤");

    if (callerRunsCount.get() > 0) {
      log.info("│ ❌ CRITICAL: CallerRunsPolicy detected!                    │");
    } else {
      log.info("│ ✅ LOGGING_ABORT_POLICY working correctly                  │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: CallerRunsPolicy가 발생하지 않아야 함
    assertThat(callerRunsCount.get())
        .as("[Nightmare] alertTaskExecutor도 CallerRunsPolicy를 사용하지 않아야 함")
        .isZero();

    // 검증: 비블로킹 확인
    assertThat(totalSubmitTime).as("[Nightmare] 전체 제출이 빠르게 완료되어야 함").isLessThan(5000L);
  }

  /**
   * 🟢 Green's Test 3: Thread Pool 메트릭 실시간 분석
   *
   * <p>실제 운영 Executor의 Thread Pool 메트릭을 실시간으로 모니터링하여 포화 상태 진입 및 회복 과정을 검증
   */
  @Test
  @DisplayName("Thread Pool 메트릭 실시간 분석 - expectationComputeExecutor")
  void shouldAnalyzeThreadPoolMetrics_withActualExecutor() throws Exception {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) expectationComputeExecutor;
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

    int corePoolSize = pool.getCorePoolSize();
    int maxPoolSize = pool.getMaximumPoolSize();

    int taskCount = maxPoolSize * 3; // 풀 크기의 3배 작업
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(taskCount);

    log.info("[Green] Monitoring expectationComputeExecutor metrics...");
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│ Time │ Active │ Pool │ Queue │ Completed │ Status         │");
    log.info("├────────────────────────────────────────────────────────────┤");

    // 메트릭 수집 스레드
    ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    AtomicInteger tick = new AtomicInteger(0);
    AtomicLong maxQueueSize = new AtomicLong(0);
    AtomicLong maxActiveCount = new AtomicLong(0);

    monitor.scheduleAtFixedRate(
        () -> {
          int active = pool.getActiveCount();
          int poolSize = pool.getPoolSize();
          int queueSize = pool.getQueue().size();
          long completed = pool.getCompletedTaskCount();

          maxQueueSize.set(Math.max(maxQueueSize.get(), queueSize));
          maxActiveCount.set(Math.max(maxActiveCount.get(), active));

          String status;
          if (queueSize > 100) {
            status = "⚠️ QUEUE HIGH";
          } else if (active >= maxPoolSize) {
            status = "🔶 POOL BUSY";
          } else if (active >= corePoolSize) {
            status = "🔵 SCALING";
          } else {
            status = "✅ NORMAL";
          }

          log.info(
              "│ T+{}s │ {}      │ {}    │ {}     │ {}         │ {} │",
              tick.incrementAndGet(),
              active,
              poolSize,
              queueSize,
              completed,
              status);
        },
        0,
        500,
        TimeUnit.MILLISECONDS);

    // 작업 제출
    AtomicInteger submittedCount = new AtomicInteger(0);
    for (int i = 0; i < taskCount; i++) {
      try {
        executor.execute(
            () -> {
              try {
                startLatch.await();
                Thread.sleep(500); // 0.5초 작업
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                doneLatch.countDown();
              }
            });
        submittedCount.incrementAndGet();
      } catch (RejectedExecutionException e) {
        doneLatch.countDown(); // 거부된 작업도 latch 감소
      }
    }

    startLatch.countDown();
    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

    monitor.shutdown();

    log.info("└────────────────────────────────────────────────────────────┘");
    log.info("[Green] Max Active Threads: {} (max={})", maxActiveCount.get(), maxPoolSize);
    log.info("[Green] Max Queue Size: {}", maxQueueSize.get());
    log.info("[Green] Tasks Submitted: {}/{}", submittedCount.get(), taskCount);
    log.info("[Green] Completed: {}", completed);

    // 검증: Pool이 실제로 확장되어야 함
    assertThat(maxActiveCount.get()).as("Pool이 실제로 스케일링되어야 함").isGreaterThanOrEqualTo(corePoolSize);
  }

  /**
   * 🟣 Purple's Test 4: RejectedExecutionException 발생 시 Future 완료 보장
   *
   * <p>운영 환경의 AbortPolicy가 CompletableFuture의 exceptionally 완료를 보장하는지 검증. DiscardPolicy와 달리 Future가
   * 영원히 pending되지 않음을 확인.
   */
  @Test
  @DisplayName("RejectedExecutionException 발생 시 Future 완료 보장")
  void shouldCompleteFutureExceptionally_whenRejected() throws Exception {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) expectationComputeExecutor;
    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

    int maxPoolSize = pool.getMaximumPoolSize();
    int queueCapacity = pool.getQueue().remainingCapacity() + pool.getQueue().size();
    int taskCount = maxPoolSize + queueCapacity + 100; // 확실히 용량 초과

    List<CompletableFuture<Void>> futures = new CopyOnWriteArrayList<>();
    AtomicInteger completedNormally = new AtomicInteger(0);
    AtomicInteger completedExceptionally = new AtomicInteger(0);

    log.info("[Purple] Testing Future completion guarantee...");
    log.info(
        "[Purple] Submitting {} tasks to pool (capacity: {})",
        taskCount,
        maxPoolSize + queueCapacity);

    // When: 대량 작업 제출
    for (int i = 0; i < taskCount; i++) {
      CompletableFuture<Void> future = new CompletableFuture<>();
      futures.add(future);

      try {
        executor.execute(
            () -> {
              try {
                Thread.sleep(500);
                future.complete(null);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
              }
            });
      } catch (RejectedExecutionException e) {
        // AbortPolicy: Future를 exceptionally 완료
        future.completeExceptionally(e);
      }
    }

    // 모든 Future 완료 대기 (최대 60초)
    CompletableFuture<Void> allFutures =
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

    try {
      allFutures.get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      // 일부 Future가 exceptionally 완료되어도 OK
    }

    // 결과 분석
    for (CompletableFuture<Void> future : futures) {
      if (future.isDone()) {
        if (future.isCompletedExceptionally()) {
          completedExceptionally.incrementAndGet();
        } else {
          completedNormally.incrementAndGet();
        }
      }
    }

    int pendingCount = taskCount - completedNormally.get() - completedExceptionally.get();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      Nightmare 03: Future Completion Analysis              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Futures: {}                                          │", taskCount);
    log.info(
        "│ Completed Normally: {}                                     │", completedNormally.get());
    log.info(
        "│ Completed Exceptionally: {}                                │",
        completedExceptionally.get());
    log.info("│ Pending (Memory Leak Risk): {}                             │", pendingCount);
    log.info("├────────────────────────────────────────────────────────────┤");

    if (pendingCount > 0) {
      log.info("│ ❌ DANGER: {} Futures never completed!                     │", pendingCount);
      log.info("│ 🔧 This indicates DiscardPolicy - change to AbortPolicy    │");
    } else {
      log.info("│ ✅ All Futures completed (no memory leak)                  │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: 모든 Future가 완료되어야 함 (pending 없음)
    assertThat(pendingCount)
        .as("[Nightmare] DiscardPolicy는 Future를 pending 상태로 방치 - AbortPolicy 필수")
        .isZero();
  }

  /**
   * 개념 비교: CallerRunsPolicy vs AbortPolicy (교육용)
   *
   * <p>테스트 전용 Executor를 생성하여 두 정책의 차이를 명확히 비교. 이 테스트는 운영 환경이 아닌 교육 목적으로만 사용.
   */
  @Test
  @DisplayName("[개념 비교] CallerRunsPolicy vs AbortPolicy 동작 차이")
  void shouldDemonstratePolicyDifference_forEducationalPurpose() throws Exception {
    // CallerRunsPolicy Executor (위험한 설정)
    ThreadPoolTaskExecutor callerRunsExecutor = new ThreadPoolTaskExecutor();
    callerRunsExecutor.setCorePoolSize(2);
    callerRunsExecutor.setMaxPoolSize(2);
    callerRunsExecutor.setQueueCapacity(2);
    callerRunsExecutor.setThreadNamePrefix("callerRuns-");
    callerRunsExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    callerRunsExecutor.initialize();

    // AbortPolicy Executor (운영 권장 설정)
    ThreadPoolTaskExecutor abortExecutor = new ThreadPoolTaskExecutor();
    abortExecutor.setCorePoolSize(2);
    abortExecutor.setMaxPoolSize(2);
    abortExecutor.setQueueCapacity(2);
    abortExecutor.setThreadNamePrefix("abort-");
    abortExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    abortExecutor.initialize();

    String mainThread = Thread.currentThread().getName();
    int taskCount = 10;

    // CallerRunsPolicy 테스트
    long callerRunsStart = System.nanoTime();
    AtomicInteger callerRunsInMain = new AtomicInteger(0);

    for (int i = 0; i < taskCount; i++) {
      callerRunsExecutor.execute(
          () -> {
            if (Thread.currentThread().getName().equals(mainThread)) {
              callerRunsInMain.incrementAndGet();
            }
            try {
              Thread.sleep(500);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }
    long callerRunsTime = (System.nanoTime() - callerRunsStart) / 1_000_000;

    // AbortPolicy 테스트
    long abortStart = System.nanoTime();
    AtomicInteger abortRejected = new AtomicInteger(0);

    for (int i = 0; i < taskCount; i++) {
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
        abortRejected.incrementAndGet();
      }
    }
    long abortTime = (System.nanoTime() - abortStart) / 1_000_000;

    callerRunsExecutor.shutdown();
    abortExecutor.shutdown();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│         Policy Comparison (Educational)                    │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ CallerRunsPolicy:                                          │");
    log.info("│   - Submit Time: {}ms                                      │", callerRunsTime);
    log.info(
        "│   - Tasks in Main Thread: {}                               │", callerRunsInMain.get());
    log.info("│   - ❌ BLOCKS main thread!                                 │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ AbortPolicy:                                               │");
    log.info("│   - Submit Time: {}ms                                      │", abortTime);
    log.info("│   - Tasks Rejected: {}                                     │", abortRejected.get());
    log.info("│   - ✅ Fast fail, no blocking                              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🎯 Production Recommendation: AbortPolicy + Fallback       │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: CallerRunsPolicy는 메인 스레드 블로킹 발생
    assertThat(callerRunsTime)
        .as("CallerRunsPolicy는 메인 스레드를 블로킹하여 제출 시간이 길어짐")
        .isGreaterThan(abortTime);
  }
}
