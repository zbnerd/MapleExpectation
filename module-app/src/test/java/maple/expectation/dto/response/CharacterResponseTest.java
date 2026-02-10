package maple.expectation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import maple.expectation.domain.v2.GameCharacter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** CharacterResponse DTO 테스트 (Issue #128, #194) */
class CharacterResponseTest {

  @Nested
  @DisplayName("팩토리 메서드 (from)")
  class FromTest {

    @Test
    @DisplayName("Entity에서 DTO로 정상 변환")
    void shouldConvertEntityToDto() {
      // given
      GameCharacter entity = new GameCharacter("TestUser", "ocid-12345");
      entity.like();
      entity.like();
      entity.like();

      // when
      CharacterResponse response = CharacterResponse.from(entity);

      // then
      assertThat(response.userIgn()).isEqualTo("TestUser");
      assertThat(response.ocid()).isEqualTo("ocid-12345");
      assertThat(response.likeCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("초기 상태 Entity 변환")
    void shouldConvertInitialEntity() {
      // given
      GameCharacter entity = new GameCharacter("NewUser", "new-ocid");

      // when
      CharacterResponse response = CharacterResponse.from(entity);

      // then
      assertThat(response.userIgn()).isEqualTo("NewUser");
      assertThat(response.ocid()).isEqualTo("new-ocid");
      assertThat(response.likeCount()).isZero();
    }
  }

  @Nested
  @DisplayName("Record 기본 기능")
  class RecordTest {

    @Test
    @DisplayName("equals/hashCode 동작 확인")
    void shouldHaveCorrectEqualsAndHashCode() {
      // given
      CharacterResponse response1 =
          new CharacterResponse("User", "ocid", 10L, "Scania", "Hero", null);
      CharacterResponse response2 =
          new CharacterResponse("User", "ocid", 10L, "Scania", "Hero", null);
      CharacterResponse response3 =
          new CharacterResponse("Other", "ocid", 10L, "Scania", "Hero", null);

      // then
      assertThat(response1).isEqualTo(response2);
      assertThat(response1.hashCode()).isEqualTo(response2.hashCode());
      assertThat(response1).isNotEqualTo(response3);
    }

    @Test
    @DisplayName("toString 동작 확인")
    void shouldHaveCorrectToString() {
      // given
      CharacterResponse response =
          new CharacterResponse("TestUser", "test-ocid", 5L, "Scania", "Hero", null);

      // then
      assertThat(response.toString()).contains("TestUser").contains("test-ocid").contains("5");
    }
  }
}
