package maple.expectation.domain

import java.time.Instant

/**
 * Redis에 저장되는 세션 정보를 담는 불변 Record
 *
 * <p>Redis Key: session:{sessionId}
 *
 * @property sessionId 세션 식별자 (UUID)
 * @property fingerprint API Key의 HMAC-SHA256 해시
 * @property userIgn 로그인 캐릭터명
 * @property accountId 넥슨 계정 식별자 (SHA-256 of sorted myOcids) - 좋아요 중복 판별 키
 * @property apiKey Nexon API Key (Redis에만 저장, JWT에는 절대 포함 금지!)
 * @property myOcids 이 계정이 소유한 캐릭터 OCID 목록 (Self-Like 방지용)
 * @property role 사용자 권한 (USER 또는 ADMIN)
 * @property createdAt 세션 생성 시간
 * @property lastAccessedAt 마지막 접근 시간 (Sliding Window TTL용)
 */
data class Session(
    val sessionId: String,
    val fingerprint: String,
    val userIgn: String,
    val accountId: String,
    val apiKey: String,
    val myOcids: Set<String>,
    val role: String,
    val createdAt: Instant,
    val lastAccessedAt: Instant
) {
    // Explicit Record-style accessors for production code compatibility
    fun sessionId(): String = sessionId
    fun fingerprint(): String = fingerprint
    fun userIgn(): String = userIgn
    fun accountId(): String = accountId
    fun apiKey(): String = apiKey
    fun myOcids(): Set<String> = myOcids
    fun role(): String = role
    fun createdAt(): Instant = createdAt
    fun lastAccessedAt(): Instant = lastAccessedAt

    /**
     * 주어진 OCID가 이 세션 사용자의 캐릭터인지 확인합니다.
     *
     * @param ocid 확인할 OCID
     * @return 본인 캐릭터 여부
     */
    fun isMyCharacter(ocid: String): Boolean = myOcids != null && myOcids.contains(ocid)

    /** ADMIN 권한인지 확인합니다. */
    fun isAdmin(): Boolean = ROLE_ADMIN == role

    /**
     * 마지막 접근 시간을 갱신한 새 세션을 반환합니다.
     */
    fun withUpdatedAccessTime(): Session = copy(lastAccessedAt = Instant.now())

    /**
     * API Key 마스킹된 문자열 반환
     *
     * <p><b>CLAUDE.md 섹션 19 준수:</b> AOP 로깅 시 API Key 평문 노출 방지
     *
     * <p><b>Purple Agent P1 FIX:</b> Record 기본 toString()은 모든 필드를 노출하므로 오버라이드 필수
     */
    override fun toString(): String = "Session[" +
        "sessionId=$sessionId, " +
        "fingerprint=$fingerprint, " +
        "userIgn=$userIgn, " +
        "accountId=$accountId, " +
        "apiKey=${maskApiKey(apiKey)}, " +
        "myOcids=$myOcids, " +
        "role=$role, " +
        "createdAt=$createdAt, " +
        "lastAccessedAt=$lastAccessedAt" +
        "]"

    private fun maskApiKey(key: String?): String {
        if (key == null || key.length < 8) {
            return "****"
        }
        return key.substring(0, 4) + "****" + key.substring(key.length - 4)
    }

    companion object {
        const val ROLE_USER: String = "USER"
        const val ROLE_ADMIN: String = "ADMIN"

        /**
         * 새 세션 생성용 팩토리 메서드
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            sessionId: String,
            fingerprint: String,
            userIgn: String,
            accountId: String,
            apiKey: String,
            myOcids: Set<String>,
            role: String
        ): Session {
            val now = Instant.now()
            return Session(sessionId, fingerprint, userIgn, accountId, apiKey, myOcids, role, now, now)
        }
    }
}
