package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.aop.util.CustomSpelParser;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.lock.LockStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 분산 락 AOP
 *
 * <p>코드 평탄화를 적용하여 {@code throws Throwable}과 try-catch 보일러플레이트를 제거했습니다.
 *
 * <h3>Before (기존 코드)</h3>
 * <pre>{@code
 * public Object applyLock(...) throws Throwable {
 *     try {
 *         return lockStrategy.executeWithLock(key, waitSeconds, leaseSeconds, () -> {
 *             log.debug("🔑 [Locked Aspect] 락 획득 성공: {}", key);
 *             return joinPoint.proceed();
 *         });
 *     } catch (DistributedLockException e) {
 *         log.warn("⏭️ [Locked Timeout] {} - 락 획득 실패. 직접 조회를 시도합니다.", key);
 *         return joinPoint.proceed();
 *     } catch (Throwable e) {
 *         throw e;
 *     }
 * }
 * }</pre>
 *
 * <h3>After (평탄화 적용)</h3>
 * <pre>{@code
 * public Object applyLock(...) {
 *     return executor.executeWithRecovery(
 *         () -> this.executeLockProtectedTask(joinPoint, key, waitSeconds, leaseSeconds),
 *         e -> this.handleLockFailure(joinPoint, key, e),
 *         "lockAspect:" + key
 *     );
 * }
 *
 * private Object executeLockProtectedTask(...) throws Throwable {
 *     return lockStrategy.executeWithLock(key, wait, lease, this.createLockedTask(joinPoint, key));
 * }
 * }</pre>
 *
 * <h3>개선 효과</h3>
 * <ul>
 *   <li>throws Throwable 제거</li>
 *   <li>try-catch 블록 제거</li>
 *   <li>비즈니스 로직을 4개 메서드로 분리 (평탄화)</li>
 *   <li>메서드 참조 활용 (joinPoint::proceed, this::executeLockProtectedTask)</li>
 * </ul>
 *
 * @see LogicExecutor
 * @see LockStrategy
 * @since 1.0.0
 */
@Slf4j
@Aspect
@Order(0)
@Component
@RequiredArgsConstructor
public class LockAspect {

    private final LockStrategy lockStrategy;
    private final LogicExecutor executor;
    private final CustomSpelParser spelParser;

    /**
     * 분산 락 어드바이스 (코드 평탄화 적용)
     *
     * <p>throws Throwable 제거, try-catch 블록 제거, 메서드 참조 활용
     */
    @Around("@annotation(locked)")
    public Object applyLock(ProceedingJoinPoint joinPoint, Locked locked) {
        String key = getDynamicKey(joinPoint, locked.key());

        // 🎯 SSOT: 어노테이션에서 락 타이밍 정책 읽기
        long waitSeconds = locked.timeUnit().toSeconds(locked.waitTime());
        long leaseSeconds = locked.timeUnit().toSeconds(locked.leaseTime());

        return executor.executeWithRecovery(
            () -> this.executeLockProtectedTask(joinPoint, key, waitSeconds, leaseSeconds),
            e -> this.handleLockFailure(joinPoint, key, e),
            "lockAspect:" + key
        );
    }

    /**
     * 락으로 보호된 작업 실행 (평탄화: 별도 메서드로 분리)
     *
     * <p>1등이 넥슨 API에서 OCID를 가져와 DB에 저장할 시간을 충분히 벌어줍니다.
     */
    private Object executeLockProtectedTask(
        ProceedingJoinPoint joinPoint,
        String key,
        long waitSeconds,
        long leaseSeconds
    ) throws Throwable {
        return lockStrategy.executeWithLock(
            key,
            waitSeconds,
            leaseSeconds,
            this.createLockedTask(joinPoint, key)
        );
    }

    /**
     * 락 보호 작업 생성 (평탄화: 메서드 참조 활용)
     */
    private ThrowingSupplier<Object> createLockedTask(ProceedingJoinPoint joinPoint, String key) {
        return () -> {
            log.debug("🔑 [Locked Aspect] 락 획득 성공: {}", key);
            return joinPoint.proceed();
        };
    }

    /**
     * 락 획득 실패 시 복구 로직 (평탄화: 별도 메서드로 분리)
     *
     * <p>락은 못 잡았지만, 그 사이 1등이 DB에 캐릭터를 생성했을 확률이 매우 높습니다.
     * 에러를 던지는 대신 조회를 시도하여 유저에게 정상 응답을 줍니다.
     *
     * <h3>예외 처리 전략</h3>
     * <ul>
     *   <li>{@link DistributedLockException} → 락 획득 실패는 정상 흐름, 직접 조회 시도</li>
     *   <li>기타 예외 → 락 실행 중 터진 '진짜 장애', {@link InternalSystemException}으로 규격화</li>
     * </ul>
     *
     * @param joinPoint AOP ProceedingJoinPoint
     * @param key 락 키
     * @param e 발생한 예외
     * @return 작업 결과
     */
    private Object handleLockFailure(ProceedingJoinPoint joinPoint, String key, Throwable e) {
        if (e instanceof DistributedLockException) {
            log.warn("⏭️ [Locked Timeout] {} - 락 획득 실패. 직접 조회를 시도합니다.", key);
            return proceedWithoutLock(joinPoint);
        }

        // 락 획득 실패가 아닌, 실행 중 터진 예상치 못한 '진짜 장애'
        // 이 경우 "락 처리 중 에러"라는 구체적인 메시지와 함께 시스템 예외로 던짐
        throw new InternalSystemException("DistributedLockExecution:" + key, e);
    }

    /**
     * 락 없이 작업 진행 (평탄화: 메서드 참조 활용)
     */
    private Object proceedWithoutLock(ProceedingJoinPoint joinPoint) {
        return executor.execute(
            joinPoint::proceed,
            "proceedWithoutLock"
        );
    }

    /**
     * SpEL 표현식을 파싱하여 동적 락 키 생성
     *
     * <p>CustomSpelParser 유틸리티를 사용하여 Aspect에서 SpEL 파싱 로직을 제거했습니다.
     *
     * @param joinPoint AOP ProceedingJoinPoint
     * @param keyExpression SpEL 표현식
     * @return 파싱된 락 키
     */
    private String getDynamicKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        return spelParser.parse(joinPoint, keyExpression);
    }
}