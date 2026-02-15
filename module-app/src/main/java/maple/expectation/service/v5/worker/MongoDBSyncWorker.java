package maple.expectation.service.v5.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.event.ExpectationCalculationCompletedEvent;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: MongoDB Sync Worker - Consumes Redis Stream events
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Consume calculation events from character-sync stream
 *   <li>Transform V4 response to MongoDB view document
 *   <li>Upsert to CharacterValuationView collection
 *   <li>Acknowledge processed messages
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * <ol>
 *   <li>Read from Redis Stream (blocking poll with timeout)
 *   <li>Deserialize event payload
 *   <li>Map to CharacterValuationView
 *   <li>Upsert to MongoDB
 *   <li>ACK message
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class MongoDBSyncWorker implements Runnable {

  private static final String STREAM_KEY = "character-sync";
  private static final String CONSUMER_GROUP = "mongodb-sync-group";
  private static final String CONSUMER_NAME = "mongodb-sync-worker";
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(2000);

  private final RedissonClient redissonClient;
  private final CharacterViewQueryService queryService;
  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;
  private final Counter processedCounter;
  private final Counter errorCounter;

  private Thread workerThread;
  private volatile boolean running = false;

  public MongoDBSyncWorker(
      RedissonClient redissonClient,
      CharacterViewQueryService queryService,
      LogicExecutor executor,
      ObjectMapper objectMapper,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.redissonClient = redissonClient;
    this.queryService = queryService;
    this.executor = executor;
    this.objectMapper = objectMapper;
    this.processedCounter = meterRegistry.counter("mongodb.sync.processed");
    this.errorCounter = meterRegistry.counter("mongodb.sync.errors");
  }

  @PostConstruct
  public void start() {
    initializeStream();
    running = true;
    workerThread =
        new Thread(this, "V5-MongoDBSyncWorker-" + System.currentTimeMillis());
    workerThread.setDaemon(true);
    workerThread.setUncaughtExceptionHandler(
        (t, e) -> {
          log.error("[MongoDBSyncWorker] Thread crashed", e);
          errorCounter.increment();
        });
    workerThread.start();
    log.info("[MongoDBSyncWorker] Worker started");
  }

  @PreDestroy
  public void stop() {
    running = false;
    if (workerThread != null) {
      workerThread.interrupt();
      try {
        workerThread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("[MongoDBSyncWorker] Interrupted during shutdown");
      }
    }
    log.info("[MongoDBSyncWorker] Worker stopped");
  }

  @Override
  public void run() {
    log.info("[MongoDBSyncWorker] Sync worker running");

    while (running && !Thread.currentThread().isInterrupted()) {
      executor.executeVoid(this::processNextBatch, TaskContext.of("MongoDBSyncWorker", "Poll"));
    }

    log.info("[MongoDBSyncWorker] Sync worker stopped");
  }

  private void initializeStream() {
    TaskContext context = TaskContext.of("MongoDBSyncWorker", "InitStream");

    executor.executeVoid(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

          // Create consumer group if not exists
          if (!stream.isExists()) {
            stream.createGroup(
                StreamCreateGroupArgs.name(CONSUMER_GROUP).makeStream());
            log.info("[MongoDBSyncWorker] Created consumer group: {}", CONSUMER_GROUP);
            return;
          }

          // Stream exists, check if group exists
          try {
            stream.readGroup(
                CONSUMER_GROUP,
                CONSUMER_NAME,
                StreamReadGroupArgs.neverDelivered().count(1));
          } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
              stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP));
              log.info("[MongoDBSyncWorker] Consumer group created: {}", CONSUMER_GROUP);
            }
          }
        },
        context);
  }

  private void processNextBatch() {
    try {
      RStream<String, String> stream =
          redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

      // Read with timeout using Redisson RStream API
      Map<StreamMessageId, Map<String, String>> messages =
          stream.readGroup(
              CONSUMER_GROUP,
              CONSUMER_NAME,
              StreamReadGroupArgs.neverDelivered().count(1).timeout(POLL_TIMEOUT));

      if (messages == null || messages.isEmpty()) {
        return;
      }

      for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
        StreamMessageId messageId = entry.getKey();
        Map<String, String> data = entry.getValue();

        try {
          processMessage(messageId, data);
          // ACK message
          stream.ack(CONSUMER_GROUP, messageId);
          processedCounter.increment();
        } catch (Exception e) {
          log.error("[MongoDBSyncWorker] Failed to process message: {}", messageId, e);
          errorCounter.increment();
          // Message will be retried after TTL (pending messages timeout)
        }
      }

    } catch (Exception e) {
      log.error("[MongoDBSyncWorker] Error in processNextBatch", e);
    }
  }

  private void processMessage(StreamMessageId messageId, Map<String, String> data) {
    TaskContext context =
        TaskContext.of("MongoDBSyncWorker", "ProcessMessage", messageId.toString());

    executor.executeVoid(
        () -> {
          try {
            // Deserialize IntegrationEvent wrapper
            String payloadJson = data.get("payload");
            if (payloadJson == null) {
              log.warn("[MongoDBSyncWorker] No payload in message");
              return;
            }

            // Deserialize to ExpectationCalculationCompletedEvent
            ExpectationCalculationCompletedEvent event =
                objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class);

            CharacterValuationView view = toViewDocument(event);
            queryService.upsert(view);

            log.debug(
                "[MongoDBSyncWorker] Synced to MongoDB: userIgn={}, ocid={}",
                event.getUserIgn(),
                event.getCharacterOcid());

          } catch (Exception e) {
            log.error("[MongoDBSyncWorker] Failed to process message: {}", messageId, e);
            throw e;
          }
        },
        context);
  }

  private CharacterValuationView toViewDocument(ExpectationCalculationCompletedEvent event) {
    // TODO: Parse payload JSON and build full view document
    return CharacterValuationView.builder()
        .userIgn(event.getUserIgn())
        .characterOcid(event.getCharacterOcid())
        .characterClass(event.getCharacterClass())
        .characterLevel(event.getCharacterLevel())
        .totalExpectedCost(Integer.parseInt(event.getTotalExpectedCost()))
        .maxPresetNo(event.getMaxPresetNo())
        .calculatedAt(Instant.parse(event.getCalculatedAt()))
        .lastApiSyncAt(Instant.now())
        .version(System.currentTimeMillis())
        .fromCache(false)
        .presets(List.of()) // TODO: Parse from payload
        .build();
  }
}
