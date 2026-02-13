package maple.expectation.service.v2.auth;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.RefreshToken;
import maple.expectation.domain.repository.RedisRefreshTokenRepository;
import maple.expectation.error.exception.auth.InvalidRefreshTokenException;
import maple.expectation.error.exception.auth.RefreshTokenExpiredException;
import maple.expectation.error.exception.auth.TokenReusedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Refresh Token 서비스 (Issue #279)
 *
 * <p>Token Rotation 패턴 구현:
 *
 * <ol>
 *   <li>Refresh Token 사용 시 새 토큰 발급
 *   <li>기존 토큰 used=true 처리
 *   <li>이미 used=true인 토큰 재사용 시 → 탈취 감지 → Family 무효화
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private final RedisRefreshTokenRepository refreshTokenRepository;

  @Value("${auth.refresh-token.expiration}")
  private long refreshTokenExpirationSeconds;

  /**
   * 새 Refresh Token 생성 (최초 로그인 시)
   *
   * @param sessionId 연결할 세션 ID
   * @param fingerprint 사용자 fingerprint
   * @return 새 RefreshToken
   */
  public RefreshToken createRefreshToken(String sessionId, String fingerprint) {
    String familyId = UUID.randomUUID().toString();
    return createRefreshTokenWithFamily(sessionId, fingerprint, familyId);
  }

  /**
   * Token Rotation: 기존 토큰 무효화 + 새 토큰 발급
   *
   * <p>보안 검증:
   *
   * <ol>
   *   <li>토큰 존재 여부 확인
   *   <li>만료 여부 확인
   *   <li>재사용(탈취) 감지 (Atomic check-and-mark)
   * </ol>
   *
   * @param refreshTokenId 기존 Refresh Token ID
   * @return 새 RefreshToken
   * @throws InvalidRefreshTokenException 토큰이 존재하지 않는 경우
   * @throws RefreshTokenExpiredException 토큰이 만료된 경우
   * @throws TokenReusedException 이미 사용된 토큰인 경우 (탈취 의심)
   */
  public RefreshToken rotateRefreshToken(String refreshTokenId) {
    // 1. 토큰 조회
    RefreshToken oldToken =
        refreshTokenRepository
            .findById(refreshTokenId)
            .orElseThrow(InvalidRefreshTokenException::new);

    // 2. 만료 확인
    if (oldToken.isExpired()) {
      refreshTokenRepository.deleteById(refreshTokenId);
      throw new RefreshTokenExpiredException();
    }

    // 3. Atomic Check-and-Mark (P1 Fix: TOCTOU 방지)
    // Redis Lua script로 원자적으로 used 확인 및 마크 수행
    RefreshToken markedToken =
        refreshTokenRepository
            .checkAndMarkAsUsed(refreshTokenId)
            .orElseThrow(
                () -> {
                  log.warn(
                      "Token reuse detected! Possible token theft. " + "familyId={}, tokenId={}",
                      oldToken.familyId(),
                      refreshTokenId);
                  // Family 전체 무효화
                  invalidateFamily(oldToken.familyId());
                  return new TokenReusedException();
                });

    // 4. 같은 familyId로 새 토큰 발급
    RefreshToken newToken =
        createRefreshTokenWithFamily(
            markedToken.sessionId(), markedToken.fingerprint(), markedToken.familyId());

    log.debug(
        "Token rotated: oldTokenId={}, newTokenId={}, familyId={}",
        refreshTokenId,
        newToken.refreshTokenId(),
        markedToken.familyId());

    return newToken;
  }

  /**
   * Token Family 무효화 (탈취 감지 시)
   *
   * @param familyId Token Family ID
   */
  public void invalidateFamily(String familyId) {
    refreshTokenRepository.deleteByFamilyId(familyId);
    log.warn("Token family invalidated: familyId={}", familyId);
  }

  /**
   * 세션의 모든 Refresh Token 삭제 (로그아웃 시)
   *
   * @param sessionId 세션 ID
   */
  public void deleteBySessionId(String sessionId) {
    refreshTokenRepository.deleteBySessionId(sessionId);
  }

  /**
   * Refresh Token 만료 시간(초) 반환
   *
   * @return 만료 시간 (초)
   */
  public long getExpirationSeconds() {
    return refreshTokenExpirationSeconds;
  }

  /**
   * Refresh Token ID로 토큰 조회
   *
   * @param refreshTokenId Refresh Token ID
   * @return RefreshToken
   * @throws InvalidRefreshTokenException 토큰이 존재하지 않는 경우
   */
  public RefreshToken getRefreshToken(String refreshTokenId) {
    return refreshTokenRepository
        .findById(refreshTokenId)
        .orElseThrow(InvalidRefreshTokenException::new);
  }

  private RefreshToken createRefreshTokenWithFamily(
      String sessionId, String fingerprint, String familyId) {
    RefreshToken token =
        RefreshToken.create(sessionId, fingerprint, familyId, refreshTokenExpirationSeconds);

    refreshTokenRepository.save(token);

    log.debug(
        "RefreshToken created: tokenId={}, sessionId={}, familyId={}",
        token.refreshTokenId(),
        sessionId,
        familyId);

    return token;
  }
}
