package maple.expectation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 트랜잭션 설정 (Issue #158)
 *
 * <h4>TransactionTemplate 정책</h4>
 * <ul>
 *   <li><b>transactionTemplate</b> (Primary): 범용 읽기/쓰기 템플릿</li>
 *   <li><b>readOnlyTransactionTemplate</b>: Expectation 경로 전용 읽기 전용</li>
 * </ul>
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
@Configuration
public class TransactionConfig {

    /**
     * 기본 TransactionTemplate (읽기/쓰기 가능)
     *
     * <p>범용 트랜잭션 템플릿. 테스트 및 쓰기 작업에 사용.</p>
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
     * <p>P0-5 정책: readOnly=true, timeout=5초</p>
     *
     * <h4>사용 목적</h4>
     * <ul>
     *   <li>Tx 안에서는 CharacterSnapshot만 생성하고 종료</li>
     *   <li>Lazy 로딩/세션 종료 리스크 제거</li>
     *   <li>follower 대기, 캐시 조회는 Tx 밖에서 수행</li>
     * </ul>
     *
     * @param transactionManager Spring이 제공하는 트랜잭션 매니저
     * @return 읽기 전용 TransactionTemplate
     */
    @Bean(name = "readOnlyTransactionTemplate")
    public TransactionTemplate readOnlyTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setTimeout(5); // 5초 타임아웃
        return template;
    }
}
