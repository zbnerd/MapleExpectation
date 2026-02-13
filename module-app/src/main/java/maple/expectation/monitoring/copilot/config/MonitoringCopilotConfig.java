package maple.expectation.monitoring.copilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.TimeoutProperties;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.monitoring.copilot.client.PrometheusClient;
import maple.expectation.monitoring.copilot.detector.AnomalyDetector;
import maple.expectation.monitoring.copilot.ingestor.GrafanaJsonIngestor;
import maple.expectation.monitoring.copilot.notifier.DiscordNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Monitoring Copilot Configuration Provides HTTP clients and copilot components. */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MonitoringCopilotConfig {

  private final TimeoutProperties timeoutProperties;

  /** Shared HTTP client for external API calls. */
  @Bean
  public HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(timeoutProperties.getApiCall()).build();
  }

  /** Prometheus client for metric queries. Only created if monitoring is enabled. */
  @Bean
  @ConditionalOnProperty(
      name = "app.monitoring.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PrometheusClient prometheusClient(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      LogicExecutor executor,
      @Value("${app.monitoring.prometheus.base-url:http://localhost:9090}") String prometheusUrl) {
    return new PrometheusClient(httpClient, objectMapper, executor, prometheusUrl);
  }

  /** Grafana JSON ingestor for dashboard parsing. */
  @Bean
  @ConditionalOnProperty(
      name = "app.monitoring.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public GrafanaJsonIngestor grafanaJsonIngestor(
      ObjectMapper objectMapper, LogicExecutor executor) {
    return new GrafanaJsonIngestor(objectMapper, executor);
  }

  /** Anomaly detector for metric analysis. */
  @Bean
  @ConditionalOnProperty(
      name = "app.monitoring.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public AnomalyDetector anomalyDetector() {
    return new AnomalyDetector();
  }

  /** Discord webhook notifier. Only created if webhook URL is configured. */
  @Bean
  @ConditionalOnProperty(name = "alert.discord.webhook-url")
  public DiscordNotifier discordNotifier(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      LogicExecutor executor,
      maple.expectation.config.DiscordTimeoutProperties timeoutProperties) {
    return new DiscordNotifier(httpClient, objectMapper, executor, timeoutProperties);
  }
}
