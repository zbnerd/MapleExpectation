package maple.expectation.infrastructure.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 💡 넥슨 API 전용 2층 캐시 전략 (DB + API) */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NexonDataCache {}
