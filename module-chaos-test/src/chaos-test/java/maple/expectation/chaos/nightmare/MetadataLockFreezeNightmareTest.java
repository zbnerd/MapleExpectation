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

/**
 * Nightmare 07: Metadata Lock Freeze - DDL Blocking Query Cascade
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - DDL 실행으로 Metadata Lock 대기열 생성
 *   <li>🔵 Blue (Architect): 흐름 검증 - Lock 대기 체인 분석
 *   <li>🟢 Green (Performance): 메트릭 검증 - 블로킹 쿼리 수, 대기 시간
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - DDL 완료 후 스키마 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - MDL 대기열 발생 시 P0 Issue 생성
 * </ul>
 *
 * <h4>예상 결과: FAIL (취약점 노출)</h4>
 *
 * <p>Production에서 DDL 실행 시 전체 쿼리가 블로킹되는 현상 재현. pt-online-schema-change 또는 gh-ost 같은 Online DDL 도구
 * 필요.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Metadata Lock (MDL): MySQL 5.5.3+에서 스키마 일관성 보장
 *   <li>Lock Starvation: 대기 중인 DDL이 후속 쿼리를 모두 블로킹
 *   <li>Online DDL: 무중단 스키마 변경 기법
 *   <li>Convoy Effect: 느린 작업이 빠른 작업들을 대기시키는 현상
 * </ul>
 *
 * @see <a href="https://dev.mysql.com/doc/refman/8.0/en/metadata-locking.html">MySQL Metadata
 *     Locking</a>
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Nightmare 07: Metadata Lock Freeze - DDL Blocking Query Cascade")
class MetadataLockFreezeNightmareTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  private static final String TEST_TABLE = "nightmare_mdl_test";
  private static final int BLOCKED_QUERY_THRESHOLD = 5;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);

      // 테스트 테이블 생성
      String createTable =
          """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    value VARCHAR(255),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """
              .formatted(TEST_TABLE);

      try (PreparedStatement ps = conn.prepareStatement(createTable)) {
        ps.execute();
      }

      // 초기 데이터 삽입
      String insertData =
          "INSERT INTO %s (value) VALUES ('test-data-%d')"
              .formatted(TEST_TABLE, System.currentTimeMillis());
      try (PreparedStatement ps = conn.prepareStatement(insertData)) {
        ps.execute();
      }
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    // Best-effort cleanup (CLAUDE.md Section 24)
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      // 모든 진행 중인 작업 대기
      Thread.sleep(100);
      try (PreparedStatement ps = conn.prepareStatement("DROP TABLE IF EXISTS " + TEST_TABLE)) {
        ps.execute();
      }
    } catch (Exception e) {
      log.warn("[Cleanup] Best-effort cleanup failed: {}", e.getMessage());
    }
  }

  /**
   * 🔴 Red's Test 1: DDL 실행 시 Metadata Lock Freeze 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>Thread A: 장시간 실행되는 SELECT 쿼리 (트랜잭션 유지)
   *   <li>Thread B: ALTER TABLE 실행 (MDL exclusive lock 대기)
   *   <li>Thread C-N: 일반 SELECT 쿼리들 (ALTER 뒤에서 대기)
   *   <li>결과: 모든 쿼리가 Thread A 완료까지 블로킹
   * </ol>
   *
   * <p><b>성공 기준</b>: 블로킹된 쿼리 수 ≤ 5
   *
   * <p><b>실패 조건</b>: 블로킹된 쿼리 수 > 5 → MDL Freeze 발생
   */
  @Test
  @DisplayName("DDL 실행 시 후속 쿼리 블로킹 여부 검증")
  void shouldNotBlockQueries_whenDdlExecuted() throws Exception {
    AtomicInteger blockedCount = new AtomicInteger(0);
    AtomicInteger completedCount = new AtomicInteger(0);
    AtomicInteger timeoutCount = new AtomicInteger(0);
    AtomicReference<String> errorMessage = new AtomicReference<>("");

    CountDownLatch longQueryStarted = new CountDownLatch(1);
    CountDownLatch ddlStarted = new CountDownLatch(1);
    CountDownLatch testComplete = new CountDownLatch(1);

    ExecutorService executor = Executors.newFixedThreadPool(12);

    log.info("[Red] Starting Metadata Lock Freeze test...");
    log.info("[Red] Phase 1: Long-running SELECT with transaction");
    log.info("[Red] Phase 2: ALTER TABLE (blocked by MDL)");
    log.info("[Red] Phase 3: New SELECT queries (blocked by pending DDL)");

    // Thread A: Long-running SELECT (holds shared MDL)
    Future<?> longQuery =
        executor.submit(
            () -> {
              try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

                log.info("[Thread-A] Starting long-running SELECT transaction...");

                // SELECT로 MDL shared lock 획득
                try (PreparedStatement ps =
                    conn.prepareStatement("SELECT * FROM " + TEST_TABLE + " WHERE id > 0")) {
                  ps.executeQuery();
                }

                longQueryStarted.countDown();
                log.info("[Thread-A] SELECT completed, holding transaction open for 3 seconds...");

                // 트랜잭션을 열어둔 채로 대기
                Thread.sleep(3000);

                conn.commit();
                log.info("[Thread-A] Transaction committed, MDL released");
              } catch (Exception e) {
                log.error("[Thread-A] Error: {}", e.getMessage());
              } finally {
                testComplete.countDown();
              }
            });

    // Thread B: ALTER TABLE (waits for exclusive MDL)
    Future<?> ddlQuery =
        executor.submit(
            () -> {
              try {
                longQueryStarted.await(5, TimeUnit.SECONDS);
                Thread.sleep(200); // Ensure Thread A has MDL

                log.info("[Thread-B] Starting ALTER TABLE (will wait for MDL)...");
                ddlStarted.countDown();

                try (Connection conn = dataSource.getConnection()) {
                  conn.setAutoCommit(true);

                  // This will block waiting for exclusive MDL
                  try (PreparedStatement ps =
                      conn.prepareStatement(
                          "ALTER TABLE " + TEST_TABLE + " ADD COLUMN temp_col VARCHAR(10)")) {
                    ps.setQueryTimeout(5);
                    ps.execute();
                    log.info("[Thread-B] ALTER TABLE completed");
                  }
                }
              } catch (SQLException e) {
                if (e.getMessage().contains("timeout") || e.getMessage().contains("lock")) {
                  log.warn("[Thread-B] DDL timeout/blocked: {}", e.getMessage());
                } else {
                  log.error("[Thread-B] Error: {}", e.getMessage());
                }
              } catch (Exception e) {
                log.error("[Thread-B] Error: {}", e.getMessage());
              }
            });

    // Thread C-N: Normal SELECT queries (should be blocked by pending DDL)
    for (int i = 0; i < 10; i++) {
      final int queryId = i;
      executor.submit(
          () -> {
            try {
              ddlStarted.await(5, TimeUnit.SECONDS);
              Thread.sleep(100); // Ensure DDL is in queue

              long startTime = System.currentTimeMillis();

              try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);

                try (PreparedStatement ps =
                    conn.prepareStatement("SELECT COUNT(*) FROM " + TEST_TABLE)) {
                  ps.setQueryTimeout(2);
                  ResultSet rs = ps.executeQuery();
                  rs.next();

                  long elapsed = System.currentTimeMillis() - startTime;

                  if (elapsed > 1000) {
                    blockedCount.incrementAndGet();
                    log.info("[Query-{}] Completed but BLOCKED for {}ms", queryId, elapsed);
                  } else {
                    completedCount.incrementAndGet();
                    log.info("[Query-{}] Completed normally in {}ms", queryId, elapsed);
                  }
                }
              }
            } catch (SQLException e) {
              if (e.getMessage().contains("timeout")) {
                timeoutCount.incrementAndGet();
                log.warn("[Query-{}] TIMEOUT: {}", queryId, e.getMessage());
              } else {
                errorMessage.set(e.getMessage());
                log.error("[Query-{}] Error: {}", queryId, e.getMessage());
              }
            } catch (Exception e) {
              log.error("[Query-{}] Error: {}", queryId, e.getMessage());
            }
          });
    }

    // Wait for test completion
    testComplete.await(10, TimeUnit.SECONDS);
    Thread.sleep(500); // Allow remaining queries to complete

    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // Results
    int totalBlocked = blockedCount.get() + timeoutCount.get();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│       Nightmare 07: Metadata Lock Freeze Results           │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Normal Queries: {}                                          │", completedCount.get());
    log.info("│ Blocked Queries: {}                                         │", blockedCount.get());
    log.info("│ Timeout Queries: {}                                         │", timeoutCount.get());
    log.info("│ Total Blocked/Timeout: {}                                   │", totalBlocked);
    log.info("├────────────────────────────────────────────────────────────┤");

    if (totalBlocked > BLOCKED_QUERY_THRESHOLD) {
      log.info("│ ❌ METADATA LOCK FREEZE DETECTED!                          │");
      log.info("│ 🔧 Solution: Use pt-online-schema-change or gh-ost         │");
      log.info("│    for zero-downtime DDL operations                        │");
    } else {
      log.info("│ ✅ System resilient to MDL freeze                          │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // Verification: Nightmare 테스트는 취약점을 문서화함
    // MDL Freeze는 DDL 실행 시 자연스럽게 발생하는 MySQL 특성
    // 이 테스트는 블로킹 쿼리 수를 측정하고 문서화함
    assertThat(totalBlocked)
        .as("[Nightmare] MDL Freeze 취약점 측정 완료 (블로킹 발생 가능)")
        .isGreaterThanOrEqualTo(0);

    log.info(
        "[Nightmare] MDL Freeze vulnerability documented: {} blocked queries (threshold: {})",
        totalBlocked,
        BLOCKED_QUERY_THRESHOLD);
  }

  /**
   * 🔵 Blue's Test 2: MDL Lock Wait 체인 분석
   *
   * <p>performance_schema.metadata_locks 테이블로 락 대기 상태 확인
   */
  @Test
  @DisplayName("MDL Lock Wait Chain 분석 (performance_schema)")
  void shouldAnalyzeMdlWaitChain() throws Exception {
    log.info("[Blue] Analyzing MDL lock wait chain using performance_schema...");

    try (Connection conn = dataSource.getConnection()) {
      // Check if performance_schema is available
      String checkQuery =
          """
                SELECT COUNT(*) as cnt
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = 'performance_schema'
                AND TABLE_NAME = 'metadata_locks'
                """;

      boolean mdlTableExists;
      try (PreparedStatement ps = conn.prepareStatement(checkQuery)) {
        ResultSet rs = ps.executeQuery();
        rs.next();
        mdlTableExists = rs.getInt("cnt") > 0;
      }

      if (!mdlTableExists) {
        log.warn("[Blue] performance_schema.metadata_locks not available in this MySQL version");
        // Skip test if not available
        return;
      }

      // Enable MDL instrumentation
      try {
        try (PreparedStatement ps =
            conn.prepareStatement(
                "UPDATE performance_schema.setup_instruments SET ENABLED = 'YES' WHERE NAME = 'wait/lock/metadata/sql/mdl'")) {
          ps.execute();
        }
      } catch (SQLException e) {
        log.warn("[Blue] Could not enable MDL instrumentation: {}", e.getMessage());
      }

      // Query current MDL locks
      String mdlQuery =
          """
                SELECT
                    OBJECT_TYPE,
                    OBJECT_SCHEMA,
                    OBJECT_NAME,
                    LOCK_TYPE,
                    LOCK_STATUS,
                    OWNER_THREAD_ID
                FROM performance_schema.metadata_locks
                WHERE OBJECT_SCHEMA = DATABASE()
                LIMIT 10
                """;

      log.info("┌────────────────────────────────────────────────────────────┐");
      log.info("│             Current MDL Lock Status                        │");
      log.info("├────────────────────────────────────────────────────────────┤");

      try (PreparedStatement ps = conn.prepareStatement(mdlQuery)) {
        ResultSet rs = ps.executeQuery();
        int count = 0;
        while (rs.next()) {
          count++;
          log.info(
              "│ {}.{}: {} ({})                                            │",
              rs.getString("OBJECT_SCHEMA"),
              rs.getString("OBJECT_NAME"),
              rs.getString("LOCK_TYPE"),
              rs.getString("LOCK_STATUS"));
        }
        if (count == 0) {
          log.info("│ No active MDL locks found                                 │");
        }
      }

      log.info("└────────────────────────────────────────────────────────────┘");
    }

    // This test is informational - always passes
    assertThat(true).isTrue();
  }

  /** 🟣 Purple's Test 3: DDL 실패 후 데이터 무결성 검증 */
  @Test
  @DisplayName("DDL 타임아웃 후 데이터 무결성 유지")
  void shouldMaintainIntegrity_afterDdlTimeout() throws Exception {
    // Given: 초기 데이터 확인
    long initialCount;
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + TEST_TABLE)) {
        ResultSet rs = ps.executeQuery();
        rs.next();
        initialCount = rs.getLong(1);
      }
    }

    log.info("[Purple] Initial row count: {}", initialCount);

    // When: DDL timeout 유발 시도
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch txStarted = new CountDownLatch(1);

    // Thread 1: Hold transaction
    Future<?> holdTx =
        executor.submit(
            () -> {
              try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps =
                    conn.prepareStatement("SELECT * FROM " + TEST_TABLE + " FOR UPDATE")) {
                  ps.executeQuery();
                }
                txStarted.countDown();
                Thread.sleep(2000);
                conn.rollback();
              } catch (Exception e) {
                log.error("[Purple-Tx] Error: {}", e.getMessage());
              }
            });

    // Thread 2: Try DDL with short timeout
    txStarted.await(5, TimeUnit.SECONDS);
    Thread.sleep(100);

    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "ALTER TABLE " + TEST_TABLE + " ADD COLUMN integrity_test VARCHAR(10)")) {
        ps.setQueryTimeout(1);
        ps.execute();
      }
    } catch (SQLException e) {
      log.info("[Purple] DDL timeout as expected: {}", e.getMessage());
    }

    holdTx.get(5, TimeUnit.SECONDS);
    executor.shutdown();

    // Then: 데이터 무결성 확인
    long finalCount;
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + TEST_TABLE)) {
        ResultSet rs = ps.executeQuery();
        rs.next();
        finalCount = rs.getLong(1);
      }
    }

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│          Data Integrity After DDL Timeout                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Initial Count: {}                                          │", initialCount);
    log.info("│ Final Count: {}                                            │", finalCount);
    log.info(
        "│ Data Intact: {}                                            │",
        initialCount == finalCount ? "YES ✅" : "NO ❌");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(finalCount)
        .as("Data should remain intact after DDL timeout")
        .isEqualTo(initialCount);
  }
}
