package maple.expectation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration
public class MaplestoryApiConfig {

    @Bean
    public WebClient webclient() {
        // URI 인코딩 모드 설정 (한글 깨짐 방지)
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("https://open.api.nexon.com");
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        // VALUES_ONLY: 파라미터 값만 인코딩 함 (가장 안전)

        return WebClient.builder()
                .uriBuilderFactory(factory) // 팩토리 적용
                .baseUrl("https://open.api.nexon.com")
                .defaultHeader("accept", "application/json")
                .build();
    }
}