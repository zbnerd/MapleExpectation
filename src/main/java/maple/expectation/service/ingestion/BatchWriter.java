package maple.expectation.service.ingestion;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.port.MessageQueue;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.NexonCharacterRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch writer for consuming from queue and writing to database.
 *
 * <p><strong>Stage 3 (Storage) - Anti-Corruption Layer:</strong>
 * <ul>
 *   <li>Consumes from {@link MessageQueue} (decoupled from collector)</li>
 *   <li>Accumulates to batch size (1000 records)</li>
 *   <li>Uses JDBC batch update via repository</li>
 * </ul>
 *
 * <p><strong>Backpressure Control:</strong>
 * Queue acts as a buffer between collector and writer:
 * <ul>
 *   <li>Collector can publish faster than writer can process</li>
 *   <li>Queue absorbs spikes in traffic</li>
 *   <li>Writer processes at its own pace (steady state)</li>
 * </ul>
 *
 * <p><strong>Performance Benefits:</strong>
 * <ul>
 *   <li>90% reduction in DB I/O (1000 records in 1 transaction vs 1000 transactions)</li>
 *   <li>Better network utilization (1 round-trip instead of 1000)</li>
 *   <li>Reduced transaction overhead</li>
 * </ul>
 *
 * <p><strong>SOLID Compliance:</strong>
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - batch writing</li>
 *   <li><b>DIP:</b> Depends on {@link MessageQueue} abstraction</li>
 *   <li><b>OCP:</b> Open for extension (new batch strategies), closed for modification</li>
 * </ul>
 *
 * <h3>Batch Size Tuning:</h3>
 * Current setting: BATCH_SIZE = 1000
 * <ul>
 *   <li>Too small (e.g., 10): High overhead, network inefficiency</li>
 *   <li>Too large (e.g., 10000): Memory pressure, long transaction duration</li>
 *   <li>Optimal (1000): Balance between throughput and latency</li>
 * </ul>
 *
 * <h3>Schedule Configuration:</h3>
 * Current: {@code fixedRate = 5000} (every 5 seconds)
 * <ul>
 *   <li>Too fast (e.g., 1s): Many empty batches, wasted CPU</li>
 *   <li>Too slow (e.g., 60s): Queue buildup, increased lag</li>
 *   <li>Optimal (5s): Responsive without excessive polling</li>
 * </ul>
 *
 * @see MessageQueue
 * @see IntegrationEvent
 * @see NexonCharacterRepository
 * @see ADR-018 Strategy Pattern for ACL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchWriter {

  private final MessageQueue<String> messageQueue;
  private final NexonCharacterRepository repository;
  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;

  /**
   * Batch size for database operations.
   *
   * <p>Accumulates this many records before flushing to database.
   * Tuned for balance between throughput and latency.
   */
  private static final int BATCH_SIZE = 1000;

  /**
   * Scheduled batch processing (runs every 5 seconds).
   *
   * <p><strong>Workflow:</strong>
   * <ol>
   *   <li>Poll from queue up to BATCH_SIZE</li>
   *   <li>If empty, return (no-op)</li>
 *   *   <li>Extract payloads from {@link IntegrationEvent}</li>
   *   <li>Call repository.batchUpsert() for JDBC batch insert</li>
   *   <li>Log batch size</li>
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
              new ArrayList<>(BATCH_SIZE);

          // Accumulate batch from queue (JSON strings)
          for (int i = 0; i < BATCH_SIZE; i++) {
            String jsonPayload = messageQueue.poll();
            if (jsonPayload == null) {
              break;  // Queue empty
            }

            try {
              // Deserialize JSON back to IntegrationEvent
              IntegrationEvent<NexonApiCharacterData> event = objectMapper.readValue(
                  jsonPayload,
                  new TypeReference<IntegrationEvent<NexonApiCharacterData>>() {}
              );
              batch.add(event);
            } catch (Exception e) {
              log.error("[BatchWriter] Failed to deserialize event: {}", jsonPayload, e);
              // Skip invalid message and continue
            }
          }

          if (batch.isEmpty()) {
            log.debug("[BatchWriter] No messages to process");
            return;  // No-op if queue is empty
          }

          // Batch write to database
          batchWrite(batch);

          log.info("[BatchWriter] Processed batch: {} records", batch.size());
        },
        context
    );
  }

  /**
   * Batch write to database using repository.
   *
   * <p><strong>Implementation:</strong>
   * Delegates to {@link NexonCharacterRepository#batchUpsert(List)}
   * which uses JdbcTemplate.batchUpdate() for efficient JDBC batching.
   *
   * @param batch Events to write
   */
  private void batchWrite(List<IntegrationEvent<NexonApiCharacterData>> batch) {
    // Extract payloads from IntegrationEvent wrapper
    List<NexonApiCharacterData> dataList = batch.stream()
        .map(IntegrationEvent::getPayload)
        .toList();

    // Repository batch upsert (uses JdbcTemplate.batchUpdate internally)
    repository.batchUpsert(dataList);

    log.debug("[BatchWriter] Batch upsert completed: {} records", dataList.size());
  }
}
