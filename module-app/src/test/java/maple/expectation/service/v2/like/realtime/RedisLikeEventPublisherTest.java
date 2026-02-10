package maple.expectation.service.v2.like.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.queue.RedisKey;
import maple.expectation.service.v2.like.realtime.dto.LikeEvent;
import maple.expectation.service.v2.like.realtime.impl.RedisLikeEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RedisLikeEventPublisher 단위 테스트
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴 준수 검증</h3>
 *
 * <ul>
 *   <li>executeOrDefault 패턴 사용
 *   <li>Graceful Degradation: 장애 시 기본값 반환
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisLikeEventPublisher 단위 테스트")
class RedisLikeEventPublisherTest {

  @Mock private RedissonClient redissonClient;

  @Mock private RTopic topic;

  @Mock private LogicExecutor executor;

  private MeterRegistry meterRegistry;
  private RedisLikeEventPublisher publisher;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();

    // executeOrDefault를 호출하면 task를 직접 실행하도록 설정
    when(executor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              try {
                maple.expectation.global.common.function.ThrowingSupplier<?> task =
                    invocation.getArgument(0);
                return task.get();
              } catch (Throwable e) {
                return invocation.getArgument(1); // defaultValue 반환
              }
            });

    publisher = new RedisLikeEventPublisher(redissonClient, executor, meterRegistry);
    ReflectionTestUtils.setField(publisher, "instanceId", "test-instance");

    when(redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.getKey())).thenReturn(topic);
  }

  @Test
  @DisplayName("LIKE 이벤트 발행 성공 시 메트릭 기록")
  void shouldRecordSuccessMetricOnPublish() {
    // Given
    when(topic.publish(any(LikeEvent.class))).thenReturn(2L); // 2 clients received

    // When
    publisher.publishLike("testUser", 5L);

    // Then
    verify(topic).publish(any(LikeEvent.class));
    double successCount = meterRegistry.counter("like.event.publish", "status", "success").count();
    assertThat(successCount).isEqualTo(1.0);
  }

  @Test
  @DisplayName("UNLIKE 이벤트 발행 성공")
  void shouldPublishUnlikeEvent() {
    // Given
    when(topic.publish(any(LikeEvent.class))).thenReturn(1L);

    // When
    publisher.publishUnlike("testUser", -3L);

    // Then
    verify(topic)
        .publish(
            argThat(
                (LikeEvent event) ->
                    event.eventType() == LikeEvent.EventType.UNLIKE && event.newDelta() == -3L));
  }

  @Test
  @DisplayName("발행 실패(0 clients) 시 failure 메트릭 기록")
  void shouldRecordFailureMetricWhenNoSubscribers() {
    // Given
    when(topic.publish(any(LikeEvent.class))).thenReturn(0L); // No subscribers

    // When
    publisher.publishLike("testUser", 1L);

    // Then
    double failureCount = meterRegistry.counter("like.event.publish", "status", "failure").count();
    assertThat(failureCount).isEqualTo(1.0);
  }

  @Test
  @DisplayName("발행 시 올바른 instanceId 포함")
  void shouldIncludeCorrectInstanceId() {
    // Given
    when(topic.publish(any(LikeEvent.class))).thenReturn(1L);

    // When
    publisher.publishLike("testUser", 10L);

    // Then
    verify(topic)
        .publish(argThat((LikeEvent event) -> "test-instance".equals(event.sourceInstanceId())));
  }

  @Test
  @DisplayName("Redis 장애 시 Graceful Degradation - 메트릭만 기록하고 예외 전파 없음")
  void shouldHandleRedisFailureGracefully() {
    // Given: Redis 장애 시뮬레이션 - executeOrDefault가 기본값 0L 반환
    reset(executor); // setUp에서 설정한 stubbing 초기화
    when(executor.executeOrDefault(any(), any(), any())).thenReturn(0L);

    // When
    publisher.publishLike("testUser", 5L);

    // Then: 예외 없이 failure 메트릭만 기록
    double failureCount = meterRegistry.counter("like.event.publish", "status", "failure").count();
    assertThat(failureCount).isEqualTo(1.0);
  }
}
