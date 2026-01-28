package maple.expectation.controller;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.auth.CharacterLikeService;
import maple.expectation.service.v2.auth.CharacterLikeService.LikeToggleResult;
import maple.expectation.service.v2.cache.LikeBufferStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * GameCharacterControllerV2 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 * <p>순수 단위 테스트로 Controller 메서드만 직접 테스트합니다.</p>
 *
 * <h4>테스트 범위</h4>
 * <ul>
 *   <li>GET /{userIgn}/equipment - 장비 조회</li>
 *   <li>GET /{userIgn}/expectation - 기대값 계산</li>
 *   <li>POST /{userIgn}/like - 좋아요 토글</li>
 *   <li>GET /{userIgn}/like/status - 좋아요 상태 확인</li>
 * </ul>
 */
@Tag("unit")
class GameCharacterControllerV2Test {

    private EquipmentService equipmentService;
    private CharacterLikeService characterLikeService;
    private GameCharacterService gameCharacterService;
    private LikeBufferStrategy likeBufferStrategy;
    private GameCharacterControllerV2 controller;

    @BeforeEach
    void setUp() {
        equipmentService = mock(EquipmentService.class);
        characterLikeService = mock(CharacterLikeService.class);
        gameCharacterService = mock(GameCharacterService.class);
        likeBufferStrategy = mock(LikeBufferStrategy.class);
        controller = new GameCharacterControllerV2(
                equipmentService,
                characterLikeService,
                gameCharacterService,
                likeBufferStrategy
        );
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
            given(equipmentService.getEquipmentByUserIgnAsync(userIgn))
                    .willReturn(delayedFuture);

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
            TotalExpectationResponse mockResponse = TotalExpectationResponse.builder()
                    .totalCost(1000000L)
                    .build();
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
            AuthenticatedUser user = new AuthenticatedUser(
                    "session-123", "fingerprint-abc", "api-key-test", Set.of(), "USER"
            );

            LikeToggleResult toggleResult = new LikeToggleResult(true, 5L);
            given(characterLikeService.toggleLike(eq(userIgn), any(AuthenticatedUser.class)))
                    .willReturn(toggleResult);

            GameCharacter mockCharacter = mock(GameCharacter.class);
            given(mockCharacter.getLikeCount()).willReturn(10L);
            given(gameCharacterService.getCharacterIfExist(userIgn))
                    .willReturn(Optional.of(mockCharacter));

            // when
            ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeToggleResponse>> response =
                    controller.toggleLike(userIgn, user);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().data().liked()).isTrue();
            assertThat(response.getBody().data().likeCount()).isEqualTo(15L); // 10 + 5
            verify(characterLikeService).toggleLike(userIgn, user);
        }

        @Test
        @DisplayName("좋아요 토글 성공 - 좋아요 취소")
        void whenToggleUnlike_shouldReturnUnlikeResult() {
            // given
            String userIgn = "TargetUser";
            AuthenticatedUser user = new AuthenticatedUser(
                    "session-456", "fingerprint-def", "api-key-test", Set.of(), "USER"
            );

            LikeToggleResult toggleResult = new LikeToggleResult(false, -1L);
            given(characterLikeService.toggleLike(eq(userIgn), any(AuthenticatedUser.class)))
                    .willReturn(toggleResult);

            GameCharacter mockCharacter = mock(GameCharacter.class);
            given(mockCharacter.getLikeCount()).willReturn(5L);
            given(gameCharacterService.getCharacterIfExist(userIgn))
                    .willReturn(Optional.of(mockCharacter));

            // when
            ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeToggleResponse>> response =
                    controller.toggleLike(userIgn, user);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().data().liked()).isFalse();
            assertThat(response.getBody().data().likeCount()).isEqualTo(4L); // 5 - 1
        }

        @Test
        @DisplayName("캐릭터 미존재 시 likeCount 0으로 계산")
        void whenCharacterNotExists_shouldReturnZeroBasedCount() {
            // given
            String userIgn = "NonExistentUser";
            AuthenticatedUser user = new AuthenticatedUser(
                    "session-789", "fingerprint-ghi", "api-key-test", Set.of(), "USER"
            );

            LikeToggleResult toggleResult = new LikeToggleResult(true, 1L);
            given(characterLikeService.toggleLike(eq(userIgn), any(AuthenticatedUser.class)))
                    .willReturn(toggleResult);
            given(gameCharacterService.getCharacterIfExist(userIgn))
                    .willReturn(Optional.empty());

            // when
            ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeToggleResponse>> response =
                    controller.toggleLike(userIgn, user);

            // then
            assertThat(response.getBody().data().likeCount()).isEqualTo(1L); // 0 + 1
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
            String fingerprint = "fingerprint-123";
            AuthenticatedUser user = new AuthenticatedUser(
                    "session-789", fingerprint, "api-key-test", Set.of(), "USER"
            );
            given(characterLikeService.hasLiked(userIgn, fingerprint)).willReturn(true);

            GameCharacter mockCharacter = mock(GameCharacter.class);
            given(mockCharacter.getLikeCount()).willReturn(10L);
            given(gameCharacterService.getCharacterIfExist(userIgn))
                    .willReturn(Optional.of(mockCharacter));
            given(likeBufferStrategy.get(userIgn)).willReturn(2L);

            // when
            ResponseEntity<ApiResponse<GameCharacterControllerV2.LikeStatusResponse>> response =
                    controller.getLikeStatus(userIgn, user);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().data().liked()).isTrue();
            assertThat(response.getBody().data().likeCount()).isEqualTo(12L); // 10 + 2
        }

        @Test
        @DisplayName("좋아요하지 않은 경우 liked=false 반환")
        void whenNotLiked_shouldReturnFalse() {
            // given
            String userIgn = "NotLikedTarget";
            String fingerprint = "fingerprint-456";
            AuthenticatedUser user = new AuthenticatedUser(
                    "session-abc", fingerprint, "api-key-test", Set.of(), "USER"
            );
            given(characterLikeService.hasLiked(userIgn, fingerprint)).willReturn(false);
            given(gameCharacterService.getCharacterIfExist(userIgn))
                    .willReturn(Optional.empty());
            given(likeBufferStrategy.get(userIgn)).willReturn(null);

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
