package maple.expectation.global.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import maple.expectation.domain.Session;
import maple.expectation.global.security.FingerprintGenerator;
import maple.expectation.global.security.jwt.JwtPayload;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import maple.expectation.service.v2.auth.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * JwtAuthenticationFilter 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 * <p>Spring Context 없이 Mockito만으로 JWT 인증 필터를 검증합니다.</p>
 *
 * <h4>테스트 범위</h4>
 * <ul>
 *   <li>토큰 추출 (Bearer 접두사 처리)</li>
 *   <li>인증 성공 시 SecurityContext 설정</li>
 *   <li>토큰 없음/유효하지 않음 시 인증 스킵</li>
 *   <li>Fingerprint 이중 검증</li>
 * </ul>
 */
@Tag("unit")
class JwtAuthenticationFilterTest {

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String SESSION_ID = "session-123";
    private static final String FINGERPRINT = "fp-abc";
    private static final String API_KEY = "test-api-key";

    private JwtTokenProvider jwtTokenProvider;
    private SessionService sessionService;
    private FingerprintGenerator fingerprintGenerator;
    private JwtAuthenticationFilter filter;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        sessionService = mock(SessionService.class);
        fingerprintGenerator = mock(FingerprintGenerator.class);

        filter = new JwtAuthenticationFilter(jwtTokenProvider, sessionService, fingerprintGenerator);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);

        // SecurityContext 초기화
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("토큰 추출")
    class TokenExtractionTest {

        @Test
        @DisplayName("Bearer 토큰 정상 추출")
        void shouldExtractBearerToken() throws Exception {
            // given
            given(request.getHeader("Authorization")).willReturn("Bearer " + VALID_TOKEN);
            given(jwtTokenProvider.parseToken(VALID_TOKEN)).willReturn(Optional.empty());

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            verify(jwtTokenProvider).parseToken(VALID_TOKEN);
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("Authorization 헤더 없음 시 인증 스킵")
        void shouldSkipWhenNoAuthHeader() throws Exception {
            // given
            given(request.getHeader("Authorization")).willReturn(null);

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            verify(jwtTokenProvider, never()).parseToken(anyString());
            verify(filterChain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("Bearer 접두사 없음 시 인증 스킵")
        void shouldSkipWhenNoBearerPrefix() throws Exception {
            // given
            given(request.getHeader("Authorization")).willReturn("Basic sometoken");

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            verify(jwtTokenProvider, never()).parseToken(anyString());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("빈 Authorization 헤더 시 인증 스킵")
        void shouldSkipWhenEmptyAuthHeader() throws Exception {
            // given
            given(request.getHeader("Authorization")).willReturn("");

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            verify(jwtTokenProvider, never()).parseToken(anyString());
            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("인증 처리")
    class AuthenticationTest {

        @Test
        @DisplayName("유효한 토큰으로 인증 성공")
        void shouldAuthenticateWithValidToken() throws Exception {
            // given
            JwtPayload payload = JwtPayload.of(SESSION_ID, FINGERPRINT, "USER", 3600L);
            Session session = Session.create(SESSION_ID, FINGERPRINT, "TestUser", "test-account-id", API_KEY, Set.of("ocid1"), "USER");

            given(request.getHeader("Authorization")).willReturn("Bearer " + VALID_TOKEN);
            given(jwtTokenProvider.parseToken(VALID_TOKEN)).willReturn(Optional.of(payload));
            given(sessionService.getSessionAndRefresh(SESSION_ID)).willReturn(Optional.of(session));
            given(fingerprintGenerator.verify(API_KEY, FINGERPRINT)).willReturn(true);

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("ADMIN 역할 인증 성공")
        void shouldAuthenticateAdminRole() throws Exception {
            // given
            JwtPayload payload = JwtPayload.of(SESSION_ID, FINGERPRINT, "ADMIN", 3600L);
            Session session = Session.create(SESSION_ID, FINGERPRINT, "TestUser", "test-account-id", API_KEY, Set.of("ocid1"), "ADMIN");

            given(request.getHeader("Authorization")).willReturn("Bearer " + VALID_TOKEN);
            given(jwtTokenProvider.parseToken(VALID_TOKEN)).willReturn(Optional.of(payload));
            given(sessionService.getSessionAndRefresh(SESSION_ID)).willReturn(Optional.of(session));
            given(fingerprintGenerator.verify(API_KEY, FINGERPRINT)).willReturn(true);

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("파싱 실패 시 인증 스킵")
        void shouldSkipWhenParsingFails() throws Exception {
            // given
            given(request.getHeader("Authorization")).willReturn("Bearer " + VALID_TOKEN);
            given(jwtTokenProvider.parseToken(VALID_TOKEN)).willReturn(Optional.empty());

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(sessionService, never()).getSessionAndRefresh(anyString());
        }

        @Test
        @DisplayName("세션 없음 시 인증 스킵")
        void shouldSkipWhenSessionNotFound() throws Exception {
            // given
            JwtPayload payload = JwtPayload.of(SESSION_ID, FINGERPRINT, "USER", 3600L);

            given(request.getHeader("Authorization")).willReturn("Bearer " + VALID_TOKEN);
            given(jwtTokenProvider.parseToken(VALID_TOKEN)).willReturn(Optional.of(payload));
            given(sessionService.getSessionAndRefresh(SESSION_ID)).willReturn(Optional.empty());

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Nested
    @DisplayName("Fingerprint 검증")
    class FingerprintValidationTest {

        @Test
        @DisplayName("JWT와 세션 fingerprint 불일치 시 인증 실패")
        void shouldFailWhenFingerprintMismatch() throws Exception {
            // given
            JwtPayload payload = JwtPayload.of(SESSION_ID, "wrong-fingerprint", "USER", 3600L);
            Session session = Session.create(SESSION_ID, FINGERPRINT, "TestUser", "test-account-id", API_KEY, Set.of("ocid1"), "USER");

            given(request.getHeader("Authorization")).willReturn("Bearer " + VALID_TOKEN);
            given(jwtTokenProvider.parseToken(VALID_TOKEN)).willReturn(Optional.of(payload));
            given(sessionService.getSessionAndRefresh(SESSION_ID)).willReturn(Optional.of(session));

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verify(fingerprintGenerator, never()).verify(anyString(), anyString());
        }

        @Test
        @DisplayName("HMAC 검증 실패 시 인증 실패")
        void shouldFailWhenHmacVerificationFails() throws Exception {
            // given
            JwtPayload payload = JwtPayload.of(SESSION_ID, FINGERPRINT, "USER", 3600L);
            Session session = Session.create(SESSION_ID, FINGERPRINT, "TestUser", "test-account-id", API_KEY, Set.of("ocid1"), "USER");

            given(request.getHeader("Authorization")).willReturn("Bearer " + VALID_TOKEN);
            given(jwtTokenProvider.parseToken(VALID_TOKEN)).willReturn(Optional.of(payload));
            given(sessionService.getSessionAndRefresh(SESSION_ID)).willReturn(Optional.of(session));
            given(fingerprintGenerator.verify(API_KEY, FINGERPRINT)).willReturn(false);

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Nested
    @DisplayName("필터 체인")
    class FilterChainTest {

        @Test
        @DisplayName("인증 성공/실패 관계없이 필터 체인 항상 호출")
        void shouldAlwaysContinueFilterChain() throws Exception {
            // given - 인증 실패 시나리오
            given(request.getHeader("Authorization")).willReturn("Bearer invalid");
            given(jwtTokenProvider.parseToken("invalid")).willReturn(Optional.empty());

            // when
            filter.doFilterInternal(request, response, filterChain);

            // then - 필터 체인은 항상 호출됨
            verify(filterChain).doFilter(request, response);
        }
    }
}
