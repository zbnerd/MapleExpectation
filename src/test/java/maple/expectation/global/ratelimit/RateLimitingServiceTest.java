package maple.expectation.global.ratelimit;

import maple.expectation.global.ratelimit.config.RateLimitProperties;
import maple.expectation.global.ratelimit.strategy.IpBasedRateLimiter;
import maple.expectation.global.ratelimit.strategy.UserBasedRateLimiter;
import maple.expectation.global.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RateLimitingService 단위 테스트 (Issue #152)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitingService 테스트")
class RateLimitingServiceTest {

    @Mock
    private IpBasedRateLimiter ipRateLimiter;

    @Mock
    private UserBasedRateLimiter userRateLimiter;

    @Mock
    private RateLimitProperties properties;

    @InjectMocks
    private RateLimitingService service;

    @Nested
    @DisplayName("전략 선택 테스트")
    class StrategySelectionTests {

        @Test
        @DisplayName("인증 사용자 - User 전략 사용 (fingerprint 기반)")
        void checkRateLimit_AuthenticatedUser_UsesUserStrategy() {
            // Given
            AuthenticatedUser user = createTestUser("user-fingerprint-123");
            RateLimitContext context = RateLimitContext.of("192.168.1.1", user, "/api/test");

            given(userRateLimiter.isEnabled()).willReturn(true);
            given(userRateLimiter.tryConsume(eq("user-fingerprint-123")))
                    .willReturn(ConsumeResult.allowed(199));

            // When
            ConsumeResult result = service.checkRateLimit(context);

            // Then
            verify(userRateLimiter).tryConsume("user-fingerprint-123");
            verify(ipRateLimiter, never()).tryConsume(org.mockito.ArgumentMatchers.anyString());
            assertThat(result.remainingTokens()).isEqualTo(199);
        }

        @Test
        @DisplayName("비인증 사용자 - IP 전략 사용")
        void checkRateLimit_AnonymousUser_UsesIpStrategy() {
            // Given
            RateLimitContext context = RateLimitContext.of("192.168.1.100", null, "/api/test");

            given(ipRateLimiter.isEnabled()).willReturn(true);
            given(ipRateLimiter.tryConsume(eq("192.168.1.100")))
                    .willReturn(ConsumeResult.allowed(99));

            // When
            ConsumeResult result = service.checkRateLimit(context);

            // Then
            verify(ipRateLimiter).tryConsume("192.168.1.100");
            verify(userRateLimiter, never()).tryConsume(org.mockito.ArgumentMatchers.anyString());
            assertThat(result.remainingTokens()).isEqualTo(99);
        }

        @Test
        @DisplayName("User 전략 비활성화 시 IP 전략 사용")
        void checkRateLimit_UserStrategyDisabled_FallsBackToIp() {
            // Given
            AuthenticatedUser user = createTestUser("user-fingerprint-123");
            RateLimitContext context = RateLimitContext.of("192.168.1.1", user, "/api/test");

            given(userRateLimiter.isEnabled()).willReturn(false);
            given(ipRateLimiter.isEnabled()).willReturn(true);
            given(ipRateLimiter.tryConsume(eq("192.168.1.1")))
                    .willReturn(ConsumeResult.allowed(99));

            // When
            ConsumeResult result = service.checkRateLimit(context);

            // Then
            verify(ipRateLimiter).tryConsume("192.168.1.1");
            verify(userRateLimiter, never()).tryConsume(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("전략 비활성화 테스트")
    class StrategyDisableTests {

        @Test
        @DisplayName("모든 전략 비활성화 시 허용")
        void checkRateLimit_AllStrategiesDisabled_ReturnsAllowed() {
            // Given
            RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/test");

            given(ipRateLimiter.isEnabled()).willReturn(false);

            // When
            ConsumeResult result = service.checkRateLimit(context);

            // Then
            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("Rate Limit 결과 테스트")
    class RateLimitResultTests {

        @Test
        @DisplayName("Rate Limit 초과 시 denied 결과 반환")
        void checkRateLimit_WhenExceeded_ReturnsDenied() {
            // Given
            RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/test");

            given(ipRateLimiter.isEnabled()).willReturn(true);
            given(ipRateLimiter.tryConsume(eq("192.168.1.1")))
                    .willReturn(ConsumeResult.denied(0, 45));

            // When
            ConsumeResult result = service.checkRateLimit(context);

            // Then
            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(45);
        }
    }

    @Nested
    @DisplayName("활성화 상태 테스트")
    class EnabledStatusTests {

        @Test
        @DisplayName("isEnabled() - properties에서 활성화 상태 반환")
        void isEnabled_ReturnsPropertiesValue() {
            // Given
            given(properties.getEnabled()).willReturn(true);

            // When & Then
            assertThat(service.isEnabled()).isTrue();
        }
    }

    private AuthenticatedUser createTestUser(String fingerprint) {
        return new AuthenticatedUser(
                "test-session-id",
                fingerprint,
                "test-api-key",
                Collections.emptySet(),
                "USER"
        );
    }
}
