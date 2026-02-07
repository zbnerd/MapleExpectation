package maple.expectation.domain.repository;

import java.util.Optional;
import maple.expectation.domain.RefreshToken;

/**
 * Redis 기반 Refresh Token 저장소 인터페이스
 *
 * <p>저장 구조:
 *
 * <ul>
 *   <li>Token: refresh:{refreshTokenId} → JSON (String)
 *   <li>Family Index: refresh:family:{familyId} → Set&lt;refreshTokenId&gt;
 *   <li>Session Index: refresh:session:{sessionId} → Set&lt;refreshTokenId&gt;
 * </ul>
 *
 * <p>TTL 정책:
 *
 * <ul>
 *   <li>Token TTL: 7일 (auth.refresh-token.expiration)
 *   <li>Family/Session Index TTL: 7일 (토큰과 동일)
 * </ul>
 *
 * <p>구현체:
 *
 * <ul>
 *   <li>{@link maple.expectation.repository.v2.RedisRefreshTokenRepositoryImpl} - Redisson 기반 구현
 * </ul>
 */
public interface RedisRefreshTokenRepository {

  /**
   * Refresh Token 저장
   *
   * @param token 저장할 Refresh Token
   */
  void save(RefreshToken token);

  /**
   * Refresh Token 조회
   *
   * @param refreshTokenId Refresh Token ID
   * @return RefreshToken (Optional)
   */
  Optional<RefreshToken> findById(String refreshTokenId);

  /**
   * Refresh Token 사용 처리 (Token Rotation)
   *
   * <p>기존 토큰의 used 필드를 true로 설정하여 재사용 감지 가능하게 함
   *
   * @param refreshTokenId Refresh Token ID
   */
  void markAsUsed(String refreshTokenId);

  /**
   * Family 전체 무효화 (탈취 감지 시)
   *
   * @param familyId Token Family ID
   */
  void deleteByFamilyId(String familyId);

  /**
   * 세션의 모든 Refresh Token 삭제 (로그아웃 시)
   *
   * @param sessionId 세션 ID
   */
  void deleteBySessionId(String sessionId);

  /**
   * 단일 Refresh Token 삭제
   *
   * @param refreshTokenId Refresh Token ID
   */
  void deleteById(String refreshTokenId);
}
