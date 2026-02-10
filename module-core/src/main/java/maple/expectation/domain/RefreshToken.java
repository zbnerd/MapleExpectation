package maple.expectation.domain;

import java.time.Instant;
import java.util.UUID;

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
 * @param refreshTokenId Refresh Token 식별자 (UUID)
 * @param sessionId 연결된 세션 ID
 * @param fingerprint 사용자 식별용 fingerprint
 * @param familyId Token Rotation 추적용 Family ID
 * @param expiresAt 만료 시간
 * @param used 사용 여부 (Rotation 감지용)
 */
public record RefreshToken(
    String refreshTokenId,
    String sessionId,
    String fingerprint,
    String familyId,
    Instant expiresAt,
    boolean used) {
  /**
   * 새 Refresh Token 생성 (최초 로그인 시)
   *
   * @param sessionId 연결할 세션 ID
   * @param fingerprint 사용자 fingerprint
   * @param familyId Token Family ID (최초 로그인 시 새로 생성)
   * @param expirationSeconds 만료 시간 (초)
   * @return 새 RefreshToken
   */
  public static RefreshToken create(
      String sessionId, String fingerprint, String familyId, long expirationSeconds) {
    return new RefreshToken(
        UUID.randomUUID().toString(),
        sessionId,
        fingerprint,
        familyId,
        Instant.now().plusSeconds(expirationSeconds),
        false);
  }

  /**
   * 만료 여부 확인
   *
   * @return 만료되었으면 true
   */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isExpired() {
    return Instant.now().isAfter(expiresAt);
  }

  /**
   * 사용 처리된 새 토큰 반환 (불변 객체)
   *
   * @return used=true인 새 RefreshToken
   */
  public RefreshToken markAsUsed() {
    return new RefreshToken(refreshTokenId, sessionId, fingerprint, familyId, expiresAt, true);
  }
}
