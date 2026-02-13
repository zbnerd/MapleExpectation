package maple.expectation.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import reactor.netty.http.client.HttpClient;

/**
 * Nexon Open API WebClient 설정
 *
 * <p>타임아웃 계층:
 *
 * <ul>
 *   <li>connectTimeout: TCP 연결 타임아웃 (기본 3초)
 *   <li>responseTimeout: 응답 수신 타임아웃 (기본 5초)
 * </ul>
 *
 * @see NexonApiProperties 타임아웃 설정 프로퍼티
 */
@Configuration
@EnableConfigurationProperties(NexonApiProperties.class)
public class MaplestoryApiConfig {

  private final NexonApiProperties properties;

  public MaplestoryApiConfig(NexonApiProperties properties) {
    this.properties = properties;
  }

  @Bean("mapleWebClient")
  public WebClient mapleWebClient() {
    // URI 인코딩 모드 설정 (한글 깨짐 방지)
    DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("https://open.api.nexon.com");
    factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);

    // HttpClient with timeouts and compression
    HttpClient httpClient =
        HttpClient.create()
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                (int) properties.getConnectTimeout().toMillis())
            .responseTimeout(properties.getResponseTimeout())
            .compress(true);

    return WebClient.builder()
        .uriBuilderFactory(factory)
        .baseUrl("https://open.api.nexon.com")
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }
}
