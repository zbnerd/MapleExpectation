package maple.expectation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.auth.CharacterLikeService;
import maple.expectation.service.v2.auth.CharacterLikeService.LikeToggleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * GameCharacterControllerV2 단위 테스트 (Issue #194, #285)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>순수 단위 테스트로 Controller 메서드만 직접 테스트합니다.
 *
 * <h4>Issue #285 리팩토링 반영</h4>
 *
 * <ul>
 *   <li>P0-4: Service에서 likeCount 반환 (Controller 비즈니스 로직 제거)
 *   <li>P0-8: GameCharacterService, LikeBufferStrategy 의존성 제거
 * </ul>
 */
@Tag("unit")
class GameCharacterControllerV2Test {

  private EquipmentService equipmentService;
  private CharacterLikeService characterLikeService;
  private GameCharacterControllerV2 controller;

  @BeforeEach
  void setUp() {
    equipmentService = mock(EquipmentService.class);
    characterLikeService = mock(CharacterLikeService.class);
    controller = new GameCharacterControllerV2(equipmentService, characterLikeService);
  }

  @Nested
  @DisplayName("장비 조회 getCharacterEquipment")
  class GetCharacterEquipmentTest {

    @Test
    @DisplayName("비동기 장비 조회 성공")
    void whenEquipmentExists_shouldReturnEquipmentResponse() throws Exception {
      // given
      String userIgn = "TestUser";
      EquipmentResponse mockResponse = new EquipmentResponse();
      given(equipmentService.getEquipmentByUserIgnAsync(userIgn))
          .willReturn(CompletableFuture.completedFuture(mockResponse));

      // when
      CompletableFuture<ResponseEntity<EquipmentResponse>> future =
          controller.getCharacterEquipment(userIgn);
      ResponseEntity<EquipmentResponse> response = future.join();

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      verify(equipmentService).getEquipmentByUserIgnAsync(userIgn);
    }

    @Test
    @DisplayName("비동기 장비 조회 시 톰캣 스레드 즉시 반환")
    void shouldReturnCompletableFutureForAsyncProcessing() {
      // given
      String userIgn = "AsyncUser";
      CompletableFuture<EquipmentResponse> delayedFuture = new CompletableFuture<>();
      given(equipmentService.getEquipmentByUserIgnAsync(userIgn)).willReturn(delayedFuture);

      // when
      CompletableFuture<ResponseEntity<EquipmentResponse>> result =
          controller.getCharacterEquipment(userIgn);

      // then - Future가 즉시 반환되어야 함 (완료되지 않은 상태)
      assertThat(result).isNotNull();
      assertThat(result.isDone()).isFalse();

      // cleanup - Complete the future to avoid hanging
      delayedFuture.complete(new EquipmentResponse());
    }
  }

  @Nested
  @DisplayName("기대값 계산 calculateTotalCost")
  class CalculateTotalCostTest {

    @Test
    @DisplayName("기대값 계산 성공")
    void whenCalculation_shouldReturnTotalExpectation() throws Exception {
      // given
      String userIgn = "CalcUser";
      TotalExpectationResponse mockResponse =
          TotalExpectationResponse.builder().totalCost(1000000L).build();
      given(equipmentService.calculateTotalExpectationAsync(userIgn))
          .willReturn(CompletableFuture.completedFuture(mockResponse));

      // when
      CompletableFuture<ResponseEntity<TotalExpectationResponse>> future =
          controller.calculateTotalCost(userIgn);
      ResponseEntity<TotalExpectationResponse> response = future.join();

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().getTotalCost()).isEqualTo(1000000L);
    }
  }

  @Nested
  @DisplayName("좋아요 토글 toggleLike")
  class ToggleLikeTest {

    @Test
    @DisplayName("좋아요 토글 성공 - 좋아요 추가")
    void whenToggleLike_shouldReturnLikeResult() {
      // given
      String userIgn = "TargetUser";
      AuthenticatedUser user =
          new AuthenticatedUser(
              "session-123",
              "fingerprint-abc",
              "TestLiker",
              "test-account-id",
              "api-key-test",
              Set.of(),
              "USER");

      // P0-4: Service에서 likeCount(15) 직접 반환
      LikeToggleResult toggleResult = new LikeToggleResult(true, 5L, 15L);
      given(characterLikeService.toggleLike(eq(userIgn), any(AuthenticatedUser.class)))
          .willReturn(toggleResult);

      // when
      ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeToggleResponse>> response =
          controller.toggleLike(userIgn, user);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().data().liked()).isTrue();
      assertThat(response.getBody().data().likeCount()).isEqualTo(15L);
      verify(characterLikeService).toggleLike(userIgn, user);
    }

    @Test
    @DisplayName("좋아요 토글 성공 - 좋아요 취소")
    void whenToggleUnlike_shouldReturnUnlikeResult() {
      // given
      String userIgn = "TargetUser";
      AuthenticatedUser user =
          new AuthenticatedUser(
              "session-456",
              "fingerprint-def",
              "TestLiker2",
              "test-account-id",
              "api-key-test",
              Set.of(),
              "USER");

      // P0-4: Service에서 likeCount(4) 직접 반환
      LikeToggleResult toggleResult = new LikeToggleResult(false, -1L, 4L);
      given(characterLikeService.toggleLike(eq(userIgn), any(AuthenticatedUser.class)))
          .willReturn(toggleResult);

      // when
      ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeToggleResponse>> response =
          controller.toggleLike(userIgn, user);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().data().liked()).isFalse();
      assertThat(response.getBody().data().likeCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("캐릭터 미존재 시에도 Service가 likeCount 계산")
    void whenCharacterNotExists_shouldReturnServiceCalculatedCount() {
      // given
      String userIgn = "NonExistentUser";
      AuthenticatedUser user =
          new AuthenticatedUser(
              "session-789",
              "fingerprint-ghi",
              "TestLiker3",
              "test-account-id",
              "api-key-test",
              Set.of(),
              "USER");

      // P0-4: Service에서 likeCount(1) 직접 반환 (0 + delta 1)
      LikeToggleResult toggleResult = new LikeToggleResult(true, 1L, 1L);
      given(characterLikeService.toggleLike(eq(userIgn), any(AuthenticatedUser.class)))
          .willReturn(toggleResult);

      // when
      ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeToggleResponse>> response =
          controller.toggleLike(userIgn, user);

      // then
      assertThat(response.getBody().data().likeCount()).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("좋아요 상태 확인 getLikeStatus")
  class GetLikeStatusTest {

    @Test
    @DisplayName("이미 좋아요한 경우 liked=true 반환")
    void whenAlreadyLiked_shouldReturnTrue() {
      // given
      String userIgn = "LikedTarget";
      String accountId = "test-account-id";
      AuthenticatedUser user =
          new AuthenticatedUser(
              "session-789",
              "fingerprint-123",
              "LikerChar",
              accountId,
              "api-key-test",
              Set.of(),
              "USER");
      given(characterLikeService.hasLiked(userIgn, accountId)).willReturn(true);
      // P0-8: Service에서 effectiveLikeCount 반환
      given(characterLikeService.getEffectiveLikeCount(userIgn)).willReturn(12L);

      // when
      ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeStatusResponse>> response =
          controller.getLikeStatus(userIgn, user);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().data().liked()).isTrue();
      assertThat(response.getBody().data().likeCount()).isEqualTo(12L);
    }

    @Test
    @DisplayName("좋아요하지 않은 경우 liked=false 반환")
    void whenNotLiked_shouldReturnFalse() {
      // given
      String userIgn = "NotLikedTarget";
      String accountId = "test-account-id-2";
      AuthenticatedUser user =
          new AuthenticatedUser(
              "session-abc",
              "fingerprint-456",
              "LikerChar2",
              accountId,
              "api-key-test",
              Set.of(),
              "USER");
      given(characterLikeService.hasLiked(userIgn, accountId)).willReturn(false);
      given(characterLikeService.getEffectiveLikeCount(userIgn)).willReturn(0L);

      // when
      ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeStatusResponse>> response =
          controller.getLikeStatus(userIgn, user);

      // then
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().data().liked()).isFalse();
      assertThat(response.getBody().data().likeCount()).isEqualTo(0L);
    }
  }
}
