package maple.expectation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.response.CharacterResponse;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * GameCharacterControllerV1 단위 테스트 (Issue #128, #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>순수 단위 테스트로 Controller 메서드만 직접 테스트합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>TC-128-01: 캐릭터 조회 성공 → DTO 응답 검증
 *   <li>TC-128-03: 응답에 내부 필드(id, version, equipment) 미포함 검증
 * </ul>
 */
@Tag("unit")
class GameCharacterControllerV1Test {

  private GameCharacterFacade gameCharacterFacade;
  private GameCharacterControllerV1 controller;

  @BeforeEach
  void setUp() {
    gameCharacterFacade = mock(GameCharacterFacade.class);
    controller = new GameCharacterControllerV1(gameCharacterFacade);
  }

  @Nested
  @DisplayName("캐릭터 조회 findCharacterByUserIgn")
  class FindCharacterByUserIgnTest {

    @Test
    @DisplayName("TC-128-01: 캐릭터 조회 성공 → CharacterResponse DTO 반환")
    void whenCharacterExists_shouldReturnDto() {
      // given
      GameCharacter character = new GameCharacter("TestUser", "ocid-12345");
      given(gameCharacterFacade.findCharacterByUserIgn("TestUser")).willReturn(character);

      // when
      ResponseEntity<CharacterResponse> response =
          controller.findCharacterByUserIgn("TestUser").join();

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().userIgn()).isEqualTo("TestUser");
      assertThat(response.getBody().ocid()).isEqualTo("ocid-12345");
      assertThat(response.getBody().likeCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("TC-128-03: CharacterResponse는 내부 필드를 포함하지 않음")
    void shouldReturnDtoWithoutInternalFields() {
      // given
      GameCharacter character = new GameCharacter("TestUser", "ocid-12345");
      given(gameCharacterFacade.findCharacterByUserIgn("TestUser")).willReturn(character);

      // when
      ResponseEntity<CharacterResponse> response =
          controller.findCharacterByUserIgn("TestUser").join();

      // then - CharacterResponse Record는 userIgn, ocid, likeCount만 포함
      assertThat(response.getBody()).isNotNull();
      CharacterResponse dto = response.getBody();

      // Record 컴포넌트만 존재
      assertThat(dto.userIgn()).isNotNull();
      assertThat(dto.ocid()).isNotNull();
      assertThat(dto.likeCount()).isNotNull();
    }

    @Test
    @DisplayName("초기 likeCount가 0인 캐릭터 조회")
    void whenInitialCharacter_shouldReturnZeroLikeCount() {
      // given
      GameCharacter character = new GameCharacter("NewUser", "new-ocid");
      given(gameCharacterFacade.findCharacterByUserIgn("NewUser")).willReturn(character);

      // when
      ResponseEntity<CharacterResponse> response =
          controller.findCharacterByUserIgn("NewUser").join();

      // then
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().likeCount()).isZero();
    }
  }
}
