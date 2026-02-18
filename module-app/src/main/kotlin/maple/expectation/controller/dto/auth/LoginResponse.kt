package maple.expectation.controller.dto.auth

/**
 * Login response DTO (Issue #279: Refresh Token 추가)
 *
 * @param accessToken JWT access token
 * @param expiresIn Access Token expiration time (seconds)
 * @param user role
 * @param fingerprint account fingerprint (for Admin registration)
 * @param refreshToken Refresh Token ID (Issue #279)
 * @param refreshExpiresIn Refresh Token expiration time (seconds) (Issue #279)
 */
data class LoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val role: String,
    val fingerprint: String,
    val refreshToken: String? = null,
    val refreshExpiresIn: Long = 0
) {
    companion object {
        /**
         * Create LoginResponse with Refresh Token
         */
        @JvmStatic
        fun of(
            accessToken: String,
            expiresIn: Long,
            role: String,
            fingerprint: String,
            refreshToken: String,
            refreshExpiresIn: Long
        ): LoginResponse = LoginResponse(
            accessToken, expiresIn, role, fingerprint, refreshToken, refreshExpiresIn
        )

        /**
         * Create LoginResponse without Refresh Token (legacy compatibility)
         *
         * @deprecated Use [of] with all parameters instead
         */
        @Deprecated("Use full constructor with refresh token", ReplaceWith("of(accessToken, expiresIn, role, fingerprint, null, 0)"))
        @JvmStatic
        fun of(
            accessToken: String,
            expiresIn: Long,
            role: String,
            fingerprint: String
        ): LoginResponse = LoginResponse(accessToken, expiresIn, role, fingerprint)
    }
}