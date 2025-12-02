package maple.expectation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import reactor.netty.http.client.HttpClient;

@Configuration
public class MaplestoryApiConfig {

    @Bean
    public WebClient webclient() {
        // URI 인코딩 모드 설정 (한글 깨짐 방지)
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("https://open.api.nexon.com");
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        // VALUES_ONLY: 파라미터 값만 인코딩 함 (가장 안전)

        // 2. [핵심] GZIP 압축 활성화 🔥
        // 이 설정을 넣으면 요청 헤더에 'Accept-Encoding: gzip'을 자동으로 붙이고,
        // 응답이 gzip으로 오면 자동으로 압축을 풀어서 줍니다.
        HttpClient httpClient = HttpClient.create()
                .compress(true);

        return WebClient.builder()
                .uriBuilderFactory(factory) // 팩토리 적용
                .baseUrl("https://open.api.nexon.com")
                .defaultHeader("accept", "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}