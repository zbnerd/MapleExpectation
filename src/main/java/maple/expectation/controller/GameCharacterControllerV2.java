package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.response.ApiResponse;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.auth.CharacterLikeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 캐릭터 장비 조회 (Public)
     */
    @GetMapping("/{userIgn}/equipment")
    public ResponseEntity<EquipmentResponse> getCharacterEquipment(@PathVariable String userIgn) {
        return ResponseEntity.ok(equipmentService.getEquipmentByUserIgn(userIgn));
    }

    /**
     * 기대 비용 시뮬레이션 (Public)
     */
    @GetMapping("/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> calculateTotalCost(@PathVariable String userIgn) {
        return ResponseEntity.ok(equipmentService.calculateTotalExpectationLegacy(userIgn));
    }

    /**
     * 좋아요 API (인증 필요)
     *
     * <p>제한사항:
     * <ul>
     *   <li>Self-Like 불가 (403)</li>
     *   <li>중복 좋아요 불가 (409)</li>
     * </ul>
     * </p>
     *
     * @param userIgn 대상 캐릭터 닉네임
     * @param user    인증된 사용자
     */
    @PostMapping("/{userIgn}/like")
    public ResponseEntity<ApiResponse<Void>> likeCharacter(
            @PathVariable String userIgn,
            @AuthenticationPrincipal AuthenticatedUser user) {

        characterLikeService.likeCharacter(userIgn, user);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

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
        return ResponseEntity.ok(ApiResponse.success(new LikeStatusResponse(hasLiked)));
    }

    /**
     * 좋아요 상태 응답 DTO
     */
    public record LikeStatusResponse(boolean liked) {}
}