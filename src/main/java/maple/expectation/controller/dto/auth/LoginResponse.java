package maple.expectation.controller.dto.auth;

/**
 * 로그인 응답 DTO
 *
 * @param accessToken JWT 액세스 토큰
 * @param expiresIn   만료 시간 (초)
 * @param role        사용자 권한
 */
public record LoginResponse(
    String accessToken,
    long expiresIn,
    String role
) {
    public static LoginResponse of(String accessToken, long expiresIn, String role) {
        return new LoginResponse(accessToken, expiresIn, role);
    }
}
