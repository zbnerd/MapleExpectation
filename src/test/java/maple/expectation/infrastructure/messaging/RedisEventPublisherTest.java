package maple.expectation.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.application.port.MessageTopic;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.global.error.exception.QueuePublishException;
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
 * <ul>
 *   <li>Successful publish serializes event and calls MessageTopic</li>
 *   <li>Publish failure throws QueuePublishException</li>
 *   <li>Async publish completes successfully</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisEventPublisher Tests")
class RedisEventPublisherTest {

  @Mock
  private MessageTopic<String> messageTopic;

  private ObjectMapper objectMapper;
  private RedisEventPublisher publisher;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    // Configure ObjectMapper to handle empty beans
    objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
    publisher = new RedisEventPublisher(messageTopic, objectMapper);
  }

  @Test
  @DisplayName("publish() should serialize event and call MessageTopic")
  void testPublish_Success() throws Exception {
    // Given
    String topic = "test-topic";
    IntegrationEvent<TestPayload> event = IntegrationEvent.of(
        "TEST_EVENT",
        new TestPayload("test-data", 123)
    );

    // When
    publisher.publish(topic, event);

    // Then
    verify(messageTopic).publish(eq(topic), anyString());
  }

  @Test
  @DisplayName("publish() should throw QueuePublishException on MessageTopic failure")
  void testPublish_MessageTopicFailure() {
    // Given
    String topic = "test-topic";
    IntegrationEvent<String> event = IntegrationEvent.of("TEST_EVENT", "payload");

    doThrow(new RuntimeException("Redis connection failed"))
        .when(messageTopic).publish(anyString(), anyString());

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

    RedisEventPublisher failingPublisher = new RedisEventPublisher(messageTopic, failingMapper);

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

    // When
    var future = publisher.publishAsync(topic, event);

    // Then
    assertNotNull(future);
    future.join();  // Should complete without exception
    verify(messageTopic).publish(eq(topic), anyString());
  }

  @Test
  @DisplayName("publishAsync() should complete exceptionally on failure")
  void testPublishAsync_Failure() {
    // Given
    String topic = "test-topic";
    IntegrationEvent<String> event = IntegrationEvent.of("TEST_EVENT", "payload");

    doThrow(new RuntimeException("Redis connection failed"))
        .when(messageTopic).publish(anyString(), anyString());

    // When
    var future = publisher.publishAsync(topic, event);

    // Then - CompletableFuture wraps exceptions in CompletionException
    Exception exception = assertThrows(java.util.concurrent.CompletionException.class, future::join);
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

    String getName() { return name; }
    int getValue() { return value; }
  }
}
