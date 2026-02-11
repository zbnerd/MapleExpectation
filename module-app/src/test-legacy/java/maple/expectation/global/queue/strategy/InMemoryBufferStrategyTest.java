package maple.expectation.global.queue.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import maple.expectation.global.queue.QueueMessage;
import maple.expectation.global.queue.QueueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * InMemoryBufferStrategy 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): CyclicBarrier로 동기화, Thread.sleep 최소화
 *   <li>Purple (Auditor): msgId 기반 ACK, DLQ 무결성 검증
 *   <li>Green (Performance): 동시성 테스트 (5 threads × 100 tasks)
 * </ul>
 */
@DisplayName("InMemoryBufferStrategy 단위 테스트")
class InMemoryBufferStrategyTest {

  private InMemoryBufferStrategy<TestMessage> buffer;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    buffer = new InMemoryBufferStrategy<>(meterRegistry, 3, 100);
  }

  @Nested
  @DisplayName("publish() 테스트")
  class PublishTest {

    @Test
    @DisplayName("정상 publish - msgId 반환")
    void publish_shouldReturnMsgId() {
      // Given
      TestMessage message = new TestMessage(1L, "test");

      // When
      String msgId = buffer.publish(message);

      // Then
      assertThat(msgId).isNotNull().isNotBlank();
      assertThat(buffer.getPendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("백프레셔 - 큐 가득 찼을 때 null 반환")
    void publish_shouldReturnNullWhenFull() {
      // Given: 큐를 가득 채움
      for (int i = 0; i < 100; i++) {
        buffer.publish(new TestMessage((long) i, "test" + i));
      }

      // When: 추가 publish 시도
      String msgId = buffer.publish(new TestMessage(999L, "overflow"));

      // Then
      assertThat(msgId).isNull();
      assertThat(buffer.getPendingCount()).isEqualTo(100);
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
    @DisplayName("정상 consume - INFLIGHT로 이동")
    void consume_shouldMoveToInflight() {
      // Given
      String msgId = buffer.publish(new TestMessage(1L, "test"));

      // When
      List<QueueMessage<TestMessage>> batch = buffer.consume(10);

      // Then
      assertThat(batch).hasSize(1);
      assertThat(batch.get(0).msgId()).isEqualTo(msgId);
      assertThat(buffer.getPendingCount()).isZero();
      assertThat(buffer.getInflightCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("배치 크기만큼 consume")
    void consume_shouldRespectBatchSize() {
      // Given: 10개 publish
      for (int i = 0; i < 10; i++) {
        buffer.publish(new TestMessage((long) i, "test" + i));
      }

      // When: batchSize=5로 consume
      List<QueueMessage<TestMessage>> batch = buffer.consume(5);

      // Then
      assertThat(batch).hasSize(5);
      assertThat(buffer.getPendingCount()).isEqualTo(5);
      assertThat(buffer.getInflightCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("빈 큐에서 consume - 빈 리스트 반환")
    void consume_shouldReturnEmptyListWhenQueueEmpty() {
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
    @DisplayName("정상 ack - INFLIGHT에서 제거")
    void ack_shouldRemoveFromInflight() {
      // Given
      String msgId = buffer.publish(new TestMessage(1L, "test"));
      buffer.consume(10);
      assertThat(buffer.getInflightCount()).isEqualTo(1);

      // When
      buffer.ack(msgId);

      // Then
      assertThat(buffer.getInflightCount()).isZero();
    }

    @Test
    @DisplayName("멱등성 - 이미 ACK된 메시지 재ACK")
    void ack_shouldBeIdempotent() {
      // Given
      String msgId = buffer.publish(new TestMessage(1L, "test"));
      buffer.consume(10);
      buffer.ack(msgId);

      // When: 재ACK
      buffer.ack(msgId);

      // Then: 오류 없이 처리
      assertThat(buffer.getInflightCount()).isZero();
    }
  }

  @Nested
  @DisplayName("nack() 테스트")
  class NackTest {

    @Test
    @DisplayName("정상 nack - Main Queue로 복귀")
    void nack_shouldReturnToMainQueue() {
      // Given
      String msgId = buffer.publish(new TestMessage(1L, "test"));
      buffer.consume(10);

      // When: retryCount=0으로 nack (maxRetries=3 미만)
      buffer.nack(msgId, 0);

      // Then: Main Queue로 복귀
      assertThat(buffer.getInflightCount()).isZero();
      assertThat(buffer.getPendingCount()).isEqualTo(1);
      assertThat(buffer.getDlqCount()).isZero();
    }

    @Test
    @DisplayName("최대 재시도 초과 - DLQ로 이동")
    void nack_shouldMoveToDlqWhenMaxRetriesExceeded() {
      // Given
      String msgId = buffer.publish(new TestMessage(1L, "test"));
      buffer.consume(10);

      // When: retryCount=3으로 nack (maxRetries=3 이상)
      buffer.nack(msgId, 3);

      // Then: DLQ로 이동
      assertThat(buffer.getInflightCount()).isZero();
      assertThat(buffer.getPendingCount()).isZero();
      assertThat(buffer.getDlqCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DLQ 메시지 조회")
    void pollDlq_shouldReturnDlqMessages() {
      // Given: DLQ로 이동
      String msgId = buffer.publish(new TestMessage(1L, "test"));
      buffer.consume(10);
      buffer.nack(msgId, 3);

      // When
      List<QueueMessage<TestMessage>> dlqMessages = buffer.pollDlq(10);

      // Then
      assertThat(dlqMessages).hasSize(1);
      assertThat(dlqMessages.get(0).msgId()).isEqualTo(msgId);
      assertThat(buffer.getDlqCount()).isZero();
    }
  }

  @Nested
  @DisplayName("동시성 테스트")
  class ConcurrencyTest {

    @Test
    @DisplayName("동시 publish - 데이터 무결성")
    void concurrentPublish_shouldMaintainIntegrity() throws Exception {
      // Given
      int threadCount = 5;
      int messagesPerThread = 100;
      CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);
      Set<String> publishedIds = ConcurrentHashMap.newKeySet();

      // 큰 버퍼 사용
      buffer = new InMemoryBufferStrategy<>(meterRegistry, 3, 10000);

      ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

      // When
      for (int t = 0; t < threadCount; t++) {
        int threadId = t;
        executorService.submit(
            () -> {
              try {
                barrier.await();
                for (int i = 0; i < messagesPerThread; i++) {
                  long id = (long) threadId * 1000 + i;
                  String msgId = buffer.publish(new TestMessage(id, "msg" + id));
                  if (msgId != null) {
                    publishedIds.add(msgId);
                  }
                }
              } catch (Exception e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      barrier.await(); // 동시 시작
      executorService.shutdown();
      executorService.awaitTermination(10, TimeUnit.SECONDS);

      // Then
      int expectedTotal = threadCount * messagesPerThread;
      assertThat(publishedIds).hasSize(expectedTotal);
      assertThat(buffer.getPendingCount()).isEqualTo(expectedTotal);
    }

    @Test
    @DisplayName("동시 consume/ack - 중복 없음")
    void concurrentConsumeAck_shouldNotDuplicate() throws Exception {
      // Given: 100개 메시지 publish
      for (int i = 0; i < 100; i++) {
        buffer.publish(new TestMessage((long) i, "test" + i));
      }

      int threadCount = 5;
      CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);
      Set<String> processedIds = ConcurrentHashMap.newKeySet();
      AtomicInteger duplicateCount = new AtomicInteger(0);

      ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

      // When
      for (int t = 0; t < threadCount; t++) {
        executorService.submit(
            () -> {
              try {
                barrier.await();
                while (true) {
                  List<QueueMessage<TestMessage>> batch = buffer.consume(5);
                  if (batch.isEmpty()) {
                    break;
                  }
                  for (QueueMessage<TestMessage> msg : batch) {
                    boolean added = processedIds.add(msg.msgId());
                    if (!added) {
                      duplicateCount.incrementAndGet();
                    }
                    buffer.ack(msg.msgId());
                  }
                }
              } catch (Exception e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      barrier.await(); // 동시 시작
      executorService.shutdown();
      executorService.awaitTermination(10, TimeUnit.SECONDS);

      // Then
      assertThat(duplicateCount.get()).isZero();
      assertThat(processedIds).hasSize(100);
      assertThat(buffer.getPendingCount()).isZero();
      assertThat(buffer.getInflightCount()).isZero();
    }
  }

  @Nested
  @DisplayName("상태 조회 테스트")
  class StateTest {

    @Test
    @DisplayName("getType() - IN_MEMORY 반환")
    void getType_shouldReturnInMemory() {
      assertThat(buffer.getType()).isEqualTo(QueueType.IN_MEMORY);
    }

    @Test
    @DisplayName("isHealthy() - 정상 상태")
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
    @DisplayName("isEmpty() - 빈 큐")
    void isEmpty_shouldReturnTrue() {
      assertThat(buffer.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("isEmpty() - 비어있지 않은 큐")
    void isEmpty_shouldReturnFalse() {
      buffer.publish(new TestMessage(1L, "test"));
      assertThat(buffer.isEmpty()).isFalse();
    }
  }

  /** 테스트용 메시지 */
  record TestMessage(Long id, String content) {}
}
