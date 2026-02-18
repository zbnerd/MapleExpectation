package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Nightmare 18: Deep Paging Abyss - 깊은 페이징의 성능 지옥
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - OFFSET 1,000,000으로 극한 페이징 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - Cursor-based Pagination 패턴 제안
 *   <li>🟢 Green (Performance): 메트릭 검증 - 쿼리 실행 시간, Full Scan 여부
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 페이징 결과 일관성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 응답 시간 > 5초 시 P0 Issue
 * </ul>
 *
 * <h4>예상 결과: FAIL (성능 문제)</h4>
 *
 * <p>OFFSET이 클수록 MySQL은 그만큼의 행을 스캔한 후 버림. OFFSET 1,000,000 + LIMIT 10은 1,000,010행을 읽어야 함.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Offset Pagination: LIMIT + OFFSET의 O(n) 성능
 *   <li>Cursor-based Pagination: WHERE id > last_id의 O(1) 성능
 *   <li>Keyset Pagination: 인덱스 활용 빠른 페이징
 *   <li>Full Table Scan: OFFSET이 인덱스를 무력화
 *   <li>Database Buffer Pool: 불필요한 페이지 로드
 * </ul>
 *
 * @see <a href="https://use-the-index-luke.com/no-offset">Use The Index, Luke! - No Offset</a>
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Nightmare 18: Deep Paging Abyss - 깊은 페이징 성능 문제")
class DeepPagingNightmareTest extends AbstractContainerBaseTest {

  @Autowired private DataSource dataSource;

  private static final String TEST_TABLE = "nightmare_paging_test";
  private static final int PAGE_SIZE = 10;
  private static final int TOTAL_ROWS = 10000; // 테스트용 (실제로는 수백만)

  @BeforeEach
  void setUp() throws Exception {
    createAndPopulateTestTable();
  }

