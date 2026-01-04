package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import net.bytebuddy.implementation.bytecode.Throw;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * MySQL Named Lock 전략 (100% 평탄화 및 보일러플레이트 박멸 버전)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlNamedLockStrategy implements LockStrategy {

    @Qualifier("lockJdbcTemplate")
    private final JdbcTemplate lockJdbcTemplate;
    private final LogicExecutor executor;

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
        String lockKey = buildLockKey(key);
        TaskContext context = TaskContext.of("Lock", "MySqlExecute", key);

        // [패턴 6] 최상단에서 모든 예외를 도메인 예외로 세탁
        return executor.executeWithTranslation(
                () -> this.executeInSession(lockKey, waitTime, task, context),
                ExceptionTranslator.forLock(),
                context
        );
    }

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) {
        return executeWithLock(key, 10, 20, task);
    }

    /**
     * 🚀 평탄화의 핵심: 람다 중첩과 try-catch를 메서드 추출로 해결
     */
    private <T> T executeInSession(String lockKey, long waitTime, ThrowingSupplier<T> task, TaskContext context) {
        // 1. 명시적 캐스팅으로 람다 모호성 해결 (괄호 한 번만 열림)
        return lockJdbcTemplate.execute((ConnectionCallback<T>) conn ->
                this.runLogicWithPinnedSession(conn, lockKey, waitTime, task, context)
        );
    }

    /**
     * P0: 세션 고정 환경에서 로직 실행 (패턴 1 활용)
     * 이 메서드는 체크 예외를 던지지 않으므로 콜백 내부에서 안전하게 실행됩니다.
     */
    private <T> T runLogicWithPinnedSession(Connection conn, String lockKey, long waitTime, ThrowingSupplier<T> task, TaskContext context) {
        JdbcTemplate sessionJdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));

        // [패턴 1] try-finally 키워드 대신 executeWithFinally 사용
        return executor.executeWithFinally(
                () -> this.acquireAndExecute(sessionJdbc, lockKey, waitTime, task),
                () -> this.releaseLock(sessionJdbc, lockKey, context),
                context
        );
    }

    private <T> T acquireAndExecute(JdbcTemplate sessionJdbc, String lockKey, long waitTime, ThrowingSupplier<T> task) throws Throwable {
        if (!tryAcquire(sessionJdbc, lockKey, waitTime)) {
            throw new DistributedLockException("락 획득 타임아웃: " + lockKey);
        }
        log.info("🔓 [MySQL Lock] '{}' 획득 성공", lockKey);
        return task.get();
    }

    private boolean tryAcquire(JdbcTemplate sessionJdbc, String lockKey, long waitTime) {
        return Boolean.TRUE.equals(sessionJdbc.queryForObject(
                "SELECT GET_LOCK(?, ?)", Integer.class, lockKey, waitTime) == 1);
    }

    private void releaseLock(JdbcTemplate sessionJdbc, String lockKey, TaskContext context) {
        executor.executeVoid(
                () -> sessionJdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockKey),
                context
        );
        log.debug("🔒 [MySQL Lock] '{}' 해제 완료", lockKey);
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        String lockKey = buildLockKey(key);
        return executor.executeOrDefault(
                () -> lockJdbcTemplate.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, lockKey) == 1,
                false,
                TaskContext.of("Lock", "MySqlTryImmediate", key)
        );
    }

    @Override
    public void unlock(String key) {
        log.warn("⚠️ [MySQL Lock] unlock() 호출 생략 - 세션 기반 자동 해제 정책 적용 중");
    }

    private String buildLockKey(String key) {
        return "maple_lock:" + key;
    }
}