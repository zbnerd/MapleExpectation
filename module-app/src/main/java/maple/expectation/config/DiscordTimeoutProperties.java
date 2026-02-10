package maple.expectation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Discord Webhook timeout configuration properties.
 *
 * <h3>application.yml 설정 예시</h3>
 *
 * <pre>
 * expectation:
 *   discord:
 *     webhook-timeout-seconds: 5
 *     retry-after-default-ms: 1000
 * </pre>
 *
 * @param webhookTimeoutSeconds Discord Webhook 요청 타임아웃 (초)
 * @param retryAfterDefaultMs Discord 429 응답 시 기본 Retry-After 딜레이 (밀리초)
 */
@Validated
@ConfigurationProperties(prefix = "expectation.discord")
public record DiscordTimeoutProperties(
    @DefaultValue("5") @Min(1) @Max(30) int webhookTimeoutSeconds,
    @DefaultValue("1000") @Min(100) @Max(10000) long retryAfterDefaultMs) {

  /**
   * 기본값을 사용하는 팩토리 메서드
   *
   * <p>테스트 또는 기본 설정 시 사용
   */
  public static DiscordTimeoutProperties defaults() {
    return new DiscordTimeoutProperties(5, 1000L);
  }
}
