package maple.expectation.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.out.EventPublisher;
import maple.expectation.core.port.out.MessageQueue;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.error.exception.QueuePublishException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis-based event publisher implementation.
 *
 * <p><strong>Concrete Strategy A:</strong> Uses {@link MessageQueue} for queue-based messaging.
 * This is the default implementation for Phase 1.
 *
 * <p><strong>Configuration:</strong> Activated when {@code app.event-publisher.type=redis}
 * (default: true). To switch to Kafka in Phase 8, change to {@code type=kafka}.
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>DIP:</b> Implements {@link EventPublisher} interface
 *   <li><b>SRP:</b> Single responsibility - Redis publishing logic
 *   <li><b>OCP:</b> Can be replaced by KafkaEventPublisher without changing business logic
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance</h3>
 *
 * <ul>
 *   <li>Uses LogicExecutor.executeWithTranslation() for exception handling
 *   <li>No raw try-catch blocks in business logic
 * </ul>
 *
 * <h3>Redis vs Kafka Trade-off:</h3>
 *
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
 *
 * <ol>
 *   <li>Add {@code spring-kafka} dependency
 *   <li>Implement {@code KafkaEventPublisher}
 *   <li>Change configuration: {@code app.event-publisher.type=kafka}
 *   <li>Zero code changes in business logic (DIP works!)
 * </ol>
 *
 * @see EventPublisher
 * @see MessageQueue
 * @see maple.expectation.infrastructure.messaging.RedisMessageQueue
 * @see ADR-018 Strategy Pattern for ACL
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = "type",
    havingValue = "redis",
    matchIfMissing = true)
public class RedisEventPublisher implements EventPublisher {

  private final MessageQueue<String> messageQueue;
  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;

  public RedisEventPublisher(
      @Qualifier("integrationEventQueue") MessageQueue<String> messageQueue,
      ObjectMapper objectMapper,
      LogicExecutor executor) {
    this.messageQueue = messageQueue;
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  @Override
  public void publish(String topic, IntegrationEvent<?> event) {
    executor.executeVoid(
        () -> publishInternal(topic, event),
        TaskContext.of("RedisEventPublisher", "Publish", topic));
  }

  /**
   * Internal publish implementation with checked exceptions.
   *
   * <p>Wrapped by LogicExecutor.executeWithTranslation() which translates checked exceptions to
   * QueuePublishException.
   */
  private void publishInternal(String topic, IntegrationEvent<?> event) throws Exception {
    // Serialize IntegrationEvent to JSON
    String jsonPayload = objectMapper.writeValueAsString(event);

    // Offer to Redis queue
    boolean offered = messageQueue.offer(jsonPayload);

    if (!offered) {
      log.warn(
          "[RedisEventPublisher] Queue full, could not publish to topic {}: eventId={}, eventType={}",
          topic,
          event.getEventId(),
          event.getEventType());
      throw new QueuePublishException(
          String.format("Redis queue full: topic=%s, eventType=%s", topic, event.getEventType()));
    }

    log.debug(
        "[RedisEventPublisher] Published to queue {}: eventId={}, eventType={}",
        topic,
        event.getEventId(),
        event.getEventType());
  }

  @Override
  public java.util.concurrent.CompletableFuture<Void> publishAsync(
      String topic, IntegrationEvent<?> event) {

    // Redis publish is already fast (in-memory), so we use the default async wrapper
    // For Kafka implementation, this would use KafkaTemplate.send() which returns CompletableFuture
    return EventPublisher.super.publishAsync(topic, event);
  }
}
