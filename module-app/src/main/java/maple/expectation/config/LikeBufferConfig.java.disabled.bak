package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.port.LikeBufferStrategy;
import maple.expectation.application.port.LikeRelationBufferStrategy;
import maple.expectation.application.port.PersistenceTrackerStrategy;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.queue.like.AtomicLikeToggleExecutor;
import maple.expectation.infrastructure.queue.like.PartitionedFlushStrategy;
import maple.expectation.infrastructure.queue.like.RedisLikeBufferStorage;
import maple.expectation.infrastructure.queue.like.RedisLikeRelationBuffer;
import maple.expectation.infrastructure.queue.persistence.RedisEquipmentPersistenceTracker;
import maple.expectation.service.v2.LikeSyncExecutor;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Stateless 버퍼 설정 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 *
 * <p>Feature Flag 기반으로 In-Memory 또는 Redis 버퍼를 선택합니다.
 *
 * <h3>관리 대상</h3>
 *
 * <ul>
 *   <li>LikeBufferStrategy: Like 버퍼 전략
 *   <li>PersistenceTrackerStrategy: Equipment 저장 추적 전략
 * </ul>
 *
 * <h3>활성화 조건</h3>
 *
 * <ul>
 *   <li>{@code app.buffer.redis.enabled=true} (기본): Redis HINCRBY (Scale-out)
 *   <li>{@code app.buffer.redis.enabled=false}: In-Memory Caffeine (단일 인스턴스/테스트)
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Feature Flag로 점진적 마이그레이션
 *   <li>Red (SRE): 운영 중 롤백 가능
 *   <li>Yellow (QA): 테스트에서 In-Memory 사용 가능
 * </ul>
 */
@Slf4j
@Configuration
public class LikeBufferConfig {

  /**
   * Redis 기반 Like 버퍼 전략 빈
   *
   * <p>{@code app.buffer.redis.enabled=true} 일 때만 활성화됩니다. @Primary로 기존 LikeBufferStorage 빈을 대체합니다.
   *
   * <p>In-Memory 모드(기본)에서는 LikeBufferStorage가 @Component로 자동 등록되어 LikeBufferStrategy 타입으로 주입됩니다.
   */
  @Bean
  @Primary
  @ConditionalOnProperty(
      name = "app.buffer.redis.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public LikeBufferStrategy redisLikeBufferStrategy(
      RedissonClient redissonClient, LogicExecutor executor, MeterRegistry meterRegistry) {

    log.info("[LikeBufferConfig] Redis Like Buffer ENABLED - Scale-out mode");
    return new RedisLikeBufferStorage(redissonClient, executor, meterRegistry);
  }

  /**
   * Redis 기반 Like Relation 버퍼 전략 빈
   *
   * <p>{@code app.buffer.redis.enabled=true} 일 때만 활성화됩니다. @Primary로 기존 LikeRelationBuffer 빈을 대체합니다.
   *
   * <p>In-Memory 모드(기본)에서는 LikeRelationBuffer가 @Component로 자동 등록되어 LikeRelationBufferStrategy 타입으로
   * 주입됩니다.
   */
  @Bean
  @Primary
  @ConditionalOnProperty(
      name = "app.buffer.redis.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public LikeRelationBufferStrategy redisLikeRelationBufferStrategy(
      RedissonClient redissonClient, LogicExecutor executor, MeterRegistry meterRegistry) {

    log.info("[LikeBufferConfig] Redis Like Relation Buffer ENABLED - Scale-out mode");
    return new RedisLikeRelationBuffer(redissonClient, executor, meterRegistry);
  }

  /**
   * Partitioned Flush 전략 빈 (P0-10: Flush Race 해결)
   *
   * <p>Redis 버퍼 활성화 시에만 의미있습니다. LikeSyncScheduler에서 분산 환경 DB 동기화에 사용됩니다.
   */
  @Bean
  @ConditionalOnProperty(
      name = "app.buffer.redis.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PartitionedFlushStrategy partitionedFlushStrategy(
      RedissonClient redissonClient,
      LikeBufferStrategy bufferStrategy,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      LikeSyncExecutor syncExecutor) {

    if (!(bufferStrategy instanceof RedisLikeBufferStorage redisBuffer)) {
      throw new IllegalStateException(
          "PartitionedFlushStrategy requires RedisLikeBufferStorage but got "
              + bufferStrategy.getClass());
    }

    log.info("[LikeBufferConfig] Partitioned Flush Strategy ENABLED");
    return new PartitionedFlushStrategy(
        redissonClient, redisBuffer, executor, meterRegistry, syncExecutor);
  }

  /**
   * Atomic Like Toggle 실행기 (P0-1/P0-2/P0-3 해결)
   *
   * <p>Lua Script로 SISMEMBER + SADD/SREM + HINCRBY를 원자적으로 수행. Redis 모드에서만 활성화됩니다.
   */
  @Bean
  @ConditionalOnProperty(
      name = "app.buffer.redis.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public AtomicLikeToggleExecutor atomicLikeToggleExecutor(
      RedissonClient redissonClient, LogicExecutor executor, MeterRegistry meterRegistry) {

    log.info("[LikeBufferConfig] Atomic Like Toggle Executor ENABLED");
    return new AtomicLikeToggleExecutor(redissonClient, executor, meterRegistry);
  }

  // ==================== Persistence Tracker Strategy ====================

  /**
   * Redis 기반 Persistence Tracker 전략 빈
   *
   * <p>{@code app.buffer.redis.enabled=true} 일 때만 활성화됩니다. @Primary로 기존 EquipmentPersistenceTracker
   * 빈을 대체합니다.
   *
   * <p>In-Memory 모드(기본)에서는 EquipmentPersistenceTracker가 @Component로 자동 등록되어
   * PersistenceTrackerStrategy 타입으로 주입됩니다.
   */
  @Bean
  @Primary
  @ConditionalOnProperty(
      name = "app.buffer.redis.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PersistenceTrackerStrategy redisPersistenceTrackerStrategy(
      RedissonClient redissonClient, LogicExecutor executor, MeterRegistry meterRegistry) {

    log.info("[LikeBufferConfig] Redis Persistence Tracker ENABLED - Scale-out mode");
    return new RedisEquipmentPersistenceTracker(redissonClient, executor, meterRegistry);
  }
}
