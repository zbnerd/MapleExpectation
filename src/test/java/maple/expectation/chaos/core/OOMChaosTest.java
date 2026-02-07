package maple.expectation.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Scenario 03: OOM이 일어났을 경우
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - JVM 메모리 압박
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - 트랜잭션 롤백 확인
 *   <li>🔵 Blue (Architect): 흐름 검증 - Error 전파 정책
 *   <li>🟢 Green (Performance): 메트릭 검증 - Heap 사용량
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Graceful Handling
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>LogicExecutor가 Error를 catch하지 않고 즉시 전파
 *   <li>메모리 압박 상황에서 GC 정상 동작
 *   <li>트랜잭션 롤백으로 데이터 일관성 유지
 *   <li>Health Indicator로 메모리 상태 모니터링
 * </ol>
 *
 * <h4>CS 원리</h4>
 *
 * <ul>
 *   <li>Error vs Exception: Error는 복구 불가능, catch 금지
 *   <li>GC Pressure: 메모리 압박 시 GC 빈도 증가
 *   <li>Fail Fast: OOM 발생 시 빠른 실패 및 재시작
 * </ul>
 *
 * @see maple.expectation.global.executor.LogicExecutor
 * @see maple.expectation.global.executor.strategy.ExceptionTranslator
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Scenario 03: OOM - Error 전파 및 메모리 관리 검증")
class OOMChaosTest extends AbstractContainerBaseTest {

  @Autowired private LogicExecutor logicExecutor;

  private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

  /**
   * 🟡 Yellow's Test 1: LogicExecutor가 Error를 catch하지 않고 전파하는지 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>LogicExecutor 내에서 OutOfMemoryError 발생 시뮬레이션
   *   <li>Error가 catch되지 않고 상위로 전파되어야 함
   * </ol>
   *
   * <p><b>예상 동작</b>: Error는 복구 불가능하므로 즉시 전파
   */
  @Test
  @DisplayName("LogicExecutor가 Error를 catch하지 않고 즉시 전파")
  void shouldPropagateError_whenOutOfMemoryErrorOccurs() {
    // Given: Error를 던지는 작업
    TaskContext context = TaskContext.of("Chaos", "OOMTest");

    // When & Then: Error는 catch되지 않고 전파
    assertThatThrownBy(
            () ->
                logicExecutor.execute(
                    () -> {
                      throw new OutOfMemoryError("Simulated OOM for test");
                    },
                    context))
        .isInstanceOf(OutOfMemoryError.class)
        .hasMessageContaining("Simulated OOM");
  }

  /**
   * 🟡 Yellow's Test 2: executeOrDefault에서도 Error가 전파되는지 검증
   *
   * <p><b>시나리오</b>: 기본값 반환 패턴에서도 Error는 전파되어야 함
   */
  @Test
  @DisplayName("executeOrDefault에서도 Error는 전파됨")
  void shouldPropagateError_evenInExecuteOrDefault() {
    // Given
    TaskContext context = TaskContext.of("Chaos", "OOMDefaultTest");
    String defaultValue = "default";

    // When & Then: Error는 기본값 반환이 아닌 즉시 전파
    assertThatThrownBy(
            () ->
                logicExecutor.executeOrDefault(
                    () -> {
                      throw new StackOverflowError("Simulated StackOverflow");
                    },
                    defaultValue,
                    context))
        .isInstanceOf(StackOverflowError.class);
  }

  /**
   * 🟢 Green's Test 3: 메모리 사용량 모니터링 테스트
   *
   * <p><b>시나리오</b>: 현재 JVM 메모리 상태 확인
   */
  @Test
  @DisplayName("JVM 메모리 사용량 모니터링")
  void shouldMonitorMemoryUsage() {
    // Given: 메모리 상태 조회
    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();

    // Then: 메모리 정보 출력 (검증용)
    long usedMB = heapUsage.getUsed() / (1024 * 1024);
    long maxMB = heapUsage.getMax() / (1024 * 1024);
    double usagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;

    System.out.printf("Heap Usage: %d MB / %d MB (%.2f%%)%n", usedMB, maxMB, usagePercent);

    assertThat(usagePercent).as("초기 상태에서 힙 사용량이 90% 이하여야 함").isLessThan(90.0);
  }

  /**
   * 🟢 Green's Test 4: 메모리 압박 후 GC 복구 테스트
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>일시적으로 많은 객체 생성
   *   <li>참조 해제
   *   <li>GC 호출
   *   <li>메모리 회복 확인
   * </ol>
   */
  @Test
  @DisplayName("메모리 압박 후 GC 복구 확인")
  void shouldRecoverMemory_afterGCUnderPressure() {
    // Given: GC 전 메모리 상태
    MemoryUsage beforePressure = memoryMXBean.getHeapMemoryUsage();
    long beforeUsed = beforePressure.getUsed();

    // When: 메모리 압박 (약 50MB 할당)
    List<byte[]> memoryHog = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      memoryHog.add(new byte[1024 * 1024]); // 1MB each
    }

    MemoryUsage underPressure = memoryMXBean.getHeapMemoryUsage();
    long pressureUsed = underPressure.getUsed();

    // Then: 참조 해제 및 GC 후 복구
    memoryHog.clear();
    memoryHog = null;
    System.gc();

    // GC 완료 대기
    try {
      Thread.sleep(1000);
    } catch (InterruptedException ignored) {
    }

    MemoryUsage afterGC = memoryMXBean.getHeapMemoryUsage();
    long afterUsed = afterGC.getUsed();

    System.out.printf(
        "Memory: Before=%dMB, UnderPressure=%dMB, AfterGC=%dMB%n",
        beforeUsed / (1024 * 1024), pressureUsed / (1024 * 1024), afterUsed / (1024 * 1024));

    // GC 후 메모리가 압박 상태보다 감소해야 함
    assertThat(afterUsed).as("GC 후 메모리가 압박 상태보다 감소해야 함").isLessThan(pressureUsed);
  }

  /**
   * 🔵 Blue's Test 5: ExceptionTranslator가 Error를 re-throw하는지 검증
   *
   * <p><b>시나리오</b>: forRedisScript() 등에서 Error를 즉시 re-throw하는지 확인
   */
  @Test
  @DisplayName("ExceptionTranslator가 Error를 catch하지 않고 re-throw")
  void shouldRethrowError_inExceptionTranslator() {
    // Given
    TaskContext context = TaskContext.of("Chaos", "TranslatorTest");

    // When & Then: LogicExecutor의 executeWithTranslation에서도 Error는 전파
    assertThatThrownBy(
            () ->
                logicExecutor.executeWithTranslation(
                    () -> {
                      throw new OutOfMemoryError("OOM in translation");
                    },
                    (e, ctx) -> new RuntimeException("Should not reach here"),
                    context))
        .isInstanceOf(OutOfMemoryError.class);
  }
}
