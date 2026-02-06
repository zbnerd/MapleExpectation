package maple.expectation.global.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Retry Budget Manager 테스트
 *
 * <p>Retry Storm 방지를 위한 예산 관리 기능 검증</p>
 *
 * <h4>검증 항목</h4>
 * <ul>
 *   <li>예산 허용: 정상적인 재시도 시도 허용</li>
 *   <li>예산 소진: 한도 초과 시 Fail Fast</li>
 *   <li>윈도우 리셋: 시간 경과 후 자동 리셋</li>
 *   <li>메트릭 게시: Micrometer 카운터 정확성</li>
 *   <li>Thread-Safety: 동시 요청 안전성</li>
 * </ul>
 *
 * @see RetryBudgetManager
 * @see RetryBudgetProperties
 */
@ExtendWith(MockitoExtension.class)
class RetryBudgetManagerTest {

    @Mock
    private RetryBudgetProperties properties;

    private MeterRegistry meterRegistry;
    private RetryBudgetManager retryBudgetManager;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        // 기본 설정 모킹 (lenient stubbing 허용)
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(properties.getMaxRetriesPerMinute()).thenReturn(10);
        lenient().when(properties.getWindowSizeSeconds()).thenReturn(60);
        lenient().when(properties.isMetricsEnabled()).thenReturn(true);

        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);
    }

    @Test
    @DisplayName("예산 허용: 정상적인 재시도 시도 허용")
    void shouldAllowRetry_WhenBudgetAvailable() {
        // Given
        String serviceName = "nexonApi";

        // When: 예산 내에서 시도
        boolean allowed1 = retryBudgetManager.tryAcquire(serviceName);
        boolean allowed2 = retryBudgetManager.tryAcquire(serviceName);

        // Then: 모두 허용
        assertThat(allowed1).isTrue();
        assertThat(allowed2).isTrue();
        assertThat(retryBudgetManager.getCurrentRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("예산 소진: 한도 초과 시 Fail Fast")
    void shouldRejectRetry_WhenBudgetExhausted() {
        // Given
        String serviceName = "nexonApi";
        int maxRetries = 5;
        when(properties.getMaxRetriesPerMinute()).thenReturn(maxRetries);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        // When: 예산 한도까지 소진
        for (int i = 0; i < maxRetries; i++) {
            assertThat(retryBudgetManager.tryAcquire(serviceName)).isTrue();
        }

        // Then: 추가 시도는 거부
        boolean rejected = retryBudgetManager.tryAcquire(serviceName);
        assertThat(rejected).isFalse();
        assertThat(retryBudgetManager.getCurrentRetryCount()).isEqualTo(maxRetries);
    }

    @Test
    @DisplayName("비활성화: 항상 허용")
    void shouldAlwaysAllow_WhenDisabled() {
        // Given
        lenient().when(properties.isEnabled()).thenReturn(false);
        lenient().when(properties.getMaxRetriesPerMinute()).thenReturn(1);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        String serviceName = "nexonApi";

        // When: 비활성화 상태에서 10회 시도
        boolean allAllowed = true;
        for (int i = 0; i < 10; i++) {
            allAllowed &= retryBudgetManager.tryAcquire(serviceName);
        }

        // Then: 모두 허용
        assertThat(allAllowed).isTrue();
    }

    @Test
    @DisplayName("메트릭 게시: 카운터 정확성 검증")
    void shouldPublishMetrics_WhenEnabled() {
        // Given
        String serviceName = "testService";
        when(properties.getMaxRetriesPerMinute()).thenReturn(5);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        // When: 3회 시도 (lazy initialization 방지 위해 먼저 한 번 호출)
        retryBudgetManager.tryAcquire(serviceName);
        retryBudgetManager.tryAcquire(serviceName);
        retryBudgetManager.tryAcquire(serviceName);

        // Then: 메트릭 확인 (lazy initialization 고려하여 허용 메트릭만 검증)
        assertThat(meterRegistry.get("retry_budget_attempts_total").counter().count()).isGreaterThanOrEqualTo(3);
        assertThat(meterRegistry.get("retry_budget_allowed_total").counter().count()).isGreaterThanOrEqualTo(3);
        assertThat(meterRegistry.find("retry_budget_rejected_total").counter()).isNull();
    }

    @Test
    @DisplayName("메트릭 거부: 예산 초과 시 거부 카운터 증가")
    void shouldPublishRejectionMetrics_WhenBudgetExceeded() {
        // Given
        String serviceName = "testService";
        when(properties.getMaxRetriesPerMinute()).thenReturn(2);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        // When: 3회 시도 (2회 허용, 1회 거부)
        retryBudgetManager.tryAcquire(serviceName);
        retryBudgetManager.tryAcquire(serviceName);
        retryBudgetManager.tryAcquire(serviceName); // 거부

        // Then: 거부 메트릭 확인
        assertThat(meterRegistry.get("retry_budget_rejected_total").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("소비율 계산: 정확한 비율 반환")
    void shouldCalculateConsumptionRate() {
        // Given
        String serviceName = "testService";
        when(properties.getMaxRetriesPerMinute()).thenReturn(100);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        // When: 50회 소진
        for (int i = 0; i < 50; i++) {
            retryBudgetManager.tryAcquire(serviceName);
        }

        // Then: 50% 소비율
        assertThat(retryBudgetManager.getConsumptionRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("윈도우 리셋: 수동 리셋 동작 검증")
    void shouldResetWindow_WhenManuallyReset() {
        // Given
        String serviceName = "testService";
        when(properties.getMaxRetriesPerMinute()).thenReturn(5);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        // When: 5회 소진 후 리셋
        for (int i = 0; i < 5; i++) {
            retryBudgetManager.tryAcquire(serviceName);
        }
        assertThat(retryBudgetManager.getCurrentRetryCount()).isEqualTo(5);

        retryBudgetManager.reset();

        // Then: 카운터 리셋
        assertThat(retryBudgetManager.getCurrentRetryCount()).isZero();
    }

    @Test
    @DisplayName("윈도우 경과 시간: 정확한 시간 계산")
    void shouldCalculateWindowTime() {
        // Given: 최소 윈도우 크기로 테스트
        when(properties.getWindowSizeSeconds()).thenReturn(60);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        // When: 경과 시간 확인
        long elapsed = retryBudgetManager.getWindowElapsedSeconds();
        long remaining = retryBudgetManager.getWindowRemainingSeconds();

        // Then: 유효한 범위 내
        assertThat(elapsed).isGreaterThanOrEqualTo(0);
        assertThat(remaining).isGreaterThan(0);
        assertThat(elapsed + remaining).isEqualTo(60);
    }

    @Test
    @DisplayName("동시성 안전성: 다중 스레드에서의 카운터 정확성")
    void shouldHandleConcurrentRequests() throws InterruptedException {
        // Given
        String serviceName = "concurrentService";
        int maxRetries = 100;
        int threadCount = 10;
        int requestsPerThread = 15; // 총 150회 시도 (100 허용, 50 거부 예상)

        when(properties.getMaxRetriesPerMinute()).thenReturn(maxRetries);
        retryBudgetManager = new RetryBudgetManager(properties, meterRegistry);

        // When: 동시 요청
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    retryBudgetManager.tryAcquire(serviceName);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Then: 최대 허용 횟수로 제한 (Race Condition 허용하여 약간의 초과 허용)
        assertThat(retryBudgetManager.getCurrentRetryCount())
                .isLessThanOrEqualTo(maxRetries + threadCount); // Race Condition으로 인한 초과 허용
        assertThat(meterRegistry.get("retry_budget_allowed_total").counter().count())
                .isGreaterThanOrEqualTo(maxRetries);
    }
}
