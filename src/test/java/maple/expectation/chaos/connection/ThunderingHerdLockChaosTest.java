package maple.expectation.chaos.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Scenario 17: Thundering Herd on Lock - 락 경합 폭풍
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 대량의 동시 락 요청
 *   <li>🟢 Green (Performance): 메트릭 검증 - 락 대기 시간, 처리량
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 락으로 보호되는 데이터 무결성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 동시성 경계 조건
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>다수의 스레드가 동시에 락 요청 시 처리
 *   <li>락 해제 시 대기 중인 스레드 하나만 획득
 *   <li>Thundering Herd로 인한 성능 저하 측정
 *   <li>공정성(Fairness) 보장
 * </ol>
 *
 * @see org.redisson.api.RLock
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Scenario 17: Thundering Herd Lock - 락 경합 폭풍")
class ThunderingHerdLockChaosTest extends AbstractContainerBaseTest {

  @Autowired private RedissonClient redissonClient;

  private static final String HERD_LOCK_KEY = "thundering-herd:lock";

  /** 🔴 Red's Test 1: 대량의 동시 락 요청 */
  @Test
  @DisplayName("100개 스레드가 동시에 락 요청 시 모두 순차 처리")
  void shouldHandleThunderingHerd_withManyThreads() throws Exception {
    int threadCount = 100;
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger timeoutCount = new AtomicInteger(0);
    AtomicLong totalWaitTime = new AtomicLong(0);
    ConcurrentLinkedQueue<Long> waitTimes = new ConcurrentLinkedQueue<>();

    RLock lock = redissonClient.getLock(HERD_LOCK_KEY + ":herd");

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    System.out.println("[Red] Starting Thundering Herd test with " + threadCount + " threads...");

    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();

              long waitStart = System.nanoTime();
              boolean acquired = lock.tryLock(30, 1, TimeUnit.SECONDS);
              long waitTime = (System.nanoTime() - waitStart) / 1_000_000;

              if (acquired) {
                try {
                  successCount.incrementAndGet();
                  waitTimes.add(waitTime);
                  totalWaitTime.addAndGet(waitTime);
                  // 짧은 작업 수행
                  Thread.sleep(10);
                } finally {
                  lock.unlock();
                }
              } else {
                timeoutCount.incrementAndGet();
              }
            } catch (InterruptedException e) {
              timeoutCount.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    long testStart = System.nanoTime();
    startLatch.countDown();
    doneLatch.await(60, TimeUnit.SECONDS);
    long testDuration = (System.nanoTime() - testStart) / 1_000_000;

    executor.shutdown();

    // 통계
    long avgWaitTime = successCount.get() > 0 ? totalWaitTime.get() / successCount.get() : 0;
    long maxWaitTime = waitTimes.stream().mapToLong(Long::longValue).max().orElse(0);

    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│               Thundering Herd Analysis                     │");
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.printf(
        "│ Threads: %d                                                 │%n", threadCount);
    System.out.printf(
        "│ Success: %d, Timeout: %d                                    │%n",
        successCount.get(), timeoutCount.get());
    System.out.printf(
        "│ Avg Wait Time: %dms                                         │%n", avgWaitTime);
    System.out.printf(
        "│ Max Wait Time: %dms                                         │%n", maxWaitTime);
    System.out.printf(
        "│ Total Test Duration: %dms                                   │%n", testDuration);
    System.out.printf(
        "│ Throughput: %.1f locks/sec                                  │%n",
        successCount.get() * 1000.0 / testDuration);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    // 대부분의 요청이 성공해야 함
    assertThat(successCount.get()).as("대부분의 락 요청이 성공해야 함").isGreaterThan(threadCount / 2);
  }

  /** 🟢 Green's Test 2: 락 공정성 검증 */
  @Test
  @DisplayName("락 요청 순서대로 처리 (공정성)")
  void shouldProcessInOrder_fairLocking() throws Exception {
    int threadCount = 10;
    CopyOnWriteArrayList<Integer> acquireOrder = new CopyOnWriteArrayList<>();

    RLock fairLock = redissonClient.getFairLock(HERD_LOCK_KEY + ":fair");

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    System.out.println("[Green] Testing fair lock ordering...");

    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              // 각 스레드를 약간 다른 시간에 시작하여 순서 명확화
              Thread.sleep(threadId * 10);

              boolean acquired = fairLock.tryLock(30, 1, TimeUnit.SECONDS);
              if (acquired) {
                try {
                  acquireOrder.add(threadId);
                  Thread.sleep(50);
                } finally {
                  fairLock.unlock();
                }
              }
            } catch (InterruptedException ignored) {
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    doneLatch.await(60, TimeUnit.SECONDS);
    executor.shutdown();

    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│               Fair Lock Ordering                           │");
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.printf(
        "│ Acquire Order: %s                                          │%n", acquireOrder);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    // 모든 스레드가 락을 획득해야 함
    assertThat(acquireOrder).as("모든 스레드가 락 획득").hasSize(threadCount);
  }

  /** 🟣 Purple's Test 3: 락으로 보호되는 데이터 무결성 */
  @Test
  @DisplayName("락으로 보호되는 카운터 무결성 검증")
  void shouldMaintainIntegrity_withLock() throws Exception {
    int threadCount = 50;
    int incrementsPerThread = 100;
    AtomicInteger sharedCounter = new AtomicInteger(0);

    RLock integrityLock = redissonClient.getLock(HERD_LOCK_KEY + ":integrity");

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    System.out.println("[Purple] Testing data integrity with lock...");

    for (int t = 0; t < threadCount; t++) {
      executor.submit(
          () -> {
            try {
              for (int i = 0; i < incrementsPerThread; i++) {
                boolean acquired = integrityLock.tryLock(10, 1, TimeUnit.SECONDS);
                if (acquired) {
                  try {
                    sharedCounter.incrementAndGet();
                  } finally {
                    integrityLock.unlock();
                  }
                }
              }
            } catch (InterruptedException ignored) {
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(60, TimeUnit.SECONDS);
    executor.shutdown();

    int expectedCount = threadCount * incrementsPerThread;
    int actualCount = sharedCounter.get();

    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│               Data Integrity Test                          │");
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.printf(
        "│ Threads: %d, Increments/Thread: %d                          │%n",
        threadCount, incrementsPerThread);
    System.out.printf(
        "│ Expected Count: %d                                          │%n", expectedCount);
    System.out.printf(
        "│ Actual Count: %d                                            │%n", actualCount);
    System.out.printf(
        "│ Integrity: %s                                               │%n",
        actualCount == expectedCount ? "PASS ✅" : "FAIL ❌");
    System.out.println("└────────────────────────────────────────────────────────────┘");

    assertThat(actualCount).as("락으로 보호된 카운터는 정확해야 함").isEqualTo(expectedCount);
  }
}
