package maple.expectation.config;

import maple.expectation.global.executor.CheckedLogicExecutor;
import maple.expectation.global.executor.DefaultCheckedLogicExecutor;
import maple.expectation.global.executor.DefaultLogicExecutor;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.policy.ExecutionPipeline;
import maple.expectation.global.executor.policy.ExecutionPolicy;
import maple.expectation.global.executor.policy.LoggingPolicy;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ExecutorLoggingProperties.class)
public class ExecutorConfig {

    @Bean
    public ExceptionTranslator exceptionTranslator() {
        // DefaultLogicExecutor가 기본적으로 사용할 번역기를 지정합니다.
        return ExceptionTranslator.defaultTranslator();
    }

    @Bean
    public LoggingPolicy loggingPolicy(ExecutorLoggingProperties props) {
        return new LoggingPolicy(props.getSlowMs());
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionPipeline.class)
    public ExecutionPipeline executionPipeline(List<ExecutionPolicy> policies) {
        List<ExecutionPolicy> ordered = new ArrayList<>(policies);
        AnnotationAwareOrderComparator.sort(ordered);
        return new ExecutionPipeline(ordered);
    }

    /**
     * 비즈니스 레이어 기본 Executor (Primary)
     *
     * <p>서비스/도메인 내부에서 기본으로 주입되는 Executor입니다.
     * IO 경계에서는 {@link CheckedLogicExecutor}를 {@code @Qualifier("checkedLogicExecutor")}로 opt-in합니다.</p>
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(LogicExecutor.class)
    public LogicExecutor logicExecutor(ExecutionPipeline pipeline, ExceptionTranslator translator) {
        return new DefaultLogicExecutor(pipeline, translator);
    }

    /**
     * IO 경계 전용 CheckedLogicExecutor 빈 등록
     *
     * <p>파일 I/O, 네트워크 통신, 분산 락 등 checked 예외가 발생하는
     * IO 경계에서 try-catch 없이 예외를 처리합니다.</p>
     *
     * <h4>주입 패턴 (Qualifier 명시 권장)</h4>
     * <p>Lombok {@code @RequiredArgsConstructor}는 {@code @Qualifier}를 생성자 파라미터로
     * 전파하지 않을 수 있으므로, 명시적 생성자를 권장합니다:</p>
     * <pre>{@code
     * class ResilientNexonApiClient {
     *     private final CheckedLogicExecutor checkedExecutor;
     *
     *     ResilientNexonApiClient(
     *         @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor
     *     ) {
     *         this.checkedExecutor = checkedExecutor;
     *     }
     * }
     * }</pre>
     */
    @Bean(name = "checkedLogicExecutor")
    @ConditionalOnMissingBean(CheckedLogicExecutor.class)
    public CheckedLogicExecutor checkedLogicExecutor(ExecutionPipeline pipeline) {
        return new DefaultCheckedLogicExecutor(pipeline);
    }
}
