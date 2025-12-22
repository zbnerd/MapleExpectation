package maple.expectation.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Locked {
    /**
     * 락 식별자 (SpEL 지원: 예: #userIgn)
     */
    String key();
}