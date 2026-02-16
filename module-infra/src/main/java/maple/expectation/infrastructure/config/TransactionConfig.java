package maple.expectation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 트랜잭션 설정 (Issue #158)
 *
 * <h4>TransactionTemplate 정책</h4>
 *
 * <ul>
 *   <li><b>transactionTemplate</b> (Primary): 범용 읽기/쓰기 템플릿
 *   <li><b>readOnlyTransactionTemplate</b>: Expectation 경로 전용 읽기 전용
 * </ul>
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
@Configuration
public class TransactionConfig {

  /**
   * 기본 TransactionTemplate (읽기/쓰기 가능)
   *
   * <p>범용 트랜잭션 템플릿. 테스트 및 쓰기 작업에 사용.
   *
   * @param transactionManager Spring이 제공하는 트랜잭션 매니저
   * @return 읽기/쓰기 TransactionTemplate
   */
  @Bean
  @Primary
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }

  /**
   * Expectation 경로 전용 읽기 전용 TransactionTemplate
   *
   * <p>P0-5 정책: readOnly=true, timeout=5초
   *
   * <h4>사용 목적</h4>
   *
   * <ul>
   *   <li>Tx 안에서는 CharacterSnapshot만 생성하고 종료
   *   <li>Lazy 로딩/세션 종료 리스크 제거
   *   <li>follower 대기, 캐시 조회는 Tx 밖에서 수행
   * </ul>
   *
   * @param transactionManager Spring이 제공하는 트랜잭션 매니저
   * @return 읽기 전용 TransactionTemplate
   */
  @Bean(name = "readOnlyTransactionTemplate")
  public TransactionTemplate readOnlyTransactionTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setReadOnly(true);
    // Issue #225: 5s → 10s (Timeout Hierarchy 정렬 - MySQL lock_wait 8s보다 여유 있게)
    template.setTimeout(10);
    return template;
  }
}
