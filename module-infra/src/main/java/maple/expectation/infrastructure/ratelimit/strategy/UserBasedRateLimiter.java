package maple.expectation.infrastructure.ratelimit.strategy;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.ratelimit.config.RateLimitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * User 기반 Rate Limiter (Strategy 구현체)
 *
 * <p>인증된 사용자의 Rate Limiting을 담당 (fingerprint 기반)
 *
 * <h4>설정</h4>
 *
 * <ul>
 *   <li>기본 용량: 200 요청/분 (비인증 대비 2배)
 *   <li>리필: 6초마다 20 토큰
 *   <li>Redis 키: {ratelimit}:user:{fingerprint}
 * </ul>
 *
 * <h4>PR #192: Conditional Bean 등록</h4>
 *
 * <p>{@code ratelimit.enabled=true} 설정 시에만 Bean 등록됨 (기본값: true)
 *
 * @since Issue #152
 */
@Component
@ConditionalOnProperty(
    prefix = "ratelimit",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UserBasedRateLimiter extends AbstractBucket4jRateLimiter {

  private static final String STRATEGY_NAME = "user";
  private static final String KEY_PREFIX = "user";

  public UserBasedRateLimiter(
      ProxyManager<String> proxyManager,
      RateLimitProperties properties,
      LogicExecutor executor,
      MeterRegistry meterRegistry) {
    super(proxyManager, properties, executor, meterRegistry);
  }

  @Override
  public String getStrategyName() {
    return STRATEGY_NAME;
  }

  @Override
  protected String getKeyPrefix() {
    return KEY_PREFIX;
  }

  @Override
  protected int getCapacity() {
    return properties.getUser().getCapacity();
  }

  @Override
  protected int getRefillTokens() {
    return properties.getUser().getRefillTokens();
  }

  @Override
  protected Duration getRefillPeriod() {
    return properties.getUser().getRefillPeriod();
  }

  /**
   * User 기반 Rate Limiting 활성화 여부 확인
   *
   * @return 활성화 여부
   */
  public boolean isEnabled() {
    return properties.getUser().getEnabled();
  }
}
