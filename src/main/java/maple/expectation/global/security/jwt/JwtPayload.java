package maple.expectation.global.security.jwt;

import java.time.Instant;

/**
 * JWT 페이로드 데이터를 담는 불변 Record
 *
 * <p>보안 주의사항: apiKey는 절대 포함하지 않습니다!
 *
 * @param sessionId 세션 식별자 (UUID)
 * @param fingerprint API Key의 HMAC-SHA256 해시
 * @param role 사용자 권한 (USER 또는 ADMIN)
 * @param issuedAt 토큰 발급 시간
 * @param expiration 토큰 만료 시간
 */
public record JwtPayload(
    String sessionId, String fingerprint, String role, Instant issuedAt, Instant expiration) {
  /**
   * 새 토큰 생성용 팩토리 메서드
   *
   * @param sessionId 세션 ID
   * @param fingerprint fingerprint
   * @param role 권한
   * @param ttlSeconds 유효 시간 (초)
   * @return JwtPayload
   */
  public static JwtPayload of(String sessionId, String fingerprint, String role, long ttlSeconds) {
    Instant now = Instant.now();
    return new JwtPayload(sessionId, fingerprint, role, now, now.plusSeconds(ttlSeconds));
  }

  /**
   * 토큰이 만료되었는지 확인합니다.
   *
   * @return 만료 여부
   */
  public boolean isExpired() {
    return Instant.now().isAfter(expiration);
  }
}
