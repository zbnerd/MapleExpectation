package maple.expectation.global.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.Session;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.global.security.FingerprintGenerator;
import maple.expectation.global.security.jwt.JwtPayload;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import maple.expectation.service.v2.auth.SessionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * JWT 인증 필터 (OncePerRequestFilter)
 *
 * <p>인증 흐름:
 * <ol>
 *   <li>Authorization 헤더에서 Bearer 토큰 추출</li>
 *   <li>JWT 파싱 및 유효성 검증</li>
 *   <li>Redis 세션 조회 및 fingerprint 이중 검증</li>
 *   <li>SecurityContext에 인증 정보 저장</li>
 *   <li>세션 TTL 갱신 (Sliding Window)</li>
 * </ol>
 * </p>
 *
 * <p>보안 고려사항:
 * <ul>
 *   <li>JWT와 Redis 세션의 fingerprint 이중 검증 (Session Hijacking 방지)</li>
 *   <li>세션이 없으면 JWT가 유효해도 인증 실패</li>
 * </ul>
 * </p>
 *
 * <p>CRITICAL (Spring Security 6.x Best Practice):
 * <ul>
 *   <li>@Component 사용 금지 → CGLIB 프록시 생성 시 OncePerRequestFilter의 logger NPE 발생</li>
 *   <li>SecurityConfig에서 @Bean으로 수동 등록</li>
 *   <li>FilterRegistrationBean으로 서블릿 컨테이너 중복 등록 방지</li>
 * </ul>
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final SessionService sessionService;
    private final FingerprintGenerator fingerprintGenerator;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            authenticateWithToken(token);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * JWT 토큰으로 인증 처리 (Optional 체이닝 + Context7 SecurityContext 패턴)
     *
     * <p>CLAUDE.md 섹션 4 준수: Optional 체이닝으로 선언적 처리</p>
     * <p>Context7 Best Practice: SecurityContextHolder.createEmptyContext() 사용</p>
     */
    private void authenticateWithToken(String token) {
        jwtTokenProvider.parseToken(token)
            .flatMap(this::validateAndGetSession)
            .ifPresent(this::setSecurityContext);
    }

    /**
     * JWT Payload 검증 및 세션 조회
     */
    private Optional<Session> validateAndGetSession(JwtPayload payload) {
        String sessionId = payload.sessionId();
        String jwtFingerprint = payload.fingerprint();

        return sessionService.getSessionAndRefresh(sessionId)
            .filter(session -> validateFingerprint(session, jwtFingerprint, sessionId));
    }

    /**
     * Fingerprint 이중 검증 (JWT vs Redis vs HMAC)
     */
    private boolean validateFingerprint(Session session, String jwtFingerprint, String sessionId) {
        // JWT vs Redis fingerprint 비교
        if (!jwtFingerprint.equals(session.fingerprint())) {
            log.warn("Fingerprint mismatch: sessionId={}", sessionId);
            return false;
        }

        // API Key로 HMAC 재검증 (추가 보안)
        if (!fingerprintGenerator.verify(session.apiKey(), jwtFingerprint)) {
            log.warn("Fingerprint HMAC verification failed: sessionId={}", sessionId);
            return false;
        }

        return true;
    }

    /**
     * SecurityContext에 인증 정보 저장 (Context7 Best Practice)
     *
     * <p>중요: SecurityContextHolder.createEmptyContext()로 새 컨텍스트 생성
     * - 기존 컨텍스트 재사용 시 동시성 문제 가능
     * - Spring Security 6.x 공식 권장 패턴</p>
     */
    private void setSecurityContext(Session session) {
        AuthenticatedUser authenticatedUser = new AuthenticatedUser(
            session.sessionId(),
            session.fingerprint(),
            session.userIgn(),
            session.accountId(),
            session.apiKey(),
            session.myOcids(),
            session.role()
        );

        List<SimpleGrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("ROLE_" + session.role())
        );

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(authenticatedUser, null, authorities);

        // Context7 Best Practice: 빈 컨텍스트 생성 후 설정
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        log.debug("User authenticated: sessionId={}, role={}", session.sessionId(), session.role());
    }
}
