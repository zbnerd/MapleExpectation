package maple.expectation.service.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import maple.expectation.application.port.MessageQueue;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.repository.v2.NexonCharacterRepository;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.testcontainers.shaded.org.awaitility.Awaitility;

/**
 * End-to-End integration tests for ACL Pipeline (Issue #300).
 *
 * <h4>Test Scope</h4>
 *
 * <ul>
 *   <li>MessageQueue (Redis) → BatchWriter → MySQL flow
 *   <li>IntegrationEvent JSON serialization/deserialization
 *   <li>Database persistence via JDBC batch upsert
 * </ul>
 *
 * <h4>Test Infrastructure</h4>
 *
 * <ul>
 *   <li>Testcontainers for MySQL and Redis
 *   <li>Real MessageQueue, BatchWriter, Repository components
 *   <li>Direct IntegrationEvent creation (no NexonDataCollector)
 * </ul>
 *
 * @see BatchWriter
 * @see MessageQueue
 * @see NexonCharacterRepository
 */
@Tag("integration")
@DisplayName("ACL Pipeline Integration Tests")
class AclPipelineIntegrationTest extends AbstractContainerBaseTest {

  @Autowired private BatchWriter batchWriter;

  @Autowired
  @Qualifier("nexonDataQueue") private MessageQueue<String> nexonDataQueue;

  @Autowired private NexonCharacterRepository repository;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private LogicExecutor executor;

  @BeforeEach
  void setUp() {
    // Clear queue and database before each test
    while (nexonDataQueue.poll() != null) {
      // Drain queue
    }
    repository.deleteAll();
  }

  @Nested
  @DisplayName("MessageQueue → BatchWriter → Database Flow")
  class QueueToDatabaseFlow {

    @Test
    @DisplayName("IntegrationEvent JSON 직렬화 후 큐에 저장")
    void eventSerialization_JsonToQueue() throws Exception {
      // Given
      NexonApiCharacterData testData =
          NexonApiCharacterData.builder()
              .ocid("test-ocid-001")
              .characterName("TestChar")
              .characterLevel(250)
              .worldName("스카니아")
              .characterClass("제로")
              .build();

      IntegrationEvent<NexonApiCharacterData> event =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", testData);

      // When - JSON 직렬화 후 큐에 저장
      String json = objectMapper.writeValueAsString(event);
      boolean offered = nexonDataQueue.offer(json);

      // Then - 큐에 성공적으로 저장
      assertThat(offered).isTrue();

      // And - 큐에서 메시지 확인
      String polledJson = nexonDataQueue.poll();
      assertThat(polledJson).isNotNull();

      // And - JSON 역직렬화 검증
      IntegrationEvent<NexonApiCharacterData> deserializedEvent =
          objectMapper.readValue(
              polledJson, new TypeReference<IntegrationEvent<NexonApiCharacterData>>() {});

      assertThat(deserializedEvent.getEventType()).isEqualTo("NEXON_DATA_COLLECTED");
      assertThat(deserializedEvent.getPayload().getOcid()).isEqualTo("test-ocid-001");
      assertThat(deserializedEvent.getPayload().getCharacterName()).isEqualTo("TestChar");
    }

