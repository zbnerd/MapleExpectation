package maple.expectation.service.v2.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.controller.dto.auth.LoginRequest;
import maple.expectation.controller.dto.auth.LoginResponse;
import maple.expectation.controller.dto.auth.TokenResponse;
import maple.expectation.domain.Session;
import maple.expectation.infrastructure.security.AccountIdGenerator;
import maple.expectation.infrastructure.security.FingerprintGenerator;
import org.springframework.stereotype.Service;

/**
 * 인증 서비스 (Facade)
 *
 * <p>책임 (Single Responsibility Principle):
 *
 * <ul>
 *   <li>로그인 흐름 조율 (Orchestration)
 *   <li>로그아웃 처리
 *   <li>Token Refresh 처리
 * </ul>
 *
 * <p>위임된 책임 (Delegation):
 *
 * <ul>
 *   <li>API Key 검증: {@link ApiKeyValidator}
 *   <li>세션 관리: {@link SessionManager}
 *   <li>토큰 생성/갱신: {@link TokenService}
 * </ul>
 *
 * <p>로그인 흐름:
 *
 * <ol>
 *   <li>API Key 검증 및 캐릭터 소유권 확인 ({@link ApiKeyValidator})
 *   <li>Fingerprint 생성 (HMAC-SHA256)
 *   <li>Account ID 생성 (넥슨 계정 식별자)
 *   <li>ADMIN 여부 판별 (fingerprint allowlist)
 *   <li>세션 생성 ({@link SessionManager})
 *   <li>JWT + Refresh Token 발급 ({@link TokenService})
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final ApiKeyValidator apiKeyValidator;
  private final FingerprintGenerator fingerprintGenerator;
  private final AccountIdGenerator accountIdGenerator;
  private final SessionManager sessionManager;
  private final TokenService tokenService;
  private final AdminService adminService;

  /**
   * 로그인 처리
   *
   * @param request 로그인 요청 (apiKey, userIgn)
   * @return 로그인 응답 (accessToken, expiresIn, role, refreshToken)
   */
  public LoginResponse login(LoginRequest request) {
    String apiKey = request.apiKey();
    String userIgn = request.userIgn();

    // 1. API Key 검증 및 캐릭터 소유권 확인
    ApiKeyValidator.CharacterOwnershipValidationResult validation =
        apiKeyValidator.validateAndVerifyOwnership(apiKey, userIgn);

    // 2. Fingerprint 생성
    String fingerprint = fingerprintGenerator.generate(apiKey);

    // 3. Account ID 생성 (넥슨 계정 식별자 - 좋아요 중복 방지용)
    String accountId = accountIdGenerator.generate(validation.myOcids());

    // 4. ADMIN 여부 판별
    String role = adminService.isAdmin(fingerprint) ? Session.ROLE_ADMIN : Session.ROLE_USER;

    // 5. 세션 생성
    Session session =
        sessionManager.createSession(
            fingerprint, userIgn, accountId, apiKey, validation.myOcids(), role);

    // 6. JWT + Refresh Token 발급
    TokenPair tokens = tokenService.createTokens(session);

    // fingerprint는 로컬에서만 로깅 (운영환경 보안)
    logLoginSuccess(userIgn, role, fingerprint);

    return LoginResponse.of(
        tokens.accessToken(),
        tokens.accessTokenExpiresIn(),
        role,
        fingerprint,
        tokens.refreshTokenId(),
        tokens.refreshTokenExpiresIn());
  }

  private void logLoginSuccess(String userIgn, String role, String fingerprint) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Login successful: userIgn={}, role={}, fingerprint={}", userIgn, role, fingerprint);
    } else {
      log.info("Login successful: userIgn={}, role={}", userIgn, role);
    }
  }

  /**
   * 로그아웃 처리
   *
   * @param sessionId 세션 ID
   */
  public void logout(String sessionId) {
    sessionManager.deleteSession(sessionId);
    log.info("Logout successful: sessionId={}", sessionId);
  }

  /**
   * Token Refresh 처리
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
   * @return 새 TokenResponse (accessToken, refreshToken)
   */
  public TokenResponse refresh(String refreshTokenId) {
    TokenPair tokens = tokenService.rotateTokens(refreshTokenId);

    log.info("Token refreshed: refreshTokenId={}", maskRefreshTokenId(refreshTokenId));

    return TokenResponse.of(
        tokens.accessToken(),
        tokens.accessTokenExpiresIn(),
        tokens.refreshTokenId(),
        tokens.refreshTokenExpiresIn());
  }

  private String maskRefreshTokenId(String refreshTokenId) {
    if (refreshTokenId == null || refreshTokenId.length() < 8) {
      return "***";
    }
    return refreshTokenId.substring(0, 4)
        + "..."
        + refreshTokenId.substring(refreshTokenId.length() - 4);
  }
}
