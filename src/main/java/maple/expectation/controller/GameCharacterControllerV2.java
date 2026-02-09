package maple.expectation.controller;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import maple.expectation.controller.util.AsyncResponseUtils;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.auth.CharacterLikeService;
import maple.expectation.service.v2.auth.CharacterLikeService.LikeToggleResult;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캐릭터 API 컨트롤러 V2
 *
 * <h3>Issue #285: P0-4/P0-6/P0-8 리팩토링</h3>
 *
 * <ul>
 *   <li>P0-4: Controller double-read race 제거 (Service에서 likeCount 반환)
 *   <li>P0-6: JOIN FETCH 제거 (불필요한 DB 호출 제거)
 *   <li>P0-8: LikeBufferStrategy 의존성 제거 (비즈니스 로직 Service 이동)
 * </ul>
 *
 * <p>API 목록:
 *
 * <ul>
 *   <li>GET /{userIgn}/equipment - 장비 조회 (Public)
 *   <li>GET /{userIgn}/expectation - 기대값 계산 (Public)
 *   <li>POST /{userIgn}/like - 좋아요 토글 (인증 필요)
 *   <li>GET /{userIgn}/like/status - 좋아요 여부 확인 (인증 필요)
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/characters")
public class GameCharacterControllerV2 {

  private final EquipmentService equipmentService;
  private final CharacterLikeService characterLikeService;

  /**
   * 캐릭터 장비 조회 (Public)
   *
   * <p>Issue #118: 비동기 파이프라인 전환 - 톰캣 스레드 즉시 반환
   */
  @GetMapping("/{userIgn}/equipment")
  public CompletableFuture<ResponseEntity<EquipmentResponse>> getCharacterEquipment(
      @PathVariable String userIgn) {
    return AsyncResponseUtils.ok(equipmentService.getEquipmentByUserIgnAsync(userIgn));
  }

  /**
   * 기대 비용 시뮬레이션 (Public)
   *
   * <p>Issue #118: 비동기 파이프라인 전환
   */
  @GetMapping("/{userIgn}/expectation")
  public CompletableFuture<ResponseEntity<TotalExpectationResponse>> calculateTotalCost(
      @PathVariable String userIgn) {
    return AsyncResponseUtils.ok(equipmentService.calculateTotalExpectationAsync(userIgn));
  }

  /**
   * 좋아요 토글 API (인증 필요)
   *
   * <h3>Issue #285 개선</h3>
   *
   * <ul>
   *   <li>Service에서 likeCount 직접 반환 (JOIN FETCH 제거)
   *   <li>Controller는 HTTP 관심사만 담당 (SOLID SRP)
   * </ul>
   *
   * @param userIgn 대상 캐릭터 닉네임
   * @param user 인증된 사용자
   * @return 토글 결과 (liked, likeCount)
   */
  @PostMapping("/{userIgn}/like")
  public ResponseEntity<ApiResponse<LikeToggleResponse>> toggleLike(
      @PathVariable String userIgn, @AuthenticationPrincipal AuthenticatedUser user) {

    LikeToggleResult result = characterLikeService.toggleLike(userIgn, user);
    return ResponseEntity.ok(
        ApiResponse.success(new LikeToggleResponse(result.liked(), result.likeCount())));
  }

  /** 좋아요 토글 응답 DTO */
  public record LikeToggleResponse(boolean liked, Long likeCount) {}

  /**
   * 좋아요 상태 확인 API (비인증 허용)
   *
   * <p>비인증: liked=false, likeCount만 반환 <br>
   * 인증: liked(실제 상태) + likeCount 반환
   *
   * @param userIgn 대상 캐릭터 닉네임
   * @param user 인증된 사용자 (nullable)
   * @return 좋아요 여부 + 좋아요 수
   */
  @GetMapping("/{userIgn}/like/status")
  public ResponseEntity<ApiResponse<LikeStatusResponse>> getLikeStatus(
      @PathVariable String userIgn, @AuthenticationPrincipal @Nullable AuthenticatedUser user) {

    boolean hasLiked = user != null && characterLikeService.hasLiked(userIgn, user.accountId());
    long likeCount = characterLikeService.getEffectiveLikeCount(userIgn);
    return ResponseEntity.ok(ApiResponse.success(new LikeStatusResponse(hasLiked, likeCount)));
  }

  /** 좋아요 상태 응답 DTO */
  public record LikeStatusResponse(boolean liked, Long likeCount) {}
}
