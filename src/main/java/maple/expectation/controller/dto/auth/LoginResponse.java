package maple.expectation.controller.dto.auth;

/**
 * 로그인 응답 DTO
 *
 * @param accessToken JWT 액세스 토큰
 * @param expiresIn   만료 시간 (초)
 * @param role        사용자 권한
 * @param fingerprint 계정 fingerprint (Admin 등록용)
 */
public record LoginResponse(
    String accessToken,
    long expiresIn,
    String role,
    String fingerprint
) {
    public static LoginResponse of(String accessToken, long expiresIn, String role, String fingerprint) {
        return new LoginResponse(accessToken, expiresIn, role, fingerprint);
    }
}
