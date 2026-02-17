package maple.expectation.core.port.out;

import maple.expectation.domain.event.IntegrationEvent;

/**
 * Strategy interface for event publishing.
 *
 * <p><strong>Strategy Pattern:</strong> Concrete implementations (Redis, Kafka) are interchangeable
 * via configuration. This enables OCP compliance - open for extension (new publishers), closed for
 * modification (existing code unchanged).
 *
 * <p><strong>DIP Compliance:</strong> Business logic depends on this abstraction, not concrete
 * Redis/Kafka implementations.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Business code (depends on abstraction)
 * @Service
 * public class NexonDataCollector {
 *     private final EventPublisher eventPublisher;  // Interface, not concrete class
 *
 *     public void collect(String ocid) {
 *         IntegrationEvent<CharacterData> event = ...;
 *         eventPublisher.publish("character-data", event);  // Polymorphic call
 *     }
 * }
 *
 * // Configuration (selects implementation)
 * @Configuration
 * public class MessagingConfig {
 *     @Bean
 *     @ConditionalOnProperty(name = "app.event-publisher.type", havingValue = "redis")
 *     public EventPublisher redisEventPublisher() {
 *         return new RedisEventPublisher(...);
 *     }
 *
 *     @Bean
 *     @ConditionalOnProperty(name = "app.event-publisher.type", havingValue = "kafka")
 *     public EventPublisher kafkaEventPublisher() {
 *         return new KafkaEventPublisher(...);
 *     }
 * }
 * }</pre>
 *
 * <h3>Migration Path (Redis → Kafka):</h3>
 *
 * <ol>
 *   <li>Current: {@code app.event-publisher.type=redis} (default)
 *   <li>Future (Phase 8): Change to {@code app.event-publisher.type=kafka}
 *   <li>Result: Zero code changes in business logic
 * </ol>
 *
 * @see maple.expectation.infrastructure.messaging.RedisEventPublisher
 * @see maple.expectation.infrastructure.messaging.KafkaEventPublisher
 * @see ADR-018 Strategy Pattern for ACL
 */
public interface EventPublisher {

  /**
   * Publish an event to the message broker.
   *
   * <p><strong>Synchronous blocking call:</strong> Waits for publish confirmation before returning.
   * Use {@link #publishAsync(String, IntegrationEvent)} for non-blocking behavior.
   *
   * @param topic Topic name (e.g., "character-data", "nexon-api-events")
   * @param event Event to publish (wrapped in IntegrationEvent envelope)
   * @throws maple.expectation.global.error.exception.QueuePublishException if publish fails
   */
  void publish(String topic, IntegrationEvent<?> event);

  /**
   * Publish an event asynchronously (non-blocking).
   *
   * <p><strong>Fire-and-forget semantics:</strong> Returns immediately without waiting for publish
   * confirmation. The CompletableFuture completes when the publish operation succeeds or fails.
   *
   * <p><strong>Use case:</strong> High-throughput scenarios where blocking on publish would cause
   * performance degradation (e.g., REST ingestion layer).
   *
   * <p><strong>Error handling:</strong> Exceptions are delivered via CompletableFuture. Callers
   * should handle exceptionally() if needed:
   *
   * <pre>{@code
   * eventPublisher.publishAsync(topic, event)
   *     .exceptionally(ex -> {
   *         log.error("Publish failed", ex);
   *         return null;  // or fallback logic
   *     });
   * }</pre>
   *
   * @param topic Topic name
   * @param event Event to publish
   * @return CompletableFuture that completes when published
   */
  default java.util.concurrent.CompletableFuture<Void> publishAsync(
      String topic, IntegrationEvent<?> event) {
    return java.util.concurrent.CompletableFuture.runAsync(() -> publish(topic, event));
  }
}
