package maple.expectation.service.v2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Set;
import maple.expectation.controller.dto.auth.LoginRequest;
import maple.expectation.controller.dto.auth.LoginResponse;
import maple.expectation.controller.dto.auth.TokenResponse;
import maple.expectation.domain.RefreshToken;
import maple.expectation.domain.Session;
import maple.expectation.error.exception.auth.CharacterNotOwnedException;
import maple.expectation.error.exception.auth.InvalidApiKeyException;
import maple.expectation.error.exception.auth.InvalidRefreshTokenException;
import maple.expectation.error.exception.auth.SessionNotFoundException;
import maple.expectation.error.exception.auth.TokenReusedException;
import maple.expectation.infrastructure.security.AccountIdGenerator;
import maple.expectation.infrastructure.security.FingerprintGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AuthService 단위 테스트 (Issue #194, #279)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 인증 서비스를 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>로그인 성공 흐름 (Access Token + Refresh Token 발급)
 *   <li>API Key 유효성 검증
 *   <li>캐릭터 소유권 검증
 *   <li>ADMIN 역할 판별
 *   <li>로그아웃 처리 (세션 + Refresh Token 삭제)
 *   <li>Token Refresh (Issue #279)
 * </ul>
 */
@Tag("unit")
class AuthServiceTest {

  private static final String API_KEY = "test-api-key";
  private static final String USER_IGN = "TestUser";
  private static final String FINGERPRINT = "generated-fingerprint";
  private static final String SESSION_ID = "session-123";
  private static final String ACCESS_TOKEN = "jwt-access-token";
  private static final String REFRESH_TOKEN_ID = "refresh-token-id";
  private static final String FAMILY_ID = "family-id";
  private static final long EXPIRATION_SECONDS = 900L;
  private static final long REFRESH_EXPIRATION_SECONDS = 604800L;

  private ApiKeyValidator apiKeyValidator;
  private FingerprintGenerator fingerprintGenerator;
  private AccountIdGenerator accountIdGenerator;
  private SessionManager sessionManager;
  private TokenService tokenService;
  private AdminService adminService;
  private AuthService authService;

  @BeforeEach
  void setUp() {
    apiKeyValidator = mock(ApiKeyValidator.class);
    fingerprintGenerator = mock(FingerprintGenerator.class);
    accountIdGenerator = mock(AccountIdGenerator.class);
    sessionManager = mock(SessionManager.class);
    tokenService = mock(TokenService.class);
    adminService = mock(AdminService.class);

    // AccountIdGenerator mock: 항상 "test-account-id" 반환
    given(accountIdGenerator.generate(anySet())).willReturn("test-account-id");

    authService =
        new AuthService(
            apiKeyValidator,
            fingerprintGenerator,
            accountIdGenerator,
            sessionManager,
            tokenService,
            adminService);
  }

  @Nested
  @DisplayName("로그인")
  class LoginTest {

    @Test
    @DisplayName("로그인 성공 - USER 역할 + Refresh Token 발급")
    void shouldLoginSuccessfully_asUser() {
      // given
      LoginRequest request = new LoginRequest(API_KEY, USER_IGN);
      Session session =
          Session.create(
              SESSION_ID,
              FINGERPRINT,
              USER_IGN,
              "test-account-id",
              API_KEY,
              Set.of("ocid-123"),
              "USER");

      // ApiKeyValidator: API Key 검증 및 캐릭터 소유권 확인
      ApiKeyValidator.CharacterOwnershipValidationResult validationResult =
          mock(ApiKeyValidator.CharacterOwnershipValidationResult.class);
      given(validationResult.myOcids()).willReturn(Set.of("ocid-123"));
      given(apiKeyValidator.validateAndVerifyOwnership(API_KEY, USER_IGN))
          .willReturn(validationResult);

      given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
      given(adminService.isAdmin(FINGERPRINT)).willReturn(false);

      // SessionManager: 세션 생성
      given(
              sessionManager.createSession(
                  eq(FINGERPRINT),
                  eq(USER_IGN),
                  eq("test-account-id"),
                  eq(API_KEY),
                  eq(Set.of("ocid-123")),
                  eq("USER")))
          .willReturn(session);

      // TokenService: Token 쌍 생성 (Access + Refresh)
      TokenService.TokenPair tokenPair = mock(TokenService.TokenPair.class);
      given(tokenPair.accessToken()).willReturn(ACCESS_TOKEN);
      given(tokenPair.accessTokenExpiresIn()).willReturn(EXPIRATION_SECONDS);
      given(tokenPair.refreshTokenId()).willReturn(REFRESH_TOKEN_ID);
      given(tokenPair.refreshTokenExpiresIn()).willReturn(REFRESH_EXPIRATION_SECONDS);
      given(tokenService.createTokens(session)).willReturn(tokenPair);

      // when
      LoginResponse response = authService.login(request);

      // then
      assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
      assertThat(response.expiresIn()).isEqualTo(EXPIRATION_SECONDS);
      assertThat(response.role()).isEqualTo("USER");
      assertThat(response.fingerprint()).isEqualTo(FINGERPRINT);
      assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN_ID);
      assertThat(response.refreshExpiresIn()).isEqualTo(REFRESH_EXPIRATION_SECONDS);
    }

    @Test
    @DisplayName("로그인 성공 - ADMIN 역할")
    void shouldLoginSuccessfully_asAdmin() {
      // given
      LoginRequest request = new LoginRequest(API_KEY, USER_IGN);
      Session session =
          Session.create(
              SESSION_ID,
              FINGERPRINT,
              USER_IGN,
              "test-account-id",
              API_KEY,
              Set.of("ocid-123"),
              "ADMIN");

      ApiKeyValidator.CharacterOwnershipValidationResult validationResult =
          mock(ApiKeyValidator.CharacterOwnershipValidationResult.class);
      given(validationResult.myOcids()).willReturn(Set.of("ocid-123"));
      given(apiKeyValidator.validateAndVerifyOwnership(API_KEY, USER_IGN))
          .willReturn(validationResult);

      given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
      given(adminService.isAdmin(FINGERPRINT)).willReturn(true);

      given(
              sessionManager.createSession(
                  eq(FINGERPRINT),
                  eq(USER_IGN),
                  eq("test-account-id"),
                  eq(API_KEY),
                  eq(Set.of("ocid-123")),
                  eq("ADMIN")))
          .willReturn(session);

      TokenService.TokenPair tokenPair = mock(TokenService.TokenPair.class);
      given(tokenPair.accessToken()).willReturn(ACCESS_TOKEN);
      given(tokenPair.accessTokenExpiresIn()).willReturn(EXPIRATION_SECONDS);
      given(tokenPair.refreshTokenId()).willReturn(REFRESH_TOKEN_ID);
      given(tokenPair.refreshTokenExpiresIn()).willReturn(REFRESH_EXPIRATION_SECONDS);
      given(tokenService.createTokens(session)).willReturn(tokenPair);

      // when
      LoginResponse response = authService.login(request);

      // then
      assertThat(response.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("로그인 시 캐릭터명 대소문자 무시")
    void shouldIgnoreCaseForCharacterName() {
      // given
      LoginRequest request = new LoginRequest(API_KEY, "testuser"); // 소문자
      Session session =
          Session.create(
              SESSION_ID,
              FINGERPRINT,
              USER_IGN,
              "test-account-id",
              API_KEY,
              Set.of("ocid-123"),
              "USER");

      ApiKeyValidator.CharacterOwnershipValidationResult validationResult =
          mock(ApiKeyValidator.CharacterOwnershipValidationResult.class);
      given(validationResult.myOcids()).willReturn(Set.of("ocid-123"));
      // ApiKeyValidator 내부에서 대소문자 무시 처리
      given(apiKeyValidator.validateAndVerifyOwnership(API_KEY, "testuser"))
          .willReturn(validationResult);

      given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
      given(adminService.isAdmin(FINGERPRINT)).willReturn(false);

      given(
              sessionManager.createSession(
                  eq(FINGERPRINT),
                  anyString(), // "testuser" (lowercase) vs USER_IGN (uppercase)
                  eq("test-account-id"),
                  eq(API_KEY),
                  anySet(),
                  eq("USER")))
          .willReturn(session);

      TokenService.TokenPair tokenPair = mock(TokenService.TokenPair.class);
      given(tokenPair.accessToken()).willReturn(ACCESS_TOKEN);
      given(tokenPair.accessTokenExpiresIn()).willReturn(EXPIRATION_SECONDS);
      given(tokenPair.refreshTokenId()).willReturn(REFRESH_TOKEN_ID);
      given(tokenPair.refreshTokenExpiresIn()).willReturn(REFRESH_EXPIRATION_SECONDS);
      given(tokenService.createTokens(any(Session.class))).willReturn(tokenPair);

      // when
      LoginResponse response = authService.login(request);

      // then
      assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("다중 캐릭터 계정 로그인 - 모든 OCID 수집")
    void shouldCollectAllOcidsForMultiCharacterAccount() {
      // given
      LoginRequest request = new LoginRequest(API_KEY, USER_IGN);
      Set<String> allOcids = Set.of("ocid-1", "ocid-2", "ocid-3");
      Session session =
          Session.create(
              SESSION_ID, FINGERPRINT, USER_IGN, "test-account-id", API_KEY, allOcids, "USER");

      ApiKeyValidator.CharacterOwnershipValidationResult validationResult =
          mock(ApiKeyValidator.CharacterOwnershipValidationResult.class);
      given(validationResult.myOcids()).willReturn(allOcids);
      given(apiKeyValidator.validateAndVerifyOwnership(API_KEY, USER_IGN))
          .willReturn(validationResult);

      given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
      given(adminService.isAdmin(FINGERPRINT)).willReturn(false);

      given(
              sessionManager.createSession(
                  eq(FINGERPRINT),
                  eq(USER_IGN),
                  eq("test-account-id"),
                  eq(API_KEY),
                  argThat(ocids -> ocids.size() == 3 && ocids.contains("ocid-1")),
                  eq("USER")))
          .willReturn(session);

      TokenService.TokenPair tokenPair = mock(TokenService.TokenPair.class);
      given(tokenPair.accessToken()).willReturn(ACCESS_TOKEN);
      given(tokenService.createTokens(session)).willReturn(tokenPair);

      // when
      LoginResponse response = authService.login(request);

      // then
      assertThat(response.accessToken()).isNotNull();
    }
  }

  @Nested
  @DisplayName("로그인 실패")
  class LoginFailureTest {

    @Test
    @DisplayName("유효하지 않은 API Key")
    void shouldThrowWhenInvalidApiKey() {
      // given
      LoginRequest request = new LoginRequest("invalid-key", USER_IGN);
      given(apiKeyValidator.validateAndVerifyOwnership("invalid-key", USER_IGN))
          .willThrow(new InvalidApiKeyException());

      // when & then
      assertThatThrownBy(() -> authService.login(request))
          .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    @DisplayName("캐릭터 소유권 없음")
    void shouldThrowWhenCharacterNotOwned() {
      // given
      LoginRequest request = new LoginRequest(API_KEY, "NotOwnedCharacter");
      given(apiKeyValidator.validateAndVerifyOwnership(API_KEY, "NotOwnedCharacter"))
          .willThrow(new CharacterNotOwnedException("NotOwnedCharacter"));

      // when & then
      assertThatThrownBy(() -> authService.login(request))
          .isInstanceOf(CharacterNotOwnedException.class)
          .hasMessageContaining("NotOwnedCharacter");
    }
  }

  @Nested
  @DisplayName("로그아웃")
  class LogoutTest {

    @Test
    @DisplayName("로그아웃 성공 - 세션 + Refresh Token 삭제")
    void shouldLogoutSuccessfully() {
      // given
      String sessionId = "session-to-delete";

      // when
      authService.logout(sessionId);

      // then
      verify(sessionManager).deleteSession(sessionId);
    }
  }

  @Nested
  @DisplayName("토큰 갱신 (Issue #279)")
  class RefreshTest {

    TokenService.TokenPair tokenPair = mock(TokenService.TokenPair.class);

    @Test
    @DisplayName("토큰 갱신 성공 - 새 Access Token + Refresh Token 발급")
    void shouldRefreshTokenSuccessfully() {
      // given
      RefreshToken newToken = createNewRefreshToken(SESSION_ID, FINGERPRINT);
      Session session =
          Session.create(
              SESSION_ID,
              FINGERPRINT,
              USER_IGN,
              "test-account-id",
              API_KEY,
              Set.of("ocid-123"),
              "USER");

      given(sessionManager.getSession(SESSION_ID)).willReturn(session);

      given(tokenPair.accessToken()).willReturn("new-access-token");
      given(tokenPair.accessTokenExpiresIn()).willReturn(EXPIRATION_SECONDS);
      given(tokenPair.refreshTokenId()).willReturn("new-refresh-token-id");
      given(tokenPair.refreshTokenExpiresIn()).willReturn(REFRESH_EXPIRATION_SECONDS);
      given(tokenService.rotateTokens(REFRESH_TOKEN_ID)).willReturn(tokenPair);

      // when
      TokenResponse response = authService.refresh(REFRESH_TOKEN_ID);

      // then
      assertThat(response.accessToken()).isEqualTo("new-access-token");
      assertThat(response.accessExpiresIn()).isEqualTo(EXPIRATION_SECONDS);
      assertThat(response.refreshToken()).isEqualTo("new-refresh-token-id");
      assertThat(response.refreshExpiresIn()).isEqualTo(REFRESH_EXPIRATION_SECONDS);
    }

    @Test
    @DisplayName("토큰 갱신 실패 - 유효하지 않은 Refresh Token")
    void shouldThrowWhenInvalidRefreshToken() {
      // given
      given(tokenService.rotateTokens("invalid-token"))
          .willThrow(new InvalidRefreshTokenException());

      // when & then
      assertThatThrownBy(() -> authService.refresh("invalid-token"))
          .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("토큰 갱신 실패 - 이미 사용된 토큰 (탈취 감지)")
    void shouldThrowWhenTokenReused() {
      // given
      given(tokenService.rotateTokens(REFRESH_TOKEN_ID)).willThrow(new TokenReusedException());

      // when & then
      assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_ID))
          .isInstanceOf(TokenReusedException.class);
    }

    @Test
    @DisplayName("토큰 갱신 실패 - 세션 만료")
    void shouldThrowWhenSessionExpired() {
      // given
      // Note: Session validation happens inside TokenService.rotateTokens(), not in AuthService
      given(tokenService.rotateTokens(REFRESH_TOKEN_ID))
          .willThrow(new SessionNotFoundException());

      // when & then
      assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_ID))
          .isInstanceOf(SessionNotFoundException.class);
    }
  }

  // ==================== Helper Methods ====================

  private RefreshToken createRefreshToken(String sessionId, String fingerprint) {
    return new RefreshToken(
        REFRESH_TOKEN_ID,
        sessionId,
        fingerprint,
        FAMILY_ID,
        java.time.Instant.now().plusSeconds(REFRESH_EXPIRATION_SECONDS),
        false);
  }

  private RefreshToken createNewRefreshToken(String sessionId, String fingerprint) {
    return new RefreshToken(
        "new-refresh-token-id",
        sessionId,
        fingerprint,
        FAMILY_ID,
        java.time.Instant.now().plusSeconds(REFRESH_EXPIRATION_SECONDS),
        false);
  }
}
