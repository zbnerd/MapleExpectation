package maple.expectation.global.resilience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.MapleDataProcessingException;
import maple.expectation.global.executor.CheckedLogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.springframework.stereotype.Service;

/**
 * Compensation Log Service (Issue #218)
 *
 * <p>MySQL 장애 중 발생한 데이터 변경을 Redis Stream에 기록합니다.
 *
 * <h4>Redis Stream 구조 (P1-2 Hash Tag)</h4>
 *
 * <ul>
 *   <li>Stream: {mysql}:compensation:stream
 *   <li>DLQ: {mysql}:compensation:dlq
 *   <li>Consumer Group: compensation-sync
 * </ul>
 *
 * <h4>엔트리 필드</h4>
 *
 * <ul>
 *   <li>type: 데이터 타입 (equipment, ocid)
 *   <li>key: 캐시 키
 *   <li>data: JSON 직렬화된 데이터
 *   <li>timestamp: 기록 시각
 *   <li>retryCount: 재시도 횟수
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationLogService {

  private static final String FIELD_TYPE = "type";
  private static final String FIELD_KEY = "key";
  private static final String FIELD_DATA = "data";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String FIELD_RETRY_COUNT = "retryCount";

  private final RedissonClient redissonClient;
  private final MySQLFallbackProperties properties;
  private final ObjectMapper objectMapper;
  private final CheckedLogicExecutor checkedExecutor;

  private final String instanceId = generateInstanceId();

  private static String generateInstanceId() {
    return Optional.ofNullable(System.getenv("HOSTNAME"))
            .or(() -> Optional.ofNullable(System.getenv("COMPUTERNAME")))
            .orElse("unknown")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  /** Consumer Group 초기화 (P1-N1) */
  @PostConstruct
  public void initConsumerGroup() {
    log.info("[CompensationLog] Instance consumer ID: {}", instanceId);

    checkedExecutor.executeUncheckedVoid(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(properties.getCompensationStream());

          // 그룹이 없으면 생성 (MKSTREAM으로 스트림도 자동 생성)
          if (!stream.isExists()) {
            stream.createGroup(
                StreamCreateGroupArgs.name(properties.getSyncConsumerGroup()).makeStream());
            log.info("[CompensationLog] Consumer Group 생성: {}", properties.getSyncConsumerGroup());
          }
        },
        TaskContext.of("Compensation", "InitConsumerGroup", properties.getSyncConsumerGroup()),
        e -> new MapleDataProcessingException("Consumer Group 초기화 실패", e));
  }

  /**
   * Compensation Log 기록 (P1-N3: MAXLEN 적용)
   *
   * @param type 데이터 타입 (equipment, ocid)
   * @param key 캐시 키
   * @param data 저장할 데이터 객체
   * @return Stream 메시지 ID
   */
  public StreamMessageId writeLog(String type, String key, Object data) {
    return checkedExecutor.executeUnchecked(
        () -> {
          String jsonData = serializeData(data);

          RStream<String, String> stream =
              redissonClient.getStream(properties.getCompensationStream());

          // XADD with MAXLEN ~ (P1-N3: trimNonStrict = approximate ~)
          StreamMessageId messageId =
              stream.add(
                  StreamAddArgs.entries(
                          Map.of(
                              FIELD_TYPE, type,
                              FIELD_KEY, key,
                              FIELD_DATA, jsonData,
                              FIELD_TIMESTAMP, Instant.now().toString(),
                              FIELD_RETRY_COUNT, "0"))
                      .trimNonStrict()
                      .maxLen(properties.getStreamMaxLen())
                      .noLimit());

          log.info(
              "[CompensationLog] 로그 기록 완료: type={}, key={}, messageId={}", type, key, messageId);
          return messageId;
        },
        TaskContext.of("Compensation", "WriteLog", key),
        e -> new MapleDataProcessingException("Compensation Log 기록 실패: " + key, e));
  }

  /**
   * Compensation Log 읽기 (Consumer Group)
   *
   * @param consumerId Consumer 식별자 (deprecated, instanceId 사용됨)
   * @param count 읽을 최대 메시지 수
   * @return 읽은 메시지 목록
   */
  public Map<StreamMessageId, Map<String, String>> readLogs(String consumerId, int count) {
    return checkedExecutor.executeUnchecked(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(properties.getCompensationStream());

          return stream.readGroup(
              properties.getSyncConsumerGroup(),
              instanceId,
              StreamReadGroupArgs.neverDelivered().count(count));
        },
        TaskContext.of("Compensation", "ReadLogs", instanceId),
        e -> new MapleDataProcessingException("Compensation Log 읽기 실패", e));
  }

  /**
   * Compensation Log ACK (처리 완료)
   *
   * @param messageIds 완료된 메시지 ID 목록
   */
  public void ackLogs(List<StreamMessageId> messageIds) {
    if (messageIds.isEmpty()) {
      return;
    }

    checkedExecutor.executeUncheckedVoid(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(properties.getCompensationStream());
          stream.ack(properties.getSyncConsumerGroup(), messageIds.toArray(new StreamMessageId[0]));
          log.debug("[CompensationLog] ACK 완료: {} 건", messageIds.size());
        },
        TaskContext.of("Compensation", "AckLogs", String.valueOf(messageIds.size())),
        e -> new MapleDataProcessingException("Compensation Log ACK 실패", e));
  }

  /**
   * DLQ로 이동 (P1-4: 3회 실패 후)
   *
   * @param originalMessageId 원본 메시지 ID
   * @param entry 원본 메시지 데이터
   * @param errorMessage 실패 원인
   */
  public void moveToDlq(
      StreamMessageId originalMessageId, Map<String, String> entry, String errorMessage) {
    checkedExecutor.executeUncheckedVoid(
        () -> {
          RStream<String, String> dlqStream =
              redissonClient.getStream(properties.getCompensationDlq());

          // DLQ에 에러 정보 추가하여 기록 (P1-N3: trimNonStrict = approximate ~)
          dlqStream.add(
              StreamAddArgs.entries(
                      Map.of(
                          FIELD_TYPE,
                          entry.getOrDefault(FIELD_TYPE, "unknown"),
                          FIELD_KEY,
                          entry.getOrDefault(FIELD_KEY, "unknown"),
                          FIELD_DATA,
                          entry.getOrDefault(FIELD_DATA, "{}"),
                          FIELD_TIMESTAMP,
                          entry.getOrDefault(FIELD_TIMESTAMP, Instant.now().toString()),
                          FIELD_RETRY_COUNT,
                          entry.getOrDefault(FIELD_RETRY_COUNT, "0"),
                          "originalMessageId",
                          originalMessageId.toString(),
                          "errorMessage",
                          errorMessage,
                          "dlqTimestamp",
                          Instant.now().toString()))
                  .trimNonStrict()
                  .maxLen(properties.getStreamMaxLen())
                  .noLimit());

          // 원본 스트림에서 ACK (재처리 방지)
          ackLogs(List.of(originalMessageId));

          log.warn(
              "[CompensationLog] DLQ 이동: messageId={}, error={}", originalMessageId, errorMessage);
        },
        TaskContext.of("Compensation", "MoveToDlq", originalMessageId.toString()),
        e -> new MapleDataProcessingException("DLQ 이동 실패: " + originalMessageId, e));
  }

  /** Pending 메시지 수 조회 (메트릭용) */
  public long getPendingCount() {
    return checkedExecutor.executeUnchecked(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(properties.getCompensationStream());
          return stream.getPendingInfo(properties.getSyncConsumerGroup()).getTotal();
        },
        TaskContext.of("Compensation", "GetPendingCount", properties.getCompensationStream()),
        e -> new MapleDataProcessingException("Pending 메시지 수 조회 실패", e));
  }

  /** DLQ 메시지 수 조회 (메트릭용) */
  public long getDlqCount() {
    return checkedExecutor.executeUnchecked(
        () -> {
          RStream<String, String> dlqStream =
              redissonClient.getStream(properties.getCompensationDlq());
          return dlqStream.isExists() ? dlqStream.size() : 0;
        },
        TaskContext.of("Compensation", "GetDlqCount", properties.getCompensationDlq()),
        e -> new MapleDataProcessingException("DLQ 메시지 수 조회 실패", e));
  }

  /** JSON 직렬화 */
  private String serializeData(Object data) throws JsonProcessingException {
    return objectMapper.writeValueAsString(data);
  }

  /** JSON 역직렬화 */
  public <T> T deserializeData(String json, Class<T> type) {
    return checkedExecutor.executeUnchecked(
        () -> objectMapper.readValue(json, type),
        TaskContext.of("Compensation", "DeserializeData", type.getSimpleName()),
        e -> new MapleDataProcessingException("JSON 역직렬화 실패: " + type.getSimpleName(), e));
  }
}
