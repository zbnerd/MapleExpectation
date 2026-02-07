package maple.expectation.domain.event;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IntegrationEvent}.
 *
 * <p><strong>Test Coverage:</strong>
 * <ul>
 *   <li>Factory method creates event with generated metadata</li>
 *   <li>Explicit constructor allows custom metadata (for testing/replay)</li>
 *   <li>Getter methods return correct values</li>
 * </ul>
 */
@DisplayName("IntegrationEvent Tests")
class IntegrationEventTest {

  @Test
  @DisplayName("of() should create event with generated metadata")
  void testFactoryMethod_GeneratesMetadata() {
    // Given
    String eventType = "TEST_EVENT";
    String payload = "test-payload";

    // When
    IntegrationEvent<String> event = IntegrationEvent.of(eventType, payload);

    // Then
    assertNotNull(event.getEventId());
    assertTrue(UUID.fromString(event.getEventId()) instanceof UUID, "eventId should be valid UUID");

    assertEquals(eventType, event.getEventType());

    assertNotNull(event.getTimestamp());
    assertTrue(event.getTimestamp() > 0, "timestamp should be positive");
    assertTrue(event.getTimestamp() <= Instant.now().toEpochMilli(), "timestamp should be current or past");

    assertEquals(payload, event.getPayload());
  }

  @Test
  @DisplayName("of() with explicit metadata should create event with provided values")
  void testFactoryMethod_ExplicitMetadata() {
    // Given
    String eventId = "test-event-id-123";
    String eventType = "TEST_EVENT";
    long timestamp = 1704067200000L; // 2024-01-01 00:00:00 UTC
    String payload = "test-payload";

    // When
    IntegrationEvent<String> event = IntegrationEvent.of(eventId, eventType, timestamp, payload);

    // Then
    assertEquals(eventId, event.getEventId());
    assertEquals(eventType, event.getEventType());
    assertEquals(timestamp, event.getTimestamp());
    assertEquals(payload, event.getPayload());
  }

  @Test
  @DisplayName("Multiple events should have unique IDs")
  void testMultipleEvents_UniqueIds() {
    // Given
    String eventType = "TEST_EVENT";
    String payload = "test-payload";

    // When
    IntegrationEvent<String> event1 = IntegrationEvent.of(eventType, payload);
    IntegrationEvent<String> event2 = IntegrationEvent.of(eventType, payload);

    // Then
    assertNotEquals(event1.getEventId(), event2.getEventId(),
        "Each event should have unique UUID");
  }

  @Test
  @DisplayName("Events created in quick succession should have increasing timestamps")
  void testQuickSuccession_IncreasingTimestamps() {
    // Given
    String eventType = "TEST_EVENT";
    String payload = "test-payload";

    // When
    IntegrationEvent<String> event1 = IntegrationEvent.of(eventType, payload);
    IntegrationEvent<String> event2 = IntegrationEvent.of(eventType, payload);

    // Then
    assertTrue(event2.getTimestamp() >= event1.getTimestamp(),
        "Timestamps should be monotonically increasing");
  }

  @Test
  @DisplayName("Generic payload type should work with custom objects")
  void testGenericPayload_CustomObject() {
    // Given
    class TestPayload {
      private final String name;
      private final int value;

      TestPayload(String name, int value) {
        this.name = name;
        this.value = value;
      }

      String getName() { return name; }
      int getValue() { return value; }
    }

    TestPayload payload = new TestPayload("test", 123);

    // When
    IntegrationEvent<TestPayload> event = IntegrationEvent.of("TEST_EVENT", payload);

    // Then
    assertEquals(payload, event.getPayload());
    assertEquals("test", event.getPayload().getName());
    assertEquals(123, event.getPayload().getValue());
  }
}
