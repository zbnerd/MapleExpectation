package maple.expectation.infrastructure.resilience;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retry Budget 설정 프로퍼티
 *
 * <p>장기간 장애 시 재시도 폭주(Retry Storm)를 방지하기 위한 예산 관리
 *
 * <h3>Retry Budget 개념</h3>
 *
 * <ul>
 *   <li>일정 시간 윈도우 내 허용 가능한 최대 재시도 횟수 제한
 *   <li>예산 소진 시 재시도 대신 즉시 실패(Fail Fast)
 *   <li>Circuit Breaker와 함께 사용하여 장기 장애 대응
 * </ul>
 *
 * <h4>application.yml 설정 예시</h4>
 *
 * <pre>
 * resilience:
 *   retry-budget:
 *     enabled: true
 *     max-retries-per-minute: 100  # 1분당 최대 100회 재시도
 *     window-size-seconds: 60      # 예산 윈도우 (초)
 * </pre>
 *
 * <h4>참고 자료</h4>
 *
 * <ul>
 *   <li>Google SRE Book: https://sre.google/sre-book/addressing-cascading-failures/
 *   <li>docs/01_Chaos_Engineering/03_Resource/09-retry-storm.md
 * </ul>
 *
 * @see RetryBudgetManager
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "resilience.retry-budget")
public class RetryBudgetProperties {

  /**
   * Retry Budget 활성화 여부
   *
   * <p>기본값: true
   */
  @NotNull private boolean enabled = true;

  /**
   * 시간 윈도우 내 허용 가능한 최대 재시도 횟수
   *
   * <p>기본값: 100회/분
   *
   * <p>권장 범위: 50 ~ 500
   */
  @Min(10)
  private int maxRetriesPerMinute = 100;

  /**
   * 예산 윈도우 크기 (초)
   *
   * <p>기본값: 60초 (1분)
   *
   * <p>권장 범위: 30 ~ 300초
   */
  @Min(10)
  private int windowSizeSeconds = 60;

  /**
   * 메트릭 게시 여부
   *
   * <p>기본값: true (Actuator /metrics 엔드포인트에 게시)
   */
  @NotNull private boolean metricsEnabled = true;
}
