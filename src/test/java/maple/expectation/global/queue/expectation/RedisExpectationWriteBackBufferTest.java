package maple.expectation.global.queue.expectation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.global.queue.QueueMessage;
import maple.expectation.global.queue.strategy.RedisBufferStrategy;
import maple.expectation.service.v4.buffer.ExpectationWriteTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RedisExpectationWriteBackBuffer 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): Mock 기반 단위 테스트로 빠른 피드백
 *   <li>Purple (Auditor): ACK/NACK 패턴 검증
 *   <li>Blue (Architect): 기존 API 호환성 검증
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisExpectationWriteBackBuffer 단위 테스트")
class RedisExpectationWriteBackBufferTest {

  @Mock private RedisBufferStrategy<ExpectationWriteTask> redisStrategy;

  private SimpleMeterRegistry meterRegistry;
  private RedisExpectationWriteBackBuffer buffer;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();

    // 기본 Mock 설정
    lenient().when(redisStrategy.isShuttingDown()).thenReturn(false);
    lenient().when(redisStrategy.getPendingCount()).thenReturn(0L);
    lenient().when(redisStrategy.getInflightCount()).thenReturn(0L);
    lenient().when(redisStrategy.getRetryCount()).thenReturn(0L);
    lenient().when(redisStrategy.getDlqCount()).thenReturn(0L);

