package maple.expectation.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.port.EventPublisher;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Kafka-based event publisher implementation.
 *
 * <p><strong>Concrete Strategy B:</strong> Uses Apache Kafka for distributed event streaming. This
 * is Phase 8 implementation for scale-out scenarios requiring high throughput.
 *
 * <p><strong>Configuration:</strong> Activated when {@code app.event-publisher.type=kafka}. To use
 * Kafka instead of Redis (default), change configuration to {@code type=kafka}.
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>DIP:</b> Implements {@link EventPublisher} interface
 *   <li><b>SRP:</b> Single responsibility - Kafka publishing logic
 *   <li><b>OCP:</b> Can replace RedisEventPublisher without changing business logic
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance</h3>
 *
 * <ul>
 *   <li>Uses LogicExecutor.executeWithTranslation() for exception handling
 *   <li>No raw try-catch blocks in business logic
 * </ul>
 *
 * <h3>TODO: Real Implementation (Phase 8)</h3>
 *
 * <p>Current implementation is a stub that logs events without actual Kafka publishing. Real
 * implementation requires:
 *
 * <ol>
 *   <li>Add {@code spring-kafka} dependency (optional, not enforced)
 *   <li>Inject {@code KafkaTemplate<String, String>} for publishing
 *   <li>Configure Kafka producer properties (bootstrap-servers, acks, retries)
 *   <li>Replace publishInternal() with actual {@code kafkaTemplate.send(topic, payload)}
 *   <li>Implement publishAsync() using KafkaTemplate's native CompletableFuture
 *   <li>Add error handling for KafkaException (timeout, broker unavailable)
 * </ol>
 *
 * <h3>Kafka Producer Configuration Example (application.yml):</h3>
 *
 * <pre>{@code
 * spring:
 *   kafka:
 *     bootstrap-servers: localhost:9092
 *     producer:
 *       key-serializer: org.apache.kafka.common.serialization.StringSerializer
 *       value-serializer: org.apache.kafka.common.serialization.StringSerializer
 *       acks: all  # Wait for all replicas
 *       retries: 3
 *       max-in-flight-requests-per-connection: 1
 *       properties:
 *         enable.idempotence: true  # Exactly-once semantics
 * }</pre>
 *
 * <h3>Redis vs Kafka Trade-off:</h3>
 *
 * <table border="1">
 *   <tr><th>Aspect</th><th>Redis (Phase 1)</th><th>Kafka (Phase 8)</th></tr>
 *   <tr><td>Throughput</td><td>~10K msg/s</td><td>~100K+ msg/s</td></tr>
 *   <tr><td>Persistence</td><td>AOF (configurable)</td><td>Log-based (durable)</td></tr>
 *   <tr><td>Replay</td><td>Limited</td><td>Offset-based replay</td></tr>
 *   <tr><td>Operations</td><td>Already using Redis</td><td>New infrastructure</td></tr>
 *   <tr><td>Cost</td><td>Free (using existing)</td><td>$$ (new cluster)</td></tr>
 *   <tr><td>Latency</td><td>< 1ms (in-memory)</td><td>~5-10ms (network)</td></tr>
 * </table>
 *
 * <h3>Migration Path (Redis → Kafka):</h3>
 *
 * <ol>
 *   <li>Deploy Kafka cluster (AWS MSK, Confluent Cloud, or self-hosted)
 *   <li>Add spring-kafka to build.gradle (optional dependency)
 *   <li>Complete TODO items in this class
 *   <li>Change configuration: {@code app.event-publisher.type=kafka}
 *   <li>Verify: Zero code changes in business logic (DIP works!)
 * </ol>
 *
 * @see EventPublisher
 * @see maple.expectation.infrastructure.messaging.RedisEventPublisher
 * @see ADR-018 Strategy Pattern for ACL
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "app.event-publisher",
    name = "type",
    havingValue = "kafka",
    matchIfMissing = false)
public class KafkaEventPublisher implements EventPublisher {

  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;

  // TODO: Phase 8 - Inject KafkaTemplate for actual Kafka publishing
  // private final KafkaTemplate<String, String> kafkaTemplate;

  public KafkaEventPublisher(ObjectMapper objectMapper, LogicExecutor executor) {
    this.objectMapper = objectMapper;
    this.executor = executor;
  }

  @Override
  public void publish(String topic, IntegrationEvent<?> event) {
    executor.executeVoid(
        () -> publishInternal(topic, event),
        TaskContext.of("KafkaEventPublisher", "Publish", topic));
  }

  /**
   * Internal publish implementation with checked exceptions.
   *
   * <p><strong>TODO (Phase 8):</strong> Replace stub with real Kafka publishing:
   *
   * <pre>{@code
   * private void publishInternal(String topic, IntegrationEvent<?> event) throws Exception {
   *   String jsonPayload = objectMapper.writeValueAsString(event);
   *
   *   // TODO: Use KafkaTemplate.send() which returns ListenableFuture
   *   // RecordMetadata metadata = kafkaTemplate.send(topic, jsonPayload).get();
   *
   *   log.info("[KafkaEventPublisher] Published to topic {}: partition={}, offset={}, eventId={}",
   *       topic, metadata.partition(), metadata.offset(), event.getEventId());
   * }
   * }</pre>
   *
   * <p>Wrapped by LogicExecutor.executeWithTranslation() which translates checked exceptions to
   * QueuePublishException.
   */
  private void publishInternal(String topic, IntegrationEvent<?> event) throws Exception {
    // TODO: Phase 8 - Remove stub implementation, use KafkaTemplate.send()
    // Serialize IntegrationEvent to JSON (prepare for Kafka)
    String jsonPayload = objectMapper.writeValueAsString(event);

    // Stub: Log instead of actual Kafka publish
    log.warn(
        "[KafkaEventPublisher] STUB MODE - Event not published to Kafka. "
            + "topic={}, eventId={}, eventType={}, payload={}",
        topic,
        event.getEventId(),
        event.getEventType(),
        jsonPayload);

    // TODO: Phase 8 - Uncomment and implement real Kafka publishing
    // try {
    //   ProducerRecord<String, String> record = new ProducerRecord<>(topic, jsonPayload);
    //   RecordMetadata metadata = kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
    //
    //   log.info("[KafkaEventPublisher] Published to topic {}: partition={}, offset={},
    // eventId={}",
    //       topic, metadata.partition(), metadata.offset(), event.getEventId());
    // } catch (TimeoutException e) {
    //   throw new QueuePublishException(
    //       String.format("Kafka publish timeout: topic=%s, eventId=%s", topic,
    // event.getEventId()),
    //       e);
    // } catch (KafkaException e) {
    //   throw new QueuePublishException(
    //       String.format("Kafka publish failed: topic=%s, eventId=%s", topic, event.getEventId()),
    //       e);
    // }
  }

  @Override
  public java.util.concurrent.CompletableFuture<Void> publishAsync(
      String topic, IntegrationEvent<?> event) {

    // TODO: Phase 8 - Use KafkaTemplate's native CompletableFuture support
    // return kafkaTemplate.send(topic, objectMapper.writeValueAsString(event))
    //     .thenApply(metadata -> {
    //       log.info("[KafkaEventPublisher] Published async: topic={}, partition={}, offset={}",
    //           topic, metadata.partition(), metadata.offset());
    //       return null;
    //     });

    // Stub: Log async publish
    log.warn(
        "[KafkaEventPublisher] STUB MODE - Async publish not implemented. topic={}, eventId={}",
        topic,
        event.getEventId());

    return EventPublisher.super.publishAsync(topic, event);
  }
}
