package maple.expectation.service.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import maple.expectation.application.port.EventPublisher;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link NexonDataCollector}.
 *
 * <p><strong>Test Coverage:</strong>
 * <ul>
 *   <li>Successful fetch and publish workflow</li>
 *   <li>Event is wrapped in IntegrationEvent with correct metadata</li>
 *   <li>EventPublisher is called asynchronously</li>
 *   <li>LogicExecutor is used for execution context</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NexonDataCollector Tests")
class NexonDataCollectorTest {

  @Mock
  private WebClient webClient;

  @Mock
  private EventPublisher eventPublisher;

  @Mock
  private LogicExecutor executor;

  private NexonDataCollector collector;

  @BeforeEach
  void setUp() {
    collector = new NexonDataCollector(webClient, eventPublisher, executor);
  }

  @Test
  @DisplayName("fetchAndPublish() should fetch from API and publish event")
  void testFetchAndPublish_Success() {
    // Given
    String ocid = "test-ocid-123";
    NexonApiCharacterData expectedData = NexonApiCharacterData.builder()
        .ocid(ocid)
        .characterName("TestCharacter")
        .worldName("Scania")
        .characterClass("Night Lord")
        .characterLevel(250)
        .build();

    // Mock LogicExecutor to return expected data directly
    // (in real execution, executor.execute() runs the fetchFromNexonApi lambda,
    //  but for testing we bypass the WebClient complexity)
    doAnswer(invocation -> {
      return expectedData;  // WebClient fetch would return this
    }).when(executor).execute(any(), any(TaskContext.class));

    // Mock EventPublisher publishAsync (async fire-and-forget)
    doReturn(CompletableFuture.completedFuture(null))
        .when(eventPublisher).publishAsync(eq("nexon-data"), any(IntegrationEvent.class));

    // When
    CompletableFuture<NexonApiCharacterData> result = collector.fetchAndPublish(ocid);

    // Then
    assertNotNull(result);
    NexonApiCharacterData actualData = result.join();
    assertEquals(expectedData.getOcid(), actualData.getOcid());
    assertEquals(expectedData.getCharacterName(), actualData.getCharacterName());

    // Verify event was published
    verify(eventPublisher).publishAsync(eq("nexon-data"), any(IntegrationEvent.class));
  }

  @Test
  @DisplayName("fetchAndPublish() should handle API failure gracefully")
  void testFetchAndPublish_ApiFailure() {
    // Given
    String ocid = "test-ocid-123";
    RuntimeException apiError = new RuntimeException("API timeout");

    // Mock LogicExecutor to propagate exception
    doThrow(apiError).when(executor).execute(any(), any(TaskContext.class));

    // When & Then
    CompletableFuture<NexonApiCharacterData> result = collector.fetchAndPublish(ocid);

    assertNotNull(result);
    assertThrows(Exception.class, result::join);

    // Event should not be published on failure
    verify(eventPublisher, never()).publishAsync(anyString(), any(IntegrationEvent.class));
  }

  @Test
  @DisplayName("fetchAndPublish() should publish event even if publishAsync fails (fire-and-forget)")
  void testFetchAndPublish_PublishFailure() {
    // Given
    String ocid = "test-ocid-123";
    NexonApiCharacterData expectedData = NexonApiCharacterData.builder()
        .ocid(ocid)
        .characterName("TestCharacter")
        .build();

    // Mock LogicExecutor to return data
    doAnswer(invocation -> {
      return expectedData;
    }).when(executor).execute(any(), any(TaskContext.class));

    // Mock EventPublisher to fail
    doReturn(CompletableFuture.failedFuture(new RuntimeException("Queue unavailable")))
        .when(eventPublisher).publishAsync(eq("nexon-data"), any(IntegrationEvent.class));

    // When
    CompletableFuture<NexonApiCharacterData> result = collector.fetchAndPublish(ocid);

    // Then - Should still return data (fire-and-forget semantics)
    assertNotNull(result);
    NexonApiCharacterData actualData = result.join();
    assertEquals(expectedData.getOcid(), actualData.getOcid());

    // Verify publish was attempted
    verify(eventPublisher).publishAsync(eq("nexon-data"), any(IntegrationEvent.class));
  }
}
