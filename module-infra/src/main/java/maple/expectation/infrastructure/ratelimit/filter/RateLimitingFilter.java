package maple.expectation.infrastructure.ratelimit.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.ratelimit.ConsumeResult;
import maple.expectation.infrastructure.ratelimit.RateLimitContext;
import maple.expectation.infrastructure.ratelimit.RateLimitingFacade;
import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties;
import maple.expectation.infrastructure.security.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate Limiting 필터 (OncePerRequestFilter)
 *
 * <p>Filter Chain 위치: MDCFilter → RateLimitingFilter → JwtAuthenticationFilter
 *
 * <h4>처리 흐름</h4>
 *
 * <ol>
 *   <li>클라이언트 IP 추출 (X-Forwarded-For 우선)
 *   <li>SecurityContext에서 인증 정보 확인 (있으면 User 전략, 없으면 IP 전략)
 *   <li>Rate Limit 확인
 *   <li>초과 시 429 응답 + Retry-After 헤더
 * </ol>
 *
 * <p>CRITICAL (Spring Security 6.x Best Practice - Context7):
 *
 * <ul>
 *   <li>@Component 사용 금지 (CGLIB 프록시 → logger NPE)
 *   <li>SecurityConfig에서 @Bean으로 수동 등록
 *   <li>FilterRegistrationBean으로 서블릿 컨테이너 중복 등록 방지
 * </ul>
 *
 * @since Issue #152
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

  private final RateLimitingFacade rateLimitingFacade;
  private final RateLimitProperties properties;
  private final LogicExecutor executor;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // 1. Rate Limit 컨텍스트 생성
    RateLimitContext context = buildContext(request);

    // 2. Rate Limit 확인 (P0-2 FIX: Fail-Open on ANY exception)
    // CLAUDE.md 섹션 12: LogicExecutor로 전체 Rate Limit 체크 래핑
    ConsumeResult result =
        executor.executeOrDefault(
            () -> rateLimitingFacade.checkRateLimit(context),
            ConsumeResult.failOpen(), // 예외 발생 시 Fail-Open (가용성 > 보안)
            TaskContext.of("RateLimit", "Filter", maskIp(context.clientIp())));

    // 3. 결과에 따른 처리
    if (!result.allowed()) {
      handleRateLimitExceeded(response, result);
      return;
    }

    // 4. Rate Limit 관련 헤더 추가 (정상 응답)
    addRateLimitHeaders(response, result);

    // 5. 다음 필터 진행
    filterChain.doFilter(request, response);
  }

  /**
   * Rate Limit 컨텍스트 생성
   *
   * @param request HTTP 요청
   * @return RateLimitContext 인스턴스
   */
  private RateLimitContext buildContext(HttpServletRequest request) {
    String clientIp = extractClientIp(request);
    AuthenticatedUser user = extractAuthenticatedUser();
    String requestUri = request.getRequestURI();

    return RateLimitContext.of(clientIp, user, requestUri);
  }

  /**
   * 클라이언트 IP 추출 (IP 스푸핑 방지)
   *
   * <p>Purple Agent P1-1 FIX: trustedHeaders 순서대로 확인, 없으면 remoteAddr 사용
   *
   * @param request HTTP 요청
   * @return 클라이언트 IP 주소
   */
  private String extractClientIp(HttpServletRequest request) {
    List<String> trustedHeaders = properties.getTrustedHeaders();

    for (String header : trustedHeaders) {
      String headerValue = request.getHeader(header);
      if (headerValue != null && !headerValue.isBlank()) {
        // X-Forwarded-For는 콤마로 구분된 IP 목록 → 첫 번째 IP 사용
        String ip = headerValue.split(",")[0].trim();
        if (!ip.isBlank()) {
          return ip;
        }
      }
    }

    // 신뢰할 수 있는 헤더 없음 → 직접 연결 IP 사용
    return request.getRemoteAddr();
  }

  /**
   * SecurityContext에서 인증된 사용자 추출
   *
   * <p>JwtAuthenticationFilter가 이 필터보다 나중에 실행되므로, 이 필터에서는 SecurityContext가 비어있을 수 있음 (IP 전략 사용)
   *
   * @return AuthenticatedUser 또는 null
   */
  private AuthenticatedUser extractAuthenticatedUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }

    return null;
  }

  /**
   * Rate Limit 초과 시 429 응답 (Filter 레벨에서 직접 응답)
   *
   * <p>예외를 던지지 않고 직접 응답하여 불필요한 처리 방지
   *
   * @param response HTTP 응답
   * @param result ConsumeResult (거부 결과)
   */
  private void handleRateLimitExceeded(HttpServletResponse response, ConsumeResult result)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE + 16); // 429
    response.setContentType("application/json;charset=UTF-8");
    response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
    response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));

    String errorResponse =
        String.format(
            "{\"code\":\"R001\",\"message\":\"요청 한도를 초과했습니다. %d초 후 다시 시도해주세요.\"}",
            result.retryAfterSeconds());

    response.getWriter().write(errorResponse);

    log.warn("[RateLimit-Exceeded] Retry-After={}s", result.retryAfterSeconds());
  }

  /**
   * Rate Limit 관련 HTTP 헤더 추가
   *
   * @param response HTTP 응답
   * @param result ConsumeResult (허용 결과)
   */
  private void addRateLimitHeaders(HttpServletResponse response, ConsumeResult result) {
    // Fail-Open 상황이 아닌 경우에만 헤더 추가 (remainingTokens == -1 은 Fail-Open)
    if (result.remainingTokens() >= 0) {
      response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
    }
  }

  /**
   * IP 마스킹 (로깅용)
   *
   * <p>CLAUDE.md 섹션 19 준수: 민감 데이터 마스킹
   *
   * @param ip IP 주소
   * @return 마스킹된 IP (예: 192.168.***.***) 또는 "***"
   */
  private String maskIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return "null";
    }
    String[] parts = ip.split("\\.");
    if (parts.length != 4) {
      return "***";
    }
    return parts[0] + "." + parts[1] + ".***.***";
  }
}
