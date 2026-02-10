package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Nightmare 14: Pipeline Blackhole - 예외가 사라지는 블랙홀
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - LogicExecutor.executeOrDefault로 예외 삼킴 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - 예외 전파 경로 및 로깅 확인
 *   <li>🟢 Green (Performance): 메트릭 검증 - 삼켜진 예외 카운터
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 예외 상황에서 데이터 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Silent Failure 발견 시 P0 Issue
 * </ul>
 *
 * <h4>예상 결과: CONDITIONAL PASS</h4>
 *
 * <p>executeOrDefault 패턴은 조회 로직에서 안전하지만, 중요한 비즈니스 로직에서 사용하면 예외가 삼켜져 디버깅 불가.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Silent Failure: 오류 발생 후 무시하고 계속 진행
 *   <li>Fail-Fast: 오류 발생 즉시 실패로 빠른 피드백
 *   <li>Exception Swallowing: 예외를 catch하고 무시
 *   <li>Observability: 시스템 내부 상태 가시성 확보
 *   <li>Defensive Programming: 예상치 못한 상황 대비
 * </ul>
 *
 * @see LogicExecutor
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 14: Pipeline Blackhole - 예외 삼킴")
class PipelineExceptionNightmareTest extends AbstractContainerBaseTest {

  @Autowired private LogicExecutor executor;

  /**
   * 🔴 Red's Test 1: executeOrDefault가 예외를 삼키는지 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>executeOrDefault 내에서 RuntimeException 발생
   *   <li>기본값 반환 여부 확인
   *   <li>예외가 로그에 기록되는지 확인 (Observability)
   * </ol>
   *
   * <p><b>성공 기준</b>: 예외가 로그에 기록되고 기본값 반환
   *
   * <p><b>실패 조건</b>: 예외가 완전히 사라짐 (Silent Failure)
   */
  @Test
  @DisplayName("executeOrDefault 예외 삼킴 검증")
  void shouldHandleExceptionGracefully_withExecuteOrDefault() {
    AtomicBoolean exceptionThrown = new AtomicBoolean(false);
    AtomicReference<String> defaultValueReturned = new AtomicReference<>();

    String defaultValue = "default-result";
    TaskContext context = TaskContext.of("Nightmare14", "ExceptionSwallow", "test-1");

    log.info("[Red] Testing executeOrDefault exception handling...");

    // When: 예외를 발생시키는 태스크 실행
    String result =
        executor.executeOrDefault(
            () -> {
              exceptionThrown.set(true);
              log.info("[Red] Throwing RuntimeException inside task...");
              throw new RuntimeException("Intentional exception for testing");
            },
            defaultValue,
            context);

    defaultValueReturned.set(result);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│     Nightmare 14: Exception Swallowing Analysis            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Exception Was Thrown: {}                                   │",
        exceptionThrown.get() ? "YES" : "NO");
    log.info("│ Default Value Returned: {}                                 │", result);
    log.info("│ Expected Default: {}                                       │", defaultValue);
    log.info(
        "│ Behavior: {}                                               │",
        defaultValue.equals(result) ? "GRACEFUL DEGRADATION ✅" : "UNEXPECTED ❌");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ⚠️ Warning: Exception was caught and default returned      │");
    log.info("│    This is safe for queries but dangerous for mutations    │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: 기본값이 반환되어야 함
    assertThat(result)
        .as("executeOrDefault should return default value on exception")
        .isEqualTo(defaultValue);

    // 검증: 예외가 발생했어야 함
    assertThat(exceptionThrown.get()).as("Exception should have been thrown inside task").isTrue();
  }

