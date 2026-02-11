package maple.expectation.global.security.filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * PrometheusSecurityFilter 테스트 (Issue #20, #34)
 *
 * <p>테스트 커버리지:
 *
 * <ul>
 *   <li>IP Whitelist 검증
 *   <li>X-Forwarded-For 헤더 스푸핑 방지
 *   <li>내부 네트워크 접근 제어
 *   <li>비인증 접근 차단
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrometheusSecurityFilter 테스트")
class PrometheusSecurityFilterTest {

  @Mock private LogicExecutor logicExecutor;

  private PrometheusSecurityFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private FilterChain filterChain;

  @BeforeEach
  void setUp() {
    filter =
        new PrometheusSecurityFilter(
            logicExecutor,
            "127.0.0.1,::1,localhost,172.17.0.1", // trusted proxies
            "172.16.0.0/12,10.0.0.0/8,192.168.0.0/16", // internal networks
            true);

    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    filterChain = mock(FilterChain.class);
  }

  @Nested
  @DisplayName("IP Whitelist 검증")
  class IpWhitelistTests {

    @Test
    @DisplayName("localhost 접근 허용")
    void testLocalhostAllowed() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("127.0.0.1");
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
      verify(logicExecutor)
          .executeOrDefault(
              any(),
              eq(false),
              eq(TaskContext.of("PrometheusSecurityFilter", "validateClientIp", "127.0.0.1")));
    }

    @Test
    @DisplayName("IPv6 localhost 접근 허용")
    void testIPv6LocalhostAllowed() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("::1");
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("신뢰할 수 있는 프록시 IP 접근 허용")
    void testTrustedProxyAllowed() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("172.17.0.1"); // Docker default bridge gateway
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("X-Forwarded-For 헤더 검증")
  class XForwardedForTests {

    @Test
    @DisplayName("X-Forwarded-For 헤더의 원본 IP로 검증")
    void testXForwardedForValidation() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("172.17.0.1"); // 프록시 IP
      request.addHeader("X-Forwarded-For", "127.0.0.1, 172.17.0.1"); // client, proxy
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Forwarded-For가 없으면 remoteAddr 사용")
    void testNoXForwardedFor() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("127.0.0.1");
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Forwarded-For 스푸핑 시도 차단")
    void testXForwardedForSpoofingBlocked() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("172.17.0.1");
      request.addHeader("X-Forwarded-For", "8.8.8.8"); // 외부 IP 스푸핑 시도
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(false);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain, never()).doFilter(request, response);
      assert response.getStatus() == HttpServletResponse.SC_FORBIDDEN;
    }
  }

  @Nested
  @DisplayName("내부 네트워크 접근 제어")
  class InternalNetworkTests {

    @Test
    @DisplayName("Docker 내부 네트워크 (172.16.0.0/12) 접근 허용")
    void testDockerInternalNetworkAllowed() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("172.20.0.5"); // 172.16.0.0/12 범위
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("사설 네트워크 (10.0.0.0/8) 접근 허용")
    void testPrivateNetworkAllowed() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("10.0.0.5");
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("사설 네트워크 (192.168.0.0/16) 접근 허용")
    void testPrivateNetwork192Allowed() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("192.168.1.5");
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(true);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }
  }

  @Nested
  @DisplayName("비인증 접근 차단")
  class UnauthorizedAccessTests {

    @Test
    @DisplayName("외부 IP 접근 차단")
    void testExternalIpBlocked() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("8.8.8.8"); // Google DNS
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(false);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain, never()).doFilter(request, response);
      assert response.getStatus() == HttpServletResponse.SC_FORBIDDEN;
    }

    @Test
    @DisplayName("차단 시 403 응답")
    void testForbiddenResponse() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("1.2.3.4");
      when(logicExecutor.executeOrDefault(
              any(),
              any(),
              eq(
                  TaskContext.of(
                      "PrometheusSecurityFilter", "validateClientIp", request.getRemoteAddr()))))
          .thenReturn(false);

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      assert response.getStatus() == HttpServletResponse.SC_FORBIDDEN;
      assert response.getContentType().equals("application/json;charset=UTF-8");
      String content = response.getContentAsString();
      assert content.contains("\"code\":\"FORBIDDEN\"");
      assert content.contains("Prometheus metrics access denied");
    }
  }

  @Nested
  @DisplayName("비활성화된 경우")
  class DisabledTests {

    @Test
    @DisplayName("필터 비활성화 시 모든 요청 통과")
    void testFilterDisabled() throws Exception {
      // Arrange
      PrometheusSecurityFilter disabledFilter =
          new PrometheusSecurityFilter(logicExecutor, "", "", false);
      request.setRequestURI("/actuator/prometheus");
      request.setRemoteAddr("1.2.3.4");

      // Act
      disabledFilter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
      assert response.getStatus() != HttpServletResponse.SC_FORBIDDEN;
    }
  }

  @Nested
  @DisplayName("다른 경로 처리")
  class NonPrometheusPathTests {

    @Test
    @DisplayName("Prometheus 외 경로는 필터 스킵")
    void testNonPrometheusPathSkipped() throws Exception {
      // Arrange
      request.setRequestURI("/api/v3/characters/test");
      request.setRemoteAddr("1.2.3.4");

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
      assert response.getStatus() != HttpServletResponse.SC_FORBIDDEN;
    }

    @Test
    @DisplayName("/actuator/health는 필터 스킵")
    void testHealthEndpointSkipped() throws Exception {
      // Arrange
      request.setRequestURI("/actuator/health");
      request.setRemoteAddr("1.2.3.4");

      // Act
      filter.doFilter(request, response, filterChain);

      // Assert
      verify(filterChain).doFilter(request, response);
    }
  }
}
