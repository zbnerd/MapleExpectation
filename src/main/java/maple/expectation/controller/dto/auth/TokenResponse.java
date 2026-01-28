package maple.expectation.controller.dto.auth;

/**
 * Token Refresh 응답 DTO (Issue #279)
 *
 * @param accessToken       새 Access Token (JWT)
 * @param accessExpiresIn   Access Token 만료 시간 (초)
 * @param refreshToken      새 Refresh Token ID
 * @param refreshExpiresIn  Refresh Token 만료 시간 (초)
 */
public record TokenResponse(
    String accessToken,
    long accessExpiresIn,
    String refreshToken,
    long refreshExpiresIn
) {
    public static TokenResponse of(String accessToken, long accessExpiresIn,
                                   String refreshToken, long refreshExpiresIn) {
        return new TokenResponse(accessToken, accessExpiresIn, refreshToken, refreshExpiresIn);
    }
}
