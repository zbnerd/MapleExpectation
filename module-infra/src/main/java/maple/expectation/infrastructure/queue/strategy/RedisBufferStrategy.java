package maple.expectation.infrastructure.queue.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.queue.BufferLuaScripts;
import maple.expectation.infrastructure.queue.MessageQueueStrategy;
import maple.expectation.infrastructure.queue.QueueMessage;
import maple.expectation.infrastructure.queue.QueueType;
import maple.expectation.infrastructure.queue.script.BufferLuaScriptProvider;
import org.redisson.api.RedissonClient;

/**
 * Redis 기반 메시지 큐 전략 (V5 Stateless Architecture) - Refactored
 *
 * <h4>책임 (Refactoring 후)</h4>
 *
 * <ul>
 *   <li><b>Core Queue Operations</b>: publish, consume, ack, nack
 *   <li><b>Health Check</b>: isHealthy, prepareShutdown
 *   <li><b>조정</b>: Lua Script 실행, Recovery Handler, Metrics Manager를 활용
 * </ul>
 *
 * <h4>분리된 책임 (별도 클래스)</h4>
 *
 * <ul>
 *   <li>{@link RedisLuaScriptExecutor}: Lua Script 실행 전담
 *   <li>{@link RedisQueueMetricsManager}: 메트릭 등록 및 카운터 관리
 *   <li>{@link RedisQueueRecoveryHandler}: Re-drive, Retry Processing, DLQ Polling
 * </ul>
 *
 * @param <T> 메시지 페이로드 타입
 */
@Slf4j
@RequiredArgsConstructor
public class RedisBufferStrategy<T> implements MessageQueueStrategy<T> {

  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final int MSG_ID_INDEX = 0;
  private static final int PAYLOAD_INDEX = 1;
  private static final int MIN_ENTRY_SIZE = 2;

  private final RedissonClient redissonClient;
  private final BufferLuaScriptProvider scriptProvider;
  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;
  private final Class<T> payloadType;

  /** 설정 */
  private final int maxRetries;

  /** Helper Classes */
  private final RedisLuaScriptExecutor luaScriptExecutor;

  private final RedisQueueMetricsManager metricsManager;
  private final RedisQueueRecoveryHandler<T> recoveryHandler;

  /** Shutdown 플래그 */
  private volatile boolean shuttingDown = false;

  /** 생성자 (Expectation 버퍼용) */
  public RedisBufferStrategy(
      RedissonClient redissonClient,
      BufferLuaScriptProvider scriptProvider,
      ObjectMapper objectMapper,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      Class<T> payloadType) {
    this(
        redissonClient,
        scriptProvider,
        objectMapper,
        executor,
        meterRegistry,
        payloadType,
        DEFAULT_MAX_RETRIES);
  }

  /** 생성자 (maxRetries 커스텀) */
  public RedisBufferStrategy(
      RedissonClient redissonClient,
      BufferLuaScriptProvider scriptProvider,
      ObjectMapper objectMapper,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      Class<T> payloadType,
      int maxRetries) {

    this.redissonClient = redissonClient;
    this.scriptProvider = scriptProvider;
    this.objectMapper = objectMapper;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
    this.payloadType = payloadType;
    this.maxRetries = maxRetries;

    // Helper Classes 초기화
    this.luaScriptExecutor = new RedisLuaScriptExecutor(redissonClient, scriptProvider, executor);
    this.metricsManager = new RedisQueueMetricsManager(meterRegistry);
    this.recoveryHandler =
        new RedisQueueRecoveryHandler<>(
            redissonClient,
            scriptProvider,
            objectMapper,
            executor,
            meterRegistry,
            payloadType,
            metricsManager,
            QueueType.REDIS_LIST);

    // 메트릭 등록
    this.metricsManager.registerMetrics(QueueType.REDIS_LIST);

    log.info("[RedisBufferStrategy] 초기화 완료 - maxRetries={}", maxRetries);
  }

