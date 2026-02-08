package maple.expectation.global.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Path Variable 유효성 검사 필터
 *
 * <p>JWT 인증 필터 이전에 실행하여 빈 Path Variable을 조기에 차단합니다.
 *
 * <h3>문제 상황</h3>
 *
 * <ul>
 *   <li>GET /api/v4/characters//expectation (빈 userIgn)
 *   <li>Spring Security의 AntPathMatcher가 `//`를 제대로 매칭하지 못함
 *   <li>JWT 필터보다 먼저 실행하여 401 대신 400 반환
 * </ul>
 *
 * <h3>해결 방안</h3>
 *
 * <ul>
 *   <li>@Order(Ordered.HIGHEST_PRECEDENCE)로 가장 높은 우선순위 부여
 *   <li>URI 패턴 매칭으로 빈 path variable 감지
 *   <li>즉시 400 Bad Request 응답
 * </ul>
 *
 * <h3>검사 대상 경로</h3>
 *
 * <ul>
 *   <li>/api/v4/characters/{userIgn}/expectation
 *   <li>/api/v4/characters/{userIgn}/expectation/preset/{presetNo}
 *   <li>/api/v3/characters/{userIgn}/**
 *   <li>/api/v2/characters/{userIgn}/**
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PathVariableValidationFilter extends OncePerRequestFilter {

  private static final String ERROR_RESPONSE =
      """
      {"status":400,"code":"BAD_REQUEST","message":"유효하지 않은 요청 파라미터입니다."}
      """;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String requestUri = request.getRequestURI();

    // 빈 Path Variable 감지 (연속된 슬래시)
    if (requestUri.contains("//")) {
      log.warn("Invalid path variable detected: {}", requestUri);
      respondWithBadRequest(response);
      return;
    }

    // 특정 경로 패턴에 대한 빈 userIgn 검사
    if (isEmptyUserIgnInPath(requestUri)) {
      log.warn("Empty userIgn detected in path: {}", requestUri);
      respondWithBadRequest(response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  /**
   * 경로에서 빈 userIgn 감지
   *
   * <p>예: `/api/v4/characters//expectation` → true
   */
  private boolean isEmptyUserIgnInPath(String requestUri) {
    // API v4: /api/v4/characters/{userIgn}/expectation
    if (requestUri.matches("^/api/v4/characters//expectation(/preset/\\d+)?$")) {
      return true;
    }

    // API v3: /api/v3/characters/{userIgn}/*
    if (requestUri.startsWith("/api/v3/characters//")) {
      return true;
    }

    // API v2: /api/v2/characters/{userIgn}/*
    if (requestUri.startsWith("/api/v2/characters//")) {
      return true;
    }

    return false;
  }

  private void respondWithBadRequest(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(ERROR_RESPONSE);
  }
}
