package maple.expectation.global.queue.strategy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.queue.MessageQueueStrategy;
import maple.expectation.global.queue.QueueMessage;
import maple.expectation.global.queue.QueueType;

/**
 * In-Memory 메시지 큐 전략 (V4 Legacy)
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 *
 * <p>기존 ExpectationWriteBackBuffer 로직을 MessageQueueStrategy 인터페이스로 리팩토링
 *
 * <h3>특성</h3>
 *
 * <ul>
 *   <li>지연: 0.1ms (Lock-free)
 *   <li>처리량: 965 RPS (단일 노드)
 *   <li>내구성: Low (장애 시 데이터 유실)
 *   <li>비용: Free
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): ConcurrentLinkedQueue로 Lock-free 구현
 *   <li>Green (Performance): CAS 기반 카운터로 경합 최소화
 *   <li>Purple (Auditor): msgId 기반 ACK로 Iteration 4 준수
 *   <li>Yellow (QA): 테스트 용이성을 위한 단순 구조
 *   <li>Red (SRE): 백프레셔 + DLQ 지원
 * </ul>
 *
 * <h3>한계점 (Scale-out 불가)</h3>
 *
 * <ul>
 *   <li>각 인스턴스가 독립 버퍼 보유
 *   <li>배포/장애 시 데이터 유실 위험
 *   <li>트래픽 > 1,000 RPS 시 V5(Redis) 전환 권장
 * </ul>
 *
 * @param <T> 메시지 페이로드 타입
 * @see maple.expectation.global.queue.strategy.RedisBufferStrategy V5 Redis 구현
 */
@Slf4j
public class InMemoryBufferStrategy<T> implements MessageQueueStrategy<T> {

  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final int DEFAULT_MAX_QUEUE_SIZE = 10_000;

  /** Main Queue (pending) */
  private final ConcurrentLinkedQueue<QueueMessage<T>> mainQueue = new ConcurrentLinkedQueue<>();

  /** INFLIGHT Map (처리 중인 메시지) */
  private final ConcurrentHashMap<String, QueueMessage<T>> inflightMap = new ConcurrentHashMap<>();

  /** Dead Letter Queue */
  private final ConcurrentLinkedQueue<QueueMessage<T>> dlq = new ConcurrentLinkedQueue<>();

  /** 카운터 */
  private final AtomicInteger pendingCount = new AtomicInteger(0);

  private final AtomicInteger inflightCount = new AtomicInteger(0);
  private final AtomicInteger dlqCount = new AtomicInteger(0);

  /** 설정 */
  private final int maxRetries;

  private final int maxQueueSize;
  private final MeterRegistry meterRegistry;

  /** Shutdown 플래그 */
  private volatile boolean shuttingDown = false;

  /**
   * 생성자
   *
   * @param meterRegistry Micrometer 레지스트리
   * @param maxRetries 최대 재시도 횟수 (기본값: 3)
   * @param maxQueueSize 최대 큐 크기 (기본값: 10,000)
   */
  public InMemoryBufferStrategy(MeterRegistry meterRegistry, int maxRetries, int maxQueueSize) {
    this.meterRegistry = meterRegistry;
    this.maxRetries = maxRetries;
    this.maxQueueSize = maxQueueSize;
    registerMetrics();
  }

  /**
   * 기본값 생성자
   *
   * @param meterRegistry Micrometer 레지스트리
   */
  public InMemoryBufferStrategy(MeterRegistry meterRegistry) {
    this(meterRegistry, DEFAULT_MAX_RETRIES, DEFAULT_MAX_QUEUE_SIZE);
  }

  /** 메트릭 등록 */
  private void registerMetrics() {
    String strategyTag = getType().name();

    Gauge.builder("queue.pending", pendingCount, AtomicInteger::get)
        .tag("strategy", strategyTag)
        .description("대기 중인 메시지 수")
        .register(meterRegistry);

    Gauge.builder("queue.inflight", inflightCount, AtomicInteger::get)
        .tag("strategy", strategyTag)
        .description("처리 중인 메시지 수")
        .register(meterRegistry);

    Gauge.builder("queue.dlq", dlqCount, AtomicInteger::get)
        .tag("strategy", strategyTag)
        .description("DLQ 메시지 수")
        .register(meterRegistry);
  }

  @Override
  public String publish(T message) {
    // Shutdown 중이면 거부
    if (shuttingDown) {
      meterRegistry
          .counter("queue.publish.rejected", "strategy", getType().name(), "reason", "shutdown")
          .increment();
      log.debug("[InMemoryBuffer] Rejected during shutdown");
      return null;
    }

    // 백프레셔 체크
    if (pendingCount.get() >= maxQueueSize) {
      meterRegistry
          .counter("queue.publish.rejected", "strategy", getType().name(), "reason", "backpressure")
          .increment();
      log.warn(
          "[InMemoryBuffer] Backpressure triggered: pending={}, max={}",
          pendingCount.get(),
          maxQueueSize);
      return null;
    }

    // msgId 생성 및 큐 추가
    String msgId = UUID.randomUUID().toString();
    QueueMessage<T> queueMessage = new QueueMessage<>(msgId, message, 0, Instant.now());

    mainQueue.offer(queueMessage);
    pendingCount.incrementAndGet();

    meterRegistry.counter("queue.publish.success", "strategy", getType().name()).increment();
    log.debug("[InMemoryBuffer] Published message: msgId={}", msgId);

    return msgId;
  }

