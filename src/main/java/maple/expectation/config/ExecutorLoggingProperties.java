package maple.expectation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * ExecutionPolicy 로깅 관련 설정 프로퍼티
 *
 * <p>application.yml에서 다음과 같이 설정:
 * <pre>
 * executor:
 *   logging:
 *     slow-ms: 200
 * </pre>
 *
 * @param slowMs 이 값(ms) 이상이면 성공 로그를 SLOW로 승격(INFO). 기본값: 200ms
 * @since 2.4.0
 */
@Validated
@ConfigurationProperties(prefix = "executor.logging")
public record ExecutorLoggingProperties(
        @Min(0) @Max(60_000) @DefaultValue("200") long slowMs
) {
}
