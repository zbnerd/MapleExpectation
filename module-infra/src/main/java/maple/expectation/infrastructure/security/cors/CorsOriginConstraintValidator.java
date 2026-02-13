package maple.expectation.infrastructure.security.cors;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link ValidCorsOrigin} 어노테이션의 검증기 구현
 *
 * <p>Issue #21: CORS 오리진 검증 강화
 *
 * <p>{@link CorsOriginValidator}를 위임하여 오리진 목록을 검증합니다.
 *
 * <p>CRITICAL: Spring Bean 주입이 필요하므로 {@code @Component}와 {@code @RequiredArgsConstructor}를 사용합니다.
 *
 * @see CorsOriginValidator
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorsOriginConstraintValidator
    implements ConstraintValidator<ValidCorsOrigin, List<String>> {

  private final CorsOriginValidator validator;

  @Override
  public boolean isValid(List<String> value, ConstraintValidatorContext context) {
    if (value == null || value.isEmpty()) {
      // @NotEmpty 어노테이션으로 null/empty 체크가 이미 되므로 여기서는 true 반환
      return true;
    }

    CorsOriginValidator.ValidationResult result = validator.validateOrigins(value);

    if (!result.isValid()) {
      // 커스텀 에러 메시지 설정
      context.disableDefaultConstraintViolation();
      String errorMessage = String.join(", ", result.errors());
      context
          .buildConstraintViolationWithTemplate("CORS 오리진 검증 실패: " + errorMessage)
          .addConstraintViolation();
      return false;
    }

    // 경고가 있어도 유효함 (로그는 StartupLogger에서 처리)
    return true;
  }
}
