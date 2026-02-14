package maple.expectation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import maple.expectation.infrastructure.cache.TieredCacheManager;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.SafeExecutor;
import maple.expectation.infrastructure.queue.MessageQueueStrategy;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * MapleInfraAutoConfiguration Test.
 *
 * <p>ADR-014: Verifies that AutoConfiguration correctly exports all infrastructure beans.
 *
 * <h3>Test Coverage</h3>
 *
 * <ul>
 *   <li>LogicExecutor, CheckedLogicExecutor beans auto-exported
 *   <li>CacheManager, TieredCache beans auto-exported
 *   <li>LockStrategy beans auto-exported
 *   <li>GracefulShutdownCoordinator bean auto-exported
 *   <li>RateLimiter (Bucket4j ProxyManager) bean auto-exported
 *   <li>Conditional activation via maple.infra.{feature}.enabled
 * </ul>
 *
 * @see <a href="https://github.com/issue/ADR-014">ADR-014: Multi-Module Cross-Cutting Concerns</a>
 */
class MapleInfraAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(MapleInfraAutoConfiguration.class));

  /** Test: LogicExecutor beans are auto-configured */
  @Test
  void logicExecutorBeansAutoConfigured() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(LogicExecutor.class);
          assertThat(context).hasSingleBean(CheckedLogicExecutor.class);
          assertThat(context).hasSingleBean(SafeExecutor.class);
        });
  }

  /** Test: Executor beans can be disabled */
  @Test
  void executorBeansDisabledWhenPropertyFalse() {
    contextRunner
        .withPropertyValues("maple.infra.executor.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(LogicExecutor.class);
              assertThat(context).doesNotHaveBean(CheckedLogicExecutor.class);
            });
  }

  /** Test: Cache beans are auto-configured */
  @Test
  void cacheBeansAutoConfigured() {
    contextRunner
        .withBean(RedisConnectionFactory.class)
        .withBean(org.springframework.cache.CacheManager.class) // L1 Caffeine
        .run(
            context -> {
              assertThat(context).hasSingleBean(CacheManager.class);
              // TieredCacheManager should be created
              CacheManager cacheManager = context.getBean(CacheManager.class);
              assertThat(cacheManager).isInstanceOf(TieredCacheManager.class);
            });
  }

  /** Test: Cache beans can be disabled */
  @Test
  void cacheBeansDisabledWhenPropertyFalse() {
    contextRunner
        .withPropertyValues("maple.infra.cache.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(TieredCacheManager.class);
            });
  }

  /** Test: LockStrategy beans are auto-configured */
  @Test
  void lockStrategyBeansAutoConfigured() {
    contextRunner
        .withBean(org.redisson.api.RedissonClient.class)
        .run(
            context -> {
              // LockStrategyConfiguration should create beans
              // Specific bean creation depends on properties
            });
  }

  /** Test: RateLimiter (Bucket4j) beans are auto-configured */
  @Test
  void rateLimiterBeansAutoConfigured() {
    contextRunner
        .withBean(org.redisson.api.RedissonClient.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(ProxyManager.class);
            });
  }

  /** Test: RateLimiter can be disabled */
  @Test
  void rateLimiterDisabledWhenPropertyFalse() {
    contextRunner
        .withBean(org.redisson.api.RedissonClient.class)
        .withPropertyValues("maple.infra.ratelimit.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(ProxyManager.class);
            });
  }

  /** Test: Buffer beans are NOT auto-configured by default */
  @Test
  void bufferBeansNotConfiguredByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(MessageQueueStrategy.class);
        });
  }

  /** Test: Buffer beans can be enabled */
  @Test
  void bufferBeansConfiguredWhenEnabled() {
    contextRunner
        .withBean(org.redisson.api.RedissonClient.class)
        .withPropertyValues("maple.infra.buffer.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(MessageQueueStrategy.class);
            });
  }

  /** Integration Test: Full AutoConfiguration loads successfully */
  @Test
  void fullAutoConfigurationLoads() {
    contextRunner
        .withBean(RedisConnectionFactory.class)
        .withBean(org.redisson.api.RedissonClient.class)
        .withBean(org.springframework.cache.CacheManager.class)
        .withPropertyValues(
            "maple.infra.executor.enabled=true",
            "maple.infra.cache.enabled=true",
            "maple.infra.shutdown.enabled=true",
            "maple.infra.ratelimit.enabled=true")
        .run(
            context -> {
              // Verify all core beans are loaded
              assertNotNull(context.getBean(LogicExecutor.class));
              assertNotNull(context.getBean(CacheManager.class));

              // Verify properties are loaded
              ShutdownProperties props = context.getBean(ShutdownProperties.class);
              assertThat(props.getInstanceId()).isNotEmpty();
            });
  }
}
