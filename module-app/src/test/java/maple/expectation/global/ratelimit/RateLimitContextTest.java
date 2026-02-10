package maple.expectation.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Optional;
import maple.expectation.global.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * RateLimitContext Record 테스트 (Issue #152)
 *
 * <p>P1-2 Purple Agent FIX: IP 마스킹 검증
 */
@DisplayName("RateLimitContext 테스트")
class RateLimitContextTest {

  @Nested
  @DisplayName("팩토리 메서드 테스트")
  class FactoryMethodTests {

    @Test
    @DisplayName("of() - 인증 사용자 포함 컨텍스트 생성")
    void of_WithAuthenticatedUser_CreatesContextWithUser() {
      // Given
      AuthenticatedUser user = createTestUser("fingerprint123", "USER");

      // When
      RateLimitContext context = RateLimitContext.of("192.168.1.1", user, "/api/v1/test");

      // Then
      assertThat(context.clientIp()).isEqualTo("192.168.1.1");
      assertThat(context.authenticatedUser()).isPresent();
      assertThat(context.requestUri()).isEqualTo("/api/v1/test");
    }

    @Test
    @DisplayName("of() - 비인증 사용자 컨텍스트 생성")
    void of_WithoutUser_CreatesContextWithEmptyUser() {
      // When
      RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/v1/test");

      // Then
      assertThat(context.clientIp()).isEqualTo("192.168.1.1");
      assertThat(context.authenticatedUser()).isEmpty();
    }
  }

  @Nested
  @DisplayName("인증 상태 확인 테스트")
  class AuthenticationStatusTests {

    @Test
    @DisplayName("isAuthenticated() - 인증 사용자일 때 true")
    void isAuthenticated_WithUser_ReturnsTrue() {
      // Given
      AuthenticatedUser user = createTestUser("fingerprint123", "USER");
      RateLimitContext context = RateLimitContext.of("192.168.1.1", user, "/api/test");

      // Then
      assertThat(context.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("isAuthenticated() - 비인증 사용자일 때 false")
    void isAuthenticated_WithoutUser_ReturnsFalse() {
      // Given
      RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/test");

      // Then
      assertThat(context.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("isAdmin() - Admin 사용자일 때 true")
    void isAdmin_WithAdminRole_ReturnsTrue() {
      // Given
      AuthenticatedUser admin = createTestUser("fingerprint123", "ADMIN");
      RateLimitContext context = RateLimitContext.of("192.168.1.1", admin, "/api/admin/test");

      // Then
      assertThat(context.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("isAdmin() - 일반 사용자일 때 false")
    void isAdmin_WithUserRole_ReturnsFalse() {
      // Given
      AuthenticatedUser user = createTestUser("fingerprint123", "USER");
      RateLimitContext context = RateLimitContext.of("192.168.1.1", user, "/api/test");

      // Then
      assertThat(context.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("isAdmin() - 비인증 사용자일 때 false")
    void isAdmin_WithoutUser_ReturnsFalse() {
      // Given
      RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/test");

      // Then
      assertThat(context.isAdmin()).isFalse();
    }
  }

  @Nested
  @DisplayName("Rate Limit 키 추출 테스트")
  class RateLimitKeyTests {

    @Test
    @DisplayName("getRateLimitKey() - 인증 사용자일 때 fingerprint 반환")
    void getRateLimitKey_WithUser_ReturnsFingerprint() {
      // Given
      AuthenticatedUser user = createTestUser("user-fingerprint-abc", "USER");
      RateLimitContext context = RateLimitContext.of("192.168.1.1", user, "/api/test");

      // Then
      assertThat(context.getRateLimitKey()).isEqualTo("user-fingerprint-abc");
    }

    @Test
    @DisplayName("getRateLimitKey() - 비인증 사용자일 때 IP 반환")
    void getRateLimitKey_WithoutUser_ReturnsClientIp() {
      // Given
      RateLimitContext context = RateLimitContext.of("192.168.1.100", null, "/api/test");

      // Then
      assertThat(context.getRateLimitKey()).isEqualTo("192.168.1.100");
    }
  }

  @Nested
  @DisplayName("IP 마스킹 테스트 (Purple Agent P1-2)")
  class IpMaskingTests {

    @Test
    @DisplayName("toString() - IPv4 IP 마스킹 (앞 2옥텟만 노출)")
    void toString_MasksIpv4Address() {
      // Given
      RateLimitContext context = RateLimitContext.of("192.168.100.50", null, "/api/test");

      // When
      String result = context.toString();

      // Then: 192.168.***.*** 형태로 마스킹
      assertThat(result).contains("clientIp=192.168.***.***");
      assertThat(result).doesNotContain("100.50");
    }

    @Test
    @DisplayName("toString() - IPv6 주소는 *** 처리")
    void toString_HandlesIpv6Address() {
      // Given
      RateLimitContext context =
          RateLimitContext.of("2001:0db8:85a3:0000:0000:8a2e:0370:7334", null, "/api/test");

      // When
      String result = context.toString();

      // Then: IPv6는 ***로 처리
      assertThat(result).contains("clientIp=***");
    }

    @Test
    @DisplayName("toString() - null IP 처리")
    void toString_HandlesNullIp() {
      // Given
      RateLimitContext context = new RateLimitContext(null, Optional.empty(), "/api/test");

      // When
      String result = context.toString();

      // Then
      assertThat(result).contains("clientIp=null");
    }

    @Test
    @DisplayName("toString() - 비인증 사용자 'anonymous' 표시")
    void toString_ShowsAnonymousForUnauthenticatedUser() {
      // Given
      RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/test");

      // When
      String result = context.toString();

      // Then
      assertThat(result).contains("authenticatedUser=anonymous");
    }
  }

  private AuthenticatedUser createTestUser(String fingerprint, String role) {
    return new AuthenticatedUser(
        "test-session-id",
        fingerprint,
        "TestUser",
        "test-account-id",
        "test-api-key",
        Collections.emptySet(),
        role);
  }
}
