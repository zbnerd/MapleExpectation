package maple.expectation.config;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.contrib.sampler.RuleBasedRoutingSampler;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.semconv.UrlAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 설정 (Issue #251 Phase 5)
 *
 * <h3>트레이싱 설정</h3>
 * <ul>
 *   <li>[P0-Green] 5% Head Sampling (비용 최적화)</li>
 *   <li>[P0-Blue] Actuator 엔드포인트 트레이싱 제외</li>
 *   <li>Error 100% Tail Sampling (에러 시 무조건 수집)</li>
 * </ul>
 *
 * <h4>Context7 Best Practice</h4>
 * <p>RuleBasedRoutingSampler로 특정 경로 제외</p>
 *
 * @see <a href="https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/sdk-configuration">OTel Docs</a>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true")
public class OpenTelemetryConfig {

    /**
     * [P0-Blue] Actuator 엔드포인트 트레이싱 제외
     *
     * <p>/actuator/* 경로는 health check, metrics 수집 등으로 빈번히 호출되므로
     * 트레이싱에서 제외하여 노이즈를 줄입니다.</p>
     *
     * @return AutoConfigurationCustomizerProvider
     */
    @Bean
    public AutoConfigurationCustomizerProvider otelCustomizer() {
        log.info("[OpenTelemetry] RuleBasedRoutingSampler 설정 - /actuator 경로 제외");

        return provider -> provider.addSamplerCustomizer((fallback, config) ->
                RuleBasedRoutingSampler.builder(SpanKind.SERVER, fallback)
                        // Actuator 엔드포인트 제외
                        .drop(UrlAttributes.URL_PATH, "^/actuator.*")
                        // Health check 제외
                        .drop(UrlAttributes.URL_PATH, "^/health.*")
                        // Swagger UI 제외 (선택적)
                        .drop(UrlAttributes.URL_PATH, "^/swagger-ui.*")
                        .drop(UrlAttributes.URL_PATH, "^/v3/api-docs.*")
                        .build()
        );
    }
}
