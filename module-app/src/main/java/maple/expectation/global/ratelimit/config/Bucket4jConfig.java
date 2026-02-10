package maple.expectation.global.ratelimit.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.redisson.Bucket4jRedisson;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bucket4j + Redisson 설정 (Issue #152)
 *
 * <p>분산 Rate Limiting을 위한 Bucket4j ProxyManager 설정
 *
 * <h4>5-Agent Council 합의</h4>
 *
 * <ul>
 *   <li><b>Green Agent</b>: Bucket4j + Redisson 원자성 보장
 *   <li><b>Red Agent</b>: 조건부 Bean 활성화 (ratelimit.enabled=true)
 * </ul>
 *
 * <p>CLAUDE.md 섹션 8 준수: Redisson 설정 패턴
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(
    prefix = "ratelimit",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Bucket4jConfig {

  private final RedissonClient redissonClient;
  private final RateLimitProperties rateLimitProperties;

  /**
   * Bucket4j ProxyManager Bean (Redisson 기반)
   *
   * <p>Context7 Best Practice:
   *
   * <ul>
   *   <li>basedOnTimeForRefillingBucketUpToMax() - 자동 버킷 만료 및 메모리 효율
   *   <li>RedissonBasedProxyManager - 분산 환경 원자성 보장
   * </ul>
   *
   * @return ProxyManager 인스턴스
   */
  @Bean
  public ProxyManager<String> rateLimitProxyManager() {
    log.info(
        "Initializing Bucket4j ProxyManager with Redisson - keyPrefix={}",
        rateLimitProperties.getKeyPrefix());

    // Context7 Best Practice: Redisson → getCommandExecutor()
    Redisson redisson = (Redisson) redissonClient;

    return Bucket4jRedisson.casBasedBuilder(redisson.getCommandExecutor())
        .expirationAfterWrite(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                Duration.ofMinutes(5)))
        .build();
  }
}