    @Test
    @DisplayName("큐 → BatchWriter → Database 전체 흐름")
    void queueToBatchWriterToDatabase() throws Exception {
      // Given - 3개의 IntegrationEvent를 JSON으로 변환하여 큐에 저장
      NexonApiCharacterData data1 =
          NexonApiCharacterData.builder()
              .ocid("flow-test-001")
              .characterName("FlowTestChar1")
              .characterLevel(200)
              .worldName("스카니아")
              .characterClass("제로")
              .build();

      NexonApiCharacterData data2 =
          NexonApiCharacterData.builder()
              .ocid("flow-test-002")
              .characterName("FlowTestChar2")
              .characterLevel(250)
              .worldName("스카니아")
              .characterClass("제로")
              .build();

      IntegrationEvent<NexonApiCharacterData> event1 =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", data1);
      IntegrationEvent<NexonApiCharacterData> event2 =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", data2);

      String json1 = objectMapper.writeValueAsString(event1);
      String json2 = objectMapper.writeValueAsString(event2);

      nexonDataQueue.offer(json1);
      nexonDataQueue.offer(json2);

      // When - BatchWriter 실행
      batchWriter.processBatch();

      // Then - 데이터베이스에 저장 확인
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                List<NexonApiCharacterData> allData = repository.findAll();
                assertThat(allData).hasSizeGreaterThanOrEqualTo(2);
                assertThat(allData).anyMatch(d -> "flow-test-001".equals(d.getOcid()));
                assertThat(allData).anyMatch(d -> "flow-test-002".equals(d.getOcid()));
              });
    }
  }

  @Nested
  @DisplayName("Boundary Conditions")
  class BoundaryConditions {

    @Test
    @DisplayName("빈 큐에서 BatchWriter 동작 (no-op)")
    void emptyQueue_BatchWriterNoOp() {
      // Given - 큐가 비어있음
      assertThat(nexonDataQueue.poll()).isNull();

      // When - 배치 처리
      batchWriter.processBatch();

      // Then - 예외 없이 정상 완료
      List<NexonApiCharacterData> allData = repository.findAll();
      assertThat(allData).isEmpty();
    }

    @Test
    @DisplayName("배치 크기 미만의 메시지 처리")
    void lessThanBatchSize_ProcessAllMessages() throws Exception {
      // Given - 2개의 메시지만 있는 경우 (BATCH_SIZE = 1000)
      NexonApiCharacterData data1 =
          NexonApiCharacterData.builder()
              .ocid("small-batch-001")
              .characterName("SmallBatchChar1")
              .characterLevel(200)
              .build();

      NexonApiCharacterData data2 =
          NexonApiCharacterData.builder()
              .ocid("small-batch-002")
              .characterName("SmallBatchChar2")
              .characterLevel(200)
              .build();

      IntegrationEvent<NexonApiCharacterData> event1 =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", data1);
      IntegrationEvent<NexonApiCharacterData> event2 =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", data2);

      nexonDataQueue.offer(objectMapper.writeValueAsString(event1));
      nexonDataQueue.offer(objectMapper.writeValueAsString(event2));

      // When
      batchWriter.processBatch();

      // Then - 2개 모두 처리됨
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                List<NexonApiCharacterData> allData = repository.findAll();
                assertThat(allData).anyMatch(d -> "small-batch-001".equals(d.getOcid()));
                assertThat(allData).anyMatch(d -> "small-batch-002".equals(d.getOcid()));
              });
    }

    @Test
    @DisplayName("중복 OCID upsert 동작")
    void duplicateOcid_UpsertUpdatesExisting() throws Exception {
      // Given - 같은 OCID로 2번 수집
      String ocid = "duplicate-test-ocid";

      NexonApiCharacterData data1 =
          NexonApiCharacterData.builder()
              .ocid(ocid)
              .characterName("DuplicateCharV1")
              .characterLevel(200)
              .build();

      IntegrationEvent<NexonApiCharacterData> event1 =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", data1);
      nexonDataQueue.offer(objectMapper.writeValueAsString(event1));

      // When - 첫 번째 배치 처리
      batchWriter.processBatch();

      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                List<NexonApiCharacterData> allData = repository.findAll();
                assertThat(allData).anyMatch(d -> ocid.equals(d.getOcid()));
              });

      // Given - 같은 OCID로 업데이트된 데이터
      NexonApiCharacterData data2 =
          NexonApiCharacterData.builder()
              .ocid(ocid)
              .characterName("DuplicateCharV2")
              .characterLevel(250)
              .build();

      IntegrationEvent<NexonApiCharacterData> event2 =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", data2);
      nexonDataQueue.offer(objectMapper.writeValueAsString(event2));

      // When - 두 번째 배치 처리
      batchWriter.processBatch();

      // Then - 1개의 레코드만 존재 (upsert)
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                List<NexonApiCharacterData> allData = repository.findAll();
                long count = allData.stream().filter(d -> ocid.equals(d.getOcid())).count();
                assertThat(count).isEqualTo(1);

                // Verify it was updated
                assertThat(allData)
                    .anyMatch(
                        d ->
                            ocid.equals(d.getOcid())
                                && "DuplicateCharV2".equals(d.getCharacterName()));
              });
    }
  }

  @Nested
  @DisplayName("IntegrationEvent Serialization Tests")
  class EventSerializationTests {

    @Test
    @DisplayName("IntegrationEvent JSON 직렬화/역직렬화 검증")
    void eventSerialization_JsonRoundTrip() throws Exception {
      // Given
      NexonApiCharacterData data =
          NexonApiCharacterData.builder()
              .ocid("serialize-test-ocid")
              .characterName("SerializeTest")
              .characterLevel(200)
              .worldName("스카니아")
              .characterClass("제로")
              .build();

      IntegrationEvent<NexonApiCharacterData> originalEvent =
          IntegrationEvent.of("NEXON_DATA_COLLECTED", data);

      // When - JSON 직렬화
      String json = objectMapper.writeValueAsString(originalEvent);

      // Then - JSON 구조 검증
      assertThat(json).contains("\"eventId\"");
      assertThat(json).contains("\"eventType\":\"NEXON_DATA_COLLECTED\"");
      assertThat(json).contains("\"payload\"");
      assertThat(json).contains("\"ocid\":\"serialize-test-ocid\"");

      // When - JSON 역직렬화
      IntegrationEvent<NexonApiCharacterData> deserializedEvent =
          objectMapper.readValue(
              json, new TypeReference<IntegrationEvent<NexonApiCharacterData>>() {});

      // Then - 데이터 무결성 검증
      assertThat(deserializedEvent.getEventId()).isEqualTo(originalEvent.getEventId());
      assertThat(deserializedEvent.getEventType()).isEqualTo(originalEvent.getEventType());
      assertThat(deserializedEvent.getPayload().getOcid()).isEqualTo(data.getOcid());
      assertThat(deserializedEvent.getPayload().getCharacterName()).isEqualTo("SerializeTest");
      assertThat(deserializedEvent.getPayload().getCharacterLevel()).isEqualTo(200);
    }
  }
}
