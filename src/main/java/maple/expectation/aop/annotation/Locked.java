package maple.expectation.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Locked {
    /**
     * 락 식별자 (SpEL 지원: 예: #userIgn)
     */
    String key();

    /**
     * 락 획득 대기 시간 (기본값: 10초)
     * waitTime 동안 락 획득을 시도하며, 시간 초과 시 DistributedLockException 발생
     */
    long waitTime() default 10;

    /**
     * 락 점유 시간 (기본값: 20초)
     * leaseTime 후 자동으로 락이 해제됩니다 (데드락 방지)
     */
    long leaseTime() default 20;

    /**
     * waitTime 및 leaseTime의 시간 단위 (기본값: SECONDS)
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}