  @Override
  public String publish(T message) {
    // Shutdown 중이면 거부
    if (shuttingDown) {
      meterRegistry
          .counter("queue.publish.rejected", "strategy", getType().name(), "reason", "shutdown")
          .increment();
      log.debug("[RedisBufferStrategy] Rejected during shutdown");
      return null;
    }

    Timer.Sample sample = Timer.start(meterRegistry);
    String msgId = UUID.randomUUID().toString();

    String result =
        scriptProvider.executeWithNoscriptHandling(
            scriptProvider::getPublishSha,
            BufferLuaScripts.PUBLISH,
            scriptProvider::updatePublishSha,
            sha -> executePublishScript(sha, msgId, message),
            "Publish");

    sample.stop(meterRegistry.timer("queue.publish.duration", "strategy", getType().name()));

    if (result != null) {
      metricsManager.getCachedPendingCount().incrementAndGet();
      meterRegistry.counter("queue.publish.success", "strategy", getType().name()).increment();
      log.debug("[RedisBufferStrategy] Published message: msgId={}", msgId);
    }

    return result;
  }

  private String executePublishScript(String sha, String msgId, T message) {
    return executor.executeOrDefault(
        () -> {
          String payloadJson =
              objectMapper.writeValueAsString(
                  new PayloadWrapper<>(message, 0, Instant.now().toEpochMilli()));

          return luaScriptExecutor.executePublish(sha, msgId, payloadJson);
        },
        null,
        TaskContext.of("RedisBuffer", "Publish", msgId));
  }

  @Override
  public List<QueueMessage<T>> consume(int batchSize) {
    Timer.Sample sample = Timer.start(meterRegistry);

    List<QueueMessage<T>> result =
        scriptProvider.executeWithNoscriptHandling(
            scriptProvider::getConsumeSha,
            BufferLuaScripts.CONSUME,
            scriptProvider::updateConsumeSha,
            sha -> executeConsumeScript(sha, batchSize),
            "Consume");

    sample.stop(meterRegistry.timer("queue.consume.duration", "strategy", getType().name()));

    if (!result.isEmpty()) {
      metricsManager.getCachedPendingCount().addAndGet(-result.size());
      metricsManager.getCachedInflightCount().addAndGet(result.size());
      meterRegistry
          .counter("queue.consume.success", "strategy", getType().name())
          .increment(result.size());
      log.debug("[RedisBufferStrategy] Consumed {} messages", result.size());
    }

    return result;
  }

  private List<QueueMessage<T>> executeConsumeScript(String sha, int batchSize) {
    return executor.executeOrDefault(
        () -> {
          List<List<String>> rawResult = luaScriptExecutor.executeConsume(sha, batchSize);
          return convertToQueueMessages(rawResult);
        },
        List.of(),
        TaskContext.of("RedisBuffer", "Consume", String.valueOf(batchSize)));
  }

  private List<QueueMessage<T>> convertToQueueMessages(List<List<String>> rawResult) {
    List<QueueMessage<T>> messages = new ArrayList<>();

    for (List<String> entry : rawResult) {
      if (entry.size() >= MIN_ENTRY_SIZE) {
        String msgId = entry.get(MSG_ID_INDEX);
        String payloadJson = entry.get(PAYLOAD_INDEX);

        QueueMessage<T> queueMessage = recoveryHandler.deserializePayload(msgId, payloadJson);
        if (queueMessage != null) {
          messages.add(queueMessage);
        }
      }
    }

    return messages;
  }

