package maple.expectation.config;

import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    @Bean
    public ExceptionTranslator exceptionTranslator() {
        // DefaultLogicExecutor가 기본적으로 사용할 번역기를 지정합니다.
        return ExceptionTranslator.defaultTranslator();
    }
}