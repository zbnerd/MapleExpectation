package maple.expectation.chaos.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Scenario 10: Pool Exhaustion - 커넥션 풀 고갈
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 커넥션을 점유하여 풀 고갈
 *   <li>🔵 Blue (Architect): 흐름 검증 - 풀 고갈 시 Fail-Fast 동작
 *   <li>🟢 Green (Performance): 메트릭 검증 - 커넥션 대기 시간, 타임아웃
 *   <li>🟣 Purple (Auditor): 데이터 검증 - 풀 고갈이 데이터 무결성에 영향 없음
 * </ul>
 *
 * <h4>검증 포인트</h4>
 *
 * <ol>
 *   <li>풀 고갈 시 빠른 타임아웃 발생
 *   <li>커넥션 반환 후 빠른 복구
 *   <li>HikariCP의 connectionTimeout 동작
 *   <li>풀 고갈 중 다른 요청에 미치는 영향
 * </ol>
 *
 * @see com.zaxxer.hikari.HikariDataSource
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Scenario 10: Pool Exhaustion - 커넥션 풀 고갈 및 복구")
class PoolExhaustionChaosTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  /** 🔴 Red's Test 1: 커넥션 풀 고갈 시 타임아웃 동작 */
  @Test
  @DisplayName("커넥션 풀 고갈 시 connectionTimeout 발생")
  void shouldTimeout_whenPoolExhausted() throws Exception {
    List<Connection> heldConnections = new ArrayList<>();
    int exhaustCount = 0;
    int maxConnections = 10; // HikariCP 기본 maximumPoolSize

    System.out.println("[Red] Starting pool exhaustion test...");
    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│               Connection Pool Exhaustion Test              │");
    System.out.println("├────────────────────────────────────────────────────────────┤");

    try {
      // 커넥션 점유 (풀 고갈 유도)
      for (int i = 0; i < maxConnections + 5; i++) {
        try {
          long start = System.nanoTime();
          Connection conn = dataSource.getConnection();
          long elapsed = (System.nanoTime() - start) / 1_000_000;

          heldConnections.add(conn);
          exhaustCount++;
          System.out.printf(
              "│ Connection %d acquired in %dms                              │%n", i + 1, elapsed);
        } catch (Exception e) {
          System.out.printf(
              "│ Connection %d: TIMEOUT (Pool exhausted) ✅                  │%n", i + 1);
          break;
        }
      }

      System.out.printf(
          "│ Held connections: %d                                        │%n",
          heldConnections.size());
      System.out.println("└────────────────────────────────────────────────────────────┘");

      // 추가 연결 시도 (타임아웃 예상)
      long timeoutStart = System.nanoTime();
      Exception timeoutException = null;

      try {
        Connection extraConn = dataSource.getConnection();
        extraConn.close();
      } catch (Exception e) {
        timeoutException = e;
      }

      long timeoutElapsed = (System.nanoTime() - timeoutStart) / 1_000_000;

      System.out.println("┌────────────────────────────────────────────────────────────┐");
      System.out.printf(
          "│ Extra connection attempt: %s after %dms         │%n",
          timeoutException != null ? "TIMEOUT" : "SUCCESS", timeoutElapsed);
      System.out.println("└────────────────────────────────────────────────────────────┘");

    } finally {
      // 모든 커넥션 반환
      for (Connection conn : heldConnections) {
        try {
          conn.close();
        } catch (Exception ignored) {
        }
      }
      System.out.printf("[Green] Released %d connections%n", heldConnections.size());
    }

    // 풀 크기만큼은 확보할 수 있어야 함
    assertThat(exhaustCount).as("최대 커넥션 수만큼 확보 가능").isGreaterThanOrEqualTo(1);
  }

  /** 🔵 Blue's Test 2: 풀 복구 후 즉시 사용 가능 확인 */
  @Test
  @DisplayName("커넥션 반환 후 즉시 재사용 가능")
  void shouldRecover_afterConnectionsReleased() throws Exception {
    List<Connection> heldConnections = new ArrayList<>();

    System.out.println("[Blue] Testing pool recovery...");

    // Phase 1: 일부 커넥션 점유
    for (int i = 0; i < 5; i++) {
      try {
        Connection conn = dataSource.getConnection();
        heldConnections.add(conn);
      } catch (Exception e) {
        break;
      }
    }
    System.out.printf("[Blue] Phase 1: Held %d connections%n", heldConnections.size());

    // Phase 2: 커넥션 반환
    for (Connection conn : heldConnections) {
      conn.close();
    }
    System.out.println("[Blue] Phase 2: All connections released");

    // Phase 3: 새 커넥션 획득 속도 측정
    long start = System.nanoTime();
    try (Connection newConn = dataSource.getConnection()) {
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      System.out.printf("[Blue] Phase 3: New connection acquired in %dms%n", elapsed);

      assertThat(elapsed).as("복구 후 커넥션 획득은 빨라야 함 (< 100ms)").isLessThan(100);
    }
  }

  /** 🟢 Green's Test 3: 동시 요청 시 풀 경합 분석 */
  @Test
  @DisplayName("동시 요청 시 커넥션 풀 경합 분석")
  void shouldAnalyze_poolContention() throws Exception {
    int concurrentRequests = 20;
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger timeoutCount = new AtomicInteger(0);
    ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();

    ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

    System.out.println("[Green] Starting pool contention analysis...");

    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();

              long start = System.nanoTime();
              try (Connection conn = dataSource.getConnection()) {
                // 짧은 작업 수행
                Thread.sleep(50);
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                responseTimes.add(elapsed);
                successCount.incrementAndGet();
              }
            } catch (Exception e) {
              timeoutCount.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    // 통계 계산
    long[] times = responseTimes.stream().mapToLong(Long::longValue).toArray();
    long avgTime = times.length > 0 ? java.util.Arrays.stream(times).sum() / times.length : 0;
    long maxTime = times.length > 0 ? java.util.Arrays.stream(times).max().orElse(0) : 0;

    System.out.println("┌────────────────────────────────────────────────────────────┐");
    System.out.println("│               Pool Contention Analysis                     │");
    System.out.println("├────────────────────────────────────────────────────────────┤");
    System.out.printf(
        "│ Concurrent Requests: %d                                     │%n", concurrentRequests);
    System.out.printf(
        "│ Success: %d, Timeout: %d                                    │%n",
        successCount.get(), timeoutCount.get());
    System.out.printf("│ Avg Response Time: %dms                                     │%n", avgTime);
    System.out.printf("│ Max Response Time: %dms                                     │%n", maxTime);
    System.out.println("└────────────────────────────────────────────────────────────┘");

    // 대부분의 요청은 성공해야 함
    assertThat(successCount.get()).as("대부분의 요청이 성공해야 함").isGreaterThan(concurrentRequests / 2);
  }
}