  @Override
  public List<QueueMessage<T>> consume(int batchSize) {
    List<QueueMessage<T>> batch = new ArrayList<>(batchSize);

    for (int i = 0; i < batchSize; i++) {
      QueueMessage<T> message = mainQueue.poll();
      if (message == null) {
        break;
      }

      // INFLIGHT로 이동
      inflightMap.put(message.msgId(), message);
      pendingCount.decrementAndGet();
      inflightCount.incrementAndGet();

      batch.add(message);
    }

    if (!batch.isEmpty()) {
      meterRegistry
          .counter("queue.consume.success", "strategy", getType().name())
          .increment(batch.size());
      log.debug("[InMemoryBuffer] Consumed {} messages", batch.size());
    }

    return batch;
  }

  @Override
  public void ack(String msgId) {
    QueueMessage<T> removed = inflightMap.remove(msgId);

    if (removed != null) {
      inflightCount.decrementAndGet();
      meterRegistry.counter("queue.ack.success", "strategy", getType().name()).increment();
      log.debug("[InMemoryBuffer] ACK message: msgId={}", msgId);
    } else {
      // 이미 ACK되었거나 존재하지 않음 (멱등성 보장)
      meterRegistry.counter("queue.ack.not_found", "strategy", getType().name()).increment();
      log.debug("[InMemoryBuffer] ACK message not found (already acked): msgId={}", msgId);
    }
  }

  @Override
  public void nack(String msgId, int retryCount) {
    QueueMessage<T> message = inflightMap.remove(msgId);

    if (message == null) {
      meterRegistry.counter("queue.nack.not_found", "strategy", getType().name()).increment();
      log.warn("[InMemoryBuffer] NACK message not found: msgId={}", msgId);
      return;
    }

    inflightCount.decrementAndGet();

    if (retryCount >= maxRetries) {
      // DLQ로 이동
      QueueMessage<T> dlqMessage = message.withRetryCount(retryCount);
      dlq.offer(dlqMessage);
      dlqCount.incrementAndGet();

      meterRegistry.counter("queue.nack.dlq", "strategy", getType().name()).increment();
      log.warn(
          "[InMemoryBuffer] Message moved to DLQ after {} retries: msgId={}", maxRetries, msgId);
    } else {
      // Main Queue로 복귀
      QueueMessage<T> retryMessage = message.withRetryCount(retryCount + 1);
      mainQueue.offer(retryMessage);
      pendingCount.incrementAndGet();

      meterRegistry.counter("queue.nack.retry", "strategy", getType().name()).increment();
      log.debug(
          "[InMemoryBuffer] Message scheduled for retry: msgId={}, retryCount={}",
          msgId,
          retryCount + 1);
    }
  }

  @Override
  public long getPendingCount() {
    return pendingCount.get();
  }

  @Override
  public long getInflightCount() {
    return inflightCount.get();
  }

  @Override
  public long getRetryCount() {
    // In-Memory에서는 Retry가 Main Queue에 포함되어 별도 추적 안함
    return 0;
  }

  @Override
  public long getDlqCount() {
    return dlqCount.get();
  }

  @Override
  public QueueType getType() {
    return QueueType.IN_MEMORY;
  }

  @Override
  public boolean isHealthy() {
    return !shuttingDown;
  }

  @Override
  public void prepareShutdown() {
    this.shuttingDown = true;
    log.info("[InMemoryBuffer] Shutdown prepared - new publish will be rejected");
  }

  @Override
  public boolean isShuttingDown() {
    return shuttingDown;
  }

  // ==================== Additional Methods ====================

  /**
   * 큐가 비어있는지 확인
   *
   * @return true: Main Queue 비어있음
   */
  public boolean isEmpty() {
    return mainQueue.isEmpty();
  }

  /**
   * DLQ 메시지 조회 (재처리용)
   *
   * @param maxCount 최대 조회 개수
   * @return DLQ 메시지 목록
   */
  public List<QueueMessage<T>> pollDlq(int maxCount) {
    List<QueueMessage<T>> batch = new ArrayList<>(maxCount);

    for (int i = 0; i < maxCount; i++) {
      QueueMessage<T> message = dlq.poll();
      if (message == null) {
        break;
      }
      dlqCount.decrementAndGet();
      batch.add(message);
    }

    return batch;
  }

  /**
   * 최대 재시도 횟수 조회
   *
   * @return maxRetries
   */
  public int getMaxRetries() {
    return maxRetries;
  }

  /**
   * 최대 큐 크기 조회
   *
   * @return maxQueueSize
   */
  public int getMaxQueueSize() {
    return maxQueueSize;
  }
}
