package maple.expectation.config;

import maple.expectation.service.v4.buffer.BackoffStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Write-Behind Buffer 설정 (#266 ADR 정합성)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): @EnableConfigurationProperties로 Record 바인딩
 *   <li>Yellow (QA): BackoffStrategy 빈 분리로 테스트 시 NoOpBackoff 주입 가능
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(BufferProperties.class)
public class BufferConfig {

  /**
   * CAS 재시도 대기 전략 빈
   *
   * <h4>운영 환경</h4>
   *
   * <p>ExponentialBackoff 사용 - 1ns부터 시작하여 512ns까지 지수적 증가
   *
   * <h4>테스트 환경</h4>
   *
   * <p>@TestConfiguration에서 NoOpBackoff로 오버라이드하여 결정적 테스트 수행
   */
  @Bean
  public BackoffStrategy backoffStrategy() {
    return new BackoffStrategy.ExponentialBackoff();
  }
}
