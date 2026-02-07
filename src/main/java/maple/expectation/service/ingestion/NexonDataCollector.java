package maple.expectation.service.ingestion;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.port.EventPublisher;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Non-blocking Nexon API data collector.
 *
 * <p><strong>Stage 1 (Ingestion) - Anti-Corruption Layer:</strong>
 * <ul>
 *   <li>Uses WebClient for non-blocking HTTP calls (prevents thread pool exhaustion)</li>
 *   <li>Parses JSON to {@link NexonApiCharacterData} domain object</li>
 *   <li>Publishes to Queue via {@link EventPublisher} (fire-and-forget)</li>
 * </ul>
 *
 * <p><strong>Anti-Corruption Layer Benefits:</strong>
 * <ul>
 *   <li><b>Isolation:</b> External REST latency is contained here</li>
 *   <li><b>Translation:</b> Converts external API format to internal domain model</li>
 *   <li><b>Decoupling:</b> Internal pipeline doesn't depend on external API structure</li>
 * </ul>
 *
 * <p><strong>SOLID Compliance:</strong>
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - data collection only</li>
 *   <li><b>DIP:</b> Depends on {@link EventPublisher} abstraction</li>
 *   <li><b>OCP:</b> Open for extension (new API endpoints), closed for modification</li>
 * </ul>
 *
 * <h3>Migration to Fully Non-Blocking (Phase 8):</h3>
 * Current implementation uses {@code WebClient.block()} which is not ideal.
 * In Phase 8, this should be refactored to fully reactive:
 * <pre>{@code
 * public Mono<NexonApiCharacterData> fetchAndPublish(String ocid) {
 *   return webClient.get()
 *       .uri("/maplestory/v1/character/{ocid}", ocid)
 *       .retrieve()
 *       .bodyToMono(NexonApiCharacterData.class)
 *       .doOnNext(data -> eventPublisher.publishAsync("nexon-data", IntegrationEvent.of("NEXON_DATA_COLLECTED", data)));
 * }
 * }</pre>
 *
 * @see EventPublisher
 * @see IntegrationEvent
 * @see NexonApiCharacterData
 * @see ADR-018 Strategy Pattern for ACL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NexonDataCollector {

  private final WebClient nexonWebClient;
  private final EventPublisher eventPublisher;
  private final LogicExecutor executor;

  private static final String NEXON_DATA_COLLECTED = "NEXON_DATA_COLLECTED";

  /**
   * Fetch character data from Nexon API and publish to queue.
   *
   * <p><strong>Workflow:</strong>
   * <ol>
   *   <li>Call Nexon API (HTTP GET)</li>
   *   <li>Parse JSON response to {@link NexonApiCharacterData}</li>
   *   <li>Wrap in {@link IntegrationEvent}</li>
   *   <li>Publish to queue (fire-and-forget)</li>
   *   <li>Return character data to caller</li>
   * </ol>
   *
   * @param ocid Character OCID
   * @return CompletableFuture that completes with character data when queued
   */
  public CompletableFuture<NexonApiCharacterData> fetchAndPublish(String ocid) {
    TaskContext context = TaskContext.of("NexonDataCollector", "FetchAndPublish", ocid);

    try {
      log.debug("[NexonDataCollector] Fetching character data: ocid={}", ocid);

      // Execute fetch logic with LogicExecutor
      NexonApiCharacterData data = executor.execute(
          () -> fetchFromNexonApi(ocid),
          context
      );

      // Wrap in IntegrationEvent with metadata
      IntegrationEvent<NexonApiCharacterData> event =
          IntegrationEvent.of(NEXON_DATA_COLLECTED, data);

      // Publish to queue (fire-and-forget async publish)
      eventPublisher.publishAsync("nexon-data", event)
          .exceptionally(ex -> {
            log.error("[NexonDataCollector] Failed to publish event: ocid={}", ocid, ex);
            return null;  // Async error handling
          });

      log.info("[NexonDataCollector] Fetched and queued: ocid={}, characterName={}",
          ocid, data.getCharacterName());

      // Return completed future
      return CompletableFuture.completedFuture(data);

    } catch (Exception e) {
      log.error("[NexonDataCollector] Failed to fetch character: ocid={}", ocid, e);
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Non-blocking HTTP call to Nexon API.
   *
   * <p><strong>Note:</strong> Currently uses {@code block()} which ties up a thread.
   * This is acceptable for Phase 1 but should be refactored to fully reactive
   * in Phase 8 (Kafka migration).
   *
   * @param ocid Character OCID
   * @return Parsed character data
   */
  private NexonApiCharacterData fetchFromNexonApi(String ocid) {
    return nexonWebClient.get()
        .uri("/maplestory/v1/character/{ocid}", ocid)
        .retrieve()
        .bodyToMono(NexonApiCharacterData.class)
        .block();  // TODO: Phase 8 - Remove block(), return Mono instead
  }
}
