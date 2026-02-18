package maple.expectation.support;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Comprehensive cleanup utility for chaos test environment.
 *
 * <p>Resets all state between test methods WITHOUT using @DirtiesContext. This avoids expensive
 * Spring Context recreation and dramatically improves test suite performance.
 *
 * <h3>Cleanup Order (Critical):</h3>
 *
 * <ol>
 *   <li><b>Database:</b> Truncate all tables with FK checks disabled
 *   <li><b>L1 Cache (Caffeine):</b> Clear local cache before Redis to prevent repopulation
 *   <li><b>L2 Cache (Redis):</b> Flush selected keys to avoid wiping shared config
 *   <li><b>Circuit Breakers:</b> Reset all Resilience4j state to CLOSED
 * </ol>
 *
 * <h3>Why NOT @DirtiesContext:</h3>
 *
 * <ul>
 *   <li>Context recreation adds 5-10 seconds per test class
 *   <li>Testcontainers with .withReuse(true) become much slower
 *   <li>Manual cleanup is faster, deterministic, and more explicit
 * </ul>
 *
 * @see <a href="https://testcontainers.com/features/reuse/">Testcontainers Reuse</a>
 */
@Slf4j
@Component
public class ChaosTestCleaner {

  private final JdbcTemplate jdbcTemplate;
  private final RedisTemplate<String, Object> redisTemplate;
  private final CacheManager cacheManager;
  private final CircuitBreakerRegistry circuitBreakerRegistry;

  @Autowired
  public ChaosTestCleaner(
      JdbcTemplate jdbcTemplate,
      @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
      @Autowired(required = false) CacheManager cacheManager,
      @Autowired(required = false) CircuitBreakerRegistry circuitBreakerRegistry) {
    this.jdbcTemplate = jdbcTemplate;
    this.redisTemplate = redisTemplate;
    this.cacheManager = cacheManager;
    this.circuitBreakerRegistry = circuitBreakerRegistry;
  }

  /**
   * Perform comprehensive environment cleanup.
   *
   * <p>Call this from @BeforeEach in test classes. Safe to call multiple times - idempotent
   * operations.
   */
  public void cleanAll() {
    cleanDatabase();
    cleanL1Cache();
    cleanRedis();
    resetCircuitBreakers();
    log.debug("[ChaosTestCleaner] Environment reset complete");
  }

  /** Clean all database tables using TRUNCATE with FK checks disabled. */
  public void cleanDatabase() {
    try {
      // Disable FK constraints for faster truncate
      jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");

      // Get all table names and truncate them
      jdbcTemplate
          .queryForList(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
              String.class)
          .forEach(
              table -> {
                try {
                  jdbcTemplate.execute("TRUNCATE TABLE " + table);
                } catch (Exception e) {
                  // Some tables may not support truncate (views, system tables)
                  log.trace("[Cleanup] Skip table {}: {}", table, e.getMessage());
                }
              });

      // Re-enable FK checks
      jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

      log.debug("[Cleanup] Database cleaned (TRUNCATE all tables)");
    } catch (Exception e) {
      log.warn("[Cleanup] Database cleanup failed: {}", e.getMessage());
    }
  }

  /** Clear all Caffeine L1 caches. MUST be called before Redis cleanup. */
  public void cleanL1Cache() {
    try {
      if (cacheManager != null) {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        log.debug("[Cleanup] L1 Cache (Caffeine) cleared");
      }
    } catch (Exception e) {
      log.warn("[Cleanup] L1 Cache cleanup failed: {}", e.getMessage());
    }
  }

  /** Flush Redis database. Use keys() pattern for selective cleanup if needed. */
  public void cleanRedis() {
    try {
      // Option 1: Full flush (simple, fast, no shared config)
      redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

      // Option 2: Selective cleanup by pattern (if shared config exists)
      // Set<String> keys = redisTemplate.keys("test:*");
      // if (keys != null && !keys.isEmpty()) {
      //   redisTemplate.delete(keys);
      // }

      log.debug("[Cleanup] Redis flushed (all keys)");
    } catch (Exception e) {
      log.warn("[Cleanup] Redis cleanup failed: {}", e.getMessage());
    }
  }

  /** Reset all Circuit Breaker states to CLOSED. Critical for stateful circuit tests. */
  public void resetCircuitBreakers() {
    try {
      circuitBreakerRegistry
          .getAllCircuitBreakers()
          .forEach(
              cb -> {
                // Reset to initial state (CLOSED for closed circuit, or reset failure count)
                cb.reset();
                // Force transition to closed if circuit was opened
                if (cb.getState() != CircuitBreaker.State.CLOSED) {
                  cb.transitionToClosedState();
                }
              });

      log.debug("[Cleanup] Circuit Breakers reset (CLOSED)");
    } catch (Exception e) {
      log.warn("[Cleanup] Circuit Breaker reset failed: {}", e.getMessage());
    }
  }

  /**
   * Clean only cache layers (L1 + L2). Useful for tests that only dirty cache state.
   *
   * <p>Note: Order matters - L1 before L2 to prevent repopulation from L2.
   */
  public void cleanCacheOnly() {
    cleanL1Cache();
    cleanRedis();
  }
}
