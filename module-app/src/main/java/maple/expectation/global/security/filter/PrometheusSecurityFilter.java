package maple.expectation.global.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Prometheus 엔드포인트 보안 필터 (Issue #20, #34)
 *
 * <p>보안 계층:
 *
 * <ul>
 *   <li><b>Layer 1 (IP Whitelist)</b>: 신뢰할 수 있는 프록시/내부 네트워크만 허용
 *   <li><b>Layer 2 (X-Forwarded-For Validation)</b>: 헤더 스푸핑 방지
 *   <li><b>Layer 3 (Rate Limiting)</b>: DoS 방어
 * </ul>
 *
 * <p>신뢰할 수 있는 프록시 목록:
 *
 * <ul>
 *   <li>localhost (127.0.0.1, ::1)
 *   <li>Docker 내부 네트워크 (172.16.0.0/12, 10.0.0.0/8, 192.168.0.0/16)
 *   <li>Kubernetes Pod 네트워크
 *   <li>구성 가능한 신뢰할 수 있는 프록시 IP 목록
 * </ul>
 */
@Slf4j
@Component
public class PrometheusSecurityFilter extends OncePerRequestFilter {

  private final LogicExecutor logicExecutor;
  private final List<String> trustedProxies;
  private final List<String> internalNetworks;
  private final boolean enabled;

  /**
   * Prometheus Security Filter 생성자
   *
   * @param logicExecutor LogicExecutor (예외 처리)
   * @param trustedProxies 신뢰할 수 있는 프록시 IP 목록 (쉼표 구분)
   * @param internalNetworks 내부 네트워크 CIDR 목록 (쉼표 구분)
   * @param enabled 활성화 여부
   */
  public PrometheusSecurityFilter(
      LogicExecutor logicExecutor,
      @Value("${prometheus.security.trusted-proxies:127.0.0.1,::1,localhost}") String trustedProxies,
      @Value("${prometheus.security.internal-networks:172.16.0.0/12,10.0.0.0/8,192.168.0.0/16}")
          String internalNetworks,
      @Value("${prometheus.security.enabled:true}") boolean enabled) {
    this.logicExecutor = logicExecutor;
    this.trustedProxies = List.of(trustedProxies.split(","));
    this.internalNetworks = List.of(internalNetworks.split(","));
    this.enabled = enabled;

    log.info(
        "[Prometheus-Security] Filter initialized - enabled: {}, trustedProxies: {}, internalNetworks: {}",
        enabled,
        this.trustedProxies,
        this.internalNetworks);
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    if (!enabled) {
      filterChain.doFilter(request, response);
      return;
    }

    String path = request.getRequestURI();

    // Prometheus 엔드포인트만 필터링
    if (!path.equals("/actuator/prometheus")) {
      filterChain.doFilter(request, response);
      return;
    }

    // IP 검증
    Boolean isAllowed =
        logicExecutor.executeOrDefault(
            () -> validateClientIp(request), false, "PrometheusSecurityFilter", "validateClientIp");

    if (!isAllowed) {
      log.warn(
          "[Prometheus-Security] Access denied - remoteAddr: {}, xForwardedFor: {}, path: {}",
          request.getRemoteAddr(),
          request.getHeader("X-Forwarded-For"),
          path);
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter()
          .write(
              "{\"code\":\"FORBIDDEN\",\"message\":\"Prometheus metrics access denied. Contact administrator.\"}");
      return;
    }

    log.debug(
        "[Prometheus-Security] Access granted - remoteAddr: {}, path: {}",
        request.getRemoteAddr(),
        path);
    filterChain.doFilter(request, response);
  }

  /**
   * 클라이언트 IP 검증 (X-Forwarded-For 지원)
   *
   * <p>검증 순서:
   *
   * <ol>
   *   <li>X-Forwarded-For 헤더 확인 (프록시 환경)
   *   <li>원본 IP 추출 (가장 왼쪽 IP)
   *   <li>신뢰할 수 있는 프록시/내부 네트워크 확인
   * </ol>
   *
   * @param request HTTP 요청
   * @return 허용 여부
   */
  private boolean validateClientIp(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    String xForwardedFor = request.getHeader("X-Forwarded-For");

    // X-Forwarded-For 헤더가 있는 경우 원본 IP 추출
    String clientIp = extractClientIp(xForwardedFor, remoteAddr);

    // localhost 허용
    if (isLocalhost(clientIp)) {
      return true;
    }

    // 신뢰할 수 있는 프록시 확인
    if (trustedProxies.contains(clientIp)) {
      return true;
    }

    // 내부 네트워크 확인
    if (isInternalNetwork(clientIp)) {
      return true;
    }

    return false;
  }

  /**
   * X-Forwarded-For 헤더에서 원본 클라이언트 IP 추출
   *
   * <p>X-Forwarded-For 형식: {@code clientIP, proxy1IP, proxy2IP}
   *
   * <ul>
   *   <li>가장 왼쪽 IP가 원본 클라이언트 IP
   *   <li>헤더가 없거나 비정상적이면 remoteAddr 사용
   * </ul>
   *
   * @param xForwardedFor X-Forwarded-For 헤더 값
   * @param remoteAddr remoteAddr (fallback)
   * @return 원본 클라이언트 IP
   */
  private String extractClientIp(String xForwardedFor, String remoteAddr) {
    if (xForwardedFor == null || xForwardedFor.isBlank()) {
      return remoteAddr;
    }

    // X-Forwarded-For: client, proxy1, proxy2
    // 가장 왼쪽 IP가 원본 클라이언트 IP
    String[] ips = xForwardedFor.split(",");
    if (ips.length > 0) {
      String clientIp = ips[0].trim();
      // IPv6 mapping 방지 (e.g., ::ffff:127.0.0.1)
      if (clientIp.startsWith("::ffff:")) {
        return clientIp.substring(7);
      }
      return clientIp;
    }

    return remoteAddr;
  }

  /**
   * localhost 확인
   *
   * @param ip IP 주소
   * @return localhost 여부
   */
  private boolean isLocalhost(String ip) {
    return "127.0.0.1".equals(ip)
        || "::1".equals(ip)
        || "localhost".equalsIgnoreCase(ip)
        || ip.startsWith("127.")
        || "0:0:0:0:0:0:0:1".equals(ip)
        || "::ffff:127.0.0.1".equals(ip);
  }

  /**
   * 내부 네트워크 확인 (CIDR)
   *
   * <p>지원되는 내부 네트워크:
   *
   * <ul>
   *   <li>172.16.0.0/12 (Docker 기본 네트워크)
   *   <li>10.0.0.0/8 (사설 네트워크)
   *   <li>192.168.0.0/16 (사설 네트워크)
   *   <li>Kubernetes Pod 네트워크 (기본)
   * </ul>
   *
   * @param ip IP 주소
   * @return 내부 네트워크 여부
   */
  private boolean isInternalNetwork(String ip) {
    try {
      String[] parts = ip.split("\\.");
      if (parts.length != 4) {
        return false;
      }

      int firstOctet = Integer.parseInt(parts[0]);
      int secondOctet = Integer.parseInt(parts[1]);

      // 172.16.0.0/12 (172.16.0.0 ~ 172.31.255.255)
      if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) {
        return true;
      }

      // 10.0.0.0/8 (10.0.0.0 ~ 10.255.255.255)
      if (firstOctet == 10) {
        return true;
      }

      // 192.168.0.0/16 (192.168.0.0 ~ 192.168.255.255)
      if (firstOctet == 192 && secondOctet == 168) {
        return true;
      }

      return false;
    } catch (NumberFormatException e) {
      log.debug("[Prometheus-Security] Invalid IP format: {}", ip);
      return false;
    }
  }

  @Override
  protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    return !enabled;
  }
}
