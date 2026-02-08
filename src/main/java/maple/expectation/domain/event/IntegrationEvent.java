package maple.expectation.domain.event;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Standardized event envelope for all integration events.
 *
 * <p>Ensures consistent metadata (eventId, eventType, timestamp) across all message types. This is
 * part of the Anti-Corruption Layer (ACL) that isolates external systems from internal processing
 * pipelines.
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - event metadata container
 *   <li><b>OCP:</b> Open for extension (generic T), closed for modification
 *   <li><b>DIP:</b> Domain layer doesn't depend on infrastructure
 * </ul>
 *
 * @param <T> Payload type (must be serializable to JSON)
 * @see ADR-018 Strategy Pattern for ACL
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class IntegrationEvent<T> {

  /**
   * Unique event identifier for tracing and deduplication. Uses UUID to ensure global uniqueness
   * across distributed systems.
   */
  private String eventId;

  /**
   * Event type identifier (e.g., "NEXON_DATA_COLLECTED", "CHARACTER_UPDATED"). Used for event
   * routing and filtering.
   */
  private String eventType;

  /**
   * Event creation timestamp in epoch milliseconds. Used for event ordering and latency
   * measurement.
   */
  private long timestamp;

  /**
   * Actual event payload. Can be any domain object that needs to be transmitted through the
   * pipeline.
   */
  private T payload;

  /**
   * Create a new event with auto-generated metadata.
   *
   * <p>This factory method encapsulates event creation logic:
   *
   * <ul>
   *   <li>eventId: UUID generation
   *   <li>timestamp: Current time in epoch millis
   * </ul>
   *
   * @param type Event type identifier
   * @param payload Event payload (domain object)
   * @return New IntegrationEvent instance with generated metadata
   */
  public static <T> IntegrationEvent<T> of(String type, T payload) {
    return new IntegrationEvent<>(
        UUID.randomUUID().toString(), type, Instant.now().toEpochMilli(), payload);
  }

  /**
   * Create a new event with explicit metadata (for testing/replay).
   *
   * @param eventId Event ID
   * @param eventType Event type
   * @param timestamp Event timestamp
   * @param payload Event payload
   * @return New IntegrationEvent instance
   */
  public static <T> IntegrationEvent<T> of(
      String eventId, String eventType, long timestamp, T payload) {
    return new IntegrationEvent<T>(eventId, eventType, timestamp, payload);
  }
}
