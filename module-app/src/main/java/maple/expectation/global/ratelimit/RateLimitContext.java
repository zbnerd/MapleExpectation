package maple.expectation.global.ratelimit;

import java.util.Optional;
import maple.expectation.global.security.AuthenticatedUser;

/**
 * Rate Limiting 요청 컨텍스트 (Immutable Record)
 *
 * <p>CLAUDE.md 섹션 4 준수: Java 17 Record + Optional 체이닝
 *
 * <h4>P1-2 Purple Agent FIX: IP 마스킹</h4>
 *
 * <p>AOP 로깅 시 IP 주소 전체 노출 방지를 위해 toString() 오버라이드
 *
 * @param clientIp 클라이언트 IP 주소
 * @param authenticatedUser 인증된 사용자 정보 (Optional)
 * @param requestUri 요청 URI
 * @since Issue #152
 */
public record RateLimitContext(
    String clientIp, Optional<AuthenticatedUser> authenticatedUser, String requestUri) {
  /**
   * IP 마스킹된 문자열 반환 (CLAUDE.md 섹션 19 준수)
   *
   * <p>Purple Agent P1-2 FIX: AOP 로깅 시 IP 평문 노출 방지
   *
   * <p>Session.java:74, AuthenticatedUser.java:44 패턴 참조
   */
  @Override
  public String toString() {
    return "RateLimitContext["
        + "clientIp="
        + maskIp(clientIp)
        + ", authenticatedUser="
        + authenticatedUser.map(AuthenticatedUser::toString).orElse("anonymous")
        + ", requestUri="
        + requestUri
        + "]";
  }

  /**
   * IPv4 주소 마스킹 (앞 2옥텟만 노출)
   *
   * @param ip IP 주소
   * @return 마스킹된 IP (예: 192.168.***.***) 또는 "***" (IPv6 등)
   */
  private String maskIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return "null";
    }
    String[] parts = ip.split("\\.");
    if (parts.length != 4) {
      return "***"; // IPv6 등 비표준 형식
    }
    return parts[0] + "." + parts[1] + ".***.***";
  }

  /**
   * 인증된 사용자인지 확인
   *
   * @return 인증 여부
   */
  public boolean isAuthenticated() {
    return authenticatedUser.isPresent();
  }

  /**
   * Admin 사용자인지 확인 (Rate Limit 바이패스 판단용)
   *
   * @return Admin 여부
   */
  public boolean isAdmin() {
    return authenticatedUser.map(AuthenticatedUser::isAdmin).orElse(false);
  }

  /**
   * Rate Limit 키 추출 (인증 여부에 따라 fingerprint 또는 IP)
   *
   * @return Rate Limit 키
   */
  public String getRateLimitKey() {
    return authenticatedUser.map(AuthenticatedUser::fingerprint).orElse(clientIp);
  }

  /**
   * 컨텍스트 생성 (Factory Method)
   *
   * @param clientIp 클라이언트 IP
   * @param user 인증 사용자 (nullable)
   * @param requestUri 요청 URI
   * @return RateLimitContext 인스턴스
   */
  public static RateLimitContext of(String clientIp, AuthenticatedUser user, String requestUri) {
    return new RateLimitContext(clientIp, Optional.ofNullable(user), requestUri);
  }
}
