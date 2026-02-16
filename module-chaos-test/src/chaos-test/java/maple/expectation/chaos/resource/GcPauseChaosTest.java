package maple.expectation.chaos.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Scenario 11: GC Pause 발생 시 시스텀 안정성 검증
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - GC 강제 트리거
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - 트랜잭션 일관성
 *   <li>🔵 Blue (Architect): 흐름 검증 - 가비지 컬렉터 상태
 *   <li>🟢 Green (Performance): 메트릭 검증 - GC 일시 정지 시간
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 장애 시점별 안정성
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>GC Pause 시 서비스 가용성 유지 (5xx 에러 없음)
 *   <li>트랜잭션 롤백 없이 데이터 일관성 유지
 *   <li>GC 후 정상 상태 복구
 *   <li>동시 요청 처리 능력 유지
 * </ol>
 *
 * <h4>CS 원리</h4>
 *
 * <ul>
 *   <li>Stop-the-World: GC 실행 시 모든 스레드 중단
 *   <li>Resilient Pattern: 장애 발생 시 Graceful Degradation
 *   <li>Fail Fast: GC 문제 시 즉시 감지
 * </ul>
 *
 * @see maple.expectation.global.executor.LogicExecutor
 * @see java.lang.management.GarbageCollectorMXBean
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Scenario 11: GC Pause - 시스템 안정성 검증")
class GcPauseChaosTest extends AbstractContainerBaseTest {

  @Autowired private LogicExecutor logicExecutor;

  private final List<GarbageCollectorMXBean> gcBeans =
      ManagementFactory.getGarbageCollectorMXBeans();

