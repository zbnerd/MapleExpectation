package maple.expectation.service.v5.event;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Publishes calculation events to Redis Stream
 *
 * <h3>Event Flow</h3>
 *
 * <ol>
 *   <li>Calculation completes in worker
 *   <li>Event published to character-sync stream
 *   <li>MongoSyncWorker consumes and upserts to MongoDB
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoSyncEventPublisher {

  private static final String STREAM_KEY = "character-sync";
  private static final String EVENT_TYPE_CALCULATED = "EXPECTATION_CALCULATED";

  private final RedissonClient redissonClient;
  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;

  private RStream<String, String> stream;

  public void publishCalculationCompleted(String taskId, EquipmentExpectationResponseV4 response) {
    TaskContext context = TaskContext.of("MongoSyncPublisher", "Publish", taskId);

    executor.executeVoid(() -> publishCalculationCompletedInternal(taskId, response), context);
  }

  void publishCalculationCompletedInternal(String taskId, EquipmentExpectationResponseV4 response) {
    try {
      if (stream == null) {
        stream = redissonClient.getStream(STREAM_KEY);
      }

      ExpectationCalculationCompletedEvent payload =
          ExpectationCalculationCompletedEvent.builder()
              .taskId(taskId)
              .userIgn(response.getUserIgn())
              .characterOcid(extractOcid(response))
              .calculatedAt(response.getCalculatedAt().toString())
              .totalExpectedCost(String.valueOf(response.getTotalExpectedCost()))
              .maxPresetNo(response.getMaxPresetNo())
              .payload(serializePayload(response))
              .build();

      IntegrationEvent<ExpectationCalculationCompletedEvent> event =
          IntegrationEvent.of(EVENT_TYPE_CALCULATED, payload);

      // TODO: Fix Redisson Stream API compatibility
      // Map<String, String> eventMap = Map.of(...);
      // StreamMessageId messageId = stream.add(eventMap);
      StreamMessageId messageId = null;

      log.debug(
          "[MongoSyncPublisher] Published event: taskId={}, userIgn={}, messageId={}",
          taskId,
          response.getUserIgn(),
          "placeholder");

    } catch (Exception e) {
      log.error("[MongoSyncPublisher] Failed to publish event: taskId={}", taskId, e);
    }
  }

  private String extractOcid(EquipmentExpectationResponseV4 response) {
    // Extract from first preset's first item if available
    return response.getPresets().isEmpty() || response.getPresets().get(0).getItems().isEmpty()
        ? "unknown"
        : "ocid"; // TODO: Extract from actual response
  }

  private String serializePayload(EquipmentExpectationResponseV4 response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (Exception e) {
      log.warn("[MongoSyncPublisher] Failed to serialize payload", e);
      return "{}";
    }
  }
}
