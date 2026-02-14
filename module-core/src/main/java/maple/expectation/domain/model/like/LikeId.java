package maple.expectation.domain.model.like;

import java.util.Objects;

/**
 * 좋아요 ID (Value Object)
 *
 * <p>순수 도메인 모델 - JPA 의존 없음
 */
public record LikeId(Long value) {

  public LikeId {
    Objects.requireNonNull(value, "LikeId value cannot be null");
    if (value < 0) {
      throw new IllegalArgumentException("LikeId value cannot be negative");
    }
  }
}