  @Override
  public void ack(String msgId) {
    Timer.Sample sample = Timer.start(meterRegistry);

    Long removed =
        scriptProvider.executeWithNoscriptHandling(
            scriptProvider::getAckSha,
            BufferLuaScripts.ACK,
            scriptProvider::updateAckSha,
            sha -> luaScriptExecutor.executeAck(sha, msgId),
            "Ack");

    sample.stop(meterRegistry.timer("queue.ack.duration", "strategy", getType().name()));

    if (removed != null && removed > 0) {
      metricsManager.getCachedInflightCount().decrementAndGet();
      meterRegistry.counter("queue.ack.success", "strategy", getType().name()).increment();
      log.debug("[RedisBufferStrategy] ACK message: msgId={}", msgId);
    } else {
      meterRegistry.counter("queue.ack.not_found", "strategy", getType().name()).increment();
      log.debug("[RedisBufferStrategy] ACK message not found (already acked): msgId={}", msgId);
    }
  }

  @Override
  public void nack(String msgId, int retryCount) {
    Timer.Sample sample = Timer.start(meterRegistry);

    if (retryCount >= maxRetries) {
      // DLQ로 이동
      scriptProvider.executeWithNoscriptHandling(
          scriptProvider::getNackToDlqSha,
          BufferLuaScripts.NACK_TO_DLQ,
          scriptProvider::updateNackToDlqSha,
          sha -> luaScriptExecutor.executeNackToDlq(sha, msgId),
          "NackToDlq");

      metricsManager.getCachedInflightCount().decrementAndGet();
      metricsManager.getCachedDlqCount().incrementAndGet();
      meterRegistry.counter("queue.nack.dlq", "strategy", getType().name()).increment();
      log.warn(
          "[RedisBufferStrategy] Message moved to DLQ after {} retries: msgId={}",
          maxRetries,
          msgId);
    } else {
      // Retry Queue로 이동 (Exponential Backoff)
      long nextAttemptAt =
          System.currentTimeMillis() + recoveryHandler.calculateBackoffDelay(retryCount);

      // 업데이트된 payload 준비
      String currentPayloadJson = recoveryHandler.getPayload(msgId);
      if (currentPayloadJson == null) {
        log.warn("[RedisBufferStrategy] Payload not found for NACK: msgId={}", msgId);
        sample.stop(meterRegistry.timer("queue.nack.duration", "strategy", getType().name()));
        return;
      }

      String updatedPayloadJson = updateRetryCountInPayload(currentPayloadJson, retryCount);

      scriptProvider.executeWithNoscriptHandling(
          scriptProvider::getNackToRetrySha,
          BufferLuaScripts.NACK_TO_RETRY,
          scriptProvider::updateNackToRetrySha,
          sha ->
              luaScriptExecutor.executeNackToRetry(
                  sha, msgId, nextAttemptAt, retryCount + 1, updatedPayloadJson),
          "NackToRetry");

      metricsManager.getCachedInflightCount().decrementAndGet();
      metricsManager.getCachedRetryCount().incrementAndGet();
      meterRegistry.counter("queue.nack.retry", "strategy", getType().name()).increment();
      log.debug(
          "[RedisBufferStrategy] Message scheduled for retry: msgId={}, retryCount={}",
          msgId,
          retryCount + 1);
    }

    sample.stop(meterRegistry.timer("queue.nack.duration", "strategy", getType().name()));
  }

  private String updateRetryCountInPayload(String payloadJson, int retryCount) {
    return executor.executeOrDefault(
        () -> {
          PayloadWrapper<T> wrapper =
              objectMapper.readValue(
                  payloadJson,
                  objectMapper
                      .getTypeFactory()
                      .constructParametricType(PayloadWrapper.class, payloadType));

          PayloadWrapper<T> updatedWrapper =
              new PayloadWrapper<>(wrapper.payload(), retryCount + 1, wrapper.createdAtMs());

          return objectMapper.writeValueAsString(updatedWrapper);
        },
        payloadJson,
        TaskContext.of("RedisBuffer", "UpdateRetryCount"));
  }

  @Override
  public long getPendingCount() {
    refreshQueueCounts();
    return metricsManager.getCachedPendingCount().get();
  }