    buffer = new RedisExpectationWriteBackBuffer(redisStrategy, meterRegistry);
  }

  @Nested
  @DisplayName("offer() 테스트")
  class OfferTest {

    @Test
    @DisplayName("정상 발행 - 모든 프리셋 성공")
    void offer_shouldPublishAllPresets() {
      // Given
      List<PresetExpectation> presets = createTestPresets();
      when(redisStrategy.publish(any(ExpectationWriteTask.class)))
          .thenReturn(UUID.randomUUID().toString());

      // When
      boolean result = buffer.offer(1L, presets);

      // Then
      assertThat(result).isTrue();
      verify(redisStrategy, times(presets.size())).publish(any(ExpectationWriteTask.class));
    }

    @Test
    @DisplayName("Shutdown 중 발행 거부")
    void offer_shouldRejectDuringShutdown() {
      // Given
      when(redisStrategy.isShuttingDown()).thenReturn(true);
      List<PresetExpectation> presets = createTestPresets();

      // When
      boolean result = buffer.offer(1L, presets);

      // Then
      assertThat(result).isFalse();
      verify(redisStrategy, never()).publish(any());
    }

    @Test
    @DisplayName("일부 발행 실패 - false 반환")
    void offer_shouldReturnFalseOnPartialFailure() {
      // Given
      List<PresetExpectation> presets = createTestPresets();
      when(redisStrategy.publish(any(ExpectationWriteTask.class)))
          .thenReturn(UUID.randomUUID().toString())
          .thenReturn(null); // 두 번째 실패

      // When
      boolean result = buffer.offer(1L, presets);

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("drain() 테스트")
  class DrainTest {

    @Test
    @DisplayName("정상 소비 - Task 목록 반환")
    void drain_shouldReturnTasks() {
      // Given
      ExpectationWriteTask task = createTestTask();
      QueueMessage<ExpectationWriteTask> message =
          new QueueMessage<>("msg-1", task, 0, Instant.now());
      when(redisStrategy.consume(10)).thenReturn(List.of(message));

      // When
      List<ExpectationWriteTask> result = buffer.drain(10);

      // Then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).characterId()).isEqualTo(task.characterId());
    }

    @Test
    @DisplayName("빈 큐 - 빈 리스트 반환")
    void drain_shouldReturnEmptyListWhenEmpty() {
      // Given
      when(redisStrategy.consume(anyInt())).thenReturn(List.of());

      // When
      List<ExpectationWriteTask> result = buffer.drain(10);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("ackAll() 테스트")
  class AckAllTest {

    @Test
    @DisplayName("drain() 후 모든 메시지 ACK")
    void ackAll_shouldAckAllDrainedMessages() {
      // Given
      ExpectationWriteTask task = createTestTask();
      QueueMessage<ExpectationWriteTask> message1 =
          new QueueMessage<>("msg-1", task, 0, Instant.now());
      QueueMessage<ExpectationWriteTask> message2 =
          new QueueMessage<>("msg-2", task, 0, Instant.now());
      when(redisStrategy.consume(10)).thenReturn(List.of(message1, message2));

      buffer.drain(10); // INFLIGHT에 추가

      // When
      buffer.ackAll();

      // Then
      verify(redisStrategy).ack("msg-1");
      verify(redisStrategy).ack("msg-2");
    }

    @Test
    @DisplayName("drain() 없이 호출 시 아무 작업 없음")
    void ackAll_shouldDoNothingWhenNoDrain() {
      // When
      buffer.ackAll();

      // Then
      verify(redisStrategy, never()).ack(any());
    }
  }

  @Nested
  @DisplayName("nackAll() 테스트")
  class NackAllTest {

    @Test
    @DisplayName("drain() 후 모든 메시지 NACK")
    void nackAll_shouldNackAllDrainedMessages() {
      // Given
      ExpectationWriteTask task = createTestTask();
      QueueMessage<ExpectationWriteTask> message =
          new QueueMessage<>("msg-1", task, 0, Instant.now());
      when(redisStrategy.consume(10)).thenReturn(List.of(message));

      buffer.drain(10);

      // When
      buffer.nackAll();

      // Then
      verify(redisStrategy).nack("msg-1", 0);
    }
  }

  @Nested
  @DisplayName("Shutdown 테스트")
  class ShutdownTest {

    @Test
    @DisplayName("prepareShutdown() - 전략에 위임")
    void prepareShutdown_shouldDelegateToStrategy() {
      // When
      buffer.prepareShutdown();

      // Then
      verify(redisStrategy).prepareShutdown();
    }

    @Test
    @DisplayName("isShuttingDown() - 전략에서 상태 조회")
    void isShuttingDown_shouldDelegateToStrategy() {
      // Given
      when(redisStrategy.isShuttingDown()).thenReturn(true);

      // When
      boolean result = buffer.isShuttingDown();

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("awaitPendingOffers() - Redis 기반이므로 항상 true")
    void awaitPendingOffers_shouldReturnTrue() {
      // When
      boolean result = buffer.awaitPendingOffers(Duration.ofSeconds(5));

      // Then
      assertThat(result).isTrue();
    }
  }

  @Nested
  @DisplayName("카운트 조회 테스트")
  class CountTest {

    @Test
    @DisplayName("getPendingCount() - 전략에 위임")
    void getPendingCount_shouldDelegateToStrategy() {
      // Given
      when(redisStrategy.getPendingCount()).thenReturn(5L);

      // When
      int result = buffer.getPendingCount();

      // Then
      assertThat(result).isEqualTo(5);
    }

    @Test
    @DisplayName("isEmpty() - pending과 inflight 모두 0이면 true")
    void isEmpty_shouldReturnTrueWhenBothZero() {
      // Given
      when(redisStrategy.getPendingCount()).thenReturn(0L);
      when(redisStrategy.getInflightCount()).thenReturn(0L);

      // When
      boolean result = buffer.isEmpty();

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isEmpty() - inflight가 있으면 false")
    void isEmpty_shouldReturnFalseWhenInflight() {
      // Given
      when(redisStrategy.getPendingCount()).thenReturn(0L);
      when(redisStrategy.getInflightCount()).thenReturn(3L);

      // When
      boolean result = buffer.isEmpty();

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("Re-drive & Retry 테스트")
  class RedriveRetryTest {

    @Test
    @DisplayName("만료된 INFLIGHT 메시지 복구")
    void redriveExpiredMessages_shouldRedriveExpired() {
      // Given
      when(redisStrategy.getExpiredInflightMessages(60000L, 100))
          .thenReturn(List.of("msg-1", "msg-2"));
      when(redisStrategy.redrive("msg-1")).thenReturn(true);
      when(redisStrategy.redrive("msg-2")).thenReturn(true);

      // When
      int redriven = buffer.redriveExpiredMessages(60000L, 100);

      // Then
      assertThat(redriven).isEqualTo(2);
      verify(redisStrategy).redrive("msg-1");
      verify(redisStrategy).redrive("msg-2");
    }

    @Test
    @DisplayName("Retry Queue 처리")
    void processRetryQueue_shouldProcessRetry() {
      // Given
      when(redisStrategy.processRetryQueue(50)).thenReturn(List.of("msg-1", "msg-2", "msg-3"));

      // When
      int processed = buffer.processRetryQueue(50);

      // Then
      assertThat(processed).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Health Check 테스트")
  class HealthCheckTest {

    @Test
    @DisplayName("isHealthy() - 전략에 위임")
    void isHealthy_shouldDelegateToStrategy() {
      // Given
      when(redisStrategy.isHealthy()).thenReturn(true);

      // When
      boolean result = buffer.isHealthy();

      // Then
      assertThat(result).isTrue();
    }
  }

  // ==================== Test Helpers ====================

  private List<PresetExpectation> createTestPresets() {
    CostBreakdownDto breakdown =
        CostBreakdownDto.builder()
            .blackCubeCost(BigDecimal.valueOf(1000))
            .redCubeCost(BigDecimal.valueOf(500))
            .additionalCubeCost(BigDecimal.valueOf(300))
            .starforceCost(BigDecimal.valueOf(200))
            .build();

    PresetExpectation preset1 =
        PresetExpectation.builder()
            .presetNo(1)
            .totalExpectedCost(BigDecimal.valueOf(2000))
            .costBreakdown(breakdown)
            .build();

    PresetExpectation preset2 =
        PresetExpectation.builder()
            .presetNo(2)
            .totalExpectedCost(BigDecimal.valueOf(3000))
            .costBreakdown(breakdown)
            .build();

    return List.of(preset1, preset2);
  }

  private ExpectationWriteTask createTestTask() {
    return new ExpectationWriteTask(
        1L,
        1,
        BigDecimal.valueOf(2000),
        BigDecimal.valueOf(1000),
        BigDecimal.valueOf(500),
        BigDecimal.valueOf(300),
        BigDecimal.valueOf(200),
        LocalDateTime.now());
  }
}
