package maple.expectation.global.security;

import java.util.Set;

/**
 * 인증된 사용자 정보 DTO
 *
 * <p>SecurityContext에 저장되어 컨트롤러에서 접근 가능</p>
 *
 * @param sessionId   세션 ID
 * @param fingerprint API Key의 HMAC-SHA256 해시
 * @param apiKey      Nexon API Key (서비스 레이어에서만 사용)
 * @param myOcids     사용자가 소유한 캐릭터 OCID 목록
 * @param role        권한 (USER 또는 ADMIN)
 */
public record AuthenticatedUser(
    String sessionId,
    String fingerprint,
    String apiKey,
    Set<String> myOcids,
    String role
) {
    /**
     * 주어진 OCID가 이 사용자의 캐릭터인지 확인합니다.
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
        return "ADMIN".equals(role);
    }

    /**
     * API Key 마스킹된 문자열 반환
     *
     * <p><b>CLAUDE.md 섹션 19 준수:</b> AOP 로깅 시 API Key 평문 노출 방지</p>
     * <p><b>Purple Agent P1 FIX:</b> Record 기본 toString()은 모든 필드를 노출하므로 오버라이드 필수</p>
     */
    @Override
    public String toString() {
        return "AuthenticatedUser[" +
                "sessionId=" + sessionId +
                ", fingerprint=" + fingerprint +
                ", apiKey=" + maskApiKey(apiKey) +
                ", myOcids=" + myOcids +
                ", role=" + role + "]";
    }

    private static String maskApiKey(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
