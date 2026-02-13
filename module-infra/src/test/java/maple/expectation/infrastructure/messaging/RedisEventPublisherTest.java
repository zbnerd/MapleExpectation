package maple.expectation.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.application.port.MessageQueue;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.error.exception.QueuePublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RedisEventPublisher}.
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Successful publish serializes event and calls MessageQueue.offer()
 *   <li>Publish failure throws QueuePublishException when queue is full
 *   <li>Async publish completes successfully
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisEventPublisher Tests")
class RedisEventPublisherTest {

  @Mock private MessageQueue<String> messageQueue;

  private ObjectMapper objectMapper;
  private RedisEventPublisher publisher;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    // Configure ObjectMapper to handle empty beans
    objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
    publisher = new RedisEventPublisher(messageQueue, objectMapper);
  }

  @Test
  @DisplayName("publish() should serialize event and call MessageQueue.offer()")
  void testPublish_Success() throws Exception {
    // Given
    String topic = "test-topic";
    IntegrationEvent<TestPayload> event =
        IntegrationEvent.of("TEST_EVENT", new TestPayload("test-data", 123));
    when(messageQueue.offer(anyString())).thenReturn(true);

    // When
    publisher.publish(topic, event);

    // Then
    verify(messageQueue).offer(anyString());
  }

  @Test
  @DisplayName("publish() should throw QueuePublishException when queue is full")
  void testPublish_QueueFull() {
    // Given
    String topic = "test-topic";
    IntegrationEvent<String> event = IntegrationEvent.of("TEST_EVENT", "payload");
    when(messageQueue.offer(anyString())).thenReturn(false);

    // When & Then
    assertThrows(QueuePublishException.class, () -> publisher.publish(topic, event));
  }

  @Test
  @DisplayName("publish() should throw QueuePublishException on MessageQueue failure")
  void testPublish_MessageQueueFailure() {
    // Given
    String topic = "test-topic";
    IntegrationEvent<String> event = IntegrationEvent.of("TEST_EVENT", "payload");

    doThrow(new RuntimeException("Redis connection failed")).when(messageQueue).offer(anyString());

    // When & Then
    assertThrows(QueuePublishException.class, () -> publisher.publish(topic, event));
  }

  @Test
  @DisplayName("publish() should throw QueuePublishException on serialization failure")
  void testPublish_SerializationFailure() throws Exception {
    // Given - Create a new publisher with a failing ObjectMapper
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonGenerationException("Serialization failed"));

    RedisEventPublisher failingPublisher = new RedisEventPublisher(messageQueue, failingMapper);

    String topic = "test-topic";
    IntegrationEvent<String> event = IntegrationEvent.of("TEST_EVENT", "payload");

    // When & Then
    assertThrows(QueuePublishException.class, () -> failingPublisher.publish(topic, event));
  }

  @Test
  @DisplayName("publishAsync() should complete successfully")
  void testPublishAsync_Success() throws Exception {
    // Given
    String topic = "test-topic";
    IntegrationEvent<String> event = IntegrationEvent.of("TEST_EVENT", "payload");
    when(messageQueue.offer(anyString())).thenReturn(true);

    // When
    var future = publisher.publishAsync(topic, event);

    // Then
    assertNotNull(future);
    future.join(); // Should complete without exception
    verify(messageQueue).offer(anyString());
  }

  @Test
  @DisplayName("publishAsync() should complete exceptionally on failure")
  void testPublishAsync_Failure() {
    // Given
    String topic = "test-topic";
    IntegrationEvent<String> event = IntegrationEvent.of("TEST_EVENT", "payload");

    doThrow(new RuntimeException("Redis connection failed")).when(messageQueue).offer(anyString());

    // When
    var future = publisher.publishAsync(topic, event);

    // Then - CompletableFuture wraps exceptions in CompletionException
    Exception exception =
        assertThrows(java.util.concurrent.CompletionException.class, future::join);
    assertInstanceOf(QueuePublishException.class, exception.getCause());
  }

  // Test payload class
  private static class TestPayload {
    private final String name;
    private final int value;

    TestPayload(String name, int value) {
      this.name = name;
      this.value = value;
    }

    String getName() {
      return name;
    }

    int getValue() {
      return value;
    }
  }
}
