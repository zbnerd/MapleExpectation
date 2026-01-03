package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL Named Lock 기반 Fallback 락 전략
 *
 * [사용 시점]
 * - Redis가 장애 상황일 때 Circuit Breaker에 의해 자동으로 전환됨
 * - ResilientLockStrategy에서 Tier 2 fallback으로 사용
 *
 * [MySQL Named Lock 특징]
 * - SELECT GET_LOCK(name, timeout): 락 획득 (1=성공, 0=타임아웃, NULL=에러)
 * - SELECT RELEASE_LOCK(name): 락 해제 (1=성공, 0=다른 스레드 소유, NULL=존재안함)
 * - 세션 기반: 커넥션이 닫히면 자동으로 락 해제
 * - 비재진입적(Non-reentrant): 동일 세션에서도 재획득 불가
 *
 * [중요 설계]
 * - 전용 커넥션 풀 사용: 메인 풀 고갈 방지
 * - waitTime 정확히 전달: ✅ 체크포인트 3번 준수
 * - finally 블록에서 항상 해제: 데드락 방지
 */
@Slf4j
@Component
@Profile("!test")  // 테스트 환경에서는 GuavaLockStrategy 사용
@RequiredArgsConstructor
public class MySqlNamedLockStrategy implements LockStrategy {

    @Qualifier("lockJdbcTemplate")
    private final JdbcTemplate lockJdbcTemplate;

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 10, 20, task);
    }

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        String lockName = "maple_lock:" + key;

        try {
            // 🔑 MySQL Named Lock 획득
            // ✅ 체크포인트 3: waitTime을 그대로 전달 (하드코딩 X)
            Integer lockResult = lockJdbcTemplate.queryForObject(
                "SELECT GET_LOCK(?, ?)",
                Integer.class,
                lockName,
                waitTime  // ✅ 어노테이션에서 넘어온 waitTime 사용
            );

            if (lockResult == null || lockResult != 1) {
                log.warn("⏭️ [MySQL Lock] '{}' acquisition failed (result: {})", lockName, lockResult);
                throw new DistributedLockException("MySQL lock acquisition timeout: " + key);
            }

            try {
                log.info("🔓 [MySQL Lock] '{}' acquired successfully (fallback mode)", lockName);
                return task.get();
            } finally {
                // 🔒 반드시 락 해제 (데드락 방지)
                releaseLock(lockName);
            }
        } catch (DistributedLockException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ [MySQL Lock] Unexpected error for key: {}", key, e);
            throw new DistributedLockException("MySQL lock operation failed: " + key, e);
        }
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        String lockName = "maple_lock:" + key;
        try {
            // MySQL Named Lock은 즉시 획득 시도 (waitTime = 0)
            Integer lockResult = lockJdbcTemplate.queryForObject(
                "SELECT GET_LOCK(?, 0)",
                Integer.class,
                lockName
            );

            if (lockResult != null && lockResult == 1) {
                log.debug("🔓 [MySQL Lock] '{}' 즉시 획득 성공.", lockName);
                return true;
            } else {
                log.debug("⏭️ [MySQL Lock] '{}' 즉시 획득 실패 (result: {}).", lockName, lockResult);
                return false;
            }
        } catch (Exception e) {
            log.error("❌ [MySQL Lock] '{}' 즉시 획득 중 오류: {}", lockName, e.getMessage());
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        String lockName = "maple_lock:" + key;
        releaseLock(lockName);
    }

    /**
     * MySQL Named Lock 해제
     *
     * @param lockName 해제할 락 이름
     */
    private void releaseLock(String lockName) {
        try {
            Integer releaseResult = lockJdbcTemplate.queryForObject(
                "SELECT RELEASE_LOCK(?)",
                Integer.class,
                lockName
            );

            if (releaseResult != null && releaseResult == 1) {
                log.debug("🔒 [MySQL Lock] '{}' released successfully", lockName);
            } else {
                log.warn("⚠️ [MySQL Lock] '{}' release returned: {} (0=not held, NULL=doesn't exist)",
                    lockName, releaseResult);
            }
        } catch (Exception e) {
            log.error("❌ [MySQL Lock] Failed to release '{}': {}", lockName, e.getMessage());
            // 해제 실패는 로그만 남기고 예외는 던지지 않음
            // (커넥션이 닫히면 자동으로 락 해제되므로)
        }
    }
}
