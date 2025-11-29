package maple.expectation.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD) // 이 어노테이션은 메서드에만 붙일 수 있습니다.
@Retention(RetentionPolicy.RUNTIME) // 런타임 시점까지 어노테이션 정보를 유지합니다.
public @interface LogExecutionTime {
}
