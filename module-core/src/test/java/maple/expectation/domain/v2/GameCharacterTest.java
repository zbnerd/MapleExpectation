package maple.expectation.domain.v2;

import static org.assertj.core.api.Assertions.*;

import maple.expectation.global.error.exception.InvalidCharacterStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * GameCharacter 엔티티 테스트 (Issue #120, #194)
 *
 * <p>Rich Domain Model 비즈니스 로직 검증
 */
class GameCharacterTest {

  @Nested
  @DisplayName("캐릭터 생성")
  class ConstructorTest {

    @Test
    @DisplayName("정상 생성")
    void whenValidParams_shouldCreateCharacter() {
      // when
      GameCharacter character = new GameCharacter("TestUser", "ocid-12345");

      // then
      assertThat(character.getUserIgn()).isEqualTo("TestUser");
      assertThat(character.getOcid()).isEqualTo("ocid-12345");
      assertThat(character.getLikeCount()).isZero();
      assertThat(character.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("OCID가 null이면 InvalidCharacterStateException 발생")
    void whenOcidNull_shouldThrowException() {
      // when & then
      assertThatThrownBy(() -> new GameCharacter("TestUser", null))
          .isInstanceOf(InvalidCharacterStateException.class)
          .hasMessageContaining("OCID");
    }

    @Test
    @DisplayName("OCID가 빈 문자열이면 InvalidCharacterStateException 발생")
    void whenOcidEmpty_shouldThrowException() {
      // when & then
      assertThatThrownBy(() -> new GameCharacter("TestUser", ""))
          .isInstanceOf(InvalidCharacterStateException.class)
          .hasMessageContaining("OCID");
    }

    @Test
    @DisplayName("OCID가 공백만 있으면 InvalidCharacterStateException 발생")
    void whenOcidBlank_shouldThrowException() {
      // when & then
      assertThatThrownBy(() -> new GameCharacter("TestUser", "   "))
          .isInstanceOf(InvalidCharacterStateException.class)
          .hasMessageContaining("OCID");
    }
  }

  @Nested
  @DisplayName("좋아요 기능 (like)")
  class LikeTest {

    @Test
    @DisplayName("좋아요 시 카운트 증가")
    void whenLike_shouldIncrementCount() {
      // given
      GameCharacter character = new GameCharacter("TestUser", "ocid-12345");

      // when
      character.like();
      character.like();
      character.like();

      // then
      assertThat(character.getLikeCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("초기 카운트는 0")
    void initialLikeCount_shouldBeZero() {
      // when
      GameCharacter character = new GameCharacter("TestUser", "ocid-12345");

      // then
      assertThat(character.getLikeCount()).isZero();
    }
  }

  @Nested
  @DisplayName("활성 상태 확인 (isActive)")
  class IsActiveTest {

    @Test
    @DisplayName("방금 생성된 캐릭터는 활성 상태")
    void whenJustCreated_shouldBeActive() {
      // given
      GameCharacter character = new GameCharacter("TestUser", "ocid-12345");

      // when & then
      assertThat(character.isActive()).isTrue();
    }
  }

  @Nested
  @DisplayName("OCID 검증 (validateOcid)")
  class ValidateOcidTest {

    @Test
    @DisplayName("유효한 OCID는 예외 없음")
    void whenValidOcid_shouldNotThrow() {
      // given
      GameCharacter character = new GameCharacter("TestUser", "ocid-12345");

      // when & then
      assertThatCode(character::validateOcid).doesNotThrowAnyException();
    }
  }
}
