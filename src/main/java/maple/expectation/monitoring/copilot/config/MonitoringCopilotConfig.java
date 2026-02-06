package maple.expectation.monitoring.copilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.monitoring.copilot.client.PrometheusClient;
import maple.expectation.monitoring.copilot.detector.AnomalyDetector;
import maple.expectation.monitoring.copilot.ingestor.GrafanaJsonIngestor;
import maple.expectation.monitoring.copilot.notifier.DiscordNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Monitoring Copilot Configuration
 * Provides HTTP clients and copilot components.
 */
@Slf4j
@Configuration
public class MonitoringCopilotConfig {

    /**
     * Shared HTTP client for external API calls.
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Prometheus client for metric queries.
     * Only created if monitoring is enabled.
     */
    @Bean
    @ConditionalOnProperty(name = "app.monitoring.enabled", havingValue = "true", matchIfMissing = true)
    public PrometheusClient prometheusClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            LogicExecutor executor,
            @Value("${app.monitoring.prometheus.base-url:http://localhost:9090}") String prometheusUrl) {
        return new PrometheusClient(httpClient, objectMapper, executor, prometheusUrl);
    }

    /**
     * Grafana JSON ingestor for dashboard parsing.
     */
    @Bean
    @ConditionalOnProperty(name = "app.monitoring.enabled", havingValue = "true", matchIfMissing = true)
    public GrafanaJsonIngestor grafanaJsonIngestor(
            ObjectMapper objectMapper,
            LogicExecutor executor) {
        return new GrafanaJsonIngestor(objectMapper, executor);
    }

    /**
     * Anomaly detector for metric analysis.
     */
    @Bean
    @ConditionalOnProperty(name = "app.monitoring.enabled", havingValue = "true", matchIfMissing = true)
    public AnomalyDetector anomalyDetector() {
        return new AnomalyDetector();
    }

    /**
     * Discord webhook notifier.
     * Only created if webhook URL is configured.
     */
    @Bean
    @ConditionalOnProperty(name = "app.monitoring.discord.webhook-url")
    public DiscordNotifier discordNotifier(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            LogicExecutor executor) {
        return new DiscordNotifier(httpClient, objectMapper, executor);
    }
}
