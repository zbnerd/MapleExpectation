package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.filter.MDCFilter;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Nightmare 12: Phantom Context - 비동기 경계에서 컨텍스트 전파 검증
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 비동기 호출로 MDC 컨텍스트 전파 검증
 *   <li>🔵 Blue (Architect): 흐름 검증 - TaskDecorator 전파 경로 분석
 *   <li>🟢 Green (Performance): 메트릭 검증 - 컨텍스트 전파 성공률 측정
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 감사 로그 무결성 검증
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 실제 Executor로 전파 검증
 * </ul>
 *
 * <h4>예상 결과: PASS (TaskDecorator 적용됨)</h4>
 *
 * <p>{@code ExecutorConfig.contextPropagatingDecorator()}가 MDC를 비동기 스레드로 전파합니다.
 *
 * <h4>테스트 목적</h4>
 *
 * <p>프로젝트의 실제 Executor({@code alertTaskExecutor}, {@code expectationComputeExecutor})가 {@link
 * MDCFilter#REQUEST_ID_KEY}를 올바르게 전파하는지 검증합니다.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>ThreadLocal: 스레드별 격리된 변수 저장소
 *   <li>MDC (Mapped Diagnostic Context): 로깅 컨텍스트 전파
 *   <li>TaskDecorator: Spring의 비동기 작업 데코레이터 패턴
 *   <li>Context Propagation: 분산 추적의 핵심 메커니즘
 * </ul>
 *
 * @see maple.expectation.config.ExecutorConfig#contextPropagatingDecorator()
 * @see maple.expectation.infrastructure.filter.MDCFilter
 */
@Slf4j
@Tag("nightmare")
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Nightmare 12: Phantom Context - 비동기 컨텍스트 전파 검증")
class AsyncContextLossNightmareTest extends IntegrationTestSupport {

  @Autowired
  @Qualifier("alertTaskExecutor") private Executor alertTaskExecutor;

  @Autowired
  @Qualifier("expectationComputeExecutor") private Executor expectationComputeExecutor;

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  /**
   * 🔴 Red's Test 1: alertTaskExecutor에서 MDC 컨텍스트 전파 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>메인 스레드에 MDC 설정 (requestId - MDCFilter와 동일 키)
   *   <li>실제 alertTaskExecutor로 비동기 작업 제출
   *   <li>비동기 스레드에서 MDC 값 확인
   *   <li>결과: TaskDecorator가 MDC를 전파하여 값 유지
   * </ol>
   *
   * <p><b>성공 기준</b>: 비동기 스레드에서 MDC 컨텍스트 유지
   */
  @Test
  @DisplayName("alertTaskExecutor에서 MDC 컨텍스트 전파 검증")
  void shouldPropagateMdcContext_withAlertTaskExecutor() throws Exception {
    // Given: 메인 스레드에 MDC 설정 (MDCFilter와 동일한 키 사용)
    String expectedRequestId = "REQ-ALERT-" + System.currentTimeMillis();
    MDC.put(MDCFilter.REQUEST_ID_KEY, expectedRequestId);

    String mainThread = Thread.currentThread().getName();
    log.info("[Red] Main thread: {}", mainThread);
    log.info("[Red] MDC set - {}: {}", MDCFilter.REQUEST_ID_KEY, expectedRequestId);

    // When: 실제 alertTaskExecutor로 비동기 작업 실행
    AtomicReference<String> asyncRequestId = new AtomicReference<>();
    AtomicReference<String> asyncThread = new AtomicReference<>();

    CompletableFuture<Void> future =
        CompletableFuture.runAsync(
            () -> {
              asyncThread.set(Thread.currentThread().getName());
              asyncRequestId.set(MDC.get(MDCFilter.REQUEST_ID_KEY));

              log.info("[Red] Async thread: {}", asyncThread.get());
              log.info("[Red] Async MDC - {}: {}", MDCFilter.REQUEST_ID_KEY, asyncRequestId.get());
            },
            alertTaskExecutor);

    future.get(5, TimeUnit.SECONDS);

    // Then: 결과 분석
    boolean contextPreserved = expectedRequestId.equals(asyncRequestId.get());

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 12: alertTaskExecutor MDC Propagation          │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Main Thread: {}                                            ", mainThread);
    log.info("│ Async Thread: {}                                           ", asyncThread.get());
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Expected requestId: {}                                     ", expectedRequestId);
    log.info(
        "│ Received requestId: {}                                     ",
        asyncRequestId.get() != null ? asyncRequestId.get() : "NULL ❌");
    log.info(
        "│ Context Propagation: {}                                    ",
        contextPreserved ? "SUCCESS ✅" : "FAILED ❌");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ TaskDecorator가 MDC를 비동기 스레드로 전파함              │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(asyncRequestId.get())
        .as("[Nightmare] alertTaskExecutor should propagate MDC context")
        .isEqualTo(expectedRequestId);
  }

  /**
   * 🔵 Blue's Test 2: expectationComputeExecutor에서 MDC 컨텍스트 전파 검증
   *
   * <p>계산 전용 Executor에서도 MDC가 올바르게 전파되는지 검증
   */
  @Test
  @DisplayName("expectationComputeExecutor에서 MDC 컨텍스트 전파 검증")
  void shouldPropagateMdcContext_withExpectationComputeExecutor() throws Exception {
    // Given
    String expectedRequestId = "REQ-COMPUTE-" + System.currentTimeMillis();
    MDC.put(MDCFilter.REQUEST_ID_KEY, expectedRequestId);

    log.info("[Blue] Testing expectationComputeExecutor MDC propagation...");

    // When
    AtomicReference<String> asyncRequestId = new AtomicReference<>();

    CompletableFuture<Void> future =
        CompletableFuture.runAsync(
            () -> {
              asyncRequestId.set(MDC.get(MDCFilter.REQUEST_ID_KEY));
              log.info("[Blue] Async thread received requestId: {}", asyncRequestId.get());
            },
            expectationComputeExecutor);

    future.get(5, TimeUnit.SECONDS);

    // Then
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   expectationComputeExecutor MDC Propagation               │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Expected: {}                                               ", expectedRequestId);
    log.info(
        "│ Received: {}                                               ",
        asyncRequestId.get() != null ? asyncRequestId.get() : "NULL ❌");
    log.info(
        "│ Propagation: {}                                            ",
        expectedRequestId.equals(asyncRequestId.get()) ? "SUCCESS ✅" : "FAILED ❌");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(asyncRequestId.get())
        .as("[Nightmare] expectationComputeExecutor should propagate MDC context")
        .isEqualTo(expectedRequestId);
  }

  /**
   * 🟢 Green's Test 3: 100회 비동기 호출 시 MDC 전파 성공률 측정
   *
   * <p>실제 Executor로 여러 번 비동기 호출하여 전파 성공률 100% 확인
   */
  @Test
  @DisplayName("100회 비동기 호출 시 MDC 전파 성공률 100% 검증")
  void shouldMaintain100PercentPropagationRate_over100Calls() throws Exception {
    int iterations = 100;
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    log.info("[Green] Measuring MDC propagation rate over {} async calls...", iterations);

    CountDownLatch latch = new CountDownLatch(iterations);

    for (int i = 0; i < iterations; i++) {
      final String requestId = "REQ-RATE-" + i + "-" + System.currentTimeMillis();

      // 각 반복마다 MDC 설정
      MDC.put(MDCFilter.REQUEST_ID_KEY, requestId);

      final String expectedId = requestId; // effectively final

      CompletableFuture.runAsync(
          () -> {
            try {
              String asyncRequestId = MDC.get(MDCFilter.REQUEST_ID_KEY);

              if (expectedId.equals(asyncRequestId)) {
                successCount.incrementAndGet();
              } else {
                failCount.incrementAndGet();
                log.warn(
                    "[Green] Propagation failed - expected: {}, actual: {}",
                    expectedId,
                    asyncRequestId);
              }
            } finally {
              latch.countDown();
            }
          },
          alertTaskExecutor);
    }

    latch.await(30, TimeUnit.SECONDS);
    MDC.clear();

    double successRate = successCount.get() * 100.0 / iterations;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│         MDC Propagation Rate Analysis                      │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Calls: {}                                            ", iterations);
    log.info("│ Success: {}                                                ", successCount.get());
    log.info("│ Failed: {}                                                 ", failCount.get());
    log.info(
        "│ Success Rate: {}%                                          ",
        String.format("%.1f", successRate));
    log.info("├────────────────────────────────────────────────────────────┤");

    if (successRate == 100.0) {
      log.info("│ ✅ 100% MDC propagation success!                           │");
      log.info("│    TaskDecorator is working correctly                      │");
    } else {
      log.info(
          "│ ⚠️ {} % propagation failure detected                       ",
          String.format("%.1f", 100 - successRate));
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(successRate).as("[Nightmare] MDC propagation rate should be 100%").isEqualTo(100.0);
  }

  /**
   * 🟣 Purple's Test 4: 비동기 체인에서 MDC 전파 검증
   *
   * <p>CompletableFuture 체인 (thenRunAsync → thenRunAsync)에서 각 단계마다 MDC가 올바르게 전파되는지 검증
   *
   * <h4>🔴 알려진 취약점</h4>
   *
   * <p>thenRunAsync() 체인에서 MDC가 손실되면 이 테스트가 FAIL합니다. 실패 시 다음 해결책을 고려해야 합니다:
   *
   * <ul>
   *   <li>모든 작업을 독립적인 runAsync()로 실행
   *   <li>명시적 MDC 전달 (thenApplyAsync에서 Context 객체 사용)
   *   <li>Micrometer Context Propagation 라이브러리 도입
   * </ul>
   */
  @Test
  @DisplayName("CompletableFuture 체인에서 MDC 전파 검증")
  void shouldPropagateContext_throughAsyncChain() throws Exception {
    String expectedRequestId = "REQ-CHAIN-" + System.currentTimeMillis();
    MDC.put(MDCFilter.REQUEST_ID_KEY, expectedRequestId);

    AtomicReference<String> stage1RequestId = new AtomicReference<>();
    AtomicReference<String> stage2RequestId = new AtomicReference<>();
    AtomicReference<String> stage3RequestId = new AtomicReference<>();

    log.info("[Purple] Testing CompletableFuture chain MDC propagation...");

    CompletableFuture.runAsync(
            () -> {
              stage1RequestId.set(MDC.get(MDCFilter.REQUEST_ID_KEY));
              log.info("[Purple] Stage 1 - requestId: {}", stage1RequestId.get());
            },
            alertTaskExecutor)
        .thenRunAsync(
            () -> {
              stage2RequestId.set(MDC.get(MDCFilter.REQUEST_ID_KEY));
              log.info("[Purple] Stage 2 - requestId: {}", stage2RequestId.get());
            },
            expectationComputeExecutor)
        .thenRunAsync(
            () -> {
              stage3RequestId.set(MDC.get(MDCFilter.REQUEST_ID_KEY));
              log.info("[Purple] Stage 3 - requestId: {}", stage3RequestId.get());
            },
            alertTaskExecutor)
        .get(10, TimeUnit.SECONDS);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│     CompletableFuture Chain MDC Propagation Analysis       │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Original: {}                                               ", expectedRequestId);
    log.info(
        "│ Stage 1 (alert): {}                                        ",
        stage1RequestId.get() != null ? stage1RequestId.get() : "NULL ❌");
    log.info(
        "│ Stage 2 (compute): {}                                      ",
        stage2RequestId.get() != null ? stage2RequestId.get() : "NULL ❌");
    log.info(
        "│ Stage 3 (alert): {}                                        ",
        stage3RequestId.get() != null ? stage3RequestId.get() : "NULL ❌");
    log.info("├────────────────────────────────────────────────────────────┤");

    int preservedStages =
        (expectedRequestId.equals(stage1RequestId.get()) ? 1 : 0)
            + (expectedRequestId.equals(stage2RequestId.get()) ? 1 : 0)
            + (expectedRequestId.equals(stage3RequestId.get()) ? 1 : 0);

    if (preservedStages == 3) {
      log.info("│ ✅ All 3 stages preserved MDC context                      │");
    } else {
      log.info("│ ❌ MDC LOST! Only {}/3 stages preserved context            ", preservedStages);
      log.info("│ 🔧 Issue: thenRunAsync chain does not propagate MDC        │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(stage1RequestId.get())
        .as("Stage 1 should preserve MDC context")
        .isEqualTo(expectedRequestId);

    assertThat(stage2RequestId.get())
        .as("Stage 2 should preserve MDC context")
        .isEqualTo(expectedRequestId);

    assertThat(stage3RequestId.get())
        .as("Stage 3 should preserve MDC context")
        .isEqualTo(expectedRequestId);
  }

  /**
   * 🟡 Yellow's Test 5: TaskDecorator 없이 일반 ExecutorService 사용 시 손실 확인
   *
   * <p>프로젝트 Executor와 달리 TaskDecorator가 없는 일반 ExecutorService는 MDC를 전파하지 못한다는 것을 대조군으로 검증
   */
  @Test
  @DisplayName("[대조군] TaskDecorator 없는 ExecutorService는 MDC 손실")
  void shouldLoseMdcContext_withoutTaskDecorator() throws Exception {
    String expectedRequestId = "REQ-CONTROL-" + System.currentTimeMillis();
    MDC.put(MDCFilter.REQUEST_ID_KEY, expectedRequestId);

    // TaskDecorator 없는 일반 ExecutorService
    ExecutorService plainExecutor = Executors.newSingleThreadExecutor();

    AtomicReference<String> asyncRequestId = new AtomicReference<>();

    Future<?> future =
        plainExecutor.submit(
            () -> {
              asyncRequestId.set(MDC.get(MDCFilter.REQUEST_ID_KEY));
              log.info("[Yellow] Plain ExecutorService - requestId: {}", asyncRequestId.get());
            });

    future.get(5, TimeUnit.SECONDS);
    plainExecutor.shutdown();

    boolean contextLost = asyncRequestId.get() == null;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   [Control] Plain ExecutorService MDC Test                 │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Expected: {}                                               ", expectedRequestId);
    log.info(
        "│ Received: {}                                               ",
        asyncRequestId.get() != null ? asyncRequestId.get() : "NULL (expected)");
    log.info(
        "│ Context Lost: {}                                           ",
        contextLost ? "YES (expected behavior)" : "NO (unexpected)");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ⚠️ 일반 ExecutorService는 MDC를 전파하지 않음               │");
    log.info("│ ✅ 프로젝트의 TaskDecorator가 이 문제를 해결함               │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // 대조군: TaskDecorator 없으면 MDC 손실됨
    assertThat(asyncRequestId.get())
        .as("[Control] Plain ExecutorService should NOT propagate MDC")
        .isNull();
  }

  /**
   * 🔵 Blue's Test 6: ThreadPoolTaskExecutor 내부 스레드 이름 검증
   *
   * <p>실제 Executor가 올바른 스레드 풀에서 실행되는지 확인
   */
  @Test
  @DisplayName("Executor 스레드 이름 패턴 검증")
  void shouldUseCorrectThreadNamePrefix() throws Exception {
    AtomicReference<String> alertThread = new AtomicReference<>();
    AtomicReference<String> computeThread = new AtomicReference<>();

    CompletableFuture.allOf(
            CompletableFuture.runAsync(
                () -> {
                  alertThread.set(Thread.currentThread().getName());
                },
                alertTaskExecutor),
            CompletableFuture.runAsync(
                () -> {
                  computeThread.set(Thread.currentThread().getName());
                },
                expectationComputeExecutor))
        .get(5, TimeUnit.SECONDS);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│         Thread Name Prefix Verification                    │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ alertTaskExecutor thread: {}                               ", alertThread.get());
    log.info("│ expectationComputeExecutor thread: {}                      ", computeThread.get());
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(alertThread.get())
        .as("alertTaskExecutor should use 'alert-' prefix")
        .startsWith("alert-");

    assertThat(computeThread.get())
        .as("expectationComputeExecutor should use 'expectation-' prefix")
        .startsWith("expectation-");
  }
}
