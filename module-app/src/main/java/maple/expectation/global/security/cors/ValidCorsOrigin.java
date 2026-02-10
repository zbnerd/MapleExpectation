package maple.expectation.global.security.cors;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CORS 오리진 유효성 검증 어노테이션
 *
 * <p>Issue #21: CORS 오리진 검증 강화
 *
 * <p>{@link CorsOriginValidator}를 사용하여 오리진 목록의 유효성을 검증합니다.
 *
 * <h4>사용 예시</h4>
 *
 * <pre>
 * {@code
 * @ValidCorsOrigin
 * private List<String> allowedOrigins;
 * }
 * </pre>
 *
 * @see CorsOriginValidator
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CorsOriginConstraintValidator.class)
@Documented
public @interface ValidCorsOrigin {

  String message() default "유효하지 않은 CORS 오리진이 포함되어 있습니다.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
