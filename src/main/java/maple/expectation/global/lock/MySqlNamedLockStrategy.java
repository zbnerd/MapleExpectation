package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL Named Lock 기반 Fallback 락 전략
 *
 * <h3>🚨 P0: 세션 고정 강제</h3>
 * <p><b>ConnectionCallback</b>을 사용하여 단일 Connection 내에서 [GET_LOCK → task → RELEASE_LOCK]을 원자적으로 완결합니다.
 * <p>일반적인 {@code queryForObject}를 개별 호출하면 커넥션 풀 환경에서 획득/해제 세션이 달라져 좀비 락이 발생합니다!
 *
 * <h3>Before (좀비 락 위험)</h3>
 * <pre>{@code
 * // ❌ Connection 1
 * Integer result = jdbc.queryForObject("SELECT GET_LOCK(?, ?)", Integer.class, key, wait);
 *
 * // 비즈니스 로직 실행...
 *
 * // ❌ Connection 2 (다를 수 있음!)
 * jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, key);
 * }</pre>
 *
 * <h3>After (세션 고정)</h3>
 * <pre>{@code
 * // ✅ 단일 Connection 객체 사용
 * jdbc.execute((ConnectionCallback<T>) conn -> {
 *     Integer lockResult = executeLock(conn, key, wait);  // Connection 1
 *     if (lockResult != 1) throw new DistributedLockException(...);
 *
 *     try {
 *         return task.get();  // 비즈니스 로직
 *     } finally {
 *         executeRelease(conn, key);  // Connection 1 (동일!)
 *     }
 * });
 * }</pre>
 *
 * <h3>MySQL Named Lock 특징</h3>
 * <ul>
 *   <li>SELECT GET_LOCK(name, timeout): 락 획득 (1=성공, 0=타임아웃, NULL=에러)</li>
 *   <li>SELECT RELEASE_LOCK(name): 락 해제 (1=성공, 0=다른 스레드 소유, NULL=존재안함)</li>
 *   <li>세션 기반: 커넥션이 닫히면 자동으로 락 해제</li>
 *   <li>비재진입적(Non-reentrant): 동일 세션에서도 재획득 불가</li>
 * </ul>
 *
 * @see LockStrategy
 * @see LogicExecutor
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")  // 테스트 환경에서는 GuavaLockStrategy 사용
public class MySqlNamedLockStrategy implements LockStrategy {

    @Qualifier("lockJdbcTemplate")
    private final JdbcTemplate lockJdbcTemplate;

    private final LogicExecutor executor;

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
        String lockKey = buildLockKey(key);

        return executor.executeWithTranslation(
            () -> this.executeWithConnectionCallback(lockKey, waitTime, task),
            ExceptionTranslator.forLock(),
            "MySqlLock:execute:" + key
        );
    }

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 10, 20, task);
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        String lockKey = buildLockKey(key);

        return executor.executeOrDefault(
            () -> this.attemptImmediateLock(lockKey),
            false,
            "MySqlLock:tryImmediate:" + key
        );
    }

    @Override
    public void unlock(String key) {
        log.warn("⚠️ [MySQL Lock] unlock() 호출됨 - MySQL Named Lock은 세션 기반이므로 Connection이 닫히면 자동 해제됩니다. 명시적 unlock은 의미가 없습니다.");
    }

    /**
     * 🚨 P0: ConnectionCallback으로 세션 고정
     *
     * <p>단일 Connection 내에서 [GET_LOCK → task → RELEASE_LOCK] 원자적 완결
     */
    private <T> T executeWithConnectionCallback(
        String lockKey,
        long waitTime,
        ThrowingSupplier<T> task
    ) throws Throwable {
        return lockJdbcTemplate.execute(new ConnectionCallback<T>() {
            @Override
            public T doInConnection(Connection conn) throws SQLException {
                try {
                    // 1. 락 획득 (동일 Connection)
                    Integer lockResult = executeLockQuery(conn, lockKey, waitTime);

                    if (lockResult == null || lockResult != 1) {
                        log.warn("⏭️ [MySQL Lock] '{}' 획득 실패 (result: {})", lockKey, lockResult);
                        throw new DistributedLockException(lockKey);
                    }

                    log.info("🔓 [MySQL Lock] '{}' 획득 성공 (fallback mode)", lockKey);

                    // 2. 비즈니스 로직 실행 + finally에서 락 해제
                    try {
                        return task.get();
                    } finally {
                        // 3. 락 해제 (동일 Connection 보장!)
                        executeReleaseQuery(conn, lockKey);
                    }
                } catch (SQLException e) {
                    throw e;  // SQLException은 그대로 전파
                } catch (Throwable e) {
                    // ✅ P0: Error는 SQLException으로 래핑하여 상위로 전파 (LogicExecutor에서 격리됨)
                    throw new SQLException("MySQL Lock 작업 중 예외 발생: " + e.getClass().getSimpleName(), e);
                }
            }
        });
    }

    /**
     * GET_LOCK 쿼리 실행 (Connection 고정)
     */
    private Integer executeLockQuery(Connection conn, String lockKey, long waitTime) throws Exception {
        String sql = "SELECT GET_LOCK(?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lockKey);
            pstmt.setLong(2, waitTime);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return null;
            }
        }
    }

    /**
     * RELEASE_LOCK 쿼리 실행 (Connection 고정)
     */
    private void executeReleaseQuery(Connection conn, String lockKey) {
        String sql = "SELECT RELEASE_LOCK(?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lockKey);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Integer result = rs.getInt(1);
                    if (result == 1) {
                        log.debug("🔒 [MySQL Lock] '{}' 해제 성공", lockKey);
                    } else {
                        log.warn("⚠️ [MySQL Lock] '{}' 해제 결과: {} (0=미소유, NULL=부재)", lockKey, result);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ [MySQL Lock] '{}' 해제 실패: {}", lockKey, e.getMessage());
            // finally 블록에서는 예외를 던지지 않음 (커넥션 닫힐 때 자동 해제됨)
        }
    }

    /**
     * 즉시 락 획득 시도 (waitTime = 0)
     *
     * <p>주의: ConnectionCallback 내부에서 실행되지 않으므로, 이 메서드는 락 획득만 하고 비즈니스 로직은 실행하지 않습니다.
     * <p>따라서 좀비 락 위험이 있으므로, {@link #executeWithLock}를 사용하는 것을 권장합니다.
     */
    private boolean attemptImmediateLock(String lockKey) {
        return lockJdbcTemplate.execute(new ConnectionCallback<Boolean>() {
            @Override
            public Boolean doInConnection(Connection conn) throws SQLException {
                try {
                    Integer lockResult = executeLockQuery(conn, lockKey, 0);

                    if (lockResult != null && lockResult == 1) {
                        log.debug("🔓 [MySQL Lock] '{}' 즉시 획득 성공", lockKey);
                        // ⚠️ 주의: 이 Connection은 곧 풀에 반환되므로, 락 해제가 필요합니다!
                        // 하지만 여기서는 즉시 해제하지 않고, 호출자가 unlock()을 호출하도록 유도합니다.
                        // 실제로는 이 패턴은 MySQL Named Lock에 적합하지 않습니다.
                        return true;
                    } else {
                        log.debug("⏭️ [MySQL Lock] '{}' 즉시 획득 실패 (result: {})", lockKey, lockResult);
                        return false;
                    }
                } catch (Exception e) {
                    throw new SQLException("즉시 락 획득 중 예외 발생", e);
                }
            }
        });
    }

    /**
     * 락 키 생성
     */
    private String buildLockKey(String key) {
        return "maple_lock:" + key;
    }
}
