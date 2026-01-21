package maple.expectation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * PER (Probabilistic Early Recomputation) 전용 Thread Pool (#219)
 *
 * <h3>SRE 요구사항 (Red Agent)</h3>
 * <ul>
 *   <li>기존 비즈니스 Thread Pool과 분리하여 자원 격리</li>
 *   <li>DiscardPolicy: 큐 포화 시 갱신 작업 버림 (기존 Stale 데이터 유지)</li>
 *   <li>보수적인 Pool 크기: Core 2, Max 4</li>
 * </ul>
 *
 * <h3>장애 격리</h3>
 * <p>PER 갱신 작업이 폭증해도 메인 비즈니스 로직에 영향을 주지 않음.</p>
 *
 * @see maple.expectation.global.cache.per.ProbabilisticCacheAspect
 */
@Configuration
public class PerCacheExecutorConfig {

    /**
     * PER 전용 Executor
     *
     * <h4>설정 근거</h4>
     * <ul>
     *   <li>Core 2: 평상시 백그라운드 갱신 처리</li>
     *   <li>Max 4: 트래픽 증가 시 탄력적 확장</li>
     *   <li>Queue 100: Burst 대응, 초과 시 버림</li>
     *   <li>DiscardPolicy: Stale 데이터가 이미 있으므로 안전하게 버림</li>
     * </ul>
     */
    @Bean("perCacheExecutor")
    public Executor perCacheExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("per-cache-");

        // DiscardPolicy: 큐 포화 시 새 작업 버림
        // 기존 Stale 데이터가 있으므로 버려도 서비스 영향 없음
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        return executor;
    }
}
