package maple.expectation.monitoring.copilot.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;

/**
 * Prometheus HTTP Client for querying metrics. Uses Java 11+ HttpClient with Jackson for JSON
 * parsing.
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: All exceptions handled via LogicExecutor
 *   <li>No try-catch blocks in business logic
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class PrometheusClient {

  private static final int TIMESTAMP_INDEX = 0;
  private static final int VALUE_INDEX = 1;
  private static final int ARRAY_SIZE = 2;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;
  private final String prometheusUrl;

  /**
   * Query Prometheus with a time range.
   *
   * @param promql PromQL query string
   * @param start Start time (inclusive)
   * @param end End time (inclusive)
   * @param step Query step interval (e.g., "15s", "1m", "5m")
   * @return List of time series with metric labels and values
   */
  public List<TimeSeries> queryRange(String promql, Instant start, Instant end, String step) {
    // Use LogicExecutor if available (Section 12 compliance)
    if (executor != null) {
      return executor.execute(
          () -> queryRangeInternal(promql, start, end, step),
          TaskContext.of("PrometheusClient", "QueryRange", promql));
    }

    // Fallback for legacy usage
    return queryRangeInternal(promql, start, end, step);
  }

  /**
   * Internal query implementation with checked exceptions. Called by LogicExecutor or directly in
   * legacy mode.
   */
  private List<TimeSeries> queryRangeInternal(
      String promql, Instant start, Instant end, String step) {
    String url = buildQueryRangeUrl(promql, start, end, step);

    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

    HttpResponse<String> response = sendHttpRequest(request);

    if (response.statusCode() != 200) {
      throw new InternalSystemException(
          String.format(
              "Prometheus query failed: HTTP %d - %s", response.statusCode(), response.body()));
    }

    PrometheusResponse prometheusResponse = parseResponse(response.body());

    if (prometheusResponse == null || prometheusResponse.data() == null) {
      log.warn("Empty Prometheus response for query: {}", promql);
      return List.of();
    }

    return prometheusResponse.data().result();
  }

  /**
   * Send HTTP request with proper exception translation.
   *
   * <p>Section 12 Exception: This is a low-level HTTP operation with checked exceptions
   * (InterruptedException, IOException) that require structural try-catch handling. LogicExecutor
   * cannot be used here due to legacy mode (executor=null) constraints.
   */
  private HttpResponse<String> sendHttpRequest(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InternalSystemException("Prometheus query interrupted", e);
    } catch (IOException e) {
      throw new InternalSystemException(
          String.format("Prometheus HTTP request failed: %s", e.getMessage()), e);
    }
  }

  /**
   * Parse JSON response with proper exception translation.
   *
   * <p>Section 12 Exception: This is a low-level JSON parsing operation with checked exceptions
   * (IOException) that require structural try-catch handling. LogicExecutor cannot be used here due
   * to legacy mode (executor=null) constraints.
   */
  private PrometheusResponse parseResponse(String body) {
    try {
      return objectMapper.readValue(body, PrometheusResponse.class);
    } catch (IOException e) {
      throw new InternalSystemException(
          String.format("Failed to parse Prometheus response: %s", e.getMessage()), e);
    }
  }

  /** Build Prometheus /api/v1/query_range URL. */
  private String buildQueryRangeUrl(String promql, Instant start, Instant end, String step) {
    DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

    return String.format(
        "%s/api/v1/query_range?query=%s&start=%s&end=%s&step=%s",
        prometheusUrl, urlEncode(promql), formatter.format(start), formatter.format(end), step);
  }

  /** Simple URL encoding (handles special characters in PromQL). */
  private String urlEncode(String value) {
    return value
        .replace(" ", "+")
        .replace("\"", "%22")
        .replace("(", "%28")
        .replace(")", "%29")
        .replace("{", "%7B")
        .replace("}", "%7D")
        .replace("[", "%5B")
        .replace("]", "%5D");
  }

  /** Prometheus API response structure. */
  public record PrometheusResponse(String status, QueryResponseData data) {

    @JsonCreator
    public static PrometheusResponse create(
        @JsonProperty("status") String status, @JsonProperty("data") QueryResponseData data) {
      return new PrometheusResponse(status, data);
    }
  }

  /** Query response data wrapper. */
  public record QueryResponseData(String resultType, List<TimeSeries> result) {

    @JsonCreator
    public static QueryResponseData create(
        @JsonProperty("resultType") String resultType,
        @JsonProperty("result") List<TimeSeries> result) {
      return new QueryResponseData(resultType, result);
    }
  }

  /** Time series with metric labels and values. */
  public record TimeSeries(Map<String, String> metric, List<ValuePoint> values) {
    @JsonCreator
    public static TimeSeries create(
        @JsonProperty("metric") Map<String, String> metric,
        @JsonProperty("values") List<ValuePoint> values) {
      return new TimeSeries(metric, values);
    }
  }

  /** Single value point with timestamp and value. */
  @JsonDeserialize(using = ValuePointDeserializer.class)
  public record ValuePoint(long timestamp, String value) {

    /**
     * Parse value as Double. Returns 0.0 on parse failure (safe default).
     *
     * <p>Section 12 Exception: This is a Record method (JPA Entity-like restriction). LogicExecutor
     * cannot be used here due to lack of Spring Bean injection capability. Direct try-catch is
     * acceptable per CLAUDE.md Section 12 exception list.
     */
    public double getValueAsDouble() {
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException e) {
        log.warn("Failed to parse value as double: {}", value);
        return 0.0;
      }
    }

    /** Get timestamp as Instant. */
    public Instant getTimestampAsInstant() {
      return Instant.ofEpochSecond(timestamp);
    }
  }

  /** Custom deserializer for ValuePoint to handle Prometheus array format [[timestamp, value]]. */
  public static class ValuePointDeserializer extends JsonDeserializer<ValuePoint> {

    @Override
    public ValuePoint deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = p.getCodec().readTree(p);
      if (node.isArray() && node.size() == ARRAY_SIZE) {
        long timestamp = node.get(TIMESTAMP_INDEX).asLong();
        String value = node.get(VALUE_INDEX).asText();
        return new ValuePoint(timestamp, value);
      }
      throw new IOException(
          "Invalid ValuePoint format: expected [timestamp, value] array, got: " + node);
    }
  }
}
