package maple.expectation.domain

import java.time.Instant
import java.util.UUID

/**
 * Refresh Token 도메인 모델 (Issue #279)
 *
 * <p>Token Rotation 패턴 지원:
 *
 * <ul>
 *   <li>사용 시 새 Refresh Token 발급 + 기존 토큰 무효화
 *   <li>이미 사용된 토큰 재사용 시 → 탈취 감지 → Family 전체 무효화
 * </ul>
 *
 * <p>Redis 저장 구조:
 *
 * <ul>
 *   <li>Key: refresh:{refreshTokenId}
 *   <li>Family Index: refresh:family:{familyId} (Set)
 * </ul>
 *
 * @property refreshTokenId Refresh Token 식별자 (UUID)
 * @property sessionId 연결된 세션 ID
 * @property fingerprint 사용자 식별용 fingerprint
 * @property familyId Token Rotation 추적용 Family ID
 * @property expiresAt 만료 시간
 * @property used 사용 여부 (Rotation 감지용)
 */
data class RefreshToken(
    val refreshTokenId: String,
    val sessionId: String,
    val fingerprint: String,
    val familyId: String,
    val expiresAt: Instant,
    val used: Boolean
) {
    // Explicit Record-style accessors for production code compatibility
    // (Java Record pattern: direct property name as method)
    fun refreshTokenId(): String = refreshTokenId
    fun sessionId(): String = sessionId
    fun fingerprint(): String = fingerprint
    fun familyId(): String = familyId
    fun expiresAt(): Instant = expiresAt
    fun used(): Boolean = used

    // JavaBean-style getter for isExpired() (test compatibility)
    fun getExpired(): Boolean = isExpired()

    /**
     * 새 Refresh Token 생성 (최초 로그인 시)
     *
     * @param sessionId 연결할 세션 ID
     * @param fingerprint 사용자 fingerprint
     * @param familyId Token Family ID (최초 로그인 시 새로 생성)
     * @param expirationSeconds 만료 시간 (초)
     * @return 새 RefreshToken
     */
    companion object {
        @JvmStatic
        fun create(
            sessionId: String,
            fingerprint: String,
            familyId: String,
            expirationSeconds: Long
        ): RefreshToken = RefreshToken(
            UUID.randomUUID().toString(),
            sessionId,
            fingerprint,
            familyId,
            Instant.now().plusSeconds(expirationSeconds),
            false
        )
    }

    /**
     * 만료 여부 확인
     *
     * @return 만료되었으면 true
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)

    /**
     * 사용 처리된 새 토큰 반환 (불변 객체)
     *
     * @return used=true인 새 RefreshToken
     */
    fun markAsUsed(): RefreshToken = copy(used = true)
}