  /**
   * 🔵 Blue's Test 2: execute 패턴은 예외를 전파하는지 검증
   *
   * <p>executeOrDefault와 달리 execute는 예외를 전파해야 함
   *
   * <p><b>#230 수정</b>: LogicExecutor는 비-BaseException을 InternalSystemException으로 래핑하므로 cause 체인에서
   * 원본 메시지를 확인해야 함
   */
  @Test
  @DisplayName("execute 패턴 예외 전파 검증 - cause 체인 보존")
  void shouldPropagateException_withExecute() {
    TaskContext context = TaskContext.of("Nightmare14", "ExceptionPropagate", "test-2");

    log.info("[Blue] Testing execute exception propagation with cause chain...");

    // When/Then: 예외가 InternalSystemException으로 래핑되어 전파되고, cause 체인에 원본 메시지 보존
    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      log.info("[Blue] Throwing RuntimeException in execute()...");
                      throw new RuntimeException("propagate");
                    },
                    context))
        .isInstanceOf(InternalSystemException.class)
        .hasCauseInstanceOf(RuntimeException.class)
        .hasRootCauseMessage("propagate");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│         execute() Exception Propagation (#230 Fixed)       │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ Exception wrapped in InternalSystemException            │");
    log.info("│ ✅ Cause chain preserved (RuntimeException)                │");
    log.info("│ ✅ Root cause message accessible for debugging             │");
    log.info("│    Use execute() for critical operations                   │");
    log.info("└────────────────────────────────────────────────────────────┘");
  }

  /**
   * 🟢 Green's Test 3: 다양한 예외 타입에 대한 처리 검증
   *
   * <p>RuntimeException, NPE, IllegalStateException 등 다양한 예외 처리
   */
  @Test
  @DisplayName("다양한 예외 타입 처리 검증")
  void shouldHandleVariousExceptionTypes() {
    AtomicInteger swallowedCount = new AtomicInteger(0);
    String defaultValue = "swallowed";

    log.info("[Green] Testing various exception types handling...");

    // Test 1: NullPointerException
    String result1 =
        executor.executeOrDefault(
            () -> {
              throw new NullPointerException("Simulated NPE");
            },
            defaultValue,
            TaskContext.of("Test", "NPE", "1"));
    if (defaultValue.equals(result1)) swallowedCount.incrementAndGet();

    // Test 2: IllegalStateException
    String result2 =
        executor.executeOrDefault(
            () -> {
              throw new IllegalStateException("Simulated ISE");
            },
            defaultValue,
            TaskContext.of("Test", "ISE", "2"));
    if (defaultValue.equals(result2)) swallowedCount.incrementAndGet();

    // Test 3: IllegalArgumentException
    String result3 =
        executor.executeOrDefault(
            () -> {
              throw new IllegalArgumentException("Simulated IAE");
            },
            defaultValue,
            TaskContext.of("Test", "IAE", "3"));
    if (defaultValue.equals(result3)) swallowedCount.incrementAndGet();

    // Test 4: Custom RuntimeException
    String result4 =
        executor.executeOrDefault(
            () -> {
              throw new RuntimeException("Custom runtime exception");
            },
            defaultValue,
            TaskContext.of("Test", "Custom", "4"));
    if (defaultValue.equals(result4)) swallowedCount.incrementAndGet();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│         Exception Type Handling Analysis                   │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Tested Exception Types: 4                                  │");
    log.info(
        "│ Swallowed (returned default): {}                           │", swallowedCount.get());
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ NPE: {}                                                    │",
        defaultValue.equals(result1) ? "SWALLOWED" : "PROPAGATED");
    log.info(
        "│ ISE: {}                                                    │",
        defaultValue.equals(result2) ? "SWALLOWED" : "PROPAGATED");
    log.info(
        "│ IAE: {}                                                    │",
        defaultValue.equals(result3) ? "SWALLOWED" : "PROPAGATED");
    log.info(
        "│ Runtime: {}                                                │",
        defaultValue.equals(result4) ? "SWALLOWED" : "PROPAGATED");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(swallowedCount.get())
        .as("executeOrDefault swallows all RuntimeException types")
        .isEqualTo(4);
  }

  /**
   * 🟣 Purple's Test 4: 비즈니스 크리티컬 작업에서 예외 삼킴 위험성
   *
   * <p>결제/재고 차감 같은 중요 작업에서 executeOrDefault 사용의 위험성 데모
   */
  @Test
  @DisplayName("비즈니스 크리티컬 작업에서 예외 삼킴 위험성")
  void shouldDemonstrateRiskOfSwallowingInCriticalOperations() {
    AtomicBoolean paymentProcessed = new AtomicBoolean(false);
    AtomicBoolean inventoryDeducted = new AtomicBoolean(false);
    AtomicBoolean notificationSent = new AtomicBoolean(false);

    log.info("[Purple] Simulating critical business operation with exception swallowing...");

    // 위험한 패턴: 결제 처리에 executeOrDefault 사용
    Boolean paymentResult =
        executor.executeOrDefault(
            () -> {
              // 결제 처리 중 예외 발생!
              log.error("[Purple] Payment failed with exception!");
              throw new RuntimeException("Payment gateway timeout");
              // paymentProcessed.set(true);  // 이 코드는 실행되지 않음
              // return true;
            },
            false, // 기본값: 실패
            TaskContext.of("Order", "Payment", "order-123"));

    // 문제: 결제가 실패했지만 기본값(false)이 반환됨
    // 개발자가 이를 "정상적인 결제 실패"로 착각할 수 있음

    // 이어서 재고 차감 시도 (결제 실패를 인지하지 못하고)
    if (!paymentResult) {
      log.warn("[Purple] Payment returned false - but was it intentional or exception?");
    }

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    Critical Operation Exception Swallowing Risk            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Scenario: Payment processing with executeOrDefault         │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Payment Exception: THROWN (gateway timeout)                │");
    log.info("│ Return Value: {} (default was returned)                    │", paymentResult);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ❌ DANGER: Cannot distinguish between:                     │");
    log.info("│    - Intentional rejection (valid card but declined)       │");
    log.info("│    - System failure (exception swallowed)                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔧 Solution for critical operations:                       │");
    log.info("│    - Use execute() to propagate exceptions                 │");
    log.info("│    - Use executeOrCatch() to handle differently            │");
    log.info("│    - Never use executeOrDefault() for mutations            │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // 이 테스트는 위험성을 보여주기 위한 것
    assertThat(paymentResult)
        .as("executeOrDefault returned default value, hiding the exception")
        .isFalse();
  }

  /**
   * 추가 Test: executeOrCatch를 사용한 안전한 예외 처리
   *
   * <p>예외 발생 시 커스텀 복구 로직 실행
   */
  @Test
  @DisplayName("executeOrCatch 안전한 예외 처리 검증")
  void shouldHandleExceptionSafely_withExecuteOrCatch() {
    AtomicBoolean recoveryExecuted = new AtomicBoolean(false);
    AtomicReference<Throwable> caughtException = new AtomicReference<>();

    log.info("[Extra] Testing executeOrCatch pattern...");

    String result =
        executor.executeOrCatch(
            () -> {
              throw new RuntimeException("Test exception");
            },
            ex -> {
              recoveryExecuted.set(true);
              caughtException.set(ex);
              log.info("[Extra] Recovery executed, exception: {}", ex.getMessage());
              return "recovered";
            },
            TaskContext.of("Nightmare14", "Recovery", "test-5"));

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│         executeOrCatch Pattern Analysis                    │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Recovery Executed: {}                                      │",
        recoveryExecuted.get() ? "YES ✅" : "NO ❌");
    log.info(
        "│ Exception Caught: {}                                       │",
        caughtException.get() != null ? "YES" : "NO");
    log.info("│ Result: {}                                                 │", result);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ executeOrCatch allows custom recovery logic             │");
    log.info("│    Better than executeOrDefault for business operations    │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(recoveryExecuted.get()).as("Recovery function should be executed").isTrue();

    assertThat(result).as("Recovery function should return custom value").isEqualTo("recovered");
  }
}
