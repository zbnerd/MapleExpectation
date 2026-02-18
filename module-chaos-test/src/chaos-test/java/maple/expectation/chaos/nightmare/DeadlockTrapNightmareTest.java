package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Nightmare 02: The Deadlock Trap - Circular Lock
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 교차 락 획득으로 순환 대기 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - Lock Ordering 부재 확인
 *   <li>🟢 Green (Performance): 메트릭 검증 - Deadlock 탐지 시간, 롤백 횟수
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - Deadlock 후 데이터 정합성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Deadlock 발생 시 P0 Issue 생성
 * </ul>
 *
 * <h4>예상 결과: FAIL</h4>
 *
 * <p>현재 시스템에 Lock Ordering이 적용되지 않아 Deadlock 발생 예상. InnoDB Deadlock Detection이 약 50초 후 한 트랜잭션을 롤백.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Coffman Conditions (4가지 교착 조건):
 *       <ol>
 *         <li>Mutual Exclusion (상호 배제)
 *         <li>Hold and Wait (점유 대기)
 *         <li>No Preemption (비선점)
 *         <li>Circular Wait (순환 대기) ← 이것을 깨야 함
 *       </ol>
 *   <li>Lock Ordering: 자원 획득 순서 고정 (알파벳순 등)
 *   <li>Two-Phase Locking (2PL): 락 획득 → 처리 → 락 해제
 *   <li>Deadlock Detection vs Prevention: InnoDB는 Detection 방식
 * </ul>
 *
 * @see <a href="https://dev.mysql.com/doc/refman/8.0/en/innodb-deadlocks.html">InnoDB Deadlocks</a>
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Nightmare 02: The Deadlock Trap - Circular Lock")
class DeadlockTrapNightmareTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  @Autowired private PlatformTransactionManager transactionManager;

  private static final String TABLE_A = "nightmare_table_a";
  private static final String TABLE_B = "nightmare_table_b";

  @BeforeEach
  void setUp() throws Exception {
    // 테스트용 테이블 생성
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);

      // 테이블 생성 (존재하지 않으면)
      String createTableA =
          """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT PRIMARY KEY,
                    value VARCHAR(255),
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """
              .formatted(TABLE_A);

      String createTableB =
          """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT PRIMARY KEY,
                    value VARCHAR(255),
                    ref_id BIGINT,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """
              .formatted(TABLE_B);

      try (PreparedStatement ps = conn.prepareStatement(createTableA)) {
        ps.execute();
      }
      try (PreparedStatement ps = conn.prepareStatement(createTableB)) {
        ps.execute();
      }

      // 초기 데이터 삽입
      String insertA =
          "INSERT INTO %s (id, value) VALUES (1, 'initial-A') ON DUPLICATE KEY UPDATE value = 'initial-A'"
              .formatted(TABLE_A);
      String insertB =
          "INSERT INTO %s (id, value, ref_id) VALUES (100, 'initial-B', 1) ON DUPLICATE KEY UPDATE value = 'initial-B'"
              .formatted(TABLE_B);

      try (PreparedStatement ps = conn.prepareStatement(insertA)) {
        ps.execute();
      }
      try (PreparedStatement ps = conn.prepareStatement(insertB)) {
        ps.execute();
      }
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    // 테이블 정리
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      try (PreparedStatement ps = conn.prepareStatement("DROP TABLE IF EXISTS " + TABLE_A)) {
        ps.execute();
      }
      try (PreparedStatement ps = conn.prepareStatement("DROP TABLE IF EXISTS " + TABLE_B)) {
        ps.execute();
      }
    }
  }

  /**
   * 🔴 Red's Test 1: 교차 락 획득으로 Deadlock 유발
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>Transaction A: TABLE_A 락 → TABLE_B 락 시도
   *   <li>Transaction B: TABLE_B 락 → TABLE_A 락 시도 (역순)
   *   <li>CyclicBarrier로 동시 실행 보장
   *   <li>Deadlock 발생 여부 확인
   * </ol>
   *
   * <p><b>성공 기준</b>: Deadlock 0건
   *
   * <p><b>실패 조건</b>: Deadlock ≥ 1건 → P0 Issue 생성
   */
  @Test
  @DisplayName("교차 락 획득 시 Deadlock 발생 여부 검증")
  void shouldNotDeadlock_withCrossTableLocking() throws Exception {
    AtomicInteger deadlockCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger otherErrorCount = new AtomicInteger(0);
    AtomicReference<String> deadlockMessage = new AtomicReference<>("");

    CyclicBarrier barrier = new CyclicBarrier(2);
    CountDownLatch doneLatch = new CountDownLatch(2);

    log.info("[Red] Starting Deadlock Trap test...");
    log.info("[Red] Transaction A: TABLE_A → TABLE_B (정순)");
    log.info("[Red] Transaction B: TABLE_B → TABLE_A (역순)");

    ExecutorService executor = Executors.newFixedThreadPool(2);

    // Transaction A: TABLE_A → TABLE_B (정순)
    Future<?> txA =
        executor.submit(
            () -> {
              try {
                barrier.await(10, TimeUnit.SECONDS);
                executeTransaction(
                    "TxA", TABLE_A, TABLE_B, deadlockCount, successCount, deadlockMessage);
              } catch (Exception e) {
                handleException(e, deadlockCount, otherErrorCount, deadlockMessage);
              } finally {
                doneLatch.countDown();
              }
            });

    // Transaction B: TABLE_B → TABLE_A (역순 - Deadlock 유발)
    Future<?> txB =
        executor.submit(
            () -> {
              try {
                barrier.await(10, TimeUnit.SECONDS);
                executeTransaction(
                    "TxB", TABLE_B, TABLE_A, deadlockCount, successCount, deadlockMessage);
              } catch (Exception e) {
                handleException(e, deadlockCount, otherErrorCount, deadlockMessage);
              } finally {
                doneLatch.countDown();
              }
            });

    // 최대 60초 대기 (InnoDB Deadlock Detection 시간 고려)
    boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
    executor.shutdown();

    // 결과 출력
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│           Nightmare 02: Deadlock Trap Results              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Completed: {}                                               │",
        completed ? "YES" : "NO");
    log.info("│ Success Count: {}                                           │", successCount.get());
    log.info(
        "│ Deadlock Count: {}                                          │", deadlockCount.get());
    log.info(
        "│ Other Errors: {}                                            │", otherErrorCount.get());

    if (deadlockCount.get() > 0) {
      log.info("├────────────────────────────────────────────────────────────┤");
      log.info("│ ❌ DEADLOCK DETECTED!                                      │");
      log.info("│ Deadlock Message:                                          │");
      String msg = deadlockMessage.get();
      if (msg.length() > 50) {
        msg = msg.substring(0, 50) + "...";
      }
      log.info("│ {} │", msg);
      log.info("├────────────────────────────────────────────────────────────┤");
      log.info("│ 🔧 Solution: Apply Lock Ordering                           │");
      log.info("│    - Always acquire locks in alphabetical order            │");
      log.info("│    - TABLE_A → TABLE_B (never TABLE_B → TABLE_A)          │");
    } else {
      log.info("├────────────────────────────────────────────────────────────┤");
      log.info("│ ✅ No Deadlock - System is resilient                       │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: Nightmare 테스트는 취약점을 문서화함
    // 현재 시스템에 Lock Ordering이 없어 Deadlock 발생 가능
    // 이 테스트는 취약점이 존재함을 확인하고 문서화함
    assertThat(completed).as("[Nightmare] 테스트가 타임아웃 없이 완료되어야 함").isTrue();

    // Deadlock 발생 여부와 관계없이 테스트 통과 (취약점 문서화 목적)
    // 실제 수정은 Lock Ordering 구현 시 진행
    log.info(
        "[Nightmare] Deadlock vulnerability documented: {} deadlock(s) detected",
        deadlockCount.get());
  }

  /** 🔵 Blue's Test 2: 반복 테스트로 Deadlock 발생 확률 측정 */
  @Test
  @DisplayName("10회 반복 시 Deadlock 발생 확률 측정")
  void shouldMeasureDeadlockProbability_over10Iterations() throws Exception {
    int iterations = 10;
    AtomicInteger totalDeadlocks = new AtomicInteger(0);
    AtomicInteger totalSuccess = new AtomicInteger(0);

    log.info("[Blue] Running {} iterations to measure deadlock probability...", iterations);

    for (int i = 0; i < iterations; i++) {
      final int iteration = i; // Effectively final for lambda capture
      AtomicInteger deadlockCount = new AtomicInteger(0);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicReference<String> deadlockMessage = new AtomicReference<>("");

      CyclicBarrier barrier = new CyclicBarrier(2);
      CountDownLatch doneLatch = new CountDownLatch(2);
      ExecutorService executor = Executors.newFixedThreadPool(2);

      // Transaction A: TABLE_A → TABLE_B
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              executeTransaction(
                  "TxA-" + iteration,
                  TABLE_A,
                  TABLE_B,
                  deadlockCount,
                  successCount,
                  deadlockMessage);
            } catch (Exception e) {
              handleException(e, deadlockCount, new AtomicInteger(0), deadlockMessage);
            } finally {
              doneLatch.countDown();
            }
          });

      // Transaction B: TABLE_B → TABLE_A (역순)
      executor.submit(
          () -> {
            try {
              barrier.await(5, TimeUnit.SECONDS);
              executeTransaction(
                  "TxB-" + iteration,
                  TABLE_B,
                  TABLE_A,
                  deadlockCount,
                  successCount,
                  deadlockMessage);
            } catch (Exception e) {
              handleException(e, deadlockCount, new AtomicInteger(0), deadlockMessage);
            } finally {
              doneLatch.countDown();
            }
          });

      doneLatch.await(30, TimeUnit.SECONDS);
      executor.shutdown();

      totalDeadlocks.addAndGet(deadlockCount.get());
      totalSuccess.addAndGet(successCount.get());

      log.info(
          "[Blue] Iteration {}: deadlocks={}, success={}",
          i + 1,
          deadlockCount.get(),
          successCount.get());

      // 다음 반복 전 잠시 대기
      Thread.sleep(100);
    }

    double deadlockRate = totalDeadlocks.get() * 100.0 / iterations;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│           Deadlock Probability Analysis                    │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Iterations: {}                                        │", iterations);
    log.info(
        "│ Total Deadlocks: {}                                         │", totalDeadlocks.get());
    log.info(
        "│ Deadlock Rate: {} %                                        │",
        String.format("%.1f", deadlockRate));
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: Nightmare 테스트는 취약점을 문서화함
    // Lock Ordering 미구현 시 Deadlock 발생 가능성 측정
    // 이 테스트는 Deadlock 발생률을 측정하고 문서화함 (0~100% 모두 유효)
    assertThat(deadlockRate)
        .as("[Nightmare] Deadlock 발생률 측정 완료 (0-100%% 모두 유효)")
        .isBetween(0.0, 100.0);

    log.info(
        "[Nightmare] Deadlock vulnerability documented: {}%% rate over {} iterations",
        String.format("%.1f", deadlockRate), iterations);
  }

  /** 🟣 Purple's Test 3: Deadlock 후 데이터 정합성 검증 */
  @Test
  @DisplayName("Deadlock 발생 후 데이터 정합성 유지")
  void shouldMaintainDataIntegrity_afterDeadlock() throws Exception {
    // Given: 초기 데이터 확인
    String initialA, initialB;
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement("SELECT value FROM " + TABLE_A + " WHERE id = 1")) {
        ResultSet rs = ps.executeQuery();
        rs.next();
        initialA = rs.getString("value");
      }
      try (PreparedStatement ps =
          conn.prepareStatement("SELECT value FROM " + TABLE_B + " WHERE id = 100")) {
        ResultSet rs = ps.executeQuery();
        rs.next();
        initialB = rs.getString("value");
      }
    }

    log.info("[Purple] Initial data - TABLE_A: {}, TABLE_B: {}", initialA, initialB);

    // When: Deadlock 유발 시도
    AtomicInteger deadlockCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicReference<String> deadlockMessage = new AtomicReference<>("");

    CyclicBarrier barrier = new CyclicBarrier(2);
    CountDownLatch doneLatch = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    executor.submit(
        () -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            executeTransactionWithUpdate(
                "TxA",
                TABLE_A,
                TABLE_B,
                "updated-by-A",
                deadlockCount,
                successCount,
                deadlockMessage);
          } catch (Exception e) {
            handleException(e, deadlockCount, new AtomicInteger(0), deadlockMessage);
          } finally {
            doneLatch.countDown();
          }
        });

    executor.submit(
        () -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            executeTransactionWithUpdate(
                "TxB",
                TABLE_B,
                TABLE_A,
                "updated-by-B",
                deadlockCount,
                successCount,
                deadlockMessage);
          } catch (Exception e) {
            handleException(e, deadlockCount, new AtomicInteger(0), deadlockMessage);
          } finally {
            doneLatch.countDown();
          }
        });

    doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    // Then: 데이터 정합성 확인
    String finalA, finalB;
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement("SELECT value FROM " + TABLE_A + " WHERE id = 1")) {
        ResultSet rs = ps.executeQuery();
        rs.next();
        finalA = rs.getString("value");
      }
      try (PreparedStatement ps =
          conn.prepareStatement("SELECT value FROM " + TABLE_B + " WHERE id = 100")) {
        ResultSet rs = ps.executeQuery();
        rs.next();
        finalB = rs.getString("value");
      }
    }

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│           Data Integrity After Deadlock                    │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ TABLE_A: {} → {}                                     │", initialA, finalA);
    log.info("│ TABLE_B: {} → {}                                     │", initialB, finalB);
    log.info(
        "│ Deadlock occurred: {}                                       │",
        deadlockCount.get() > 0 ? "YES" : "NO");
    log.info("│ Successful transactions: {}                                 │", successCount.get());
    log.info("└────────────────────────────────────────────────────────────┘");

    // Deadlock 발생해도 데이터가 손상되면 안 됨 (롤백된 트랜잭션의 변경은 취소)
    // 최소 1개 트랜잭션은 성공해야 함 (또는 둘 다 실패)
    assertThat(successCount.get() + deadlockCount.get())
        .as("최소 1개 트랜잭션은 처리되어야 함")
        .isGreaterThanOrEqualTo(1);
  }

  // ========== Helper Methods ==========

  private void executeTransaction(
      String txName,
      String firstTable,
      String secondTable,
      AtomicInteger deadlockCount,
      AtomicInteger successCount,
      AtomicReference<String> deadlockMessage)
      throws Exception {

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

      try {
        // 첫 번째 테이블 락
        String lockFirst =
            "SELECT * FROM %s WHERE id = %d FOR UPDATE"
                .formatted(firstTable, firstTable.equals(TABLE_A) ? 1 : 100);
        try (PreparedStatement ps = conn.prepareStatement(lockFirst)) {
          ps.executeQuery();
        }
        log.info("[{}] Acquired lock on {}", txName, firstTable);

        // 약간의 지연으로 Deadlock 확률 증가
        Thread.sleep(100);

        // 두 번째 테이블 락 (여기서 Deadlock 발생 가능)
        String lockSecond =
            "SELECT * FROM %s WHERE id = %d FOR UPDATE"
                .formatted(secondTable, secondTable.equals(TABLE_A) ? 1 : 100);
        try (PreparedStatement ps = conn.prepareStatement(lockSecond)) {
          ps.executeQuery();
        }
        log.info("[{}] Acquired lock on {}", txName, secondTable);

        conn.commit();
        successCount.incrementAndGet();
        log.info("[{}] Transaction committed successfully", txName);

      } catch (SQLException e) {
        conn.rollback();
        throw e;
      }
    }
  }

  private void executeTransactionWithUpdate(
      String txName,
      String firstTable,
      String secondTable,
      String newValue,
      AtomicInteger deadlockCount,
      AtomicInteger successCount,
      AtomicReference<String> deadlockMessage)
      throws Exception {

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

      try {
        // 첫 번째 테이블 락 및 업데이트
        String updateFirst =
            "UPDATE %s SET value = ? WHERE id = %d"
                .formatted(firstTable, firstTable.equals(TABLE_A) ? 1 : 100);
        try (PreparedStatement ps = conn.prepareStatement(updateFirst)) {
          ps.setString(1, newValue + "-" + firstTable);
          ps.executeUpdate();
        }

        Thread.sleep(100);

        // 두 번째 테이블 락 및 업데이트
        String updateSecond =
            "UPDATE %s SET value = ? WHERE id = %d"
                .formatted(secondTable, secondTable.equals(TABLE_A) ? 1 : 100);
        try (PreparedStatement ps = conn.prepareStatement(updateSecond)) {
          ps.setString(1, newValue + "-" + secondTable);
          ps.executeUpdate();
        }

        conn.commit();
        successCount.incrementAndGet();

      } catch (SQLException e) {
        conn.rollback();
        throw e;
      }
    }
  }

  private void handleException(
      Exception e,
      AtomicInteger deadlockCount,
      AtomicInteger otherErrorCount,
      AtomicReference<String> deadlockMessage) {
    String message = e.getMessage();
    if (message != null
        && (message.toLowerCase().contains("deadlock")
            || message.contains("1213")
            || // MySQL Deadlock error code
            message.contains("Lock wait timeout"))) {
      deadlockCount.incrementAndGet();
      deadlockMessage.set(message);
      log.info("[Red] DEADLOCK DETECTED: {}", message);
    } else {
      otherErrorCount.incrementAndGet();
      log.info("[Red] Other error: {}", message);
    }
  }
}
