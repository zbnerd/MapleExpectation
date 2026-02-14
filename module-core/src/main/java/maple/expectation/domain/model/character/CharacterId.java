package maple.expectation.domain.model.character;

import java.util.Objects;

/**
 * 캐릭터 식별자 (Value Object)
 *
 * <p>순수 도메인 모델 - JPA 의존 없음
 */
public record CharacterId(String value) {

  public CharacterId {
    Objects.requireNonNull(value, "CharacterId value cannot be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("CharacterId value cannot be blank");
    }
  }

  public static CharacterId of(String value) {
    return new CharacterId(value);
  }
}
