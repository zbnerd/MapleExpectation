package maple.expectation.global.ratelimit;

import maple.expectation.global.ratelimit.config.RateLimitProperties;
import maple.expectation.global.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RateLimitingFacade 단위 테스트 (Issue #152)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitingFacade 테스트")
class RateLimitingFacadeTest {

    @Mock
    private RateLimitingService rateLimitingService;

    @Mock
    private RateLimitProperties properties;

    @InjectMocks
    private RateLimitingFacade facade;

    private void setupBypassPaths() {
        given(properties.getBypassPaths()).willReturn(List.of(
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/actuator/health",
                "/actuator/info"
        ));
    }

    @Nested
    @DisplayName("전체 비활성화 테스트")
    class GlobalDisableTests {

        @Test
        @DisplayName("Rate Limiting 비활성화 시 허용")
        void checkRateLimit_WhenDisabled_ReturnsAllowed() {
            // Given
            given(rateLimitingService.isEnabled()).willReturn(false);
            RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/test");

            // When
            ConsumeResult result = facade.checkRateLimit(context);

            // Then
            assertThat(result.allowed()).isTrue();
            verify(rateLimitingService, never()).checkRateLimit(any());
        }
    }

    @Nested
    @DisplayName("바이패스 경로 테스트")
    class BypassPathTests {

        @Test
        @DisplayName("Swagger UI 경로 바이패스")
        void checkRateLimit_SwaggerPath_BypassesRateLimit() {
            // Given
            setupBypassPaths();
            given(rateLimitingService.isEnabled()).willReturn(true);
            RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/swagger-ui/index.html");

            // When
            ConsumeResult result = facade.checkRateLimit(context);

            // Then
            assertThat(result.allowed()).isTrue();
            verify(rateLimitingService, never()).checkRateLimit(any());
        }

        @Test
        @DisplayName("Actuator health 경로 바이패스")
        void checkRateLimit_ActuatorHealthPath_BypassesRateLimit() {
            // Given
            setupBypassPaths();
            given(rateLimitingService.isEnabled()).willReturn(true);
            RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/actuator/health");

            // When
            ConsumeResult result = facade.checkRateLimit(context);

            // Then
            assertThat(result.allowed()).isTrue();
            verify(rateLimitingService, never()).checkRateLimit(any());
        }

        @Test
        @DisplayName("일반 API 경로는 Rate Limit 적용")
        void checkRateLimit_NormalApiPath_AppliesRateLimit() {
            // Given
            setupBypassPaths();
            given(rateLimitingService.isEnabled()).willReturn(true);
            given(rateLimitingService.checkRateLimit(any())).willReturn(ConsumeResult.allowed(99));
            RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/v1/characters/test");

            // When
            ConsumeResult result = facade.checkRateLimit(context);

            // Then
            verify(rateLimitingService).checkRateLimit(context);
            assertThat(result.remainingTokens()).isEqualTo(99);
        }
    }

    @Nested
    @DisplayName("Admin 바이패스 테스트 (Purple Agent P1-1)")
    class AdminBypassTests {

        @Test
        @DisplayName("Admin 사용자는 Rate Limit 바이패스")
        void checkRateLimit_AdminUser_BypassesRateLimit() {
            // Given
            setupBypassPaths();
            given(rateLimitingService.isEnabled()).willReturn(true);
            AuthenticatedUser admin = new AuthenticatedUser(
                    "session-id", "fingerprint", "api-key", Collections.emptySet(), "ADMIN"
            );
            RateLimitContext context = RateLimitContext.of("192.168.1.1", admin, "/api/admin/users");

            // When
            ConsumeResult result = facade.checkRateLimit(context);

            // Then
            assertThat(result.allowed()).isTrue();
            verify(rateLimitingService, never()).checkRateLimit(any());
        }

        @Test
        @DisplayName("일반 사용자는 Rate Limit 적용")
        void checkRateLimit_NormalUser_AppliesRateLimit() {
            // Given
            setupBypassPaths();
            given(rateLimitingService.isEnabled()).willReturn(true);
            given(rateLimitingService.checkRateLimit(any())).willReturn(ConsumeResult.allowed(99));
            AuthenticatedUser user = new AuthenticatedUser(
                    "session-id", "fingerprint", "api-key", Collections.emptySet(), "USER"
            );
            RateLimitContext context = RateLimitContext.of("192.168.1.1", user, "/api/v1/test");

            // When
            ConsumeResult result = facade.checkRateLimit(context);

            // Then
            verify(rateLimitingService).checkRateLimit(context);
            assertThat(result.remainingTokens()).isEqualTo(99);
        }
    }

    @Nested
    @DisplayName("Service 위임 테스트")
    class ServiceDelegationTests {

        @Test
        @DisplayName("Rate Limit 초과 시 denied 결과 반환")
        void checkRateLimit_WhenExceeded_ReturnsDenied() {
            // Given
            setupBypassPaths();
            given(rateLimitingService.isEnabled()).willReturn(true);
            given(rateLimitingService.checkRateLimit(any())).willReturn(ConsumeResult.denied(0, 60));
            RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/v1/test");

            // When
            ConsumeResult result = facade.checkRateLimit(context);

            // Then
            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(60);
        }
    }
}
