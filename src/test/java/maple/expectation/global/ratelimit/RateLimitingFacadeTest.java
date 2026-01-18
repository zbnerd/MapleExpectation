package maple.expectation.global.ratelimit;

import maple.expectation.global.ratelimit.config.RateLimitProperties;
import maple.expectation.global.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RateLimitingFacade 단위 테스트 (Issue #152)
 *
 * <p>CLAUDE.md Section 24 준수: @Execution(SAME_THREAD)로 병렬 실행 충돌 방지</p>
 * <p>LENIENT 모드: Mock 공유 시 UnnecessaryStubbingException 방지</p>
 * <p>Note: @Nested 구조 제거 - MockitoExtension과의 mock 공유 이슈 방지</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("RateLimitingFacade 테스트")
class RateLimitingFacadeTest {

    @Mock
    private RateLimitingService rateLimitingService;

    @Mock
    private RateLimitProperties properties;

    private RateLimitingFacade facade;

    @BeforeEach
    void setUp() {
        // 수동으로 Facade 생성 (Mock 주입)
        facade = new RateLimitingFacade(rateLimitingService, properties);

        // 기본 바이패스 경로 설정 (모든 테스트에서 필요)
        given(properties.getBypassPaths()).willReturn(List.of(
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/actuator/health",
                "/actuator/info"
        ));
    }

    // ========== 전체 비활성화 테스트 ==========

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

    // ========== 바이패스 경로 테스트 ==========

    @Test
    @DisplayName("Swagger UI 경로 바이패스")
    void checkRateLimit_SwaggerPath_BypassesRateLimit() {
        // Given
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
        given(rateLimitingService.isEnabled()).willReturn(true);
        given(rateLimitingService.checkRateLimit(any())).willReturn(ConsumeResult.allowed(99));
        RateLimitContext context = RateLimitContext.of("192.168.1.1", null, "/api/v1/characters/test");

        // When
        ConsumeResult result = facade.checkRateLimit(context);

        // Then
        verify(rateLimitingService).checkRateLimit(context);
        assertThat(result.remainingTokens()).isEqualTo(99);
    }

    // ========== Admin 바이패스 테스트 (Purple Agent P1-1) ==========

    @Test
    @DisplayName("Admin 사용자는 Rate Limit 바이패스")
    void checkRateLimit_AdminUser_BypassesRateLimit() {
        // Given
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

    // ========== Service 위임 테스트 ==========

    @Test
    @DisplayName("Rate Limit 초과 시 denied 결과 반환")
    void checkRateLimit_WhenExceeded_ReturnsDenied() {
        // Given
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
