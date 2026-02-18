package maple.expectation.service.v5.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.event.ExpectationCalculationCompletedEvent;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Publishes calculation events to Redis Stream
 *
 * <h3>Event Flow</h3>
 *
 * <ol>
 *   <li>Calculation completes in worker
 *   <li>Event published to character-sync stream via XADD
 *   <li>MongoSyncWorker consumes and upserts to MongoDB
 * </ol>
 *
 * <h3>Redis Stream Pattern (V2 Like Sync Reference)</h3>
 *
 * <p>Uses Redisson RStream API to add entries to the stream. Each event contains:
 *
 * <ul>
 *   <li>eventId: Unique UUID for tracing
 *   <li>eventType: Event type identifier
 *   <li>timestamp: Event creation time
 *   <li>payload: JSON-serialized ExpectationCalculationCompletedEvent
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "v5.query-side-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class MongoSyncEventPublisher implements MongoSyncEventPublisherInterface {

  private static final String STREAM_KEY = "character-sync";
  private static final String EVENT_TYPE_CALCULATED = "EXPECTATION_CALCULATED";

  private final RedissonClient redissonClient;
  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;

  /**
   * Publishes calculation completion event to Redis Stream.
   *
   * <p>Follows V2 LikeSyncScheduler pattern: XADD to stream → async consumption by worker.
   *
   * @param task_id Unique task identifier
   * @param response Calculation response containing full expectation data
   */
  public void publishCalculationCompleted(String taskId, EquipmentExpectationResponseV4 response) {
    TaskContext context = TaskContext.of("MongoSyncPublisher", "Publish", taskId);

    executor.executeVoid(() -> publishCalculationCompletedInternal(taskId, response), context);
  }

  void publishCalculationCompletedInternal(String taskId, EquipmentExpectationResponseV4 response) {
    RStream<String, String> stream = redissonClient.getStream(STREAM_KEY);

    executor.executeOrCatch(
        () -> {
          ExpectationCalculationCompletedEvent payload =
              ExpectationCalculationCompletedEvent.builder()
                  .taskId(taskId)
                  .userIgn(response.getUserIgn())
                  .characterOcid(extractCharacterOcid(response))
                  .characterClass(extractCharacterClass(response))
                  .characterLevel(extractCharacterLevel(response))
                  .calculatedAt(response.getCalculatedAt().toString())
                  .totalExpectedCost(String.valueOf(response.getTotalExpectedCost()))
                  .maxPresetNo(response.getMaxPresetNo())
                  .payload(serializePayload(response))
                  .build();

          IntegrationEvent<ExpectationCalculationCompletedEvent> event =
              IntegrationEvent.of(EVENT_TYPE_CALCULATED, payload);

          // Redis Stream XADD command via Redisson
          Map<String, String> eventMap = convertToStreamMap(event);

          // Use auto-generated ID (Redisson 3.x API)
          StreamMessageId messageId = stream.add(StreamAddArgs.entries(eventMap));

          log.info(
              "[MongoSyncPublisher] Published event: taskId={}, userIgn={}, messageId={}",
              taskId,
              response.getUserIgn(),
              messageId);

          return messageId;
        },
        e -> {
          log.error("[MongoSyncPublisher] Failed to publish event: taskId={}", taskId, e);
          return null;
        },
        TaskContext.of("MongoSyncPublisher", "XADD", taskId));
  }

  /**
   * Converts IntegrationEvent to Redis Stream entry map.
   *
   * <p>Redis Stream stores data as key-value pairs. We serialize the entire event to JSON for the
   * payload field.
   *
   * <p>Uses LogicExecutor.executeOrDefault() for Section 12 compliance.
   */
  private Map<String, String> convertToStreamMap(
      IntegrationEvent<ExpectationCalculationCompletedEvent> event) {
    Map<String, String> map = new HashMap<>();
    map.put("eventId", event.getEventId());
    map.put("eventType", event.getEventType());
    map.put("timestamp", String.valueOf(event.getTimestamp()));

    String payloadJson =
        executor.executeOrDefault(
            () -> objectMapper.writeValueAsString(event.getPayload()),
            "{}",
            TaskContext.of("MongoSyncPublisher", "SerializePayload", event.getEventId()));
    map.put("payload", payloadJson);

    return map;
  }

  /**
   * Extract character OCID from response.
   *
   * <p>V4 response doesn't include OCID directly. This is a placeholder for future enhancement.
   */
  private String extractCharacterOcid(EquipmentExpectationResponseV4 response) {
    // TODO: Extract from first preset's first item if available
    // V4 response structure doesn't include OCID - needs API enhancement
    return "unknown";
  }

  /**
   * Extract character class from response.
   *
   * <p>Placeholder for future enhancement when character info is added to V4 response.
   */
  private String extractCharacterClass(EquipmentExpectationResponseV4 response) {
    // TODO: Extract from response when available
    return "unknown";
  }

  /**
   * Extract character level from response.
   *
   * <p>Placeholder for future enhancement when character info is added to V4 response.
   */
  private Integer extractCharacterLevel(EquipmentExpectationResponseV4 response) {
    // TODO: Extract from response when available
    return 0;
  }

  /**
   * Serialize V4 response to JSON for MongoDB sync.
   *
   * <p>MongoDBSyncWorker will deserialize this and transform to CharacterValuationView.
   */
  private String serializePayload(EquipmentExpectationResponseV4 response) {
    return executor.executeOrDefault(
        () -> objectMapper.writeValueAsString(response),
        "{}",
        TaskContext.of("MongoSyncPublisher", "Serialize", response.getUserIgn()));
  }
}
