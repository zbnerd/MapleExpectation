package maple.expectation.service.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.application.port.MessageQueue;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.NexonCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BatchWriter}.
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li>Empty queue results in no-op
 *   <li>Batch accumulation stops at BATCH_SIZE
 *   <li>Repository batchUpsert is called with extracted payloads
 *   <li>Scheduled execution uses LogicExecutor
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchWriter Tests")
class BatchWriterTest {

  @Mock private MessageQueue<String> messageQueue;

  @Mock private NexonCharacterRepository repository;

  @Mock private LogicExecutor executor;

  private ObjectMapper objectMapper; // Real instance for actual JSON serialization

  private BatchWriter batchWriter;

  @BeforeEach
  void setUp() {
    maple.expectation.config.BatchProperties batchProperties =
        maple.expectation.config.BatchProperties.defaults();

    // Use real ObjectMapper for actual JSON deserialization
    objectMapper = new ObjectMapper();

    batchWriter =
        new BatchWriter(messageQueue, repository, executor, objectMapper, batchProperties);

    // Setup LogicExecutor to execute directly (synchronous for testing)
    doAnswer(
            invocation -> {
              maple.expectation.infrastructure.executor.function.ThrowingRunnable task =
                  invocation.getArgument(0);
              task.run(); // Execute the lambda
              return null;
            })
        .when(executor)
        .executeVoid(any(), any(TaskContext.class));

    // Configure executeOrDefault to execute the supplier and return its result
    // Use lenient() to avoid "unnecessary stubbing" warnings when not all stubbings are used
    lenient()
        .doAnswer(
            invocation -> {
              ThrowingSupplier<?> supplier = invocation.getArgument(0);
              try {
                return supplier.get(); // Execute and return result
              } catch (Throwable e) {
                throw new RuntimeException(e);
              }
            })
        .when(executor)
        .executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class));
  }

  @Test
  @DisplayName("processBatch() should do nothing when queue is empty")
  void testProcessBatch_EmptyQueue() {
    // Given
    when(messageQueue.poll()).thenReturn(null);

    // When
    batchWriter.processBatch();

    // Then
    verify(repository, never()).batchUpsert(any());
  }

  @Test
  @DisplayName("processBatch() should process single message")
  void testProcessBatch_SingleMessage() {
    // Given - Proper JSON structure matching IntegrationEvent format
    String jsonPayload =
        """
        {"eventId":"test-event-1","eventType":"TEST_EVENT","timestamp":1234567890,"payload":{"ocid":"test-ocid","character_name":"TestChar"}}
        """;

    when(messageQueue.poll()).thenReturn(jsonPayload).thenReturn(null);

    // When
    batchWriter.processBatch();

    // Then
    verify(repository)
        .batchUpsert(
            argThat(list -> list.size() == 1 && list.get(0).getOcid().equals("test-ocid")));
  }

  @Test
  @DisplayName("processBatch() should process multiple messages up to BATCH_SIZE")
  void testProcessBatch_MultipleMessages() {
    // Given - Create 3 events with proper JSON structure matching IntegrationEvent
    String json1 =
        """
        {"eventId":"event-1","eventType":"TEST_EVENT","timestamp":1234567890,"payload":{"ocid":"ocid-1","character_name":"Char-1"}}
        """;
    String json2 =
        """
        {"eventId":"event-2","eventType":"TEST_EVENT","timestamp":1234567891,"payload":{"ocid":"ocid-2","character_name":"Char-2"}}
        """;
    String json3 =
        """
        {"eventId":"event-3","eventType":"TEST_EVENT","timestamp":1234567892,"payload":{"ocid":"ocid-3","character_name":"Char-3"}}
        """;

    // Mock queue to return 3 JSON strings, then null
    when(messageQueue.poll())
        .thenReturn(json1)
        .thenReturn(json2)
        .thenReturn(json3)
        .thenReturn(null);

    // When
    batchWriter.processBatch();

    // Then
    verify(repository)
        .batchUpsert(argThat(list -> list.size() == 3 && list.get(0).getOcid().equals("ocid-1")));
  }

  @Test
  @DisplayName("processBatch() should limit batch size to BATCH_SIZE")
  void testProcessBatch_BatchSizeLimit() {
    // Given - Create 3 events with proper JSON structure matching IntegrationEvent
    String json1 =
        """
        {"eventId":"event-1","eventType":"TEST_EVENT","timestamp":1234567890,"payload":{"ocid":"ocid-1","character_name":"Char-1"}}
        """;
    String json2 =
        """
        {"eventId":"event-2","eventType":"TEST_EVENT","timestamp":1234567891,"payload":{"ocid":"ocid-2","character_name":"Char-2"}}
        """;
    String json3 =
        """
        {"eventId":"event-3","eventType":"TEST_EVENT","timestamp":1234567892,"payload":{"ocid":"ocid-3","character_name":"Char-3"}}
        """;

    // Mock queue to return 3 JSON strings
    when(messageQueue.poll())
        .thenReturn(json1)
        .thenReturn(json2)
        .thenReturn(json3)
        .thenReturn(null);

    // When
    batchWriter.processBatch();

    // Then - Should process all 3 events
    verify(repository)
        .batchUpsert(argThat(list -> list.size() == 3 && list.get(2).getOcid().equals("ocid-3")));

    // Verify poll called 4 times (3 events + 1 null check)
    verify(messageQueue, times(4)).poll();
  }

  @Test
  @DisplayName("processBatch() should extract payloads from IntegrationEvent")
  void testProcessBatch_PayloadExtraction() {
    // Given - Proper JSON structure with all fields matching IntegrationEvent
    String jsonPayload =
        """
        {"eventId":"test-event-1","eventType":"TEST_EVENT","timestamp":1234567890,"payload":{"ocid":"test-ocid","character_name":"TestChar","character_level":200}}
        """;

    when(messageQueue.poll()).thenReturn(jsonPayload).thenReturn(null);

    // When
    batchWriter.processBatch();

    // Then
    verify(repository)
        .batchUpsert(
            argThat(
                list ->
                    list.size() == 1
                        && list.get(0).getOcid().equals("test-ocid")
                        && list.get(0).getCharacterName().equals("TestChar")
                        && list.get(0).getCharacterLevel() == 200));
  }
}
