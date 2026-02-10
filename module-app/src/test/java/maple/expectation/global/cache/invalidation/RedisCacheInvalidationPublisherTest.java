package maple.expectation.global.cache.invalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import maple.expectation.global.cache.invalidation.impl.RedisCacheInvalidationPublisher;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.queue.RedisKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

/**
 * RedisCacheInvalidationPublisher 단위 테스트 (Issue #278)
 *
 * <h3>검증 항목</h3>
 *
 * <ul>
 *   <li>EVICT 이벤트 발행 및 메트릭
 *   <li>CLEAR_ALL 이벤트 발행 및 메트릭
 *   <li>Redis 장애 시 Graceful Degradation
 *   <li>LogicExecutor.executeOrDefault 패턴 준수
 * </ul>
 *
 * <h3>CLAUDE.md Section 25: 경량 테스트</h3>
 *
 * <p>컨테이너 불필요 - Mockito 순수 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisCacheInvalidationPublisher 단위 테스트")
@Tag("unit")
class RedisCacheInvalidationPublisherTest {

  @Mock private RedissonClient redissonClient;

  @Mock private RTopic topic;

  @Mock private LogicExecutor executor;

  private MeterRegistry meterRegistry;
  private RedisCacheInvalidationPublisher publisher;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();

    // P1-3: RTopic 필드 캐싱을 위해 생성자 호출 전에 mock 설정
    when(redissonClient.getTopic(RedisKey.CACHE_INVALIDATION_TOPIC.getKey())).thenReturn(topic);

    // LogicExecutor.executeOrDefault: task 직접 실행 stub
    when(executor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              maple.expectation.global.common.function.ThrowingSupplier<?> task =
                  invocation.getArgument(0);
              try {
                return task.get();
              } catch (Throwable e) {
                return invocation.getArgument(1);
              }
            });

    publisher = new RedisCacheInvalidationPublisher(redissonClient, executor, meterRegistry);
  }

  @Nested
  @DisplayName("EVICT 이벤트 발행")
  class EvictPublish {

    @Test
    @DisplayName("성공 시 success 메트릭 기록")
    void shouldRecordSuccessMetricOnEvictPublish() {
      // Given
      when(topic.publish(any(CacheInvalidationEvent.class))).thenReturn(2L);
      CacheInvalidationEvent event =
          CacheInvalidationEvent.evict("character", "testUser", "instance-1");

      // When
      publisher.publish(event);

      // Then
      verify(topic).publish(any(CacheInvalidationEvent.class));
      double successCount =
          meterRegistry.counter("cache.invalidation.publish", "status", "success").count();
      assertThat(successCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("이벤트 필드 검증 (cacheName, key, type)")
    void shouldPublishEvictEventWithCorrectFields() {
      // Given
      when(topic.publish(any(CacheInvalidationEvent.class))).thenReturn(1L);

      // When
      CacheInvalidationEvent event = CacheInvalidationEvent.evict("character", "testKey", "inst-1");
      publisher.publish(event);

      // Then
      verify(topic)
          .publish(
              argThat(
                  (CacheInvalidationEvent e) ->
                      "character".equals(e.cacheName())
                          && "testKey".equals(e.key())
                          && e.type() == InvalidationType.EVICT
                          && "inst-1".equals(e.sourceInstanceId())
                          && e.timestamp() > 0));
    }
  }

  @Nested
  @DisplayName("CLEAR_ALL 이벤트 발행")
  class ClearAllPublish {

    @Test
    @DisplayName("성공 시 success 메트릭 기록")
    void shouldRecordSuccessMetricOnClearAllPublish() {
      // Given
      when(topic.publish(any(CacheInvalidationEvent.class))).thenReturn(3L);
      CacheInvalidationEvent event = CacheInvalidationEvent.clearAll("character", "instance-1");

      // When
      publisher.publish(event);

      // Then
      verify(topic).publish(any(CacheInvalidationEvent.class));
      double successCount =
          meterRegistry.counter("cache.invalidation.publish", "status", "success").count();
      assertThat(successCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CLEAR_ALL 이벤트: key는 null")
    void shouldPublishClearAllWithNullKey() {
      // Given
      when(topic.publish(any(CacheInvalidationEvent.class))).thenReturn(1L);

      // When
      CacheInvalidationEvent event = CacheInvalidationEvent.clearAll("characterBasic", "inst-2");
      publisher.publish(event);

      // Then
      verify(topic)
          .publish(
              argThat(
                  (CacheInvalidationEvent e) ->
                      "characterBasic".equals(e.cacheName())
                          && e.key() == null
                          && e.type() == InvalidationType.CLEAR_ALL));
    }
  }

  @Nested
  @DisplayName("Graceful Degradation")
  class GracefulDegradation {

    @Test
    @DisplayName("발행 실패(0 clients) 시 failure 메트릭 기록")
    void shouldRecordFailureMetricWhenNoSubscribers() {
      // Given
      when(topic.publish(any(CacheInvalidationEvent.class))).thenReturn(0L);
      CacheInvalidationEvent event =
          CacheInvalidationEvent.evict("character", "testUser", "inst-1");

      // When
      publisher.publish(event);

      // Then
      double failureCount =
          meterRegistry.counter("cache.invalidation.publish", "status", "failure").count();
      assertThat(failureCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 장애 시 예외 전파 없이 기본값 반환")
    void shouldHandleRedisFailureGracefully() {
      // Given: Redis 장애 시뮬레이션
      reset(executor);
      when(executor.executeOrDefault(any(), any(), any())).thenReturn(0L);
      CacheInvalidationEvent event =
          CacheInvalidationEvent.evict("character", "testUser", "inst-1");

      // When
      publisher.publish(event);

      // Then: 예외 없이 failure 메트릭만 기록
      double failureCount =
          meterRegistry.counter("cache.invalidation.publish", "status", "failure").count();
      assertThat(failureCount).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("RedisKey 검증")
  class RedisKeyValidation {

    @Test
    @DisplayName("CACHE_INVALIDATION_TOPIC 키 값 검증")
    void shouldHaveCorrectTopicKey() {
      assertThat(RedisKey.CACHE_INVALIDATION_TOPIC.getKey()).isEqualTo("{cache}:invalidation");
    }

    @Test
    @DisplayName("Hash Tag 분리 검증 (likes와 cache 분리)")
    void shouldHaveSeparateHashTag() {
      assertThat(RedisKey.CACHE_INVALIDATION_TOPIC.getHashTag()).isEqualTo("cache");
      assertThat(RedisKey.LIKE_EVENTS_TOPIC.getHashTag()).isEqualTo("likes");
    }
  }
}
