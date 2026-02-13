package maple.expectation.service.ingestion;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.port.EventPublisher;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import maple.expectation.error.exception.ExternalServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Non-blocking Nexon API data collector with reactive streams.
 *
 * <p><strong>Stage 1 (Ingestion) - Anti-Corruption Layer:</strong>
 *
 * <ul>
 *   <li>Uses WebClient for non-blocking HTTP calls (prevents thread pool exhaustion)
 *   <li>Returns {@link Mono} for fully reactive pipeline (no blocking operations)
 *   <li>Parses JSON to {@link NexonApiCharacterData} domain object
 *   <li>Publishes to Queue via {@link EventPublisher} (fire-and-forget)
 * </ul>
 *
 * <p><strong>Reactive Features:</strong>
 *
 * <ul>
 *   <li><b>Timeout:</b> 5 seconds (prevents hanging requests)
 *   <li><b>Retry:</b> Up to 2 retries on 5xx errors (resilient to transient failures)
 *   <li><b>Error Translation:</b> WebClient errors → ExternalServiceException (triggers circuit
 *       breaker)
 * </ul>
 *
 * <p><strong>Anti-Corruption Layer Benefits:</strong>
 *
 * <ul>
 *   <li><b>Isolation:</b> External REST latency is contained here
 *   <li><b>Translation:</b> Converts external API format to internal domain model
 *   <li><b>Decoupling:</b> Internal pipeline doesn't depend on external API structure
 * </ul>
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - data collection only
 *   <li><b>DIP:</b> Depends on {@link EventPublisher} abstraction
 *   <li><b>OCP:</b> Open for extension (new API endpoints), closed for modification
 * </ul>
 *
 * @see EventPublisher
 * @see IntegrationEvent
 * @see NexonApiCharacterData
 * @see reactor.core.publisher.Mono
 */
@Slf4j
@Service
public class NexonDataCollector {

  private final WebClient webClient;
  private final EventPublisher eventPublisher;

  public NexonDataCollector(
      @Qualifier("mapleWebClient") WebClient webClient, EventPublisher eventPublisher) {
    this.webClient = webClient;
    this.eventPublisher = eventPublisher;
  }

  @Value("${nexon.api.key}")
  private String apiKey;

  private static final String NEXON_DATA_COLLECTED = "NEXON_DATA_COLLECTED";
  private static final Duration API_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_RETRIES = 2;

  /**
   * Fetch character data from Nexon API and publish to queue.
   *
   * <p><strong>Workflow:</strong>
   *
   * <ol>
   *   <li>Call Nexon API (HTTP GET) with reactive WebClient
   *   <li>Parse JSON response to {@link NexonApiCharacterData}
   *   <li>Wrap in {@link IntegrationEvent}
   *   <li>Publish to queue (fire-and-forget via doOnNext)
   *   <li>Return character data to caller as Mono
   * </ol>
   *
   * <p><strong>Reactive Features:</strong>
   *
   * <ul>
   *   <li>Timeout: 5 seconds (prevents hanging requests)
   *   <li>Retry: Up to 2 retries on 5xx errors (resilient to transient failures)
   *   <li>Fire-and-forget publish: Event publishing doesn't block the response
   * </ul>
   *
   * @param ocid Character OCID
   * @return Mono that emits character data when API call completes
   */
  public Mono<NexonApiCharacterData> fetchAndPublish(String ocid) {
    log.debug("[NexonDataCollector] Fetching character data: ocid={}", ocid);

    return fetchFromNexonApi(ocid)
        .doOnNext(
            data -> {
              log.info(
                  "[NexonDataCollector] Fetched and queued: ocid={}, characterName={}",
                  ocid,
                  data.getCharacterName());
              publishEvent(data);
            })
        .doOnError(
            ex ->
                log.error(
                    "[NexonDataCollector] Failed to fetch character: ocid={}, error={}",
                    ocid,
                    ex.getMessage(),
                    ex));
  }

  /**
   * Non-blocking HTTP call to Nexon API with reactive error handling.
   *
   * <p><strong>Reactive Features:</strong>
   *
   * <ul>
   *   <li>Timeout: 5 seconds (prevents hanging requests)
   *   <li>Retry: Up to 2 retries on 5xx errors (resilient to transient failures)
   *   <li>Exception translation: WebClient errors translated to ExternalServiceException
   * </ul>
   *
   * <p><strong>API Endpoint:</strong> Uses Nexon Open API {@code /character/basic} endpoint which
   * returns lightweight character data (~1-2 KB) instead of full profile (300 KB).
   *
   * @param ocid Character OCID
   * @return Mono that emits parsed character data
   */
  private Mono<NexonApiCharacterData> fetchFromNexonApi(String ocid) {
    return webClient
        .get()
        .uri("/maplestory/v1/character/basic?ocid={ocid}", ocid)
        .header("x-nxopen-api-key", apiKey)
        .retrieve()
        .bodyToMono(NexonApiCharacterData.class)
        .timeout(API_TIMEOUT)
        .retryWhen(
            reactor.util.retry.Retry.backoff(MAX_RETRIES, Duration.ofMillis(100))
                .filter(this::isRetryableError))
        .onErrorMap(this::translateWebClientError);
  }

  /**
   * Publish event to queue (fire-and-forget).
   *
   * <p><strong>Note:</strong> This method is called from doOnNext() and must not throw exceptions.
   * Publishing failures are logged but don't affect the main reactive chain.
   *
   * @param data Character data to publish
   */
  private void publishEvent(NexonApiCharacterData data) {
    IntegrationEvent<NexonApiCharacterData> event = IntegrationEvent.of(NEXON_DATA_COLLECTED, data);

    try {
      eventPublisher.publishAsync("nexon-data", event);
    } catch (Exception ex) {
      log.error(
          "[NexonDataCollector] Failed to publish event: ocid={}, characterName={}",
          data.getOcid(),
          data.getCharacterName(),
          ex);
    }
  }

  /**
   * Determine if an error is retryable (5xx server errors).
   *
   * @param ex The exception to check
   * @return true if the error is retryable, false otherwise
   */
  private boolean isRetryableError(Throwable ex) {
    if (ex instanceof WebClientResponseException webClientEx) {
      int statusCode = webClientEx.getStatusCode().value();
      return statusCode >= 500 && statusCode < 600;
    }
    return false;
  }

  /**
   * Translate WebClient exceptions to domain exceptions.
   *
   * <p><strong>Translation Strategy:</strong>
   *
   * <ul>
   *   <li>All WebClient exceptions → ExternalServiceException (triggers circuit breaker)
   *   <li>Preserves cause for debugging (Exception Chaining)
   * </ul>
   *
   * @param ex The original exception
   * @return Translated domain exception
   */
  private Throwable translateWebClientError(Throwable ex) {
    if (ex instanceof WebClientResponseException webClientEx) {
      return new ExternalServiceException(
          "NexonAPI",
          new ExternalServiceException(
              String.format(
                  "Nexon API returned %d: %s",
                  webClientEx.getStatusCode().value(), webClientEx.getStatusText()),
              ex));
    }

    return new ExternalServiceException("NexonAPI", ex);
  }
}
