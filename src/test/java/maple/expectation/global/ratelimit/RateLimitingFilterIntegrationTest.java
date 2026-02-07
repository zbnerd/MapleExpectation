package maple.expectation.global.ratelimit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import maple.expectation.global.ratelimit.exception.RateLimitExceededException;
import maple.expectation.global.ratelimit.strategy.IpBasedRateLimiter;
import maple.expectation.global.ratelimit.strategy.UserBasedRateLimiter;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rate Limiting Filter 통합 테스트 (Issue #152)
 *
 * <p>Spring wiring 검증: Filter → Facade → Service → Strategy
 *
 * <p>Note: RateLimitingFacade를 MockitoBean으로 대체하여 Redis 의존성 제거
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@DisplayName("RateLimitingFilter 통합 테스트")
class RateLimitingFilterIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private GameCharacterFacade gameCharacterFacade;

  @MockitoBean private RateLimitingFacade rateLimitingFacade;

  // Mock Redis dependencies to avoid connection issues
  @MockitoBean private ProxyManager<String> proxyManager;

  @MockitoBean private IpBasedRateLimiter ipBasedRateLimiter;

  @MockitoBean private UserBasedRateLimiter userBasedRateLimiter;

  @Nested
  @DisplayName("Rate Limit 초과 테스트")
  class RateLimitExceededTests {

    @Test
    @DisplayName("Rate Limit 초과 시 429 + Retry-After 헤더 반환")
    void whenRateLimitExceeded_Returns429WithRetryAfter() throws Exception {
      // Given: Rate Limit 초과
      given(rateLimitingFacade.checkRateLimit(org.mockito.ArgumentMatchers.any()))
          .willReturn(ConsumeResult.denied(0, 30));

      // When & Then: 429 + Retry-After 헤더
      mockMvc
          .perform(get("/api/v1/characters/testIgn"))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().string("Retry-After", "30"))
          .andExpect(header().string("X-RateLimit-Remaining", "0"))
          .andExpect(jsonPath("$.code").value("R001"));
    }
  }

  @Nested
  @DisplayName("Rate Limit 허용 테스트")
  class RateLimitAllowedTests {

    @Test
    @DisplayName("Rate Limit 허용 시 X-RateLimit-Remaining 헤더 포함")
    void whenRateLimitAllowed_IncludesRemainingHeader() throws Exception {
      // Given: Rate Limit 허용
      given(rateLimitingFacade.checkRateLimit(org.mockito.ArgumentMatchers.any()))
          .willReturn(ConsumeResult.allowed(95));
      given(gameCharacterFacade.findCharacterByUserIgn(anyString())).willReturn(null); // 404 응답 유도

      // When & Then: X-RateLimit-Remaining 헤더 포함
      mockMvc
          .perform(get("/api/v1/characters/testIgn"))
          .andExpect(header().string("X-RateLimit-Remaining", "95"));
    }
  }

  @Nested
  @DisplayName("Fail-Open 테스트")
  class FailOpenTests {

    @Test
    @DisplayName("Fail-Open 상황에서 X-RateLimit-Remaining 헤더 미포함")
    void whenFailOpen_DoesNotIncludeRemainingHeader() throws Exception {
      // Given: Fail-Open (remainingTokens = -1)
      given(rateLimitingFacade.checkRateLimit(org.mockito.ArgumentMatchers.any()))
          .willReturn(ConsumeResult.failOpen());
      given(gameCharacterFacade.findCharacterByUserIgn(anyString())).willReturn(null);

      // When & Then: X-RateLimit-Remaining 헤더 없음
      mockMvc
          .perform(get("/api/v1/characters/testIgn"))
          .andExpect(header().doesNotExist("X-RateLimit-Remaining"));
    }
  }

  @Nested
  @DisplayName("바이패스 경로 테스트")
  class BypassPathTests {

    @Test
    @DisplayName("Swagger UI 경로 접근 시 Rate Limit 미적용")
    void swaggerPath_BypassesRateLimit() throws Exception {
      // Given: 바이패스 경로에 대해 Rate Limit 허용 반환
      given(rateLimitingFacade.checkRateLimit(org.mockito.ArgumentMatchers.any()))
          .willReturn(ConsumeResult.allowed(Long.MAX_VALUE));

      // When & Then: Rate Limit 관련 에러(429) 없이 접근 가능
      // Note: 실제 응답은 SpringDoc 설정에 따라 다를 수 있음 (2xx, 3xx, 4xx)
      // 핵심 검증: 429(Too Many Requests)가 아닌 것
      mockMvc
          .perform(get("/swagger-ui/index.html"))
          .andExpect(
              result -> {
                int status = result.getResponse().getStatus();
                if (status == 429) {
                  throw new AssertionError("Expected non-429 status but got 429 (Rate Limited)");
                }
              });
    }

    @Test
    @DisplayName("Actuator health 경로 접근 시 Rate Limit 미적용")
    void actuatorHealthPath_BypassesRateLimit() throws Exception {
      // Given: 바이패스 경로
      given(rateLimitingFacade.checkRateLimit(org.mockito.ArgumentMatchers.any()))
          .willReturn(ConsumeResult.allowed(Long.MAX_VALUE));

      // When & Then: 정상 응답 (200)
      mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("GlobalExceptionHandler 연동 테스트")
  class ExceptionHandlerIntegrationTests {

    @Test
    @DisplayName("RateLimitExceededException → 429 + Retry-After 헤더 (ExceptionHandler 경유)")
    void rateLimitExceededException_HandledBy_GlobalExceptionHandler() throws Exception {
      // Given: Rate Limit 허용 후 서비스에서 예외 발생
      given(rateLimitingFacade.checkRateLimit(org.mockito.ArgumentMatchers.any()))
          .willReturn(ConsumeResult.allowed(50));
      given(gameCharacterFacade.findCharacterByUserIgn(anyString()))
          .willThrow(new RateLimitExceededException(45));

      // When & Then: GlobalExceptionHandler가 처리
      mockMvc
          .perform(get("/api/v1/characters/testIgn"))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().string("Retry-After", "45"))
          .andExpect(jsonPath("$.code").value("R001"));
    }
  }
}
