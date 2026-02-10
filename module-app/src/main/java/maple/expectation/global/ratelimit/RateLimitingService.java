package maple.expectation.global.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.ratelimit.config.RateLimitProperties;
import maple.expectation.global.ratelimit.strategy.IpBasedRateLimiter;
import maple.expectation.global.ratelimit.strategy.UserBasedRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Rate Limiting 비즈니스 로직 서비스 (Issue #152)
 *
 * <p>CLAUDE.md 섹션 7 준수: Facade/Service 분리
 *
 * <ul>
 *   <li>Service: 비즈니스 로직 (전략 선택 및 토큰 소비)
 *   <li>Facade: 오케스트레이션 (Admin 바이패스, 경로 체크 등)
 * </ul>
 *
 * <h4>전략 선택 로직</h4>
 *
 * <ul>
 *   <li>인증 사용자: User 기반 Rate Limiting (fingerprint)
 *   <li>비인증 사용자: IP 기반 Rate Limiting
 * </ul>
 *
 * @since Issue #152
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "ratelimit",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RateLimitingService {

  private final IpBasedRateLimiter ipRateLimiter;
  private final UserBasedRateLimiter userRateLimiter;
  private final RateLimitProperties properties;

  /**
   * Rate Limit 확인 (전략 선택 및 토큰 소비)
   *
   * <p>인증 여부에 따라 적절한 전략 선택
   *
   * @param context Rate Limit 컨텍스트
   * @return ConsumeResult 토큰 소비 결과
   */
  public ConsumeResult checkRateLimit(RateLimitContext context) {
    // 인증 사용자: User 기반
    if (context.isAuthenticated() && userRateLimiter.isEnabled()) {
      String fingerprint =
          context.authenticatedUser().map(user -> user.fingerprint()).orElse(context.clientIp());

      log.debug("[RateLimit] Using user strategy: fingerprint={}", maskKey(fingerprint));
      return userRateLimiter.tryConsume(fingerprint);
    }

    // 비인증 사용자: IP 기반
    if (ipRateLimiter.isEnabled()) {
      log.debug("[RateLimit] Using IP strategy: ip={}", maskIp(context.clientIp()));
      return ipRateLimiter.tryConsume(context.clientIp());
    }

    // 모든 전략 비활성화 → 허용
    log.debug("[RateLimit] All strategies disabled, allowing request");
    return ConsumeResult.allowed(Long.MAX_VALUE);
  }

  /**
   * Rate Limiting 전체 활성화 여부 확인
   *
   * @return 활성화 여부
   */
  public boolean isEnabled() {
    return properties.getEnabled();
  }

  /** 키 마스킹 (로깅용) */
  private String maskKey(String key) {
    if (key == null || key.length() <= 4) {
      return "****";
    }
    return "****" + key.substring(key.length() - 4);
  }

  /** IP 마스킹 (로깅용) */
  private String maskIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return "null";
    }
    String[] parts = ip.split("\\.");
    if (parts.length != 4) {
      return "***";
    }
    return parts[0] + "." + parts[1] + ".***.***";
  }
}