  @Override
  public long getInflightCount() {
    refreshQueueCounts();
    return metricsManager.getCachedInflightCount().get();
  }

  @Override
  public long getRetryCount() {
    refreshQueueCounts();
    return metricsManager.getCachedRetryCount().get();
  }

  @Override
  public long getDlqCount() {
    refreshQueueCounts();
    return metricsManager.getCachedDlqCount().get();
  }

  /** 큐 카운트 갱신 (Lua Script 호출) - 위임 */
  private void refreshQueueCounts() {
    scriptProvider.executeWithNoscriptHandling(
        scriptProvider::getGetQueueCountsSha,
        BufferLuaScripts.GET_QUEUE_COUNTS,
        scriptProvider::updateGetQueueCountsSha,
        this::executeGetQueueCountsScript,
        "GetQueueCounts");
  }

  private Boolean executeGetQueueCountsScript(String sha) {
    return executor.executeOrDefault(
        () -> {
          List<Long> counts = luaScriptExecutor.executeGetQueueCounts(sha);

          if (counts != null && counts.size() >= 4) {
            metricsManager.getCachedPendingCount().set(counts.get(MSG_ID_INDEX));
            metricsManager.getCachedInflightCount().set(counts.get(PAYLOAD_INDEX));
            metricsManager.getCachedRetryCount().set(counts.get(2));
            metricsManager.getCachedDlqCount().set(counts.get(3));
          }
          return true;
        },
        false,
        TaskContext.of("RedisBuffer", "GetQueueCounts"));
  }

  @Override
  public QueueType getType() {
    return QueueType.REDIS_LIST;
  }

  @Override
  public boolean isHealthy() {
    if (shuttingDown) {
      return false;
    }

    return executor.executeOrDefault(
        () -> {
          redissonClient.getBucket("health-check").isExists();
          return true;
        },
        false,
        TaskContext.of("RedisBuffer", "HealthCheck"));
  }

  @Override
  public void prepareShutdown() {
    this.shuttingDown = true;
    log.info("[RedisBufferStrategy] Shutdown prepared - new publish will be rejected");
  }

  @Override
  public boolean isShuttingDown() {
    return shuttingDown;
  }

  // ==================== Recovery Methods (위임) ====================

  /**
   * 만료된 INFLIGHT 메시지 조회 (위임)
   *
   * @param timeoutMs 타임아웃 (ms)
   * @param limit 최대 조회 개수
   * @return 만료된 msgId 목록
   */
  public List<String> getExpiredInflightMessages(long timeoutMs, int limit) {
    return recoveryHandler.getExpiredInflightMessages(timeoutMs, limit);
  }

  /**
   * INFLIGHT → Main Queue 복귀 (Re-drive) (위임)
   *
   * @param msgId 복귀할 메시지 ID
   * @return true: 복귀 성공, false: 이미 ACK됨
   */
  public boolean redrive(String msgId) {
    return recoveryHandler.redrive(msgId);
  }

  /**
   * Retry Queue 처리 (만료된 Retry → Main Queue) (위임)
   *
   * @param limit 최대 처리 개수
   * @return 처리된 msgId 목록
   */
  public List<String> processRetryQueue(int limit) {
    return recoveryHandler.processRetryQueue(limit);
  }

  /**
   * DLQ 메시지 조회 (재처리용) (위임)
   *
   * @param maxCount 최대 조회 개수
   * @return DLQ 메시지 목록
   */
  public List<QueueMessage<T>> pollDlq(int maxCount) {
    return recoveryHandler.pollDlq(maxCount);
  }

  /**
   * 최대 재시도 횟수 조회
   *
   * @return maxRetries
   */
  public int getMaxRetries() {
    return maxRetries;
  }

  // ==================== Inner Classes ====================

  /** Payload 래퍼 (retryCount, createdAt 포함) */
  private record PayloadWrapper<T>(T payload, int retryCount, long createdAtMs) {}
}
