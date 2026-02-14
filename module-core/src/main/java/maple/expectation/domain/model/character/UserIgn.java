package maple.expectation.domain.model.character;

import java.util.Objects;

/**
 * 유저 IGN (Value Object)
 *
 * <p>순수 도메인 모델 - JPA 의존 없음
 */
public record UserIgn(String value) {

  public UserIgn {
    Objects.requireNonNull(value, "UserIgn value cannot be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("UserIgn value cannot be blank");
    }
  }

  public static UserIgn of(String value) {
    return new UserIgn(value);
  }
}
