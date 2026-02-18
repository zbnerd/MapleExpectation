package maple.expectation.service.v5.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.event.ExpectationCalculationCompletedEvent;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterViewQueryService;
import maple.expectation.service.v5.event.ViewTransformer;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: MongoDB Sync Worker - Consumes Redis Stream events
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Consume calculation events from character-sync stream
 *   <li>Delegate transformation to ViewTransformer (SRP)
 *   <li>Upsert to CharacterValuationView collection
 *   <li>Acknowledge processed messages
 * </ul>
 *
 * <h3>Flow</h3>
 *
 * <ol>
 *   <li>Read from Redis Stream (blocking poll with timeout)
 *   <li>Deserialize event payload
 *   <li>Delegate transformation to ViewTransformer
 *   <li>Upsert to MongoDB
 *   <li>ACK message
 * </ol>
 *
 * <h3>Section 12 Compliance (Zero Try-Catch):</h3>
 *
 * <p>All exception handling delegated to LogicExecutor/CheckedLogicExecutor.
 *
 * <h3>Section 15 Compliance (Lambda Hell Prevention):</h3>
 *
 * <p>Complex transformation logic extracted to ViewTransformer service.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class MongoDBSyncWorker implements Runnable {

  private static final String STREAM_KEY = "character-sync";
  private static final String CONSUMER_GROUP = "mongodb-sync-group";
  private static final String CONSUMER_NAME = "mongodb-sync-worker";
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(2000);

  private final RedissonClient redissonClient;
  private final CharacterViewQueryService queryService;
  private final LogicExecutor executor;
  private final CheckedLogicExecutor checkedExecutor;
  private final ViewTransformer viewTransformer;
  private final ObjectMapper objectMapper;
  private final Counter processedCounter;
  private final Counter errorCounter;

  private Thread workerThread;
  private volatile boolean running = false;

  public MongoDBSyncWorker(
      RedissonClient redissonClient,
      CharacterViewQueryService queryService,
      LogicExecutor executor,
      @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor,
      ViewTransformer viewTransformer,
      ObjectMapper objectMapper,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.redissonClient = redissonClient;
    this.queryService = queryService;
    this.executor = executor;
    this.checkedExecutor = checkedExecutor;
    this.viewTransformer = viewTransformer;
    this.objectMapper = objectMapper;
    this.processedCounter = meterRegistry.counter("mongodb.sync.processed");
    this.errorCounter = meterRegistry.counter("mongodb.sync.errors");
  }

  @PostConstruct
  public void start() {
    initializeStream();
    running = true;
    workerThread = new Thread(this, "V5-MongoDBSyncWorker-" + System.currentTimeMillis());
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
      joinWorkerThreadWithRecovery();
    }
    log.info("[MongoDBSyncWorker] Worker stopped");
  }

  /** Join worker thread with interrupt recovery (Section 12 compliant). */
  private void joinWorkerThreadWithRecovery() {
    checkedExecutor.executeUncheckedVoid(
        () -> {
          try {
            workerThread.join(5000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[MongoDBSyncWorker] Worker interrupted during shutdown");
            throw new WorkerShutdownException(e);
          }
        },
        TaskContext.of("MongoDBSyncWorker", "JoinWorkerThread"),
        ex -> new IllegalStateException("Unexpected error during worker thread join", ex));
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
            stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP).makeStream());
            log.info("[MongoDBSyncWorker] Created consumer group: {}", CONSUMER_GROUP);
            return;
          }

          // Stream exists, check if group exists
          ensureConsumerGroupExists(stream);
        },
        context);
  }

  /** Ensure consumer group exists, creating if necessary. */
  private void ensureConsumerGroupExists(RStream<String, String> stream) {
    executor.executeOrCatch(
        () -> {
          stream.readGroup(
              CONSUMER_GROUP, CONSUMER_NAME, StreamReadGroupArgs.neverDelivered().count(1));
          return null;
        },
        e -> {
          if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
            executor.executeVoid(
                () -> {
                  stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP));
                  log.info("[MongoDBSyncWorker] Consumer group created: {}", CONSUMER_GROUP);
                },
                TaskContext.of("MongoDBSyncWorker", "CreateGroup"));
          }
          return null;
        },
        TaskContext.of("MongoDBSyncWorker", "CheckGroup"));
  }

  private void processNextBatch() {
    executor.executeOrCatch(
        () -> {
          RStream<String, String> stream =
              redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

          // Read with timeout using Redisson RStream API
          Map<StreamMessageId, Map<String, String>> messages =
              stream.readGroup(
                  CONSUMER_GROUP,
                  CONSUMER_NAME,
                  StreamReadGroupArgs.neverDelivered().count(1).timeout(POLL_TIMEOUT));

          if (messages == null || messages.isEmpty()) {
            return null;
          }

          for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processSingleMessage(stream, entry.getKey(), entry.getValue());
          }

          return null;
        },
        e -> {
          log.error("[MongoDBSyncWorker] Error in processNextBatch", e);
          return null;
        },
        TaskContext.of("MongoDBSyncWorker", "ProcessBatch"));
  }

  /** Process a single message with ACK on success. */
  private void processSingleMessage(
      RStream<String, String> stream, StreamMessageId messageId, Map<String, String> data) {
    executor.executeOrCatch(
        () -> {
          processMessage(messageId, data);
          // ACK message
          stream.ack(CONSUMER_GROUP, messageId);
          processedCounter.increment();
          return null;
        },
        e -> {
          log.error("[MongoDBSyncWorker] Failed to process message: {}", messageId, e);
          errorCounter.increment();
          // Message will be retried after TTL (pending messages timeout)
          return null;
        },
        TaskContext.of("MongoDBSyncWorker", "ProcessSingleMessage", messageId.toString()));
  }

  private void processMessage(StreamMessageId messageId, Map<String, String> data) {
    TaskContext context =
        TaskContext.of("MongoDBSyncWorker", "ProcessMessage", messageId.toString());

    executor.executeVoid(
        () -> {
          // Deserialize IntegrationEvent wrapper
          String payloadJson = data.get("payload");
          if (payloadJson == null) {
            log.warn("[MongoDBSyncWorker] No payload in message");
            return;
          }

          deserializeAndSync(messageId, payloadJson);
        },
        context);
  }

  /** Deserialize event and sync to MongoDB (Section 12 compliant). */
  private void deserializeAndSync(StreamMessageId messageId, String payloadJson) {
    checkedExecutor.executeUncheckedVoid(
        () -> {
          // Deserialize to ExpectationCalculationCompletedEvent
          ExpectationCalculationCompletedEvent event;
          try {
            event = objectMapper.readValue(payloadJson, ExpectationCalculationCompletedEvent.class);
          } catch (JsonProcessingException e) {
            throw new JsonDeserializationException("Failed to deserialize event", e);
          }

          // Delegate transformation to ViewTransformer (SRP)
          CharacterValuationView view = viewTransformer.toDocument(event);
          queryService.upsert(view);

          log.debug(
              "[MongoDBSyncWorker] Synced to MongoDB: userIgn={}, ocid={}",
              event.getUserIgn(),
              event.getCharacterOcid());
        },
        TaskContext.of("MongoDBSyncWorker", "DeserializeAndSync", messageId.toString()),
        e -> new InternalSystemException("메시지 역직렬화 실패: " + messageId, e));
  }

  /** RuntimeException to signal graceful worker shutdown. */
  private static class WorkerShutdownException extends RuntimeException {
    WorkerShutdownException(InterruptedException cause) {
      super(cause);
    }
  }

  /** RuntimeException wrapper for JsonProcessingException during deserialization. */
  private static class JsonDeserializationException extends RuntimeException {
    JsonDeserializationException(String message, JsonProcessingException cause) {
      super(message, cause);
    }
  }
}
