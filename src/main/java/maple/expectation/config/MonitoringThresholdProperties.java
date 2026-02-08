package maple.expectation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Monitoring threshold configuration properties for unified alert thresholds.
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): @ConfigurationProperties Record로 설정 외부화
 *   <li>Red (SRE): 환경별 오버라이드 가능한 설정
 *   <li>Green (Performance): 임계값 튜닝으로 모니터링 민감도 최적화
 * </ul>
 *
 * <h3>application.yml 설정 예시</h3>
 *
 * <pre>
 * expectation:
 *   monitoring-threshold:
 *     buffer-saturation-count: 5000
 *     buffer-saturation-double: 5000.0
 * </pre>
 *
 * @param bufferSaturationCount 버퍼 포화도 경고 임계값 (MonitoringAlertService)
 * @param bufferSaturationDouble 버퍼 포화도 퍼센트 계산 기준값 (RedisMetricsCollector)
 */
@Validated
@ConfigurationProperties(prefix = "expectation.monitoring-threshold")
public record MonitoringThresholdProperties(
    @DefaultValue("5000") @Min(1000L) @Max(50000L) long bufferSaturationCount,
    @DefaultValue("5000.0") double bufferSaturationDouble) {

  /**
   * 기본값을 사용하는 팩토리 메서드
   *
   * <p>테스트 또는 기본 설정 시 사용
   */
  public static MonitoringThresholdProperties defaults() {
    return new MonitoringThresholdProperties(5000L, 5000.0);
  }
}