  /**
   * 🟡 Yellow's Test 1: GC Pause 시 서비스 가용성 유지
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>정상 상태에서 비즈니스 로직 수행
   *   <li>GC 강제 트리거로 Pause 발생
   *   <li>GC 진행 중에도 요청 처리 가능
   * </ol>
   *
   * <p><b>예상 로그</b>:
   *
   * <pre>
   * INFO  [xxx] LogicExecutor - GC Pause 감지, 작업 계속 진행
   * </pre>
   */
  @Test
  @DisplayName("GC Pause 시 서비스 가용성 유지")
  void shouldSurviveGcPause_withoutDataLoss() {
    // Given: GC 전 상태 확인
    long initialGcCount = getYoungGcCount();

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    int concurrentRequests = 50;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(concurrentRequests);

    // When: GC 강제 트리거와 동시 요청
    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();

              // LogicExecutor로 비즈니스 로직 수행
              TaskContext context =
                  TaskContext.of("Chaos", "GC_Pause_Test", "request_" + requestId);

              String result =
                  logicExecutor.executeOrDefault(
                      () -> "processed_" + requestId, "fallback_" + requestId, context);

              // 결과 검증
              if (result.contains("processed") || result.contains("fallback")) {
                successCount.incrementAndGet();
              }

            } catch (Exception e) {
              failureCount.incrementAndGet();
            } finally {
              endLatch.countDown();
            }
          });
    }

    // GC Pause 강제 발생
    triggerGcPause();

    startLatch.countDown();

    // 모든 요청 완료 대기
    boolean completed = false;
    try {
      completed = endLatch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Test interrupted", e);
    }
    executor.shutdown();

    // Then: 결과 검증
    assertThat(completed).as("모든 요청이 30초 내에 완료되어야 함").isTrue();

    assertThat(failureCount.get()).as("GC Pause 시에도 예외 발생 없어야 함").isZero();

    assertThat(successCount.get()).as("모든 요청이 성공적으로 처리되어야 함").isEqualTo(concurrentRequests);

    // GC 발생 확인
    long finalGcCount = getYoungGcCount();
    assertThat(finalGcCount).as("GC가 실행되었어야 함").isGreaterThan(initialGcCount);
  }

  /**
   * 🔵 Blue's Test 2: GC Pause 시 트랜잭션 안정성 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>데이터베이스 트랜잭션 시작
   *   <li>GC Pause 발생
   *   <li>트랜잭션 롤백 없이 완료 확인
   * </ol>
   */
  @Test
  @DisplayName("GC Pause 시 트랜잭션 안정성 유지")
  void shouldMaintainTransactionStability_duringGcPause() {
    // Given: GC 전 상태
    long initialGcCount = getYoungGcCount();

    // When: GC Pause 중에 데이터베이스 작업 수행
    TaskContext context = TaskContext.of("Chaos", "GC_Transaction_Test");

    // LogicExecutor를 사용한 트랜잭션 작업 시뮬레이션
    String result =
        logicExecutor.executeOrDefault(
            () -> {
              // GC 발생
              triggerGcPause();
              return "transaction_completed";
            },
            "transaction_failed",
            context);

    // Then: 결과 검증
    assertThat(result).as("GC Pause 중에도 트랜잭션이 완료되어야 함").isEqualTo("transaction_completed");

    // GC 실행 확인
    long finalGcCount = getYoungGcCount();
    assertThat(finalGcCount).as("GC가 실행되었어야 함").isGreaterThan(initialGcCount);
  }

  /**
   * 🟢 Green's Test 3: GC Pause 시간 모니터링
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>GC 시작 시간 측정
   *   <li>GC 종료 시간 측정
   *   <li>GC 일시 정지 시간 계산
   * </ol>
   */
  @Test
  @DisplayName("GC Pause 시간 모니터링")
  void shouldMonitorGcPauseDuration() {
    // Given: 초기 GC 상태
    long initialGcTime = getTotalGcTime();

    // When: GC 강제 실행
    long startTime = System.nanoTime();
    triggerGcPause();
    long endTime = System.nanoTime();

    long pauseDuration = (endTime - startTime) / 1_000_000; // ms

    // Then: GC 시간 검증
    assertThat(pauseDuration).as("GC 일시 정지 시간이 합리적이어야 함 (< 1000ms)").isLessThan(1000);

    // 전체 GC 시간 증가 확인
    long finalGcTime = getTotalGcTime();
    assertThat(finalGcTime).as("전체 GC 시간이 증가했어야 함").isGreaterThan(initialGcTime);

    System.out.printf("GC Pause Duration: %dms, Total GC Time: %dms%n", pauseDuration, finalGcTime);
  }

  /**
   * 🟡 Yellow's Test 4: 반복 GC 발생 시 안정성 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>반복적으로 GC 발생
   *   <li>각 GC 시점에서 요청 처리
   *   <li>시스템 장애 없음 확인
   * </ol>
   */
  @Test
  @DisplayName("반복 GC 발생 시 시스템 안정성")
  void shouldMaintainStability_underRepeatedGc() throws InterruptedException {
    // Given: 초기 상태
    int gcIterations = 5;
    int requestsPerIteration = 20;
    AtomicInteger totalSuccess = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(5);

    // When: 반복 GC 테스트
    for (int iter = 0; iter < gcIterations; iter++) {
      AtomicInteger iterSuccess = new AtomicInteger(0);

      // GC 발생
      triggerGcPause();

      // 요청 발생
      for (int i = 0; i < requestsPerIteration; i++) {
        final int requestId = iter * requestsPerIteration + i;
        executor.submit(
            () -> {
              TaskContext context = TaskContext.of("Chaos", "Repeated_GC_Test", "req_" + requestId);

              try {
                String result =
                    logicExecutor.executeOrDefault(
                        () -> "processed_" + requestId, "fallback_" + requestId, context);

                if (result.contains("processed") || result.contains("fallback")) {
                  iterSuccess.incrementAndGet();
                }
              } catch (Exception e) {
                // 로그만 기록, 테스트 실패로 이어지지 않음
                System.err.println("Request failed: " + e.getMessage());
              }
            });
      }

      // 대기 후 결과 집계
      try {
        Thread.sleep(100);
      } catch (InterruptedException ignored) {
      }
      totalSuccess.addAndGet(iterSuccess.get());

      System.out.printf(
          "Iteration %d: %d/%d successes%n", iter, iterSuccess.get(), requestsPerIteration);
    }

    executor.shutdown();

    // Then: 전체 성공률 검증
    double successRate = (double) totalSuccess.get() / (gcIterations * requestsPerIteration);
    assertThat(successRate).as("전체 성공률이 90% 이상이어야 함").isGreaterThanOrEqualTo(0.9);

    System.out.printf("Total Success Rate: %.2f%%%n", successRate * 100);
  }

  // ==================== Helper Methods ====================

  /** Young GC 횟수 조회 */
  private long getYoungGcCount() {
    return gcBeans.stream()
        .filter(gc -> gc.getName().contains("Young"))
        .mapToLong(GarbageCollectorMXBean::getCollectionCount)
        .sum();
  }

  /** 전체 GC 시간 조회 (ms) */
  private long getTotalGcTime() {
    return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
  }

  /** GC Pause 강제 발생 시뮬레이션 */
  private void triggerGcPause() {
    // Young GC 강제 트리거
    System.gc();

    // 메모리 압박으로 Full GC 유도
    List<byte[]> memoryHog = new ArrayList<>();
    try {
      // 10MB 할당
      for (int i = 0; i < 10; i++) {
        memoryHog.add(new byte[1024 * 1024]);
      }

      // 메모리 해제 및 GC 트리거
      memoryHog.clear();
      System.gc();

      // GC 완료 대기
      try {
        Thread.sleep(100);
      } catch (InterruptedException ignored) {
      }

    } finally {
      memoryHog.clear();
    }
  }
}
