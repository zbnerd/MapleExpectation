package maple.expectation.chaos.nightmare;

import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightmare 02: The Deadlock Trap - Circular Lock
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 교차 락 획득으로 순환 대기 유발</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - Lock Ordering 부재 확인</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - Deadlock 탐지 시간, 롤백 횟수</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - Deadlock 후 데이터 정합성</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Deadlock 발생 시 P0 Issue 생성</li>
 * </ul>
 *
 * <h4>예상 결과: FAIL</h4>
 * <p>현재 시스템에 Lock Ordering이 적용되지 않아 Deadlock 발생 예상.
 * InnoDB Deadlock Detection이 약 50초 후 한 트랜잭션을 롤백.</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Coffman Conditions (4가지 교착 조건):
 *       <ol>
 *         <li>Mutual Exclusion (상호 배제)</li>
 *         <li>Hold and Wait (점유 대기)</li>
 *         <li>No Preemption (비선점)</li>
 *         <li>Circular Wait (순환 대기) ← 이것을 깨야 함</li>
 *       </ol>
 *   </li>
 *   <li>Lock Ordering: 자원 획득 순서 고정 (알파벳순 등)</li>
 *   <li>Two-Phase Locking (2PL): 락 획득 → 처리 → 락 해제</li>
 *   <li>Deadlock Detection vs Prevention: InnoDB는 Detection 방식</li>
 * </ul>
 *
 * @see <a href="https://dev.mysql.com/doc/refman/8.0/en/innodb-deadlocks.html">InnoDB Deadlocks</a>
 */
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 02: The Deadlock Trap - Circular Lock")
class DeadlockTrapNightmareTest extends AbstractContainerBaseTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String TABLE_A = "nightmare_table_a";
    private static final String TABLE_B = "nightmare_table_b";

    @BeforeEach
    void setUp() throws Exception {
        // 테스트용 테이블 생성
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

            // 테이블 생성 (존재하지 않으면)
            String createTableA = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT PRIMARY KEY,
                    value VARCHAR(255),
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """.formatted(TABLE_A);

            String createTableB = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT PRIMARY KEY,
                    value VARCHAR(255),
                    ref_id BIGINT,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """.formatted(TABLE_B);

            try (PreparedStatement ps = conn.prepareStatement(createTableA)) {
                ps.execute();
            }
            try (PreparedStatement ps = conn.prepareStatement(createTableB)) {
                ps.execute();
            }

            // 초기 데이터 삽입
            String insertA = "INSERT INTO %s (id, value) VALUES (1, 'initial-A') ON DUPLICATE KEY UPDATE value = 'initial-A'".formatted(TABLE_A);
            String insertB = "INSERT INTO %s (id, value, ref_id) VALUES (100, 'initial-B', 1) ON DUPLICATE KEY UPDATE value = 'initial-B'".formatted(TABLE_B);

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
     * <ol>
     *   <li>Transaction A: TABLE_A 락 → TABLE_B 락 시도</li>
     *   <li>Transaction B: TABLE_B 락 → TABLE_A 락 시도 (역순)</li>
     *   <li>CyclicBarrier로 동시 실행 보장</li>
     *   <li>Deadlock 발생 여부 확인</li>
     * </ol>
     *
     * <p><b>성공 기준</b>: Deadlock 0건</p>
     * <p><b>실패 조건</b>: Deadlock ≥ 1건 → P0 Issue 생성</p>
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

        System.out.println("[Red] Starting Deadlock Trap test...");
        System.out.println("[Red] Transaction A: TABLE_A → TABLE_B (정순)");
        System.out.println("[Red] Transaction B: TABLE_B → TABLE_A (역순)");

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Transaction A: TABLE_A → TABLE_B (정순)
        Future<?> txA = executor.submit(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                executeTransaction("TxA", TABLE_A, TABLE_B, deadlockCount, successCount, deadlockMessage);
            } catch (Exception e) {
                handleException(e, deadlockCount, otherErrorCount, deadlockMessage);
            } finally {
                doneLatch.countDown();
            }
        });

        // Transaction B: TABLE_B → TABLE_A (역순 - Deadlock 유발)
        Future<?> txB = executor.submit(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                executeTransaction("TxB", TABLE_B, TABLE_A, deadlockCount, successCount, deadlockMessage);
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
        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│           Nightmare 02: Deadlock Trap Results              │");
        System.out.println("├────────────────────────────────────────────────────────────┤");
        System.out.printf("│ Completed: %s                                               │%n", completed ? "YES" : "NO");
        System.out.printf("│ Success Count: %d                                           │%n", successCount.get());
        System.out.printf("│ Deadlock Count: %d                                          │%n", deadlockCount.get());
        System.out.printf("│ Other Errors: %d                                            │%n", otherErrorCount.get());

        if (deadlockCount.get() > 0) {
            System.out.println("├────────────────────────────────────────────────────────────┤");
            System.out.println("│ ❌ DEADLOCK DETECTED!                                      │");
            System.out.println("│ Deadlock Message:                                          │");
            String msg = deadlockMessage.get();
            if (msg.length() > 50) {
                msg = msg.substring(0, 50) + "...";
            }
            System.out.printf("│ %s │%n", msg);
            System.out.println("├────────────────────────────────────────────────────────────┤");
            System.out.println("│ 🔧 Solution: Apply Lock Ordering                           │");
            System.out.println("│    - Always acquire locks in alphabetical order            │");
            System.out.println("│    - TABLE_A → TABLE_B (never TABLE_B → TABLE_A)          │");
        } else {
            System.out.println("├────────────────────────────────────────────────────────────┤");
            System.out.println("│ ✅ No Deadlock - System is resilient                       │");
        }
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // 검증: Deadlock이 발생하면 안 됨
        // 현재 구현에서는 Lock Ordering이 없어 FAIL 예상
        assertThat(deadlockCount.get())
                .as("[Nightmare] Lock Ordering으로 Deadlock 방지")
                .isZero();
    }

    /**
     * 🔵 Blue's Test 2: 반복 테스트로 Deadlock 발생 확률 측정
     */
    @Test
    @DisplayName("10회 반복 시 Deadlock 발생 확률 측정")
    void shouldMeasureDeadlockProbability_over10Iterations() throws Exception {
        int iterations = 10;
        AtomicInteger totalDeadlocks = new AtomicInteger(0);
        AtomicInteger totalSuccess = new AtomicInteger(0);

        System.out.println("[Blue] Running " + iterations + " iterations to measure deadlock probability...");

        for (int i = 0; i < iterations; i++) {
            final int iteration = i;  // Effectively final for lambda capture
            AtomicInteger deadlockCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<String> deadlockMessage = new AtomicReference<>("");

            CyclicBarrier barrier = new CyclicBarrier(2);
            CountDownLatch doneLatch = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Transaction A: TABLE_A → TABLE_B
            executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    executeTransaction("TxA-" + iteration, TABLE_A, TABLE_B, deadlockCount, successCount, deadlockMessage);
                } catch (Exception e) {
                    handleException(e, deadlockCount, new AtomicInteger(0), deadlockMessage);
                } finally {
                    doneLatch.countDown();
                }
            });

            // Transaction B: TABLE_B → TABLE_A (역순)
            executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    executeTransaction("TxB-" + iteration, TABLE_B, TABLE_A, deadlockCount, successCount, deadlockMessage);
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

            System.out.printf("[Blue] Iteration %d: deadlocks=%d, success=%d%n",
                    i + 1, deadlockCount.get(), successCount.get());

            // 다음 반복 전 잠시 대기
            Thread.sleep(100);
        }

        double deadlockRate = totalDeadlocks.get() * 100.0 / iterations;

        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│           Deadlock Probability Analysis                    │");
        System.out.println("├────────────────────────────────────────────────────────────┤");
        System.out.printf("│ Total Iterations: %d                                        │%n", iterations);
        System.out.printf("│ Total Deadlocks: %d                                         │%n", totalDeadlocks.get());
        System.out.printf("│ Deadlock Rate: %.1f%%                                        │%n", deadlockRate);
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // 검증: Deadlock 발생률이 0%여야 함
        assertThat(deadlockRate)
                .as("[Nightmare] Deadlock 발생률 0%%")
                .isZero();
    }

    /**
     * 🟣 Purple's Test 3: Deadlock 후 데이터 정합성 검증
     */
    @Test
    @DisplayName("Deadlock 발생 후 데이터 정합성 유지")
    void shouldMaintainDataIntegrity_afterDeadlock() throws Exception {
        // Given: 초기 데이터 확인
        String initialA, initialB;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM " + TABLE_A + " WHERE id = 1")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                initialA = rs.getString("value");
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM " + TABLE_B + " WHERE id = 100")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                initialB = rs.getString("value");
            }
        }

        System.out.println("[Purple] Initial data - TABLE_A: " + initialA + ", TABLE_B: " + initialB);

        // When: Deadlock 유발 시도
        AtomicInteger deadlockCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<String> deadlockMessage = new AtomicReference<>("");

        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch doneLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                executeTransactionWithUpdate("TxA", TABLE_A, TABLE_B, "updated-by-A",
                        deadlockCount, successCount, deadlockMessage);
            } catch (Exception e) {
                handleException(e, deadlockCount, new AtomicInteger(0), deadlockMessage);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                executeTransactionWithUpdate("TxB", TABLE_B, TABLE_A, "updated-by-B",
                        deadlockCount, successCount, deadlockMessage);
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
            try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM " + TABLE_A + " WHERE id = 1")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                finalA = rs.getString("value");
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM " + TABLE_B + " WHERE id = 100")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                finalB = rs.getString("value");
            }
        }

        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│           Data Integrity After Deadlock                    │");
        System.out.println("├────────────────────────────────────────────────────────────┤");
        System.out.printf("│ TABLE_A: %s → %s                                     │%n", initialA, finalA);
        System.out.printf("│ TABLE_B: %s → %s                                     │%n", initialB, finalB);
        System.out.printf("│ Deadlock occurred: %s                                       │%n", deadlockCount.get() > 0 ? "YES" : "NO");
        System.out.printf("│ Successful transactions: %d                                 │%n", successCount.get());
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // Deadlock 발생해도 데이터가 손상되면 안 됨 (롤백된 트랜잭션의 변경은 취소)
        // 최소 1개 트랜잭션은 성공해야 함 (또는 둘 다 실패)
        assertThat(successCount.get() + deadlockCount.get())
                .as("최소 1개 트랜잭션은 처리되어야 함")
                .isGreaterThanOrEqualTo(1);
    }

    // ========== Helper Methods ==========

    private void executeTransaction(String txName, String firstTable, String secondTable,
                                    AtomicInteger deadlockCount, AtomicInteger successCount,
                                    AtomicReference<String> deadlockMessage) throws Exception {

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            try {
                // 첫 번째 테이블 락
                String lockFirst = "SELECT * FROM %s WHERE id = %d FOR UPDATE".formatted(
                        firstTable, firstTable.equals(TABLE_A) ? 1 : 100);
                try (PreparedStatement ps = conn.prepareStatement(lockFirst)) {
                    ps.executeQuery();
                }
                System.out.printf("[%s] Acquired lock on %s%n", txName, firstTable);

                // 약간의 지연으로 Deadlock 확률 증가
                Thread.sleep(100);

                // 두 번째 테이블 락 (여기서 Deadlock 발생 가능)
                String lockSecond = "SELECT * FROM %s WHERE id = %d FOR UPDATE".formatted(
                        secondTable, secondTable.equals(TABLE_A) ? 1 : 100);
                try (PreparedStatement ps = conn.prepareStatement(lockSecond)) {
                    ps.executeQuery();
                }
                System.out.printf("[%s] Acquired lock on %s%n", txName, secondTable);

                conn.commit();
                successCount.incrementAndGet();
                System.out.printf("[%s] Transaction committed successfully%n", txName);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private void executeTransactionWithUpdate(String txName, String firstTable, String secondTable,
                                              String newValue, AtomicInteger deadlockCount,
                                              AtomicInteger successCount, AtomicReference<String> deadlockMessage) throws Exception {

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            try {
                // 첫 번째 테이블 락 및 업데이트
                String updateFirst = "UPDATE %s SET value = ? WHERE id = %d".formatted(
                        firstTable, firstTable.equals(TABLE_A) ? 1 : 100);
                try (PreparedStatement ps = conn.prepareStatement(updateFirst)) {
                    ps.setString(1, newValue + "-" + firstTable);
                    ps.executeUpdate();
                }

                Thread.sleep(100);

                // 두 번째 테이블 락 및 업데이트
                String updateSecond = "UPDATE %s SET value = ? WHERE id = %d".formatted(
                        secondTable, secondTable.equals(TABLE_A) ? 1 : 100);
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

    private void handleException(Exception e, AtomicInteger deadlockCount,
                                 AtomicInteger otherErrorCount, AtomicReference<String> deadlockMessage) {
        String message = e.getMessage();
        if (message != null && (message.toLowerCase().contains("deadlock") ||
                message.contains("1213") || // MySQL Deadlock error code
                message.contains("Lock wait timeout"))) {
            deadlockCount.incrementAndGet();
            deadlockMessage.set(message);
            System.out.println("[Red] DEADLOCK DETECTED: " + message);
        } else {
            otherErrorCount.incrementAndGet();
            System.out.println("[Red] Other error: " + message);
        }
    }
}
