package maple.expectation.global.ratelimit.strategy;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.MeterRegistry;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.ratelimit.config.RateLimitProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * IP 기반 Rate Limiter (Strategy 구현체)
 *
 * <p>비인증 사용자의 Rate Limiting을 담당</p>
 *
 * <h4>설정</h4>
 * <ul>
 *   <li>기본 용량: 100 요청/분</li>
 *   <li>리필: 6초마다 10 토큰</li>
 *   <li>Redis 키: {ratelimit}:ip:{clientIp}</li>
 * </ul>
 *
 * @since Issue #152
 */
@Component
public class IpBasedRateLimiter extends AbstractBucket4jRateLimiter {

    private static final String STRATEGY_NAME = "ip";
    private static final String KEY_PREFIX = "ip";

    public IpBasedRateLimiter(
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
        return properties.getIp().getCapacity();
    }

    @Override
    protected int getRefillTokens() {
        return properties.getIp().getRefillTokens();
    }

    @Override
    protected Duration getRefillPeriod() {
        return properties.getIp().getRefillPeriod();
    }

    /**
     * IP 기반 Rate Limiting 활성화 여부 확인
     *
     * @return 활성화 여부
     */
    public boolean isEnabled() {
        return properties.getIp().getEnabled();
    }
}
