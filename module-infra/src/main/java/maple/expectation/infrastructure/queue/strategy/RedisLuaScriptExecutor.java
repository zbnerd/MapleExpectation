package maple.expectation.infrastructure.queue.strategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.queue.RedisKey;
import maple.expectation.infrastructure.queue.script.BufferLuaScriptProvider;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Redis Lua Script Executor - Lua Script 실행 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>Publish, Consume, Ack, Nack 스크립트 실행
 *   <li>GetQueueCounts 스크립트 실행
 *   <li>Script SHA 관리
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class RedisLuaScriptExecutor {

  private final RedissonClient redissonClient;
  private final BufferLuaScriptProvider scriptProvider;
  private final LogicExecutor executor;

  private final String mainQueueKey;
  private final String inflightKey;
  private final String inflightTsKey;
  private final String payloadKey;
  private final String retryKey;
  private final String dlqKey;

  private static final int MSG_ID_INDEX = 0;
  private static final int PAYLOAD_INDEX = 1;
  private static final int MIN_ENTRY_SIZE = 2;
  private static final int QUEUE_COUNT_SIZE = 4;

  public RedisLuaScriptExecutor(
      RedissonClient redissonClient,
      BufferLuaScriptProvider scriptProvider,
      LogicExecutor executor) {
    this.redissonClient = redissonClient;
    this.scriptProvider = scriptProvider;
    this.executor = executor;

    // Redis 키 초기화 (Hash Tag 패턴)
    this.mainQueueKey = RedisKey.EXPECTATION_BUFFER.getKey();
    this.inflightKey = RedisKey.EXPECTATION_BUFFER_INFLIGHT.getKey();
    this.inflightTsKey = RedisKey.EXPECTATION_BUFFER_INFLIGHT_TS.getKey();
    this.payloadKey = RedisKey.EXPECTATION_BUFFER_PAYLOAD.getKey();
    this.retryKey = RedisKey.EXPECTATION_BUFFER_RETRY.getKey();
    this.dlqKey = RedisKey.EXPECTATION_BUFFER_DLQ.getKey();
  }

  /**
   * Publish 스크립트 실행
   *
   * @param sha Script SHA
   * @param msgId 메시지 ID
   * @param payloadJson Payload JSON
   * @return msgId
   */
  public String executePublish(String sha, String msgId, String payloadJson) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          script.evalSha(
              RScript.Mode.READ_WRITE,
              sha,
              RScript.ReturnType.INTEGER,
              Arrays.asList(mainQueueKey, payloadKey),
              msgId,
              payloadJson);
          return msgId;
        },
        null,
        TaskContext.of("RedisBuffer", "Publish", msgId));
  }

  /**
   * Consume 스크립트 실행
   *
   * @param sha Script SHA
   * @param batchSize 배치 크기
   * @return 원시 결과 (List<List<String>>)
   */
  @SuppressWarnings("unchecked")
  public List<List<String>> executeConsume(String sha, int batchSize) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          long timestamp = System.currentTimeMillis();

          return (List<List<String>>)
              script.evalSha(
                  RScript.Mode.READ_WRITE,
                  sha,
                  RScript.ReturnType.MULTI,
                  Arrays.asList(mainQueueKey, inflightKey, inflightTsKey, payloadKey),
                  String.valueOf(batchSize),
                  String.valueOf(timestamp));
        },
        List.of(),
        TaskContext.of("RedisBuffer", "Consume", String.valueOf(batchSize)));
  }

  /**
   * Ack 스크립트 실행
   *
   * @param sha Script SHA
   * @param msgId 메시지 ID
   * @return 제거된 수 (1 또는 0)
   */
  public Long executeAck(String sha, String msgId) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          return script.evalSha(
              RScript.Mode.READ_WRITE,
              sha,
              RScript.ReturnType.INTEGER,
              Arrays.asList(inflightKey, inflightTsKey, payloadKey),
              msgId);
        },
        0L,
        TaskContext.of("RedisBuffer", "Ack", msgId));
  }

  /**
   * Nack to DLQ 스크립트 실행
   *
   * @param sha Script SHA
   * @param msgId 메시지 ID
   * @return 제거된 수 (1 또는 0)
   */
  public Long executeNackToDlq(String sha, String msgId) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          return script.evalSha(
              RScript.Mode.READ_WRITE,
              sha,
              RScript.ReturnType.INTEGER,
              Arrays.asList(inflightKey, inflightTsKey, dlqKey),
              msgId);
        },
        0L,
        TaskContext.of("RedisBuffer", "NackToDlq", msgId));
  }

  /**
   * Nack to Retry 스크립트 실행
   *
   * @param sha Script SHA
   * @param msgId 메시지 ID
   * @param nextAttemptAt 다음 시도 시간
   * @param retryCount 재시도 횟수
   * @param updatedPayloadJson 업데이트된 Payload JSON
   * @return 제거된 수 (1 또는 0)
   */
  public Long executeNackToRetry(
      String sha, String msgId, long nextAttemptAt, int retryCount, String updatedPayloadJson) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          return script.evalSha(
              RScript.Mode.READ_WRITE,
              sha,
              RScript.ReturnType.INTEGER,
              Arrays.asList(inflightKey, inflightTsKey, retryKey, payloadKey),
              msgId,
              String.valueOf(nextAttemptAt),
              String.valueOf(retryCount),
              updatedPayloadJson);
        },
        0L,
        TaskContext.of("RedisBuffer", "NackToRetry", msgId));
  }

  /**
   * GetQueueCounts 스크립트 실행
   *
   * @param sha Script SHA
   * @return 큐 카운트 목록 (pending, inflight, retry, dlq)
   */
  @SuppressWarnings("unchecked")
  public List<Long> executeGetQueueCounts(String sha) {
    return executor.executeOrDefault(
        () -> {
          RScript script = redissonClient.getScript(StringCodec.INSTANCE);
          List<Long> counts =
              (List<Long>)
                  script.evalSha(
                      RScript.Mode.READ_ONLY,
                      sha,
                      RScript.ReturnType.MULTI,
                      Arrays.asList(mainQueueKey, inflightKey, retryKey, dlqKey));
          return counts != null ? counts : Arrays.asList(0L, 0L, 0L, 0L);
        },
        Arrays.asList(0L, 0L, 0L, 0L),
        TaskContext.of("RedisBuffer", "GetQueueCounts"));
  }

  /**
   * Consume 결과를 QueueMessage 목록으로 변환
   *
   * @param rawResult 원시 결과
   * @return 변환된 메시지 목록
   */
  public List<String> extractMessageIdsFromConsumeResult(List<List<String>> rawResult) {
    List<String> msgIds = new ArrayList<>();
    for (List<String> entry : rawResult) {
      if (entry.size() >= MIN_ENTRY_SIZE) {
        msgIds.add(entry.get(MSG_ID_INDEX));
      }
    }
    return msgIds;
  }
}
