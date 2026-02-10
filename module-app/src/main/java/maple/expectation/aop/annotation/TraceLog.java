package maple.expectation.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE}) // 메서드와 클래스 모두 붙일 수 있음
@Retention(RetentionPolicy.RUNTIME)
public @interface TraceLog {}
