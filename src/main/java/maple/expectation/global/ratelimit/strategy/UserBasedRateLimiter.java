package maple.expectation.global.ratelimit.strategy;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.MeterRegistry;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.ratelimit.config.RateLimitProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * User 기반 Rate Limiter (Strategy 구현체)
 *
 * <p>인증된 사용자의 Rate Limiting을 담당 (fingerprint 기반)</p>
 *
 * <h4>설정</h4>
 * <ul>
 *   <li>기본 용량: 200 요청/분 (비인증 대비 2배)</li>
 *   <li>리필: 6초마다 20 토큰</li>
 *   <li>Redis 키: {ratelimit}:user:{fingerprint}</li>
 * </ul>
 *
 * @since Issue #152
 */
@Component
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
