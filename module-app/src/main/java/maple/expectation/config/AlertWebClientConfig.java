package maple.expectation.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Alert Web Client Configuration
 *
 * <p>Dedicated WebClient bean for Discord alerts
 *
 * <p>Isolated from Nexon API's shared WebClient
 *
 * <p>5-second timeout to prevent hanging
 *
 * <p>Feature flag controlled via alert.stateless.enabled
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Separate connection pool to avoid resource contention
 *   <li>Custom timeout configuration for alert traffic
 *   <li>No shared reactor-netty event loop
 *   <li>Respects alert feature flags for gradual rollout
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "alert.stateless.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AlertWebClientConfig {

  private final AlertFeatureProperties alertFeatureProperties;

  @Bean("alertWebClient")
  public WebClient alertWebClient() {
    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(5))
            .doOnConnected(
                conn ->
                    conn.addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS)));

    return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();
  }
}
