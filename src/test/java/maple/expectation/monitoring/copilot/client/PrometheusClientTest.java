package maple.expectation.monitoring.copilot.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Basic PrometheusClient tests. */
class PrometheusClientTest {

  private PrometheusClient prometheusClient;

  @BeforeEach
  void setUp() {
    // Create real PrometheusClient with test URL
    prometheusClient =
        new PrometheusClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            null, // LogicExecutor (null for tests)
            "http://localhost:9090");
  }

  @Test
  void testClientCreation() {
    // Verify client is created successfully
    assertNotNull(prometheusClient);
  }

  @Test
  void testValuePointRecord() {
    // Verify ValuePoint record works correctly
    Instant now = Instant.now();
    PrometheusClient.ValuePoint valuePoint =
        new PrometheusClient.ValuePoint(now.getEpochSecond(), "1.0");

    assertEquals(now.getEpochSecond(), valuePoint.timestamp());
    assertEquals("1.0", valuePoint.value());
    assertEquals(1.0, valuePoint.getValueAsDouble(), 0.001);
    // ValuePoint only stores epoch seconds, so compare with second precision
    assertEquals(Instant.ofEpochSecond(now.getEpochSecond()), valuePoint.getTimestampAsInstant());
  }
}
