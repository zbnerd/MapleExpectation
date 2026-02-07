package maple.expectation.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j Retry Bean 등록 (P1-2: YAML 이관)
 *
 * <h4>변경 사항</h4>
 *
 * <ul>
 *   <li>Before: 독립 RetryRegistry 생성 → Actuator 추적 불가
 *   <li>After: Spring 관리 RetryRegistry에서 인스턴스 조회 → /actuator/retries 노출
 * </ul>
 *
 * <p>설정은 application.yml의 resilience4j.retry.instances.likeSyncRetry에서 관리됩니다.
 */
@Configuration
public class ResilienceConfig {

  @Bean
  public Retry likeSyncRetry(RetryRegistry retryRegistry) {
    return retryRegistry.retry("likeSyncRetry");
  }
}
