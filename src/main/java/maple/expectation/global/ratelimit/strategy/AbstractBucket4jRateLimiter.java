package maple.expectation.global.ratelimit.strategy;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.ratelimit.ConsumeResult;
import maple.expectation.global.ratelimit.RateLimiter;
import maple.expectation.global.ratelimit.config.RateLimitProperties;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bucket4j 기반 Rate Limiter 추상 클래스 (Template Method Pattern)
 *
 * <p>CLAUDE.md 섹션 6 준수: Template Method 패턴으로 공통 로직 추상화</p>
 *
 * <h4>구현체 책임</h4>
 * <ul>
 *   <li>getKeyPrefix() - Redis 키 접두사 반환</li>
 *   <li>getCapacity() - 최대 토큰 수 반환</li>
 *   <li>getRefillTokens() - 리필 토큰 수 반환</li>
 *   <li>getRefillPeriod() - 리필 주기 반환</li>
 * </ul>
 *
 * @since Issue #152
 */
@Slf4j
public abstract class AbstractBucket4jRateLimiter implements RateLimiter {

    protected final ProxyManager<String> proxyManager;
    protected final RateLimitProperties properties;
    protected final LogicExecutor executor;
    protected final MeterRegistry meterRegistry;

    protected AbstractBucket4jRateLimiter(
            ProxyManager<String> proxyManager,
            RateLimitProperties properties,
            LogicExecutor executor,
            MeterRegistry meterRegistry) {
        this.proxyManager = proxyManager;
        this.properties = properties;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 토큰 소비 시도 (Template Method)
     *
     * <p>CLAUDE.md 섹션 12 준수: LogicExecutor 패턴</p>
     * <p>CLAUDE.md 섹션 17 준수: Graceful Degradation (Redis 장애 시 Fail-Open)</p>
     *
     * @param key Rate Limit 키 (IP 또는 fingerprint)
     * @return ConsumeResult 토큰 소비 결과
     */
    @Override
    public ConsumeResult tryConsume(String key) {
        String fullKey = buildFullKey(key);
        TaskContext context = TaskContext.of("RateLimit", "Consume", getStrategyName() + ":" + maskKey(key));

        return executor.executeOrDefault(
                () -> doTryConsume(fullKey),
                handleFailure(key),
                context
        );
    }

    /**
     * 실제 토큰 소비 로직
     *
     * @param fullKey Redis 전체 키
     * @return ConsumeResult 토큰 소비 결과
     */
    private ConsumeResult doTryConsume(String fullKey) {
        Supplier<BucketConfiguration> configSupplier = this::buildBucketConfiguration;

        ConsumptionProbe probe = proxyManager.builder()
                .build(fullKey, configSupplier)
                .tryConsumeAndReturnRemaining(1);

        recordMetrics(probe.isConsumed());

        if (probe.isConsumed()) {
            return ConsumeResult.allowed(probe.getRemainingTokens());
        }

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        return ConsumeResult.denied(probe.getRemainingTokens(), Math.max(retryAfterSeconds, 1));
    }

    /**
     * Bucket 설정 생성
     *
     * <p>Context7 Best Practice: Greedy Refill로 균등 토큰 리필</p>
     *
     * @return BucketConfiguration 버킷 설정
     */
    protected BucketConfiguration buildBucketConfiguration() {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(getCapacity())
                .refillGreedy(getRefillTokens(), getRefillPeriod())
                .build();

        return BucketConfiguration.builder()
                .addLimit(bandwidth)
                .build();
    }

    /**
     * Redis 전체 키 생성
     *
     * <p>CLAUDE.md 섹션 8-1 준수: Cluster Hash Tag 적용</p>
     *
     * @param key 원본 키
     * @return 전체 키 (예: {ratelimit}:ip:192.168.1.1)
     */
    protected String buildFullKey(String key) {
        return properties.getKeyPrefix() + ":" + getKeyPrefix() + ":" + key;
    }

    /**
     * Redis 장애 시 Fail-Open 처리
     *
     * <p>5-Agent Council 합의: 가용성 > 보안</p>
     *
     * @param key 원본 키
     * @return Fail-Open 결과
     */
    private ConsumeResult handleFailure(String key) {
        if (properties.isFailOpen()) {
            log.warn("[RateLimit-FailOpen] Redis failure, allowing request: strategy={}, key={}",
                    getStrategyName(), maskKey(key));
            meterRegistry.counter("ratelimit.failopen", "strategy", getStrategyName()).increment();
            return ConsumeResult.failOpen();
        }

        // Fail-Close 모드 (보안 우선)
        log.warn("[RateLimit-FailClose] Redis failure, denying request: strategy={}, key={}",
                getStrategyName(), maskKey(key));
        meterRegistry.counter("ratelimit.failclose", "strategy", getStrategyName()).increment();
        return ConsumeResult.denied(0, 60);  // 60초 후 재시도 권장
    }

    /**
     * 메트릭 기록
     *
     * <p>CLAUDE.md 섹션 17 준수: Micrometer 소문자 점 표기법</p>
     *
     * @param consumed 토큰 소비 성공 여부
     */
    private void recordMetrics(boolean consumed) {
        String result = consumed ? "allowed" : "denied";
        meterRegistry.counter("ratelimit.consume", "strategy", getStrategyName(), "result", result).increment();
    }

    /**
     * 키 마스킹 (로깅용)
     *
     * @param key 원본 키
     * @return 마스킹된 키 (마지막 4자 제외)
     */
    protected String maskKey(String key) {
        if (key == null || key.length() <= 4) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }

    // ===== Template Methods (구현체에서 오버라이드) =====

    /**
     * Redis 키 접두사 (예: "ip" 또는 "user")
     */
    protected abstract String getKeyPrefix();

    /**
     * 최대 토큰 수 (버킷 용량)
     */
    protected abstract int getCapacity();

    /**
     * 리필 토큰 수
     */
    protected abstract int getRefillTokens();

    /**
     * 리필 주기
     */
    protected abstract Duration getRefillPeriod();
}
