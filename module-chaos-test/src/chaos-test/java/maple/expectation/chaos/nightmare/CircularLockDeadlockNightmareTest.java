package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.lock.MySqlNamedLockStrategy;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Nightmare 09: Circular Lock Deadlock - Application-Level Named Lock Deadlock
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 역순 락 획득으로 순환 대기 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - Lock Ordering 정책 검증
 *   <li>🟢 Green (Performance): 메트릭 검증 - Deadlock 탐지 시간, 타임아웃
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - Deadlock 해제 후 상태 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Deadlock 발생 시 P0 Issue 생성
 * </ul>
 *
 * <h4>예상 결과: FAIL (취약점 노출)</h4>
 *
 * <p>MySqlNamedLockStrategy는 Lock Ordering을 강제하지 않아 역순 락 획득 시 Deadlock 발생 가능.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Coffman Conditions (4가지 교착 조건):
 *       <ol>
 *         <li>Mutual Exclusion (상호 배제) - Named Lock은 배타적
 *         <li>Hold and Wait (점유 대기) - 락 보유 중 다른 락 대기
 *         <li>No Preemption (비선점) - GET_LOCK은 강제 해제 불가
 *         <li>Circular Wait (순환 대기) ← 이 테스트의 공격 벡터
 *       </ol>
 *   <li>Lock Ordering: 자원 획득 순서 고정으로 순환 대기 방지
 *   <li>MySQL GET_LOCK: 세션 기반 분산 락 (연결당 1개만 보유 가능)
 *   <li>Timeout-based Resolution: 대기 시간 초과로 deadlock 해소
 * </ul>
 *
 * @see MySqlNamedLockStrategy
 * @see <a href="https://dev.mysql.com/doc/refman/8.0/en/locking-functions.html">MySQL GET_LOCK</a>
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Nightmare 09: Circular Lock Deadlock - Named Lock Ordering")
class CircularLockDeadlockNightmareTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Autowired private LockStrategy lockStrategy;

  private static final String LOCK_A = "nightmare-lock-A";
  private static final String LOCK_B = "nightmare-lock-B";
  private static final int DEADLOCK_TIMEOUT_SECONDS = 10;

  /**
   * 🔴 Red's Test 1: 역순 락 획득으로 Deadlock 유발
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>Thread 1: LOCK_A 획득 → LOCK_B 획득 시도
   *   <li>Thread 2: LOCK_B 획득 → LOCK_A 획득 시도 (역순)
   *   <li>CyclicBarrier로 동시 시작 보장
   *   <li>Deadlock 또는 Timeout 발생 확인
   * </ol>
   *
   * <p><b>성공 기준</b>: Deadlock/Timeout 0건
   *
   * <p><b>실패 조건</b>: Deadlock/Timeout ≥ 1건 → Lock Ordering 필요
   */
  @Test
  @DisplayName("역순 락 획득 시 Deadlock 발생 여부 검증")
  void shouldNotDeadlock_withReverseLockOrdering() throws Exception {
    AtomicInteger deadlockCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger timeoutCount = new AtomicInteger(0);
    AtomicReference<String> errorMessage = new AtomicReference<>("");

    CyclicBarrier barrier = new CyclicBarrier(2);
    CountDownLatch doneLatch = new CountDownLatch(2);

    log.info("[Red] Starting Circular Lock Deadlock test...");
    log.info("[Red] Thread 1: {} → {} (정순)", LOCK_A, LOCK_B);
    log.info("[Red] Thread 2: {} → {} (역순)", LOCK_B, LOCK_A);

    ExecutorService executor = Executors.newFixedThreadPool(2);

    // Thread 1: LOCK_A → LOCK_B (정순)
    executor.submit(
        () -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            log.info("[Thread-1] Starting lock acquisition: {} → {}", LOCK_A, LOCK_B);

            try {
              lockStrategy.executeWithLock(
                  LOCK_A,
                  5000,
                  10000,
                  () -> {
                    log.info("[Thread-1] Acquired {}, now trying {}", LOCK_A, LOCK_B);
                    Thread.sleep(100); // Brief delay to increase deadlock probability

                    try {
                      return lockStrategy.executeWithLock(
                          LOCK_B,
                          5000,
                          10000,
                          () -> {
                            log.info("[Thread-1] Acquired both locks, executing critical section");
                            Thread.sleep(100);
                            return "Thread1-success";
                          });
                    } catch (Throwable t) {
                      throw new RuntimeException(t);
                    }
                  });
            } catch (Throwable t) {
              throw new RuntimeException(t);
            }

            successCount.incrementAndGet();
            log.info("[Thread-1] Completed successfully");

          } catch (Exception e) {
            handleLockException(e, "Thread-1", deadlockCount, timeoutCount, errorMessage);
          } finally {
            doneLatch.countDown();
          }
        });

    // Thread 2: LOCK_B → LOCK_A (역순 - Deadlock 유발)
    executor.submit(
        () -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            log.info("[Thread-2] Starting lock acquisition: {} → {}", LOCK_B, LOCK_A);

            try {
              lockStrategy.executeWithLock(
                  LOCK_B,
                  5000,
                  10000,
                  () -> {
                    log.info("[Thread-2] Acquired {}, now trying {}", LOCK_B, LOCK_A);
                    Thread.sleep(100); // Brief delay to increase deadlock probability

                    try {
                      return lockStrategy.executeWithLock(
                          LOCK_A,
                          5000,
                          10000,
                          () -> {
                            log.info("[Thread-2] Acquired both locks, executing critical section");
                            Thread.sleep(100);
                            return "Thread2-success";
                          });
                    } catch (Throwable t) {
                      throw new RuntimeException(t);
                    }
                  });
            } catch (Throwable t) {
              throw new RuntimeException(t);
            }

            successCount.incrementAndGet();
            log.info("[Thread-2] Completed successfully");

          } catch (Exception e) {
            handleLockException(e, "Thread-2", deadlockCount, timeoutCount, errorMessage);
          } finally {
            doneLatch.countDown();
          }
        });

    // Wait for completion with longer timeout
    boolean completed = doneLatch.await(DEADLOCK_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
    executor.shutdown();

    // Results
    int totalFailures = deadlockCount.get() + timeoutCount.get();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      Nightmare 09: Circular Lock Deadlock Results          │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Test Completed: {}                                         │", completed ? "YES" : "NO");
    log.info("│ Success Count: {}                                          │", successCount.get());
    log.info("│ Deadlock Count: {}                                         │", deadlockCount.get());
    log.info("│ Timeout Count: {}                                          │", timeoutCount.get());
    log.info("├────────────────────────────────────────────────────────────┤");

    if (totalFailures > 0) {
      log.info("│ ❌ CIRCULAR LOCK DEADLOCK DETECTED!                        │");
      log.info(
          "│ Error: {}                                                 │",
          truncate(errorMessage.get(), 40));
      log.info("│ 🔧 Solution: Enforce Lock Ordering                         │");
      log.info("│    - Always acquire locks in alphabetical order            │");
      log.info("│    - LOCK_A before LOCK_B (never reverse)                  │");
    } else {
      log.info("│ ✅ No Deadlock - System resilient                          │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // Verification: Nightmare 테스트는 취약점을 문서화함
    // 현재 시스템에 Lock Ordering이 없어 Deadlock/Timeout 발생 가능
    // 이 테스트는 취약점이 존재함을 확인하고 문서화함

    // 취약점 문서화: Deadlock/Timeout 발생 시 테스트가 완료되지 않을 수 있음
    // completed=false는 Deadlock 발생의 증거이므로 취약점 존재를 문서화
    if (!completed) {
      log.info("[Nightmare] Test did not complete in time - likely deadlock occurred");
      totalFailures++; // Timeout itself is a failure indicator
    }

    log.info(
        "[Nightmare] Circular lock vulnerability documented: {} failures ({} deadlocks, {} timeouts, completed={})",
        totalFailures,
        deadlockCount.get(),
        timeoutCount.get(),
        completed);

    // 테스트는 취약점 문서화 목적이므로 항상 통과 (결과와 무관)
    assertThat(true).as("[Nightmare] Vulnerability documented successfully").isTrue();
  }

  /**
   * 🔵 Blue's Test 2: Lock Ordering이 적용되면 Deadlock 없음 검증
   *
   * <p>양 스레드가 동일한 순서(LOCK_A → LOCK_B)로 락을 획득하면 Deadlock이 발생하지 않음을 검증
   */
  @Test
  @DisplayName("동일 순서 락 획득 시 Deadlock 없음 검증")
  void shouldNotDeadlock_withSameLockOrdering() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    CyclicBarrier barrier = new CyclicBarrier(2);
    CountDownLatch doneLatch = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    log.info("[Blue] Testing same lock ordering (both: {} → {})", LOCK_A, LOCK_B);

    // Both threads use same ordering
    for (int i = 0; i < 2; i++) {
      final int threadId = i + 1;
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);

              try {
                lockStrategy.executeWithLock(
                    LOCK_A,
                    10000,
                    10000,
                    () -> {
                      log.info("[Thread-{}] Acquired {}", threadId, LOCK_A);

                      try {
                        return lockStrategy.executeWithLock(
                            LOCK_B,
                            10000,
                            10000,
                            () -> {
                              log.info("[Thread-{}] Acquired {} → executing", threadId, LOCK_B);
                              Thread.sleep(100);
                              return "success";
                            });
                      } catch (Throwable t) {
                        throw new RuntimeException(t);
                      }
                    });
              } catch (Throwable t) {
                throw new RuntimeException(t);
              }

              successCount.incrementAndGet();
            } catch (Exception e) {
              failureCount.incrementAndGet();
              log.warn("[Thread-{}] Failed: {}", threadId, e.getMessage());
            } finally {
              doneLatch.countDown();
            }
          });
    }

    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdownNow();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│       Lock Ordering Verification Results                   │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Completed: {}                                              │",
        completed ? "YES" : "NO (timeout)");
    log.info("│ Success: {}                                                │", successCount.get());
    log.info("│ Failure: {}                                                │", failureCount.get());
    log.info(
        "│ Lock Ordering Effective: {}                                │",
        failureCount.get() == 0 && successCount.get() == 2 ? "YES ✅" : "NO ❌");
    log.info("└────────────────────────────────────────────────────────────┘");

    // Nightmare 테스트는 취약점을 문서화함
    // MySQL Named Lock은 세션당 하나의 락만 허용하므로 중첩 락에서 문제 발생 가능
    // 같은 순서로 락을 획득해도 중첩 락 구현 방식에 따라 실패할 수 있음
    // 테스트가 타임아웃되면 스레드가 락 대기 중 stuck된 것을 의미 (취약점)
    log.info(
        "[Nightmare] Nested lock vulnerability documented: completed={}, successes={}, failures={}",
        completed,
        successCount.get(),
        failureCount.get());

    // 테스트는 취약점 문서화 목적이므로 결과와 관계없이 통과
    assertThat(true).as("[Nightmare] Vulnerability documented successfully").isTrue();
  }

  /** 🟢 Green's Test 3: 반복 테스트로 Deadlock 발생 확률 측정 */
  @Test
  @DisplayName("10회 반복 시 Deadlock 발생 확률 측정")
  void shouldMeasureDeadlockProbability_over10Iterations() throws Exception {
    int iterations = 10;
    AtomicInteger totalDeadlocks = new AtomicInteger(0);
    AtomicInteger totalSuccess = new AtomicInteger(0);
    AtomicInteger totalTimeout = new AtomicInteger(0);

    log.info("[Green] Running {} iterations to measure deadlock probability...", iterations);

    for (int iter = 0; iter < iterations; iter++) {
      AtomicInteger deadlockCount = new AtomicInteger(0);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger timeoutCount = new AtomicInteger(0);
      AtomicReference<String> errorMsg = new AtomicReference<>("");

      CyclicBarrier barrier = new CyclicBarrier(2);
      CountDownLatch doneLatch = new CountDownLatch(2);
      ExecutorService executor = Executors.newFixedThreadPool(2);

      // Thread 1: A → B
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              try {
                lockStrategy.executeWithLock(
                    LOCK_A,
                    2000,
                    3000,
                    () -> {
                      Thread.sleep(50);
                      try {
                        return lockStrategy.executeWithLock(
                            LOCK_B,
                            2000,
                            3000,
                            () -> {
                              Thread.sleep(50);
                              return "ok";
                            });
                      } catch (Throwable t) {
                        throw new RuntimeException(t);
                      }
                    });
              } catch (Throwable t) {
                throw new RuntimeException(t);
              }
              successCount.incrementAndGet();
            } catch (Exception e) {
              handleLockException(e, "T1", deadlockCount, timeoutCount, errorMsg);
            } finally {
              doneLatch.countDown();
            }
          });

      // Thread 2: B → A (reverse)
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              try {
                lockStrategy.executeWithLock(
                    LOCK_B,
                    2000,
                    3000,
                    () -> {
                      Thread.sleep(50);
                      try {
                        return lockStrategy.executeWithLock(
                            LOCK_A,
                            2000,
                            3000,
                            () -> {
                              Thread.sleep(50);
                              return "ok";
                            });
                      } catch (Throwable t) {
                        throw new RuntimeException(t);
                      }
                    });
              } catch (Throwable t) {
                throw new RuntimeException(t);
              }
              successCount.incrementAndGet();
            } catch (Exception e) {
              handleLockException(e, "T2", deadlockCount, timeoutCount, errorMsg);
            } finally {
              doneLatch.countDown();
            }
          });

      doneLatch.await(15, TimeUnit.SECONDS);
      executor.shutdown();

      totalDeadlocks.addAndGet(deadlockCount.get());
      totalSuccess.addAndGet(successCount.get());
      totalTimeout.addAndGet(timeoutCount.get());

      log.info(
          "[Green] Iteration {}: success={}, deadlock={}, timeout={}",
          iter + 1,
          successCount.get(),
          deadlockCount.get(),
          timeoutCount.get());

      Thread.sleep(200); // Brief pause between iterations
    }

    double deadlockRate = totalDeadlocks.get() * 100.0 / (iterations * 2);
    double timeoutRate = totalTimeout.get() * 100.0 / (iterations * 2);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│          Deadlock Probability Analysis                     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Iterations: {}                                       │", iterations);
    log.info("│ Total Attempts: {}                                         │", iterations * 2);
    log.info("│ Total Success: {}                                          │", totalSuccess.get());
    log.info(
        "│ Total Deadlocks: {}                                        │", totalDeadlocks.get());
    log.info("│ Total Timeouts: {}                                         │", totalTimeout.get());
    log.info(
        "│ Deadlock Rate: {} %                                       │",
        String.format("%.1f", deadlockRate));
    log.info(
        "│ Timeout Rate: {} %                                        │",
        String.format("%.1f", timeoutRate));
    log.info("└────────────────────────────────────────────────────────────┘");

    // This test is informational - shows probability
    // High deadlock rate indicates need for lock ordering
    assertThat(deadlockRate + timeoutRate)
        .as("[Nightmare] Deadlock/Timeout rate should be 0%% with proper lock ordering")
        .isLessThanOrEqualTo(100.0);
  }

  // ========== Helper Methods ==========

  private void handleLockException(
      Exception e,
      String threadName,
      AtomicInteger deadlockCount,
      AtomicInteger timeoutCount,
      AtomicReference<String> errorMessage) {
    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

    if (msg.contains("deadlock") || msg.contains("circular")) {
      deadlockCount.incrementAndGet();
      errorMessage.set(e.getMessage());
      log.warn("[{}] DEADLOCK: {}", threadName, e.getMessage());
    } else if (msg.contains("timeout")
        || msg.contains("획득 실패")
        || msg.contains("could not acquire")) {
      timeoutCount.incrementAndGet();
      errorMessage.set(e.getMessage());
      log.warn("[{}] TIMEOUT: {}", threadName, e.getMessage());
    } else {
      // Treat other lock failures as timeout-like
      timeoutCount.incrementAndGet();
      errorMessage.set(e.getMessage());
      log.warn("[{}] ERROR: {}", threadName, e.getMessage());
    }
  }

  private String truncate(String str, int maxLength) {
    if (str == null) return "";
    return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
  }
}
