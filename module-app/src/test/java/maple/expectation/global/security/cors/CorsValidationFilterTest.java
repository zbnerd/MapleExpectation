package maple.expectation.global.security.cors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * CORS 검증 필터 단위 테스트 (Issue #21)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 CORS 검증 필터를 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>허용된 오리진 요청 통과
 *   <li>허용되지 않은 오리진 요청 차단 (403)
 *   <li>OPTIONS 요청 항상 통과 (preflight)
 *   <li>Origin 헤더 없는 요청 통과 (same-origin)
 * </ul>
 */
@Tag("unit")
@DisplayName("CORS 검증 필터 테스트")
class CorsValidationFilterTest {

  private static final List<String> ALLOWED_ORIGINS =
      List.of("https://example.com", "https://www.example.com", "http://localhost:3000");

  private CorsOriginValidator validator;
  private LogicExecutor executor;
  private CorsValidationFilter filter;

  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain filterChain;

  @BeforeEach
  void setUp() throws IOException {
    validator = mock(CorsOriginValidator.class);
    executor = mock(LogicExecutor.class);

    filter = new CorsValidationFilter(validator, executor, ALLOWED_ORIGINS);

    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    filterChain = mock(FilterChain.class);

    // PrintWriter 모킹
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    given(response.getWriter()).willReturn(printWriter);
  }

  @Nested
  @DisplayName("OPTIONS 요청 처리")
  class OptionsRequestTest {

    @Test
    @DisplayName("OPTIONS 요청은 항상 통과 (preflight)")
    void shouldAlwaysPassOptionsRequest() throws Exception {
      // given
      given(request.getMethod()).willReturn(HttpMethod.OPTIONS.name());

      // when
      filter.doFilterInternal(request, response, filterChain);

      // then
      verify(filterChain).doFilter(request, response);
      verify(validator, never()).isValidRuntimeOrigin(any(), any());
    }
  }

  @Nested
  @DisplayName("Origin 헤더 없는 요청")
  class NoOriginHeaderTest {

    @Test
    @DisplayName("Origin 헤더가 없으면 통과 (same-origin)")
    void shouldPassWhenNoOriginHeader() throws Exception {
      // given
      given(request.getMethod()).willReturn(HttpMethod.GET.name());
      given(request.getHeader("Origin")).willReturn(null);

      // when
      filter.doFilterInternal(request, response, filterChain);

      // then
      verify(filterChain).doFilter(request, response);
      verify(validator, never()).isValidRuntimeOrigin(any(), any());
    }

    @Test
    @DisplayName("빈 Origin 헤더는 통과")
    void shouldPassWhenEmptyOriginHeader() throws Exception {
      // given
      given(request.getMethod()).willReturn(HttpMethod.GET.name());
      given(request.getHeader("Origin")).willReturn("");

      // when
      filter.doFilterInternal(request, response, filterChain);

      // then
      verify(filterChain).doFilter(request, response);
      verify(validator, never()).isValidRuntimeOrigin(any(), any());
    }
  }

  @Nested
  @DisplayName("허용된 오리진 요청")
  class AllowedOriginTest {

    @Test
    @DisplayName("허용된 오리진 요청 통과")
    void shouldPassAllowedOrigin() throws Exception {
      // given
      String allowedOrigin = "https://example.com";
      given(request.getMethod()).willReturn(HttpMethod.GET.name());
      given(request.getHeader("Origin")).willReturn(allowedOrigin);
      given(validator.isValidRuntimeOrigin(allowedOrigin, ALLOWED_ORIGINS)).willReturn(true);
      given(executor.executeOrDefault(any(), anyBoolean(), any())).willReturn(true);

      // when
      filter.doFilterInternal(request, response, filterChain);

      // then
      verify(filterChain).doFilter(request, response);
      verify(executor).executeOrDefault(any(), eq(false), any(TaskContext.class));
    }

    @Test
    @DisplayName("다른 허용된 오리진도 통과")
    void shouldPassAnotherAllowedOrigin() throws Exception {
      // given
      String allowedOrigin = "http://localhost:3000";
      given(request.getMethod()).willReturn(HttpMethod.POST.name());
      given(request.getHeader("Origin")).willReturn(allowedOrigin);
      given(executor.executeOrDefault(any(), anyBoolean(), any())).willReturn(true);

      // when
      filter.doFilterInternal(request, response, filterChain);

      // then
      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("허용되지 않은 오리진 요청")
  class NotAllowedOriginTest {

    @Test
    @DisplayName("허용되지 않은 오리진 요청 차단 (403)")
    void shouldRejectNotAllowedOrigin() throws Exception {
      // given
      String forbiddenOrigin = "https://evil.com";
      given(request.getMethod()).willReturn(HttpMethod.GET.name());
      given(request.getHeader("Origin")).willReturn(forbiddenOrigin);
      given(executor.executeOrDefault(any(), anyBoolean(), any())).willReturn(false);

      // when
      filter.doFilterInternal(request, response, filterChain);

      // then
      verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
      verify(response).setContentType("application/json;charset=UTF-8");
      verify(response).getWriter(); // 응답 본문 작성 확인
      verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("LogicExecutor 예외 시 차단 (fail-closed)")
    void shouldRejectOnExecutorException() throws Exception {
      // given
      String origin = "https://example.com";
      given(request.getMethod()).willReturn(HttpMethod.GET.name());
      given(request.getHeader("Origin")).willReturn(origin);
      given(executor.executeOrDefault(any(), anyBoolean(), any())).willReturn(false);

      // when
      filter.doFilterInternal(request, response, filterChain);

      // then
      verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
      verify(filterChain, never()).doFilter(request, response);
    }
  }
}
