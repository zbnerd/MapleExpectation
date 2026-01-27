package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.auth.CharacterLikeService;
import maple.expectation.service.v2.auth.CharacterLikeService.LikeToggleResult;
import maple.expectation.service.v2.cache.LikeBufferStrategy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * 캐릭터 API 컨트롤러 V2
 *
 * <p>API 목록:
 * <ul>
 *   <li>GET /{userIgn}/equipment - 장비 조회 (Public)</li>
 *   <li>GET /{userIgn}/expectation - 기대값 계산 (Public)</li>
 *   <li>POST /{userIgn}/like - 좋아요 (인증 필요, Self-Like/중복 방지)</li>
 *   <li>GET /{userIgn}/like/status - 좋아요 여부 확인 (인증 필요)</li>
 * </ul>
 * </p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/characters")
public class GameCharacterControllerV2 {

    private final EquipmentService equipmentService;
    private final CharacterLikeService characterLikeService;
    private final GameCharacterService gameCharacterService;
    private final LikeBufferStrategy likeBufferStrategy;

    /**
     * 캐릭터 장비 조회 (Public)
     *
     * <p>Issue #118: 비동기 파이프라인 전환 - 톰캣 스레드 즉시 반환</p>
     */
    @GetMapping("/{userIgn}/equipment")
    public CompletableFuture<ResponseEntity<EquipmentResponse>> getCharacterEquipment(
            @PathVariable String userIgn) {
        return equipmentService.getEquipmentByUserIgnAsync(userIgn)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 기대 비용 시뮬레이션 (Public)
     *
     * <p>Issue #118: 비동기 파이프라인 전환 - calculateTotalExpectationAsync (모던 캐싱 버전) 사용</p>
     */
    @GetMapping("/{userIgn}/expectation")
    public CompletableFuture<ResponseEntity<TotalExpectationResponse>> calculateTotalCost(
            @PathVariable String userIgn) {
        return equipmentService.calculateTotalExpectationAsync(userIgn)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 좋아요 토글 API (인증 필요)
     *
     * <p>동작:
     * <ul>
     *   <li>좋아요 안 한 상태 → 좋아요 추가</li>
     *   <li>좋아요 한 상태 → 좋아요 취소</li>
     * </ul>
     * </p>
     *
     * <p>제한사항:
     * <ul>
     *   <li>Self-Like 불가 (403)</li>
     * </ul>
     * </p>
     *
     * @param userIgn 대상 캐릭터 닉네임
     * @param user    인증된 사용자
     * @return 토글 결과 (liked: 현재 좋아요 상태, likeCount: 좋아요 수)
     */
    @PostMapping("/{userIgn}/like")
    public ResponseEntity<ApiResponse<LikeToggleResponse>> toggleLike(
            @PathVariable String userIgn,
            @AuthenticationPrincipal AuthenticatedUser user) {

        LikeToggleResult result = characterLikeService.toggleLike(userIgn, user);
        // DB likeCount + 버퍼 delta (토글 직후 원자적으로 조회됨)
        Long dbCount = gameCharacterService.getCharacterIfExist(userIgn)
                .map(gc -> gc.getLikeCount())
                .orElse(0L);
        Long likeCount = Math.max(0, dbCount + result.bufferDelta());
        return ResponseEntity.ok(ApiResponse.success(new LikeToggleResponse(result.liked(), likeCount)));
    }

    /**
     * 좋아요 토글 응답 DTO
     */
    public record LikeToggleResponse(boolean liked, Long likeCount) {}

    /**
     * 좋아요 여부 확인 API (인증 필요)
     *
     * @param userIgn 대상 캐릭터 닉네임
     * @param user    인증된 사용자
     * @return 좋아요 여부
     */
    @GetMapping("/{userIgn}/like/status")
    public ResponseEntity<ApiResponse<LikeStatusResponse>> getLikeStatus(
            @PathVariable String userIgn,
            @AuthenticationPrincipal AuthenticatedUser user) {

        boolean hasLiked = characterLikeService.hasLiked(userIgn, user.fingerprint());
        Long likeCount = getLikeCountWithBuffer(userIgn);
        return ResponseEntity.ok(ApiResponse.success(new LikeStatusResponse(hasLiked, likeCount)));
    }

    /**
     * 좋아요 상태 응답 DTO
     */
    public record LikeStatusResponse(boolean liked, Long likeCount) {}

    /**
     * DB likeCount + 버퍼 delta를 합산하여 실시간 좋아요 수 반환
     */
    private Long getLikeCountWithBuffer(String userIgn) {
        Long dbCount = gameCharacterService.getCharacterIfExist(userIgn)
                .map(gc -> gc.getLikeCount())
                .orElse(0L);
        Long bufferDelta = likeBufferStrategy.get(userIgn);
        long delta = (bufferDelta != null) ? bufferDelta : 0L;
        return Math.max(0, dbCount + delta);
    }
}