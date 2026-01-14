package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import org.springframework.core.task.TaskRejectedException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #168: ExecutorConfig AbortPolicy 동작 검증
 *
 * <p>큐 포화 시 RejectedExecutionException 발생 및 메트릭 기록 검증</p>
 */
class ExecutorConfigTest {

    private ExecutorConfig executorConfig;
    private MeterRegistry meterRegistry;
    private TaskDecorator noOpDecorator;

    @BeforeEach
    void setUp() {
        executorConfig = new ExecutorConfig();
        meterRegistry = new SimpleMeterRegistry();
        noOpDecorator = runnable -> runnable;  // 테스트용 No-Op Decorator
    }

    @Test
    @DisplayName("expectationComputeExecutor 큐 포화 시 RejectedExecutionException 발생")
    void expectationComputeExecutor_QueueFull_ThrowsRejected() throws InterruptedException {
        // Given: Executor 생성
        Executor executor = executorConfig.expectationComputeExecutor(noOpDecorator, meterRegistry);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;

        // 설정 확인: maxPoolSize=8, queueCapacity=200
        assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(8);
        assertThat(taskExecutor.getQueueCapacity()).isEqualTo(200);

        // CountDownLatch로 모든 스레드를 블로킹
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch allTasksSubmitted = new CountDownLatch(1);
        AtomicInteger submittedCount = new AtomicInteger(0);

        // When: 큐 + maxPoolSize만큼 작업 제출 (208개)
        int totalCapacity = taskExecutor.getMaxPoolSize() + taskExecutor.getQueueCapacity();

        for (int i = 0; i < totalCapacity; i++) {
            taskExecutor.execute(() -> {
                try {
                    blocker.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            submittedCount.incrementAndGet();
        }

        // Then: 다음 작업 제출 시 TaskRejectedException 발생 (Spring이 RejectedExecutionException 래핑)
        assertThatThrownBy(() -> taskExecutor.execute(() -> {}))
                .isInstanceOf(TaskRejectedException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class)
                .cause()
                .hasMessageContaining("ExpectationExecutor queue full");

        // Cleanup
        blocker.countDown();
        taskExecutor.shutdown();
    }

    @Test
    @DisplayName("alertTaskExecutor 큐 포화 시 TaskRejectedException 발생")
    void alertTaskExecutor_QueueFull_ThrowsRejected() throws InterruptedException {
        // Given: Executor 생성
        Executor executor = executorConfig.alertTaskExecutor(noOpDecorator, meterRegistry);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;

        // 설정 확인: maxPoolSize=4, queueCapacity=200
        assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(4);
        assertThat(taskExecutor.getQueueCapacity()).isEqualTo(200);

        // CountDownLatch로 모든 스레드를 블로킹
        CountDownLatch blocker = new CountDownLatch(1);

        // When: 큐 + maxPoolSize만큼 작업 제출 (204개)
        int totalCapacity = taskExecutor.getMaxPoolSize() + taskExecutor.getQueueCapacity();

        for (int i = 0; i < totalCapacity; i++) {
            taskExecutor.execute(() -> {
                try {
                    blocker.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Then: 다음 작업 제출 시 TaskRejectedException 발생 (Spring이 RejectedExecutionException 래핑)
        assertThatThrownBy(() -> taskExecutor.execute(() -> {}))
                .isInstanceOf(TaskRejectedException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class)
                .cause()
                .hasMessageContaining("AlertExecutor queue full");

        // Cleanup
        blocker.countDown();
        taskExecutor.shutdown();
    }

    @Test
    @DisplayName("expectationComputeExecutor rejected Counter 증가 검증")
    void expectationComputeExecutor_RejectedCounter_Increments() throws InterruptedException {
        // Given: Executor 생성
        Executor executor = executorConfig.expectationComputeExecutor(noOpDecorator, meterRegistry);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;

        CountDownLatch blocker = new CountDownLatch(1);

        // 큐 포화
        int totalCapacity = taskExecutor.getMaxPoolSize() + taskExecutor.getQueueCapacity();
        for (int i = 0; i < totalCapacity; i++) {
            taskExecutor.execute(() -> {
                try {
                    blocker.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // When: 추가 작업 제출 시도 (rejected 발생)
        double beforeCount = meterRegistry.counter("executor.rejected", "name", "expectation.compute").count();

        try {
            taskExecutor.execute(() -> {});
        } catch (TaskRejectedException ignored) {
            // 예상된 예외 (Spring이 RejectedExecutionException 래핑)
        }

        // Then: rejected Counter 증가
        double afterCount = meterRegistry.counter("executor.rejected", "name", "expectation.compute").count();
        assertThat(afterCount).isGreaterThan(beforeCount);

        // Cleanup
        blocker.countDown();
        taskExecutor.shutdown();
    }

    @Test
    @DisplayName("alertTaskExecutor rejected Counter 증가 검증")
    void alertTaskExecutor_RejectedCounter_Increments() throws InterruptedException {
        // Given: Executor 생성
        Executor executor = executorConfig.alertTaskExecutor(noOpDecorator, meterRegistry);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;

        CountDownLatch blocker = new CountDownLatch(1);

        // 큐 포화
        int totalCapacity = taskExecutor.getMaxPoolSize() + taskExecutor.getQueueCapacity();
        for (int i = 0; i < totalCapacity; i++) {
            taskExecutor.execute(() -> {
                try {
                    blocker.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // When: 추가 작업 제출 시도 (rejected 발생)
        double beforeCount = meterRegistry.counter("executor.rejected", "name", "alert").count();

        try {
            taskExecutor.execute(() -> {});
        } catch (TaskRejectedException ignored) {
            // 예상된 예외 (Spring이 RejectedExecutionException 래핑)
        }

        // Then: rejected Counter 증가
        double afterCount = meterRegistry.counter("executor.rejected", "name", "alert").count();
        assertThat(afterCount).isGreaterThan(beforeCount);

        // Cleanup
        blocker.countDown();
        taskExecutor.shutdown();
    }

    @Test
    @DisplayName("ExecutorServiceMetrics 등록 검증 (expectation.compute)")
    void expectationComputeExecutor_MetricsRegistered() {
        // Given & When
        executorConfig.expectationComputeExecutor(noOpDecorator, meterRegistry);

        // Then: ExecutorServiceMetrics 메트릭 등록 확인
        assertThat(meterRegistry.find("executor.pool.size")
                .tag("name", "expectation.compute")
                .gauge()).isNotNull();

        assertThat(meterRegistry.find("executor.queued")
                .tag("name", "expectation.compute")
                .gauge()).isNotNull();
    }

    @Test
    @DisplayName("ExecutorServiceMetrics 등록 검증 (alert)")
    void alertTaskExecutor_MetricsRegistered() {
        // Given & When
        executorConfig.alertTaskExecutor(noOpDecorator, meterRegistry);

        // Then: ExecutorServiceMetrics 메트릭 등록 확인
        assertThat(meterRegistry.find("executor.pool.size")
                .tag("name", "alert")
                .gauge()).isNotNull();

        assertThat(meterRegistry.find("executor.queued")
                .tag("name", "alert")
                .gauge()).isNotNull();
    }
}
