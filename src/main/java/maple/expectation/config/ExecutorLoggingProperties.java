package maple.expectation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * @since 2.4.0
 */
@Validated
@ConfigurationProperties(prefix = "executor.logging")
public class ExecutorLoggingProperties {

    /**
     * 단위: milliseconds
     * 이 값 이상이면 성공 로그를 SLOW로 승격(INFO)
     * 기본값: 200ms
     * 허용 범위: 0 ~ 60000ms (1분)
     */
    @Min(0)
    @Max(60_000)
    private long slowMs = 200L;

    public long getSlowMs() {
        return slowMs;
    }

    public void setSlowMs(long slowMs) {
        this.slowMs = slowMs;
    }
}
