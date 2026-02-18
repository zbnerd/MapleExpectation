package maple.expectation.controller.dto.auth

/**
 * Token Refresh 응답 DTO (Issue #279)
 *
 * @property accessToken 새 Access Token (JWT)
 * @property accessExpiresIn Access Token 만료 시간 (초)
 * @property refreshToken 새 Refresh Token ID
 * @property refreshExpiresIn Refresh Token 만료 시간 (초)
 */
data class TokenResponse(
    val accessToken: String?,
    val accessExpiresIn: Long?,
    val refreshToken: String?,
    val refreshExpiresIn: Long?
) {
    // Record-style accessors for production code compatibility
    fun accessToken(): String = accessToken ?: ""
    fun accessExpiresIn(): Long = accessExpiresIn ?: 0
    fun refreshToken(): String = refreshToken ?: ""
    fun refreshExpiresIn(): Long = refreshExpiresIn ?: 0

    // JavaBean-style getters for Java tests (handle null from mocks)
    @JvmName("getAccessTokenSafe")
    fun getAccessToken(): String = accessToken ?: ""

    @JvmName("getAccessExpiresInSafe")
    fun getAccessExpiresIn(): Long = accessExpiresIn ?: 0

    @JvmName("getRefreshTokenSafe")
    fun getRefreshToken(): String = refreshToken ?: ""

    @JvmName("getRefreshExpiresInSafe")
    fun getRefreshExpiresIn(): Long = refreshExpiresIn ?: 0

    companion object {
        @JvmStatic
        fun of(
            accessToken: String?,
            accessExpiresIn: Long?,
            refreshToken: String?,
            refreshExpiresIn: Long?
        ): TokenResponse = TokenResponse(accessToken, accessExpiresIn, refreshToken, refreshExpiresIn)
    }
}
