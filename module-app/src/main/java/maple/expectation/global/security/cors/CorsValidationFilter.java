package maple.expectation.global.security.cors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CORS 오리진 런타임 검증 필터
 *
 * <p>Issue #21: CORS 오리진 검증 강화
 *
 * <h4>동작 방식</h4>
 *
 * <ul>
 *   <li>요청의 Origin 헤더를 검증하여 허용된 오리진인지 확인
 *   <li>허용되지 않은 오리진인 경우 403 응답
 *   <li>OPTIONS 요청(preflight)은 Spring Security CORS 처리에 위임
 * </ul>
 *
 * <h4>CRITICAL (Spring Security 6.x Best Practice - Context7)</h4>
 *
 * <ul>
 *   <li>@Component 사용 금지 (CGLIB 프록시 → logger NPE)
 *   <li>SecurityConfig에서 @Bean으로 수동 등록
 *   <li>FilterRegistrationBean으로 서블릿 컨테이너 중복 등록 방지
 * </ul>
 *
 * <h4>보안 메트릭</h4>
 *
 * <ul>
 *   <li>거부된 요청 수를 로그에 기록
 *   <li>거부된 오리진을 마스킹하여 로그 (민감 정보 보호)
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class CorsValidationFilter extends OncePerRequestFilter {

  private final CorsOriginValidator validator;
  private final LogicExecutor executor;
  private final List<String> allowedOrigins;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // OPTIONS 요청은 Spring Security CORS 처리에 위임 (preflight)
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }

    // Origin 헤더 검증
    String originHeader = request.getHeader(HttpHeaders.ORIGIN);

    // Origin 헤더가 없는 요청은 통과 (same-origin 요청)
    if (originHeader == null || originHeader.isBlank()) {
      filterChain.doFilter(request, response);
      return;
    }

    // 런타임 오리진 검증
    boolean isValid =
        executor.executeOrDefault(
            () -> validator.isValidRuntimeOrigin(originHeader, allowedOrigins),
            false,
            TaskContext.of("CorsValidation", "ValidateOrigin", "***"));

    if (!isValid) {
      handleInvalidOrigin(response, originHeader);
      return;
    }

    filterChain.doFilter(request, response);
  }

  /**
   * 유효하지 않은 오리진 처리
   *
   * @param response HTTP 응답
   * @param originHeader 거부된 오리진
   */
  private void handleInvalidOrigin(HttpServletResponse response, String originHeader)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json;charset=UTF-8");

    String maskedOrigin = maskOrigin(originHeader);
    log.warn("[CorsValidation-Rejected] Origin '{}' is not in allowed list", maskedOrigin);

    String errorResponse =
        String.format("{\"code\":\"CORS_FORBIDDEN\",\"message\":\"허용되지 않는 오리진입니다.\"}");

    response.getWriter().write(errorResponse);
  }

  /**
   * 오리진 마스킹 (로깅용)
   *
   * <p>CLAUDE.md 섹션 19 준수: 민감 데이터 마스킹
   *
   * @param origin 오리진
   * @return 마스킹된 오리진
   */
  private String maskOrigin(String origin) {
    if (origin == null || origin.isBlank()) {
      return "null";
    }

    // 프로토콜 제거
    String withoutProtocol = origin.replaceFirst("^https?://", "");

    // 도메인의 첫 부분만 노출, 나머지 마스킹
    int dotIndex = withoutProtocol.indexOf('.');
    if (dotIndex > 0) {
      String firstPart = withoutProtocol.substring(0, dotIndex);
      String remaining = withoutProtocol.substring(dotIndex);
      // 첫 2글자만 노출
      String maskedFirst =
          firstPart.length() > 2 ? firstPart.substring(0, 2) + "***" : firstPart + "***";
      return maskedFirst + remaining;
    }

    // 포트가 있는 경우
    int portIndex = withoutProtocol.indexOf(':');
    if (portIndex > 0) {
      String host = withoutProtocol.substring(0, portIndex);
      String port = withoutProtocol.substring(portIndex);
      return maskDomain(host) + port;
    }

    return maskDomain(withoutProtocol);
  }

  private String maskDomain(String domain) {
    if (domain.length() <= 4) {
      return "***";
    }
    return domain.substring(0, 2) + "***" + domain.substring(domain.length() - 2);
  }
}
