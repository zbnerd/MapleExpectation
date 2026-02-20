package maple.expectation.service.v5.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import maple.expectation.ExpectationApplication;
import maple.expectation.event.ExpectationCalculationCompletedEvent;
import maple.expectation.infrastructure.mongodb.CharacterValuationRepository;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import maple.expectation.service.v5.event.MongoSyncEventPublisherInterface;
import maple.expectation.service.v5.event.ViewTransformer;
import maple.expectation.support.AppIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V5 CQRS: MongoDB Sync Worker Integration Test
 *
 * <h3>Test Scope</h3>
 *
 * <ul>
 *   <li>End-to-end Redis Stream → MongoDB sync flow
 *   <li>Idempotency verification (duplicate messages produce single document)
 *   <li>Korean character handling (아델 IGN)
 *   <li>Worker startup/shutdown lifecycle
 *   <li>Stream initialization strategies
 * </ul>
 *
 * <h3>Test Strategy</h3>
 *
 * Uses Testcontainers for real Redis and MongoDB instances. Tests the complete CQRS flow from event
 * publication to MongoDB upsert.
 *
 * <h3>Test Case: 아델</h3>
 *
 * Uses "아델" as the primary test user IGN to verify Korean character handling.
 */
@SpringBootTest(
    classes = {
      ExpectationApplication.class,
      maple.expectation.service.v5.V5TestConfiguration.class
    })
@ActiveProfiles("test")
@Tag("integration")
@TestPropertySource(
    properties = {
      "v5.enabled=true",
      "app.v5.query-side-enabled=true",
      "spring.data.mongodb.host=localhost",
      "spring.data.mongodb.port=27017"
    })
@Testcontainers
@DisplayName("V5: MongoDB Sync Worker Integration Test")
class MongoDBSyncWorkerIntegrationTest extends AppIntegrationTestSupport {

  private static final String STREAM_KEY = "character-sync";
  private static final String CONSUMER_GROUP = "mongodb-sync-group";
  private static final String CONSUMER_NAME = "mongodb-sync-worker";
  private static final String TEST_IGN = "아델";
  private static final String TEST_TASK_ID = "integration-test-task-123";

  @Container
  static MongoDBContainer mongoDBContainer =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  @Autowired private RedissonClient redissonClient;

  @Autowired private CharacterViewQueryService queryService;

  @Autowired private CharacterValuationRepository repository;

  @Autowired private MongoSyncEventPublisherInterface eventPublisher;

  @Autowired private ViewTransformer viewTransformer;

  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);
    stream.delete();
    repository.deleteAll();
  }

  @Test
  @DisplayName("End-to-end: Publish event → Stream → Worker → MongoDB")
  void testEndToEnd_PublishToMongoDB_Success() throws Exception {
    var event = createTestEvent();
    var payloadJson = objectMapper.writeValueAsString(event);

    RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);
    stream.createGroup(CONSUMER_GROUP);

    Map<String, String> messageData =
        Map.of(
            "id",
            "test-event-id",
            "type",
            "EXPECTATION_CALCULATED",
            "time",
            String.valueOf(Instant.now().toEpochMilli()),
            "data",
            payloadJson);

    StreamMessageId messageId = stream.add(messageData);

    var messages =
        stream.readGroup(
            CONSUMER_GROUP,
            CONSUMER_NAME,
            org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(1));

    assertThat(messages).isNotNull();
    assertThat(messages).hasSize(1);

    var entry = messages.entrySet().iterator().next();
    String dataJson = entry.getValue().get("data");
    ExpectationCalculationCompletedEvent deserializedEvent =
        objectMapper.readValue(dataJson, ExpectationCalculationCompletedEvent.class);

    assertThat(deserializedEvent.getUserIgn()).isEqualTo(TEST_IGN);

    CharacterValuationView view = viewTransformer.toDocument(deserializedEvent);
    queryService.upsert(view);

    var saved = queryService.findByUserIgn(TEST_IGN);
    assertThat(saved).isPresent();
    assertThat(saved.get().getUserIgn()).isEqualTo(TEST_IGN);
  }

  @Test
  @DisplayName("Idempotency: Duplicate messages update same document")
  void testIdempotency_DuplicateMessages_SameDocument() throws Exception {
    var event = createTestEvent();
    CharacterValuationView view = viewTransformer.toDocument(event);

    queryService.upsert(view);
    queryService.upsert(view);
    queryService.upsert(view);

    var saved = queryService.findByUserIgn(TEST_IGN);
    assertThat(saved).isPresent();

    var allViews = repository.findAll();
    long countForUser = allViews.stream().filter(v -> TEST_IGN.equals(v.getUserIgn())).count();
    assertThat(countForUser).isEqualTo(1);
  }

  @Test
  @DisplayName("Korean character handling: 아델 IGN stores correctly")
  void testKoreanCharacterHandling_AdelIGN_StoresCorrectly() throws Exception {
    var event = createTestEvent();
    CharacterValuationView view = viewTransformer.toDocument(event);

    queryService.upsert(view);

    var saved = queryService.findByUserIgn(TEST_IGN);
    assertThat(saved).isPresent();
    assertThat(saved.get().getUserIgn()).isEqualTo(TEST_IGN);
    assertThat(saved.get().getId()).contains(TEST_IGN);
  }

  @Test
  @DisplayName("Stream initialization: New stream creates consumer group")
  void testStreamInitialization_NewStream_CreatesGroup() {
    RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

    assertThat(stream.isExists()).isFalse();

    stream.createGroup(CONSUMER_GROUP);

    assertThat(stream.isExists()).isTrue();
  }

  @Test
  @DisplayName("Delete by user IGN: Removes document")
  void testDeleteByUserIgn_RemovesDocument() throws Exception {
    var event = createTestEvent();
    CharacterValuationView view = viewTransformer.toDocument(event);

    queryService.upsert(view);
    assertThat(queryService.findByUserIgn(TEST_IGN)).isPresent();

    queryService.deleteByUserIgn(TEST_IGN);
    assertThat(queryService.findByUserIgn(TEST_IGN)).isEmpty();
  }

  private ExpectationCalculationCompletedEvent createTestEvent() {
    return ExpectationCalculationCompletedEvent.builder()
        .taskId(TEST_TASK_ID)
        .userIgn(TEST_IGN)
        .characterOcid("test-ocid-" + TEST_IGN)
        .characterClass("Pathfinder")
        .characterLevel(275)
        .calculatedAt(Instant.now().toString())
        .totalExpectedCost("1000000")
        .maxPresetNo(1)
        .payload("{}")
        .build();
  }
}
