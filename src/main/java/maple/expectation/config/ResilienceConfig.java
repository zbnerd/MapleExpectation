package maple.expectation.config;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    @Bean
    public Retry likeSyncRetry() {
        // 우리가 applyBackoff에서 짰던 '1초부터 시작해서 2배씩 늘어나는 로직'을 그대로 옮깁니다.
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3) // 최대 3번 시도 (처음 1번 + 재시도 2번)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(1000, 2.0)) // 1초부터 2.0배씩 증가
                .build();

        RetryRegistry registry = RetryRegistry.of(config);
        return registry.retry("likeSyncRetry");
    }
}