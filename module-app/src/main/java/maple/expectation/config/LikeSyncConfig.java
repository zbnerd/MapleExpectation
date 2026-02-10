package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.service.v2.like.strategy.AtomicFetchStrategy;
import maple.expectation.service.v2.like.strategy.LuaScriptAtomicFetchStrategy;
import maple.expectation.service.v2.like.strategy.RenameAtomicFetchStrategy;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * LikeSync 전략 선택 설정 (Strategy Pattern)
 *
 * <p>금융수준 안전 설계:
 *
 * <ul>
 *   <li><b>lua (기본)</b>: Lua Script 기반 원자적 연산 (권장)
 *   <li><b>rename (폴백)</b>: RENAME 기반 (Lua 미지원 환경)
 * </ul>
 *
 * <p>설정:
 *
 * <pre>{@code
 * like:
 *   sync:
 *     strategy: lua  # lua | rename
 * }</pre>
 *
 * @since 2.0.0
 */
@Slf4j
@Configuration
public class LikeSyncConfig {

  private static final String STRATEGY_LUA = "lua";
  private static final String STRATEGY_RENAME = "rename";

  @Value("${like.sync.strategy:lua}")
  private String strategyType;

  @Value("${like.sync.temp-key-ttl-seconds:3600}")
  private int tempKeyTtlSeconds;

  /**
   * AtomicFetchStrategy Bean 등록
   *
   * <p>설정에 따라 Lua Script 또는 Rename 전략 선택
   */
  @Bean
  public AtomicFetchStrategy atomicFetchStrategy(
      RedissonClient redissonClient,
      StringRedisTemplate redisTemplate,
      LogicExecutor executor,
      MeterRegistry meterRegistry) {

    return switch (strategyType.toLowerCase()) {
      case STRATEGY_RENAME -> {
        log.info("AtomicFetchStrategy initialized: RENAME (fallback), TTL={}s", tempKeyTtlSeconds);
        yield new RenameAtomicFetchStrategy(
            redisTemplate, executor, meterRegistry, tempKeyTtlSeconds);
      }
      default -> {
        log.info(
            "AtomicFetchStrategy initialized: LUA_SCRIPT (primary), TTL={}s", tempKeyTtlSeconds);
        yield new LuaScriptAtomicFetchStrategy(
            redissonClient, executor, meterRegistry, tempKeyTtlSeconds);
      }
    };
  }
}
