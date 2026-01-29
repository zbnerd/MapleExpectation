package maple.expectation.service.v2.auth;

import maple.expectation.controller.dto.auth.LoginRequest;
import maple.expectation.controller.dto.auth.LoginResponse;
import maple.expectation.controller.dto.auth.TokenResponse;
import maple.expectation.domain.RefreshToken;
import maple.expectation.domain.Session;
import maple.expectation.external.NexonAuthClient;
import maple.expectation.external.dto.v2.CharacterListResponse;
import maple.expectation.global.error.exception.auth.CharacterNotOwnedException;
import maple.expectation.global.error.exception.auth.InvalidApiKeyException;
import maple.expectation.global.error.exception.auth.InvalidRefreshTokenException;
import maple.expectation.global.error.exception.auth.SessionNotFoundException;
import maple.expectation.global.error.exception.auth.TokenReusedException;
import maple.expectation.global.security.AccountIdGenerator;
import maple.expectation.global.security.FingerprintGenerator;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * AuthService 단위 테스트 (Issue #194, #279)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 * <p>Spring Context 없이 Mockito만으로 인증 서비스를 검증합니다.</p>
 *
 * <h4>테스트 범위</h4>
 * <ul>
 *   <li>로그인 성공 흐름 (Access Token + Refresh Token 발급)</li>
 *   <li>API Key 유효성 검증</li>
 *   <li>캐릭터 소유권 검증</li>
 *   <li>ADMIN 역할 판별</li>
 *   <li>로그아웃 처리 (세션 + Refresh Token 삭제)</li>
 *   <li>Token Refresh (Issue #279)</li>
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

    private NexonAuthClient nexonAuthClient;
    private FingerprintGenerator fingerprintGenerator;
    private AccountIdGenerator accountIdGenerator;
    private SessionService sessionService;
    private JwtTokenProvider jwtTokenProvider;
    private AdminService adminService;
    private RefreshTokenService refreshTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        nexonAuthClient = mock(NexonAuthClient.class);
        fingerprintGenerator = mock(FingerprintGenerator.class);
        accountIdGenerator = mock(AccountIdGenerator.class);
        sessionService = mock(SessionService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        adminService = mock(AdminService.class);
        refreshTokenService = mock(RefreshTokenService.class);

        // AccountIdGenerator mock: 항상 "test-account-id" 반환
        given(accountIdGenerator.generate(anySet())).willReturn("test-account-id");

        authService = new AuthService(
                nexonAuthClient,
                fingerprintGenerator,
                accountIdGenerator,
                sessionService,
                jwtTokenProvider,
                adminService,
                refreshTokenService
        );
    }

    @Nested
    @DisplayName("로그인")
    class LoginTest {

        @Test
        @DisplayName("로그인 성공 - USER 역할 + Refresh Token 발급")
        void shouldLoginSuccessfully_asUser() {
            // given
            LoginRequest request = new LoginRequest(API_KEY, USER_IGN);
            CharacterListResponse charList = createCharacterListResponse(USER_IGN, "ocid-123");
            Session session = Session.create(SESSION_ID, FINGERPRINT, USER_IGN, "test-account-id", API_KEY, Set.of("ocid-123"), "USER");
            RefreshToken refreshToken = createRefreshToken(SESSION_ID, FINGERPRINT);

            given(nexonAuthClient.getCharacterList(API_KEY)).willReturn(Optional.of(charList));
            given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
            given(adminService.isAdmin(FINGERPRINT)).willReturn(false);
            given(sessionService.createSession(eq(FINGERPRINT), eq(USER_IGN), anyString(), eq(API_KEY), eq(Set.of("ocid-123")), eq("USER")))
                    .willReturn(session);
            given(jwtTokenProvider.generateToken(SESSION_ID, FINGERPRINT, "USER"))
                    .willReturn(ACCESS_TOKEN);
            given(jwtTokenProvider.getExpirationSeconds()).willReturn(EXPIRATION_SECONDS);
            given(refreshTokenService.createRefreshToken(SESSION_ID, FINGERPRINT))
                    .willReturn(refreshToken);
            given(refreshTokenService.getExpirationSeconds()).willReturn(REFRESH_EXPIRATION_SECONDS);

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
            CharacterListResponse charList = createCharacterListResponse(USER_IGN, "ocid-123");
            Session session = Session.create(SESSION_ID, FINGERPRINT, USER_IGN, "test-account-id", API_KEY, Set.of("ocid-123"), "ADMIN");
            RefreshToken refreshToken = createRefreshToken(SESSION_ID, FINGERPRINT);

            given(nexonAuthClient.getCharacterList(API_KEY)).willReturn(Optional.of(charList));
            given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
            given(adminService.isAdmin(FINGERPRINT)).willReturn(true);
            given(sessionService.createSession(eq(FINGERPRINT), eq(USER_IGN), anyString(), eq(API_KEY), eq(Set.of("ocid-123")), eq("ADMIN")))
                    .willReturn(session);
            given(jwtTokenProvider.generateToken(SESSION_ID, FINGERPRINT, "ADMIN"))
                    .willReturn(ACCESS_TOKEN);
            given(jwtTokenProvider.getExpirationSeconds()).willReturn(EXPIRATION_SECONDS);
            given(refreshTokenService.createRefreshToken(SESSION_ID, FINGERPRINT))
                    .willReturn(refreshToken);
            given(refreshTokenService.getExpirationSeconds()).willReturn(REFRESH_EXPIRATION_SECONDS);

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
            CharacterListResponse charList = createCharacterListResponse("TestUser", "ocid-123"); // 대소문자 혼합
            Session session = Session.create(SESSION_ID, FINGERPRINT, USER_IGN, "test-account-id", API_KEY, Set.of("ocid-123"), "USER");
            RefreshToken refreshToken = createRefreshToken(SESSION_ID, FINGERPRINT);

            given(nexonAuthClient.getCharacterList(API_KEY)).willReturn(Optional.of(charList));
            given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
            given(adminService.isAdmin(FINGERPRINT)).willReturn(false);
            given(sessionService.createSession(eq(FINGERPRINT), anyString(), anyString(), eq(API_KEY), anySet(), eq("USER")))
                    .willReturn(session);
            given(jwtTokenProvider.generateToken(SESSION_ID, FINGERPRINT, "USER")).willReturn(ACCESS_TOKEN);
            given(jwtTokenProvider.getExpirationSeconds()).willReturn(EXPIRATION_SECONDS);
            given(refreshTokenService.createRefreshToken(SESSION_ID, FINGERPRINT))
                    .willReturn(refreshToken);
            given(refreshTokenService.getExpirationSeconds()).willReturn(REFRESH_EXPIRATION_SECONDS);

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
            CharacterListResponse charList = createMultiCharacterListResponse();
            Session session = Session.create(SESSION_ID, FINGERPRINT, USER_IGN, "test-account-id", API_KEY,
                    Set.of("ocid-1", "ocid-2", "ocid-3"), "USER");
            RefreshToken refreshToken = createRefreshToken(SESSION_ID, FINGERPRINT);

            given(nexonAuthClient.getCharacterList(API_KEY)).willReturn(Optional.of(charList));
            given(fingerprintGenerator.generate(API_KEY)).willReturn(FINGERPRINT);
            given(adminService.isAdmin(FINGERPRINT)).willReturn(false);
            given(sessionService.createSession(eq(FINGERPRINT), eq(USER_IGN), anyString(), eq(API_KEY), argThat(ocids ->
                    ocids.size() == 3 && ocids.contains("ocid-1")), eq("USER")))
                    .willReturn(session);
            given(jwtTokenProvider.generateToken(SESSION_ID, FINGERPRINT, "USER")).willReturn(ACCESS_TOKEN);
            given(jwtTokenProvider.getExpirationSeconds()).willReturn(EXPIRATION_SECONDS);
            given(refreshTokenService.createRefreshToken(SESSION_ID, FINGERPRINT))
                    .willReturn(refreshToken);
            given(refreshTokenService.getExpirationSeconds()).willReturn(REFRESH_EXPIRATION_SECONDS);

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
            given(nexonAuthClient.getCharacterList("invalid-key")).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(InvalidApiKeyException.class);
        }

        @Test
        @DisplayName("캐릭터 소유권 없음")
        void shouldThrowWhenCharacterNotOwned() {
            // given
            LoginRequest request = new LoginRequest(API_KEY, "NotOwnedCharacter");
            CharacterListResponse charList = createCharacterListResponse("DifferentUser", "ocid-123");

            given(nexonAuthClient.getCharacterList(API_KEY)).willReturn(Optional.of(charList));

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
            verify(refreshTokenService).deleteBySessionId(sessionId);
            verify(sessionService).deleteSession(sessionId);
        }
    }

    @Nested
    @DisplayName("토큰 갱신 (Issue #279)")
    class RefreshTest {

        @Test
        @DisplayName("토큰 갱신 성공 - 새 Access Token + Refresh Token 발급")
        void shouldRefreshTokenSuccessfully() {
            // given
            RefreshToken oldToken = createRefreshToken(SESSION_ID, FINGERPRINT);
            RefreshToken newToken = createNewRefreshToken(SESSION_ID, FINGERPRINT);
            Session session = Session.create(SESSION_ID, FINGERPRINT, USER_IGN, "test-account-id", API_KEY, Set.of("ocid-123"), "USER");

            given(refreshTokenService.rotateRefreshToken(REFRESH_TOKEN_ID)).willReturn(newToken);
            given(sessionService.getSession(SESSION_ID)).willReturn(Optional.of(session));
            given(sessionService.refreshSession(SESSION_ID)).willReturn(true);
            given(jwtTokenProvider.generateToken(SESSION_ID, FINGERPRINT, "USER"))
                    .willReturn("new-access-token");
            given(jwtTokenProvider.getExpirationSeconds()).willReturn(EXPIRATION_SECONDS);
            given(refreshTokenService.getExpirationSeconds()).willReturn(REFRESH_EXPIRATION_SECONDS);

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
            given(refreshTokenService.rotateRefreshToken("invalid-token"))
                    .willThrow(new InvalidRefreshTokenException());

            // when & then
            assertThatThrownBy(() -> authService.refresh("invalid-token"))
                    .isInstanceOf(InvalidRefreshTokenException.class);
        }

        @Test
        @DisplayName("토큰 갱신 실패 - 이미 사용된 토큰 (탈취 감지)")
        void shouldThrowWhenTokenReused() {
            // given
            given(refreshTokenService.rotateRefreshToken(REFRESH_TOKEN_ID))
                    .willThrow(new TokenReusedException());

            // when & then
            assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_ID))
                    .isInstanceOf(TokenReusedException.class);
        }

        @Test
        @DisplayName("토큰 갱신 실패 - 세션 만료")
        void shouldThrowWhenSessionExpired() {
            // given
            RefreshToken newToken = createNewRefreshToken(SESSION_ID, FINGERPRINT);

            given(refreshTokenService.rotateRefreshToken(REFRESH_TOKEN_ID)).willReturn(newToken);
            given(sessionService.getSession(SESSION_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_ID))
                    .isInstanceOf(SessionNotFoundException.class);

            // 세션이 없으면 새로 발급된 Refresh Token도 정리
            verify(refreshTokenService).deleteBySessionId(SESSION_ID);
        }
    }

    // ==================== Helper Methods ====================

    private CharacterListResponse createCharacterListResponse(String characterName, String ocid) {
        CharacterListResponse.CharacterInfo charInfo =
                new CharacterListResponse.CharacterInfo(ocid, characterName, "Reboot", "Bishop", 300);
        CharacterListResponse.AccountInfo accountInfo =
                new CharacterListResponse.AccountInfo("account-1", List.of(charInfo));
        return new CharacterListResponse(List.of(accountInfo));
    }

    private CharacterListResponse createMultiCharacterListResponse() {
        List<CharacterListResponse.CharacterInfo> characters = List.of(
                new CharacterListResponse.CharacterInfo("ocid-1", USER_IGN, "Reboot", "Bishop", 300),
                new CharacterListResponse.CharacterInfo("ocid-2", "AltChar1", "Reboot", "Aran", 280),
                new CharacterListResponse.CharacterInfo("ocid-3", "AltChar2", "Scania", "Phantom", 260)
        );
        CharacterListResponse.AccountInfo accountInfo =
                new CharacterListResponse.AccountInfo("account-1", characters);
        return new CharacterListResponse(List.of(accountInfo));
    }

    private RefreshToken createRefreshToken(String sessionId, String fingerprint) {
        return new RefreshToken(
                REFRESH_TOKEN_ID,
                sessionId,
                fingerprint,
                FAMILY_ID,
                Instant.now().plusSeconds(REFRESH_EXPIRATION_SECONDS),
                false
        );
    }

    private RefreshToken createNewRefreshToken(String sessionId, String fingerprint) {
        return new RefreshToken(
                "new-refresh-token-id",
                sessionId,
                fingerprint,
                FAMILY_ID,
                Instant.now().plusSeconds(REFRESH_EXPIRATION_SECONDS),
                false
        );
    }
}
