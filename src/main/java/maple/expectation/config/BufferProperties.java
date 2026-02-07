package maple.expectation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Expectation Write-Behind Buffer 설정 프로퍼티 (#266 ADR 정합성)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): @ConfigurationProperties Record로 설정 외부화
 *   <li>Red (SRE): 환경별 오버라이드 가능한 설정
 *   <li>Green (Performance): CAS 재시도 및 Shutdown 타임아웃 튜닝 가능
 * </ul>
 *
 * <h3>application.yml 설정 예시</h3>
 *
 * <pre>
 * expectation:
 *   buffer:
 *     shutdown-await-timeout-seconds: 30
 *     cas-max-retries: 10
 *     max-queue-size: 10000
 * </pre>
 *
 * @param shutdownAwaitTimeoutSeconds Shutdown 시 진행 중인 offer 대기 타임아웃 (초)
 * @param casMaxRetries CAS 연산 최대 재시도 횟수
 * @param maxQueueSize 버퍼 최대 크기 (백프레셔 임계값)
 */
@Validated
@ConfigurationProperties(prefix = "expectation.buffer")
public record BufferProperties(
    @DefaultValue("30") @Min(10) @Max(120) int shutdownAwaitTimeoutSeconds,
    @DefaultValue("10") @Min(5) @Max(100) int casMaxRetries,
    @DefaultValue("10000") @Min(1000) @Max(100000) int maxQueueSize) {
  /**
   * 기본값을 사용하는 팩토리 메서드
   *
   * <p>테스트 또는 기본 설정 시 사용
   */
  public static BufferProperties defaults() {
    return new BufferProperties(30, 10, 10000);
  }
}
