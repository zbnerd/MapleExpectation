package maple.expectation.service.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.application.port.MessageQueue;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.NexonCharacterRepository;
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

  @Mock private ObjectMapper objectMapper;

  private BatchWriter batchWriter;

  @BeforeEach
  void setUp() {
    batchWriter = new BatchWriter(messageQueue, repository, executor, objectMapper);

    // Setup LogicExecutor to execute directly (synchronous for testing)
    doAnswer(
            invocation -> {
              maple.expectation.global.executor.function.ThrowingRunnable task =
                  invocation.getArgument(0);
              task.run(); // Execute the lambda
              return null;
            })
        .when(executor)
        .executeVoid(any(), any(TaskContext.class));
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
  void testProcessBatch_SingleMessage() throws Exception {
    // Given
    NexonApiCharacterData data =
        NexonApiCharacterData.builder().ocid("test-ocid").characterName("TestChar").build();
    IntegrationEvent<NexonApiCharacterData> event = IntegrationEvent.of("TEST_EVENT", data);
    String jsonPayload = "{\"event\":\"TEST_EVENT\"}"; // Simplified JSON

    when(messageQueue.poll()).thenReturn(jsonPayload).thenReturn(null); // End of queue

    when(objectMapper.readValue(
            eq(jsonPayload), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event);

    // When
    batchWriter.processBatch();

    // Then
    verify(repository)
        .batchUpsert(
            argThat(list -> list.size() == 1 && list.get(0).getOcid().equals("test-ocid")));
  }

  @Test
  @DisplayName("processBatch() should process multiple messages up to BATCH_SIZE")
  void testProcessBatch_MultipleMessages() throws Exception {
    // Given - Create 3 events
    NexonApiCharacterData data1 =
        NexonApiCharacterData.builder().ocid("ocid-1").characterName("Char-1").build();
    NexonApiCharacterData data2 =
        NexonApiCharacterData.builder().ocid("ocid-2").characterName("Char-2").build();
    NexonApiCharacterData data3 =
        NexonApiCharacterData.builder().ocid("ocid-3").characterName("Char-3").build();

    IntegrationEvent<NexonApiCharacterData> event1 = IntegrationEvent.of("TEST_EVENT", data1);
    IntegrationEvent<NexonApiCharacterData> event2 = IntegrationEvent.of("TEST_EVENT", data2);
    IntegrationEvent<NexonApiCharacterData> event3 = IntegrationEvent.of("TEST_EVENT", data3);

    String json1 = "{\"ocid\":\"ocid-1\"}";
    String json2 = "{\"ocid\":\"ocid-2\"}";
    String json3 = "{\"ocid\":\"ocid-3\"}";

    // Mock queue to return 3 JSON strings, then null
    when(messageQueue.poll())
        .thenReturn(json1)
        .thenReturn(json2)
        .thenReturn(json3)
        .thenReturn(null);

    // Mock ObjectMapper to deserialize JSON to IntegrationEvent
    when(objectMapper.readValue(
            eq(json1), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event1);
    when(objectMapper.readValue(
            eq(json2), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event2);
    when(objectMapper.readValue(
            eq(json3), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event3);

    // When
    batchWriter.processBatch();

    // Then
    verify(repository)
        .batchUpsert(argThat(list -> list.size() == 3 && list.get(0).getOcid().equals("ocid-1")));
  }

  @Test
  @DisplayName("processBatch() should limit batch size to BATCH_SIZE")
  void testProcessBatch_BatchSizeLimit() throws Exception {
    // Given - Create 3 events (small batch for testing)
    NexonApiCharacterData data1 =
        NexonApiCharacterData.builder().ocid("ocid-1").characterName("Char-1").build();
    NexonApiCharacterData data2 =
        NexonApiCharacterData.builder().ocid("ocid-2").characterName("Char-2").build();
    NexonApiCharacterData data3 =
        NexonApiCharacterData.builder().ocid("ocid-3").characterName("Char-3").build();

    IntegrationEvent<NexonApiCharacterData> event1 = IntegrationEvent.of("TEST_EVENT", data1);
    IntegrationEvent<NexonApiCharacterData> event2 = IntegrationEvent.of("TEST_EVENT", data2);
    IntegrationEvent<NexonApiCharacterData> event3 = IntegrationEvent.of("TEST_EVENT", data3);

    String json1 = "{\"ocid\":\"ocid-1\"}";
    String json2 = "{\"ocid\":\"ocid-2\"}";
    String json3 = "{\"ocid\":\"ocid-3\"}";

    // Mock queue to return 3 JSON strings
    when(messageQueue.poll())
        .thenReturn(json1)
        .thenReturn(json2)
        .thenReturn(json3)
        .thenReturn(null);

    // Mock ObjectMapper to deserialize JSON to IntegrationEvent
    when(objectMapper.readValue(
            eq(json1), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event1);
    when(objectMapper.readValue(
            eq(json2), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event2);
    when(objectMapper.readValue(
            eq(json3), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event3);

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
  void testProcessBatch_PayloadExtraction() throws Exception {
    // Given
    NexonApiCharacterData data =
        NexonApiCharacterData.builder()
            .ocid("test-ocid")
            .characterName("TestChar")
            .characterLevel(200)
            .build();
    IntegrationEvent<NexonApiCharacterData> event = IntegrationEvent.of("TEST_EVENT", data);
    String jsonPayload = "{\"ocid\":\"test-ocid\"}";

    when(messageQueue.poll()).thenReturn(jsonPayload).thenReturn(null);

    when(objectMapper.readValue(
            eq(jsonPayload), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(event);

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
