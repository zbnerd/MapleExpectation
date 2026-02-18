package maple.expectation.service.v2.auth

/**
 * Token Pair 응답 (Issue #279)
 *
 * @property accessToken JWT Access Token
 * @property accessTokenExpiresIn Access Token 만료 시간 (초)
 * @property refreshTokenId Refresh Token 식별자
 * @property refreshTokenExpiresIn Refresh Token 만료 시간 (초)
 */
data class TokenPair(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshTokenId: String,
    val refreshTokenExpiresIn: Long
) {
    // Explicit Record-style accessors for production code compatibility
    fun accessToken(): String = accessToken
    fun accessTokenExpiresIn(): Long = accessTokenExpiresIn
    fun refreshTokenId(): String = refreshTokenId
    fun refreshTokenExpiresIn(): Long = refreshTokenExpiresIn
}
