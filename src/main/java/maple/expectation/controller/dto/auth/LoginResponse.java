package maple.expectation.controller.dto.auth;

/**
 * 로그인 응답 DTO (Issue #279: Refresh Token 추가)
 *
 * @param accessToken      JWT 액세스 토큰
 * @param expiresIn        Access Token 만료 시간 (초)
 * @param role             사용자 권한
 * @param fingerprint      계정 fingerprint (Admin 등록용)
 * @param refreshToken     Refresh Token ID (Issue #279)
 * @param refreshExpiresIn Refresh Token 만료 시간 (초) (Issue #279)
 */
public record LoginResponse(
    String accessToken,
    long expiresIn,
    String role,
    String fingerprint,
    String refreshToken,
    long refreshExpiresIn
) {
    /**
     * LoginResponse 생성 (Refresh Token 포함)
     */
    public static LoginResponse of(String accessToken, long expiresIn, String role,
                                   String fingerprint, String refreshToken, long refreshExpiresIn) {
        return new LoginResponse(accessToken, expiresIn, role, fingerprint,
                                 refreshToken, refreshExpiresIn);
    }

    /**
     * LoginResponse 생성 (하위 호환성 - Refresh Token 없음)
     *
     * @deprecated Use {@link #of(String, long, String, String, String, long)} instead
     */
    @Deprecated(forRemoval = true)
    public static LoginResponse of(String accessToken, long expiresIn, String role, String fingerprint) {
        return new LoginResponse(accessToken, expiresIn, role, fingerprint, null, 0);
    }
}
