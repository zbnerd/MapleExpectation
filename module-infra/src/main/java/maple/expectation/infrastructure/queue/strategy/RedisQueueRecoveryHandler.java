package maple.expectation.infrastructure.queue.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.queue.BufferLuaScripts;
import maple.expectation.infrastructure.queue.QueueMessage;
import maple.expectation.infrastructure.queue.QueueType;
import maple.expectation.infrastructure.queue.RedisKey;
import maple.expectation.infrastructure.queue.script.BufferLuaScriptProvider;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Redis Queue Recovery Handler - Re-drive, Retry Processing, DLQ Polling 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>만료된 INFLIGHT 메시지 조회
 *   <li>INFLIGHT → Main Queue 복귀 (Re-drive)
 *   <li>Retry Queue 처리 (만료된 Retry → Main Queue)
 *   <li>DLQ 메시지 조회 (재처리용)
 *   <li>Payload 조회 및 역직렬화
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class RedisQueueRecoveryHandler<T> {

  private final RedissonClient redissonClient;
  private final BufferLuaScriptProvider scriptProvider;
  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;
  private final Class<T> payloadType;
  private final RedisQueueMetricsManager metricsManager;
  private final QueueType queueType;

  private final String inflightTsKey;
  private final String inflightKey;
  private final String mainQueueKey;
  private final String retryKey;
  private final String dlqKey;
  private final String payloadKey;

  private static final long BASE_RETRY_DELAY_MS = 1000L; // 1초

  public RedisQueueRecoveryHandler(
      RedissonClient redissonClient,
      BufferLuaScriptProvider scriptProvider,
      ObjectMapper objectMapper,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      Class<T> payloadType,
      RedisQueueMetricsManager metricsManager,
      QueueType queueType) {
    this.redissonClient = redissonClient;
    this.scriptProvider = scriptProvider;
    this.objectMapper = objectMapper;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
    this.payloadType = payloadType;
    this.metricsManager = metricsManager;
    this.queueType = queueType;

    // Redis 키 초기화 (Hash Tag 패턴)
    this.inflightTsKey = RedisKey.EXPECTATION_BUFFER_INFLIGHT_TS.getKey();
    this.inflightKey = RedisKey.EXPECTATION_BUFFER_INFLIGHT.getKey();
    this.mainQueueKey = RedisKey.EXPECTATION_BUFFER.getKey();
    this.retryKey = RedisKey.EXPECTATION_BUFFER_RETRY.getKey();
    this.dlqKey = RedisKey.EXPECTATION_BUFFER_DLQ.getKey();
    this.payloadKey = RedisKey.EXPECTATION_BUFFER_PAYLOAD.getKey();
  }

  /**
   * 만료된 INFLIGHT 메시지 조회
   *
   * @param timeoutMs 타임아웃 (ms)
   * @param limit 최대 조회 개수
   * @return 만료된 msgId 목록
   */
  public List<String> getExpiredInflightMessages(long timeoutMs, int limit) {
    long maxTimestamp = System.currentTimeMillis() - timeoutMs;

    return scriptProvider.executeWithNoscriptHandling(
        scriptProvider::getGetExpiredInflightSha,
        BufferLuaScripts.GET_EXPIRED_INFLIGHT,
        scriptProvider::updateGetExpiredInflightSha,
        sha -> executeGetExpiredInflightScript(sha, maxTimestamp, limit),
        "GetExpiredInflight");
  }

  @SuppressWarnings("unchecked")
  private List<String> executeGetExpiredInflightScript(String sha, long maxTimestamp, int limit) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          return (List<String>)
              script.evalSha(
                  RScript.Mode.READ_ONLY,
                  sha,
                  RScript.ReturnType.MULTI,
                  List.of(inflightTsKey),
                  String.valueOf(maxTimestamp),
                  String.valueOf(limit));
        },
        List.of(),
        TaskContext.of("RedisBuffer", "GetExpiredInflight"));
  }

  /**
   * INFLIGHT → Main Queue 복귀 (Re-drive)
   *
   * @param msgId 복귀할 메시지 ID
   * @return true: 복귀 성공, false: 이미 ACK됨
   */
  public boolean redrive(String msgId) {
    Long result =
        scriptProvider.executeWithNoscriptHandling(
            scriptProvider::getRedriveSha,
            BufferLuaScripts.REDRIVE,
            scriptProvider::updateRedriveSha,
            sha -> executeRedriveScript(sha, msgId),
            "Redrive");

    if (result != null && result > 0) {
      metricsManager.getCachedInflightCount().decrementAndGet();
      metricsManager.getCachedPendingCount().incrementAndGet();
      meterRegistry.counter("queue.redrive.success", "strategy", queueType.name()).increment();
      log.info("[RedisBufferStrategy] Message redriven: msgId={}", msgId);
      return true;
    } else {
      meterRegistry.counter("queue.redrive.skip", "strategy", queueType.name()).increment();
      log.debug("[RedisBufferStrategy] Redrive skipped (already acked): msgId={}", msgId);
      return false;
    }
  }

  private Long executeRedriveScript(String sha, String msgId) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          return script.evalSha(
              RScript.Mode.READ_WRITE,
              sha,
              RScript.ReturnType.INTEGER,
              Arrays.asList(inflightKey, inflightTsKey, mainQueueKey),
              msgId);
        },
        0L,
        TaskContext.of("RedisBuffer", "Redrive", msgId));
  }

  /**
   * Retry Queue 처리 (만료된 Retry → Main Queue)
   *
   * @param limit 최대 처리 개수
   * @return 처리된 msgId 목록
   */
  public List<String> processRetryQueue(int limit) {
    long now = System.currentTimeMillis();

    @SuppressWarnings("unchecked")
    List<String> processed =
        scriptProvider.executeWithNoscriptHandling(
            scriptProvider::getProcessRetryQueueSha,
            BufferLuaScripts.PROCESS_RETRY_QUEUE,
            scriptProvider::updateProcessRetryQueueSha,
            sha -> executeProcessRetryQueueScript(sha, now, limit),
            "ProcessRetryQueue");

    if (!processed.isEmpty()) {
      metricsManager.getCachedRetryCount().addAndGet(-processed.size());
      metricsManager.getCachedPendingCount().addAndGet(processed.size());
      meterRegistry
          .counter("queue.retry.processed", "strategy", queueType.name())
          .increment(processed.size());
      log.info("[RedisBufferStrategy] Processed {} retry messages", processed.size());
    }

    return processed;
  }

  @SuppressWarnings("unchecked")
  private List<String> executeProcessRetryQueueScript(String sha, long now, int limit) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          return (List<String>)
              script.evalSha(
                  RScript.Mode.READ_WRITE,
                  sha,
                  RScript.ReturnType.MULTI,
                  Arrays.asList(retryKey, mainQueueKey),
                  String.valueOf(now),
                  String.valueOf(limit));
        },
        List.of(),
        TaskContext.of("RedisBuffer", "ProcessRetryQueue"));
  }

  /**
   * DLQ 메시지 조회 (재처리용)
   *
   * @param maxCount 최대 조회 개수
   * @return DLQ 메시지 목록
   */
  public List<QueueMessage<T>> pollDlq(int maxCount) {
    List<QueueMessage<T>> messages = new ArrayList<>();

    return executor.executeOrDefault(
        () -> {
          for (int i = 0; i < maxCount; i++) {
            String msgId =
                redissonClient.<String>getDeque(dlqKey, StringCodec.INSTANCE).pollFirst();
            if (msgId == null) {
              break;
            }

            String payloadJson = getPayload(msgId);
            QueueMessage<T> queueMessage = deserializePayload(msgId, payloadJson);
            if (queueMessage != null) {
              messages.add(queueMessage);
              metricsManager.getCachedDlqCount().decrementAndGet();
            }
          }
          return messages;
        },
        messages,
        TaskContext.of("RedisBuffer", "PollDlq"));
  }

  /**
   * Payload 조회
   *
   * @param msgId 메시지 ID
   * @return Payload JSON
   */
  public String getPayload(String msgId) {
    return executor.executeOrDefault(
        () -> redissonClient.<String, String>getMap(payloadKey, StringCodec.INSTANCE).get(msgId),
        null,
        TaskContext.of("RedisBuffer", "GetPayload", msgId));
  }

  /**
   * Payload 역직렬화
   *
   * @param msgId 메시지 ID
   * @param payloadJson Payload JSON
   * @return QueueMessage
   */
  public QueueMessage<T> deserializePayload(String msgId, String payloadJson) {
    return executor.executeOrDefault(
        () -> {
          PayloadWrapper<T> wrapper =
              objectMapper.readValue(
                  payloadJson,
                  objectMapper
                      .getTypeFactory()
                      .constructParametricType(PayloadWrapper.class, payloadType));
          return new QueueMessage<>(
              msgId,
              wrapper.payload(),
              wrapper.retryCount(),
              Instant.ofEpochMilli(wrapper.createdAtMs()));
        },
        null,
        TaskContext.of("RedisBuffer", "Deserialize", msgId));
  }

  /**
   * Exponential Backoff 지연 계산
   *
   * @param retryCount 현재 재시도 횟수
   * @return 지연 시간 (ms)
   */
  public long calculateBackoffDelay(int retryCount) {
    return BASE_RETRY_DELAY_MS * (1L << retryCount); // 1s, 2s, 4s, ...
  }

  /** Payload 래퍼 (retryCount, createdAt 포함) */
  private record PayloadWrapper<T>(T payload, int retryCount, long createdAtMs) {}
}
