package maple.expectation.infrastructure.ratelimit;

/**
 * Rate Limiter 인터페이스 (Strategy Pattern)
 *
 * <p>CLAUDE.md 섹션 4 DIP 준수: 구체적인 구현이 아닌 인터페이스에 의존
 *
 * <h4>구현체</h4>
 *
 * <ul>
 *   <li>IpBasedRateLimiter - IP 기반 Rate Limiting
 *   <li>UserBasedRateLimiter - 인증 사용자 기반 Rate Limiting
 * </ul>
 *
 * @since Issue #152
 */
public interface RateLimiter {

  /**
   * 요청에 대한 토큰 소비 시도
   *
   * <p>Bucket4j의 tryConsume()을 호출하여 Rate Limit 확인
   *
   * @param key Rate Limit 키 (IP 또는 fingerprint)
   * @return ConsumeResult 토큰 소비 결과
   */
  ConsumeResult tryConsume(String key);

  /**
   * Rate Limiter 전략 이름 (로깅 및 메트릭용)
   *
   * @return 전략 이름 ("ip" 또는 "user")
   */
  String getStrategyName();
}
