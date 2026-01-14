package maple.expectation.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Redis에 저장되는 세션 정보를 담는 불변 Record
 *
 * <p>Redis Key: session:{sessionId}</p>
 *
 * @param sessionId      세션 식별자 (UUID)
 * @param fingerprint    API Key의 HMAC-SHA256 해시
 * @param apiKey         Nexon API Key (Redis에만 저장, JWT에는 절대 포함 금지!)
 * @param myOcids        이 계정이 소유한 캐릭터 OCID 목록 (Self-Like 방지용)
 * @param role           사용자 권한 (USER 또는 ADMIN)
 * @param createdAt      세션 생성 시간
 * @param lastAccessedAt 마지막 접근 시간 (Sliding Window TTL용)
 */
public record Session(
    String sessionId,
    String fingerprint,
    String apiKey,
    Set<String> myOcids,
    String role,
    Instant createdAt,
    Instant lastAccessedAt
) {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    /**
     * 새 세션 생성용 팩토리 메서드
     */
    public static Session create(
            String sessionId,
            String fingerprint,
            String apiKey,
            Set<String> myOcids,
            String role) {
        Instant now = Instant.now();
        return new Session(sessionId, fingerprint, apiKey, myOcids, role, now, now);
    }

    /**
     * 마지막 접근 시간을 갱신한 새 세션을 반환합니다.
     */
    public Session withUpdatedAccessTime() {
        return new Session(
            sessionId, fingerprint, apiKey, myOcids, role, createdAt, Instant.now()
        );
    }

    /**
     * 주어진 OCID가 이 세션 사용자의 캐릭터인지 확인합니다.
     *
     * @param ocid 확인할 OCID
     * @return 본인 캐릭터 여부
     */
    public boolean isMyCharacter(String ocid) {
        return myOcids != null && myOcids.contains(ocid);
    }

    /**
     * ADMIN 권한인지 확인합니다.
     */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    /**
     * API Key 마스킹된 문자열 반환
     *
     * <p><b>CLAUDE.md 섹션 19 준수:</b> AOP 로깅 시 API Key 평문 노출 방지</p>
     * <p><b>Purple Agent P1 FIX:</b> Record 기본 toString()은 모든 필드를 노출하므로 오버라이드 필수</p>
     */
    @Override
    public String toString() {
        return "Session[" +
                "sessionId=" + sessionId +
                ", fingerprint=" + fingerprint +
                ", apiKey=" + maskApiKey(apiKey) +
                ", myOcids=" + myOcids +
                ", role=" + role +
                ", createdAt=" + createdAt +
                ", lastAccessedAt=" + lastAccessedAt + "]";
    }

    private static String maskApiKey(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
