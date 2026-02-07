package maple.expectation.infrastructure.messaging;

import maple.expectation.application.port.EventPublisher;
import maple.expectation.application.port.MessageTopic;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.global.error.exception.QueuePublishException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis-based event publisher implementation.
 *
 * <p><strong>Concrete Strategy A:</strong> Uses existing {@link MessageTopic}
 * for pub/sub messaging. This is the default implementation for Phase 1.
 *
 * <p><strong>Configuration:</strong> Activated when {@code app.event-publisher.type=redis}
 * (default: true). To switch to Kafka in Phase 8, change to {@code type=kafka}.
 *
 * <p><strong>SOLID Compliance:</strong>
 * <ul>
 *   <li><b>DIP:</b> Implements {@link EventPublisher} interface</li>
 *   <li><b>SRP:</b> Single responsibility - Redis publishing logic</li>
 *   <li><b>OCP:</b> Can be replaced by KafkaEventPublisher without changing business logic</li>
 * </ul>
 *
 * <h3>Redis vs Kafka Trade-off:</h3>
 * <table border="1">
 *   <tr><th>Aspect</th><th>Redis (Current)</th><th>Kafka (Phase 8)</th></tr>
 *   <tr><td>Throughput</td><td>~10K msg/s</td><td>~100K+ msg/s</td></tr>
 *   <tr><td>Persistence</td><td>AOF (configurable)</td><td>Log-based (durable)</td></tr>
 *   <tr><td>Replay</td><td>Limited</td><td>Offset-based replay</td></tr>
 *   <tr><td>Operations</td><td>Already using Redis</td><td>New infrastructure</td></tr>
 *   <tr><td>Cost</td><td>Free (using existing)</td><td>$$ (new cluster)</td></tr>
 * </table>
 *
 * <h3>Migration to Kafka (Phase 8):</h3>
 * <ol>
 *   <li>Add {@code spring-kafka} dependency</li>
 *   <li>Implement {@code KafkaEventPublisher}</li>
 *   <li>Change configuration: {@code app.event-publisher.type=kafka}</li>
 *   <li>Zero code changes in business logic (DIP works!)</li>
 * </ol>
 *
 * @see EventPublisher
 * @see MessageTopic
 * @see maple.expectation.infrastructure.messaging.RedisMessageTopic
 * @see ADR-018 Strategy Pattern for ACL
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.event-publisher.type",
    havingValue = "redis",
    matchIfMissing = true  // Default to Redis
)
public class RedisEventPublisher implements EventPublisher {

  private final MessageTopic<String> messageTopic;
  private final ObjectMapper objectMapper;

  @Override
  public void publish(String topic, IntegrationEvent<?> event) {
    try {
      // Serialize IntegrationEvent to JSON
      String jsonPayload = objectMapper.writeValueAsString(event);

      // Publish to Redis topic
      messageTopic.publish(topic, jsonPayload);

      log.debug("[RedisEventPublisher] Published to topic {}: eventId={}, eventType={}",
          topic, event.getEventId(), event.getEventType());

    } catch (Exception e) {
      log.error("[RedisEventPublisher] Failed to publish to topic {}: eventId={}, eventType={}",
          topic, event.getEventId(), event.getEventType(), e);

      throw new QueuePublishException(
          String.format("Redis publish failed: topic=%s, eventType=%s", topic, event.getEventType()),
          e
      );
    }
  }

  @Override
  public java.util.concurrent.CompletableFuture<Void> publishAsync(
      String topic,
      IntegrationEvent<?> event) {

    // Redis publish is already fast (in-memory), so we use the default async wrapper
    // For Kafka implementation, this would use KafkaTemplate.send() which returns CompletableFuture
    return EventPublisher.super.publishAsync(topic, event);
  }
}
