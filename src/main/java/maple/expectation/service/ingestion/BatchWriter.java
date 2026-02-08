package maple.expectation.service.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.port.MessageQueue;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.NexonCharacterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch writer for consuming from queue and writing to database.
 *
 * <p><strong>Stage 3 (Storage) - Anti-Corruption Layer:</strong>
 *
 * <ul>
 *   <li>Consumes from {@link MessageQueue} (decoupled from collector)
 *   <li>Accumulates to batch size (1000 records)
 *   <li>Uses JDBC batch update via repository
 * </ul>
 *
 * <p><strong>Backpressure Control:</strong> Queue acts as a buffer between collector and writer:
 *
 * <ul>
 *   <li>Collector can publish faster than writer can process
 *   <li>Queue absorbs spikes in traffic
 *   <li>Writer processes at its own pace (steady state)
 * </ul>
 *
 * <p><strong>Performance Benefits:</strong>
 *
 * <ul>
 *   <li>90% reduction in DB I/O (1000 records in 1 transaction vs 1000 transactions)
 *   <li>Better network utilization (1 round-trip instead of 1000)
 *   <li>Reduced transaction overhead
 * </ul>
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - batch writing
 *   <li><b>DIP:</b> Depends on {@link MessageQueue} abstraction
 *   <li><b>OCP:</b> Open for extension (new batch strategies), closed for modification
 * </ul>
 *
 * <h3>Batch Size Tuning:</h3>
 *
 * Current setting: BATCH_SIZE = 1000
 *
 * <ul>
 *   <li>Too small (e.g., 10): High overhead, network inefficiency
 *   <li>Too large (e.g., 10000): Memory pressure, long transaction duration
 *   <li>Optimal (1000): Balance between throughput and latency
 * </ul>
 *
 * <h3>Schedule Configuration:</h3>
 *
 * Current: {@code fixedRate = 5000} (every 5 seconds)
 *
 * <ul>
 *   <li>Too fast (e.g., 1s): Many empty batches, wasted CPU
 *   <li>Too slow (e.g., 60s): Queue buildup, increased lag
 *   <li>Optimal (5s): Responsive without excessive polling
 * </ul>
 *
 * @see MessageQueue
 * @see IntegrationEvent
 * @see NexonCharacterRepository
 * @see ADR-018 Strategy Pattern for ACL
 */
@Slf4j
@Component
public class BatchWriter {

  private final MessageQueue<String> messageQueue;
  private final NexonCharacterRepository repository;
  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;
  private final maple.expectation.config.BatchProperties batchProperties;

  public BatchWriter(
      @Qualifier("nexonDataQueue") MessageQueue<String> messageQueue,
      NexonCharacterRepository repository,
      LogicExecutor executor,
      ObjectMapper objectMapper,
      maple.expectation.config.BatchProperties batchProperties) {
    this.messageQueue = messageQueue;
    this.repository = repository;
    this.executor = executor;
    this.objectMapper = objectMapper;
    this.batchProperties = batchProperties;
  }

  /**
   * Scheduled batch processing (runs every 5 seconds).
   *
   * <p><strong>Workflow:</strong>
   *
   * <ol>
   *   <li>Poll from queue up to BATCH_SIZE
   *   <li>If empty, return (no-op) *
   *   <li>Extract payloads from {@link IntegrationEvent}
   *   <li>Call repository.batchUpsert() for JDBC batch insert
   *   <li>Log batch size
   * </ol>
   *
   * <p><strong>Transactional:</strong> Entire batch is atomic (all or nothing).
   */
  @Scheduled(fixedRate = 5000)
  @Transactional
  public void processBatch() {
    TaskContext context = TaskContext.of("BatchWriter", "ProcessBatch");

    executor.executeVoid(
        () -> {
          List<IntegrationEvent<NexonApiCharacterData>> batch =
              new ArrayList<>(batchProperties.aclWriterSize());

          // Accumulate batch from queue (JSON strings)
          for (int i = 0; i < batchProperties.aclWriterSize(); i++) {
            String jsonPayload = messageQueue.poll();
            if (jsonPayload == null) {
              break; // Queue empty
            }

            // Deserialize JSON back to IntegrationEvent (with recovery)
            IntegrationEvent<NexonApiCharacterData> event = deserializeEvent(jsonPayload);
            if (event != null) {
              batch.add(event);
            }
          }

          if (batch.isEmpty()) {
            log.debug("[BatchWriter] No messages to process");
            return; // No-op if queue is empty
          }

          // Batch write to database
          batchWrite(batch);

          log.info("[BatchWriter] Processed batch: {} records", batch.size());
        },
        context);
  }

  /**
   * Deserialize JSON payload to IntegrationEvent with error handling.
   *
   * <p><strong>LogicExecutor Compliance:</strong> Uses executeOrDefault to safely handle JSON
   * parsing failures without crashing the batch processor.
   *
   * @param jsonPayload JSON string to deserialize
   * @return Deserialized event, or null if parsing fails
   */
  private IntegrationEvent<NexonApiCharacterData> deserializeEvent(String jsonPayload) {
    return executor.executeOrDefault(
        () ->
            objectMapper.readValue(
                jsonPayload, new TypeReference<IntegrationEvent<NexonApiCharacterData>>() {}),
        null,
        TaskContext.of("BatchWriter", "DeserializeEvent", jsonPayload.substring(0, Math.min(50, jsonPayload.length()))));
  }

  /**
   * Batch write to database using repository.
   *
   * <p><strong>Implementation:</strong> Delegates to {@link
   * NexonCharacterRepository#batchUpsert(List)} which uses JdbcTemplate.batchUpdate() for efficient
   * JDBC batching.
   *
   * @param batch Events to write
   */
  private void batchWrite(List<IntegrationEvent<NexonApiCharacterData>> batch) {
    // Extract payloads from IntegrationEvent wrapper
    List<NexonApiCharacterData> dataList =
        batch.stream().map(IntegrationEvent::getPayload).toList();

    // Repository batch upsert (uses JdbcTemplate.batchUpdate internally)
    repository.batchUpsert(dataList);

    log.debug("[BatchWriter] Batch upsert completed: {} records", dataList.size());
  }
}
