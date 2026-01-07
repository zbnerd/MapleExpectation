package maple.expectation.config;

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

    @Bean
    @ConditionalOnMissingBean(LogicExecutor.class)
    public LogicExecutor logicExecutor(ExecutionPipeline pipeline, ExceptionTranslator translator) {
        return new DefaultLogicExecutor(pipeline, translator);
    }
}