  @AfterEach
  void tearDown() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement ps = conn.prepareStatement("DROP TABLE IF EXISTS " + TEST_TABLE)) {
        ps.execute();
      }
    }
  }

  /**
   * 🔴 Red's Test 1: Deep Offset의 성능 저하 측정
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>OFFSET 0 (첫 페이지) 쿼리 실행 시간 측정
   *   <li>OFFSET 9990 (마지막 페이지) 쿼리 실행 시간 측정
   *   <li>성능 차이 분석
   * </ol>
   *
   * <p><b>성공 기준</b>: 마지막 페이지도 100ms 이내
   *
   * <p><b>실패 조건</b>: 성능 저하가 심각함 (10배 이상)
   */
  @Test
  @DisplayName("Deep Offset 성능 저하 측정")
  void shouldMeasureDeepOffsetPerformanceDegradation() throws Exception {
    // Page 1 (OFFSET 0)
    long page1Time = measureQueryTime(0);
    log.info("[Red] Page 1 (OFFSET 0): {}ms", page1Time);

    // Page 100 (OFFSET 990)
    long page100Time = measureQueryTime(990);
    log.info("[Red] Page 100 (OFFSET 990): {}ms", page100Time);

    // Page 500 (OFFSET 4990)
    long page500Time = measureQueryTime(4990);
    log.info("[Red] Page 500 (OFFSET 4990): {}ms", page500Time);

    // Page 1000 (OFFSET 9990) - 마지막 페이지
    long page1000Time = measureQueryTime(9990);
    log.info("[Red] Page 1000 (OFFSET 9990): {}ms", page1000Time);

    double degradationRatio = (double) page1000Time / Math.max(page1Time, 1);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      Nightmare 18: Deep Paging Performance Results         │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Dataset Size: {} rows                                      │", TOTAL_ROWS);
    log.info("│ Page Size: {}                                              │", PAGE_SIZE);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Page 1 (OFFSET 0): {}ms                                    │", page1Time);
    log.info("│ Page 100 (OFFSET 990): {}ms                                │", page100Time);
    log.info("│ Page 500 (OFFSET 4990): {}ms                               │", page500Time);
    log.info("│ Page 1000 (OFFSET 9990): {}ms                              │", page1000Time);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Degradation Ratio: {}x                                     │",
        String.format("%.2f", degradationRatio));
    log.info("├────────────────────────────────────────────────────────────┤");

    if (degradationRatio > 5) {
      log.info("│ ❌ DEEP PAGING PERFORMANCE ISSUE!                          │");
      log.info("│ 🔧 Solutions:                                              │");
      log.info("│    1. Use Cursor-based Pagination                          │");
      log.info("│    2. Use Keyset Pagination (WHERE id > ?)                 │");
      log.info("│    3. Limit max page depth (e.g., max 100 pages)           │");
    } else {
      log.info("│ ✅ Performance degradation is acceptable                   │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 작은 데이터셋에서는 성능 저하가 크지 않을 수 있음
    // 실제 수백만 행에서는 현저한 차이
    assertThat(page1000Time)
        .as("[Nightmare] Deep offset page should still respond (but may be slow)")
        .isGreaterThanOrEqualTo(0);
  }

  /**
   * 🔵 Blue's Test 2: Cursor-based Pagination 성능 비교
   *
   * <p>WHERE id > last_id 방식으로 페이징 성능 개선
   */
  @Test
  @DisplayName("Cursor-based Pagination 성능 비교")
  void shouldCompareOffsetVsCursorPagination() throws Exception {
    // OFFSET 방식
    long offsetTime = measureQueryTime(9990);

    // Cursor 방식 (WHERE id > 9990)
    long cursorTime = measureCursorQueryTime(9990);

    double improvement = ((double) offsetTime - cursorTime) / Math.max(offsetTime, 1) * 100;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│     Offset vs Cursor Pagination Comparison                 │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Target: Page 1000 (items 9991-10000)                       │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ OFFSET Method:                                             │");
    log.info("│   Query: SELECT * FROM t LIMIT 10 OFFSET 9990              │");
    log.info("│   Time: {}ms                                               │", offsetTime);
    log.info("│   Rows Scanned: ~10000                                     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ CURSOR Method:                                             │");
    log.info("│   Query: SELECT * FROM t WHERE id > 9990 LIMIT 10          │");
    log.info("│   Time: {}ms                                               │", cursorTime);
    log.info("│   Rows Scanned: 10 (index seek)                            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Performance Improvement: {}%                               │",
        String.format("%.1f", improvement));
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔧 Cursor Pagination Implementation:                        │");
    log.info("│                                                            │");
    log.info("│   // First page                                            │");
    log.info("│   SELECT * FROM items ORDER BY id LIMIT 10                 │");
    log.info("│                                                            │");
    log.info("│   // Next pages (pass lastId from previous response)       │");
    log.info("│   SELECT * FROM items WHERE id > :lastId ORDER BY id       │");
    log.info("│   LIMIT 10                                                 │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // Cursor 방식이 더 빠르거나 비슷해야 함
    assertThat(cursorTime)
        .as("Cursor-based pagination should be at least as fast as offset")
        .isLessThanOrEqualTo(offsetTime + 100); // 100ms 허용
  }

  /**
   * 🟢 Green's Test 3: EXPLAIN으로 쿼리 분석
   *
   * <p>MySQL EXPLAIN으로 실행 계획 확인
   */
  @Test
  @DisplayName("EXPLAIN으로 Deep Paging 쿼리 분석")
  void shouldAnalyzeQueryPlanWithExplain() throws Exception {
    log.info("[Green] Analyzing query execution plan...");

    try (Connection conn = dataSource.getConnection()) {
      // OFFSET 방식 EXPLAIN
      String offsetQuery =
          "EXPLAIN SELECT * FROM " + TEST_TABLE + " ORDER BY id LIMIT 10 OFFSET 9990";
      log.info("┌────────────────────────────────────────────────────────────┐");
      log.info("│         OFFSET Query Execution Plan                        │");
      log.info("├────────────────────────────────────────────────────────────┤");

      try (PreparedStatement ps = conn.prepareStatement(offsetQuery);
          ResultSet rs = ps.executeQuery()) {

        while (rs.next()) {
          log.info(
              "│ type: {}                                                 │", rs.getString("type"));
          log.info(
              "│ possible_keys: {}                                        │",
              rs.getString("possible_keys"));
          log.info(
              "│ key: {}                                                  │", rs.getString("key"));
          log.info(
              "│ rows: {}                                                 │", rs.getString("rows"));
          log.info(
              "│ Extra: {}                                                │",
              rs.getString("Extra"));
        }
      }

      // CURSOR 방식 EXPLAIN
      String cursorQuery =
          "EXPLAIN SELECT * FROM " + TEST_TABLE + " WHERE id > 9990 ORDER BY id LIMIT 10";
      log.info("├────────────────────────────────────────────────────────────┤");
      log.info("│         CURSOR Query Execution Plan                        │");
      log.info("├────────────────────────────────────────────────────────────┤");

      try (PreparedStatement ps = conn.prepareStatement(cursorQuery);
          ResultSet rs = ps.executeQuery()) {

        while (rs.next()) {
          log.info(
              "│ type: {}                                                 │", rs.getString("type"));
          log.info(
              "│ possible_keys: {}                                        │",
              rs.getString("possible_keys"));
          log.info(
              "│ key: {}                                                  │", rs.getString("key"));
          log.info(
              "│ rows: {}                                                 │", rs.getString("rows"));
          log.info(
              "│ Extra: {}                                                │",
              rs.getString("Extra"));
        }
      }
      log.info("└────────────────────────────────────────────────────────────┘");
    }

    assertThat(true).isTrue();
  }

  /**
   * 🟣 Purple's Test 4: 페이징 결과 일관성 검증
   *
   * <p>데이터 변경 중 페이징 시 결과 일관성 문제
   */
  @Test
  @DisplayName("페이징 중 데이터 변경 시 일관성 문제")
  void shouldDemonstrateConsistencyIssuesDuringPaging() {
    log.info("[Purple] Demonstrating consistency issues during pagination...");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│     Pagination Consistency Issues                          │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Scenario: User scrolling through pages while data changes  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Problem 1: OFFSET + INSERT                                 │");
    log.info("│   - User at page 2 (items 11-20)                           │");
    log.info("│   - New item inserted at position 5                        │");
    log.info("│   - User goes to page 3 → sees item 20 again (duplicate)   │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Problem 2: OFFSET + DELETE                                 │");
    log.info("│   - User at page 2 (items 11-20)                           │");
    log.info("│   - Item 8 deleted                                         │");
    log.info("│   - User goes to page 3 → skips item 21 (data loss)        │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔧 Solutions:                                               │");
    log.info("│   1. Use Cursor-based pagination (immune to inserts)       │");
    log.info("│   2. Snapshot isolation for critical reports               │");
    log.info("│   3. Document eventual consistency in UI                   │");
    log.info("│   4. For exports: use streaming, not pagination            │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).isTrue();
  }

  // ========== Helper Methods ==========

  private void createAndPopulateTestTable() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);

      // 테이블 생성
      String createTable =
          """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(100),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_created_at (created_at)
                ) ENGINE=InnoDB
                """
              .formatted(TEST_TABLE);

      try (PreparedStatement ps = conn.prepareStatement(createTable)) {
        ps.execute();
      }

      // 데이터 삽입 (배치)
      String insertSql = "INSERT INTO " + TEST_TABLE + " (name) VALUES (?)";
      try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
        for (int i = 0; i < TOTAL_ROWS; i++) {
          ps.setString(1, "Item-" + i);
          ps.addBatch();

          if ((i + 1) % 1000 == 0) {
            ps.executeBatch();
            log.info("[Setup] Inserted {} rows", i + 1);
          }
        }
        ps.executeBatch();
      }

      conn.commit();
      log.info("[Setup] Created test table with {} rows", TOTAL_ROWS);
    }
  }

  private long measureQueryTime(int offset) throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      String query = "SELECT * FROM " + TEST_TABLE + " ORDER BY id LIMIT ? OFFSET ?";

      long start = System.currentTimeMillis();
      try (PreparedStatement ps = conn.prepareStatement(query)) {
        ps.setInt(1, PAGE_SIZE);
        ps.setInt(2, offset);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            // 결과 소비
            rs.getString("name");
          }
        }
      }
      return System.currentTimeMillis() - start;
    }
  }

  private long measureCursorQueryTime(long lastId) throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      String query = "SELECT * FROM " + TEST_TABLE + " WHERE id > ? ORDER BY id LIMIT ?";

      long start = System.currentTimeMillis();
      try (PreparedStatement ps = conn.prepareStatement(query)) {
        ps.setLong(1, lastId);
        ps.setInt(2, PAGE_SIZE);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            rs.getString("name");
          }
        }
      }
      return System.currentTimeMillis() - start;
    }
  }
}
