package maple.expectation.service.v2.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.RefreshToken;
import maple.expectation.domain.Session;
import maple.expectation.error.exception.auth.SessionNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.security.jwt.JwtTokenProvider;
import org.springframework.stereotype.Service;

/**
 * 토큰 관리 서비스
 *
 * <p>책임 (Single Responsibility Principle):
 *
 * <ul>
 *   <li>JWT Access Token 생성
 *   <li>Refresh Token 생성
 *   <li>Token Rotation (기존 토큰 무효화 + 새 토큰 발급)
 *   <li>세션 유효성 확인 및 TTL 갱신
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

  private final JwtTokenProvider jwtTokenProvider;
  private final RefreshTokenService refreshTokenService;
  private final SessionManager sessionManager;
  private final LogicExecutor executor;

  /**
   * Access Token과 Refresh Token을 생성합니다.
   *
   * @param session 세션
   * @return 토큰 쌍 (accessToken, refreshToken)
   */
  public TokenPair createTokens(Session session) {
    // 1. JWT Access Token 생성
    String accessToken =
        jwtTokenProvider.generateToken(session.sessionId(), session.fingerprint(), session.role());

    // 2. Refresh Token 생성
    RefreshToken refreshToken =
        refreshTokenService.createRefreshToken(session.sessionId(), session.fingerprint());

    log.debug(
        "Tokens created: sessionId={}, refreshTokenId={}",
        session.sessionId(),
        refreshToken.refreshTokenId());

    return new TokenPair(
        accessToken,
        jwtTokenProvider.getExpirationSeconds(),
        refreshToken.refreshTokenId(),
        refreshTokenService.getExpirationSeconds());
  }

  /**
   * Token Rotation을 수행합니다.
   *
   * <p>Token Rotation 패턴:
   *
   * <ol>
   *   <li>기존 Refresh Token 검증 및 무효화
   *   <li>연결된 세션 유효성 확인
   *   <li>새 Access Token + Refresh Token 발급
   *   <li>세션 TTL 갱신 (Sliding Window)
   * </ol>
   *
   * @param refreshTokenId 기존 Refresh Token ID
   * @return 새 토큰 쌍
   * @throws SessionNotFoundException 세션이 만료된 경우
   */
  public TokenPair rotateTokens(String refreshTokenId) {
    log.debug("Rotating tokens: refreshTokenId={}", maskRefreshTokenId(refreshTokenId));

    // 1. Token Rotation (기존 토큰 무효화 + 새 토큰 발급)
    RefreshToken newRefreshToken = refreshTokenService.rotateRefreshToken(refreshTokenId);

    // 2. 세션 유효성 확인 + Cleanup (LogicExecutor 패턴)
    Session session = getOrCleanupSession(newRefreshToken);

    // 3. 세션 TTL 갱신 (Sliding Window)
    sessionManager.refreshSessionTtl(session.sessionId());

    // 4. 새 Access Token 발급
    String newAccessToken =
        jwtTokenProvider.generateToken(session.sessionId(), session.fingerprint(), session.role());

    log.info(
        "Tokens rotated: sessionId={}, newRefreshTokenId={}",
        session.sessionId(),
        newRefreshToken.refreshTokenId());

    return new TokenPair(
        newAccessToken,
        jwtTokenProvider.getExpirationSeconds(),
        newRefreshToken.refreshTokenId(),
        refreshTokenService.getExpirationSeconds());
  }

  /**
   * 세션을 조회하거나, 세션이 만료된 경우 정리 후 예외를 전파합니다.
   *
   * <p>LogicExecutor.executeWithFinally 패턴:
   *
   * <ul>
   *   <li>세션 조회 실패 시 Refresh Token 정리 (finally 블록)
   *   <li>SessionNotFoundException 재전파
   * </ul>
   *
   * @param refreshToken 새로 발급된 Refresh Token
   * @return 세션
   * @throws SessionNotFoundException 세션이 만료된 경우
   */
  private Session getOrCleanupSession(RefreshToken refreshToken) {
    return executor.executeWithFinally(
        () -> sessionManager.getSession(refreshToken.sessionId()),
        // finally: 세션 조회 실패 시 Refresh Token 정리
        () -> refreshTokenService.deleteBySessionId(refreshToken.sessionId()),
        TaskContext.of(
            "TokenService", "GetOrCleanupSession", "sessionId=" + refreshToken.sessionId()));
  }

  /**
   * 토큰 쌍 (Access Token + Refresh Token)
   *
   * @param accessToken JWT Access Token
   * @param accessTokenExpiresIn Access Token 만료 시간 (초)
   * @param refreshTokenId Refresh Token ID
   * @param refreshTokenExpiresIn Refresh Token 만료 시간 (초)
   */
  public record TokenPair(
      String accessToken,
      long accessTokenExpiresIn,
      String refreshTokenId,
      long refreshTokenExpiresIn) {}

  private String maskRefreshTokenId(String refreshTokenId) {
    if (refreshTokenId == null || refreshTokenId.length() < 8) {
      return "***";
    }
    return refreshTokenId.substring(0, 4)
        + "..."
        + refreshTokenId.substring(refreshTokenId.length() - 4);
  }
}
