package maple.expectation.global.queue.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.QueueMessage;
import maple.expectation.global.queue.QueueType;
import maple.expectation.global.queue.script.BufferLuaScriptProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.redisson.api.RBucket;
import org.redisson.api.RDeque;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/**
 * RedisBufferStrategy 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): Mock 기반 단위 테스트로 빠른 피드백
 *   <li>Purple (Auditor): 핵심 로직 검증 (publish, consume, ack, nack)
 *   <li>Blue (Architect): 전략 패턴 계약 준수 검증
 * </ul>
 *
 * <h3>참고</h3>
 *
 * <p>Redis 실제 연동 통합 테스트는 Testcontainers를 사용하여 별도 IT 클래스에서 수행합니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisBufferStrategy 단위 테스트")
class RedisBufferStrategyTest {

  @Mock private RedissonClient redissonClient;

  @Mock private BufferLuaScriptProvider scriptProvider;

  @Mock private LogicExecutor executor;

  @Mock private RScript rScript;

  @Mock private RBucket<Object> rBucket;

  @Mock private RMap<String, String> rMap;

  @Mock private RDeque<String> rDeque;

  private ObjectMapper objectMapper;
  private SimpleMeterRegistry meterRegistry;
  private RedisBufferStrategy<TestMessage> buffer;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    meterRegistry = new SimpleMeterRegistry();

    // Redisson Mocks
    setupRedissonMocks();

    // LogicExecutor Mocks
    setupExecutorMocks();

    buffer =
        new RedisBufferStrategy<>(
            redissonClient,
            scriptProvider,
            objectMapper,
            executor,
            meterRegistry,
            TestMessage.class,
            3 // maxRetries
            );
  }

  @SuppressWarnings("unchecked")
  private void setupRedissonMocks() {
    lenient().when(redissonClient.getScript(any(Codec.class))).thenReturn(rScript);
    lenient().when(redissonClient.getBucket(anyString())).thenReturn(rBucket);
    lenient().when(redissonClient.getMap(anyString(), any(Codec.class))).thenReturn((RMap) rMap);
    lenient()
        .when(redissonClient.getDeque(anyString(), any(Codec.class)))
        .thenReturn((RDeque) rDeque);
    lenient().when(rBucket.isExists()).thenReturn(true);

    // ScriptProvider Mock - 직접 함수 실행
    lenient()
        .when(
            scriptProvider.executeWithNoscriptHandling(
                any(Supplier.class),
                anyString(),
                any(Consumer.class),
                any(Function.class),
                anyString()))
        .thenAnswer(
            invocation -> {
              Function<String, ?> scriptExecutor = invocation.getArgument(3);
              return scriptExecutor.apply("mock-sha");
            });
  }

  @SuppressWarnings("unchecked")
  private void setupExecutorMocks() {
    // executeOrDefault - 람다 직접 실행
    lenient()
        .when(executor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  ThrowingSupplier<?> task = invocation.getArgument(0);
                  Object defaultValue = invocation.getArgument(1);
                  try {
                    return task.get();
                  } catch (Exception e) {
                    return defaultValue;
                  }
                });

    // executeWithFallback - 람다 직접 실행
    lenient()
        .when(
            executor.executeWithFallback(
                any(ThrowingSupplier.class), any(Function.class), any(TaskContext.class)))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  ThrowingSupplier<?> task = invocation.getArgument(0);
                  Function<Throwable, ?> fallback = invocation.getArgument(1);
                  try {
                    return task.get();
                  } catch (Exception e) {
                    return fallback.apply(e);
                  }
                });
  }

  @Nested
  @DisplayName("publish() 테스트")
  class PublishTest {

    @Test
    @DisplayName("정상 publish - msgId 반환")
    void publish_shouldReturnMsgId() {
      // Given
      TestMessage message = new TestMessage(1L, "test");
      // evalSha(Mode, sha, ReturnType, keys, args...) - varargs 처리
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              any(),
              any()))
          .thenReturn(1L);

      // When
      String msgId = buffer.publish(message);

      // Then
      assertThat(msgId).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("Shutdown 중 - null 반환")
    void publish_shouldReturnNullDuringShutdown() {
      // Given
      buffer.prepareShutdown();

      // When
      String msgId = buffer.publish(new TestMessage(1L, "test"));

      // Then
      assertThat(msgId).isNull();
      assertThat(buffer.isShuttingDown()).isTrue();
    }
  }

  @Nested
  @DisplayName("consume() 테스트")
  class ConsumeTest {

    @Test
    @DisplayName("정상 consume - QueueMessage 리스트 반환")
    void consume_shouldReturnQueueMessages() {
      // Given: Lua Script가 [[msgId1, payloadJson1]] 형태로 반환
      String msgId = "test-msg-id";
      String payloadJson =
          "{\"payload\":{\"id\":1,\"content\":\"test\"},\"retryCount\":0,\"createdAtMs\":1700000000000}";

      List<List<String>> rawResult = List.of(Arrays.asList(msgId, payloadJson));

      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              any(),
              any()))
          .thenReturn(rawResult);

      // When
      List<QueueMessage<TestMessage>> batch = buffer.consume(10);

      // Then
      assertThat(batch).hasSize(1);
      assertThat(batch.get(0).msgId()).isEqualTo(msgId);
      assertThat(batch.get(0).payload().id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("빈 큐에서 consume - 빈 리스트 반환")
    void consume_shouldReturnEmptyListWhenQueueEmpty() {
      // Given
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              any(),
              any()))
          .thenReturn(List.of());

      // When
      List<QueueMessage<TestMessage>> batch = buffer.consume(10);

      // Then
      assertThat(batch).isEmpty();
    }
  }

  @Nested
  @DisplayName("ack() 테스트")
  class AckTest {

    @Test
    @DisplayName("정상 ack - 성공 (removed > 0)")
    void ack_shouldSucceed() {
      // Given
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(1L);

      // When & Then: 예외 없이 수행
      buffer.ack("test-msg-id");
    }

    @Test
    @DisplayName("멱등성 - 이미 ACK된 메시지 (removed == 0)")
    void ack_shouldBeIdempotent() {
      // Given
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(0L);

      // When & Then: 예외 없이 수행
      buffer.ack("already-acked-msg-id");
    }
  }

  @Nested
  @DisplayName("nack() 테스트")
  class NackTest {

    @Test
    @DisplayName("재시도 가능 - Retry Queue로 이동")
    void nack_shouldMoveToRetryQueue() {
      // Given: retryCount < maxRetries(3)
      String msgId = "test-msg-id";
      String payloadJson =
          "{\"payload\":{\"id\":1,\"content\":\"test\"},\"retryCount\":0,\"createdAtMs\":1700000000000}";

      when(rMap.get(msgId)).thenReturn(payloadJson);
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              any()))
          .thenReturn(1L);

      // When & Then: 예외 없이 수행
      buffer.nack(msgId, 0);
    }

    @Test
    @DisplayName("최대 재시도 초과 - DLQ로 이동")
    void nack_shouldMoveToDlqWhenMaxRetriesExceeded() {
      // Given: retryCount >= maxRetries(3)
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(1L);

      // When & Then: 예외 없이 수행
      buffer.nack("test-msg-id", 3);
    }
  }

  @Nested
  @DisplayName("Redrive 테스트")
  class RedriveTest {

    @Test
    @DisplayName("정상 Redrive - true 반환")
    void redrive_shouldReturnTrue() {
      // Given
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(1L);

      // When
      boolean result = buffer.redrive("test-msg-id");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("이미 ACK된 메시지 - false 반환")
    void redrive_shouldReturnFalseWhenAlreadyAcked() {
      // Given
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(0L);

      // When
      boolean result = buffer.redrive("already-acked-msg-id");

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("상태 조회 테스트")
  class StateTest {

    @Test
    @DisplayName("getType() - REDIS_LIST 반환")
    void getType_shouldReturnRedisList() {
      assertThat(buffer.getType()).isEqualTo(QueueType.REDIS_LIST);
    }

    @Test
    @DisplayName("isHealthy() - 정상 상태 true")
    void isHealthy_shouldReturnTrue() {
      assertThat(buffer.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("isHealthy() - Shutdown 중 false")
    void isHealthy_shouldReturnFalseDuringShutdown() {
      buffer.prepareShutdown();
      assertThat(buffer.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("getMaxRetries() - 설정값 반환")
    void getMaxRetries_shouldReturnConfiguredValue() {
      assertThat(buffer.getMaxRetries()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Retry Queue 테스트")
  class RetryQueueTest {

    @Test
    @DisplayName("processRetryQueue - 처리된 메시지 목록 반환")
    void processRetryQueue_shouldReturnProcessedList() {
      // Given
      List<String> processedIds = List.of("msg1", "msg2");
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              any(),
              any()))
          .thenReturn(processedIds);

      // When
      List<String> result = buffer.processRetryQueue(10);

      // Then
      assertThat(result).hasSize(2);
    }
  }

  @Nested
  @DisplayName("만료 INFLIGHT 조회 테스트")
  class ExpiredInflightTest {

    @Test
    @DisplayName("getExpiredInflightMessages - 만료 목록 반환")
    void getExpiredInflightMessages_shouldReturnExpiredList() {
      // Given
      List<String> expiredIds = List.of("expired-msg-1", "expired-msg-2");
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              any(),
              any()))
          .thenReturn(expiredIds);

      // When
      List<String> result = buffer.getExpiredInflightMessages(5000L, 10);

      // Then
      assertThat(result).hasSize(2);
    }
  }

  /** 테스트용 메시지 */
  record TestMessage(Long id, String content) {}
}
