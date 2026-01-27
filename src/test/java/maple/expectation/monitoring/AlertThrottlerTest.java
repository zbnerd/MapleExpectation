package maple.expectation.monitoring;

import maple.expectation.monitoring.throttle.AlertThrottler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 스로틀러 테스트 (Issue #251)
 *
 * <p>[P0-Green] 일일 LLM 호출 한도 및 스로틀링 검증</p>
 */
@DisplayName("AlertThrottler 테스트")
class AlertThrottlerTest {

    private AlertThrottler throttler;

    @BeforeEach
    void setUp() {
        throttler = new AlertThrottler();
        ReflectionTestUtils.setField(throttler, "dailyLimit", 5);
        ReflectionTestUtils.setField(throttler, "throttleSeconds", 60);
    }

    @Test
    @DisplayName("일일 한도 내에서 AI 분석 호출이 허용되어야 한다")
    void shouldAllowAiAnalysisWithinDailyLimit() {
        // When & Then
        assertThat(throttler.canSendAiAnalysis()).isTrue();
        assertThat(throttler.canSendAiAnalysis()).isTrue();
        assertThat(throttler.canSendAiAnalysis()).isTrue();
        assertThat(throttler.getDailyUsage()).isEqualTo(3);
        assertThat(throttler.getRemainingCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("일일 한도 초과 시 AI 분석 호출이 거부되어야 한다")
    void shouldRejectAiAnalysisWhenExceedingDailyLimit() {
        // Given: 한도까지 호출
        for (int i = 0; i < 5; i++) {
            throttler.canSendAiAnalysis();
        }

        // When & Then
        assertThat(throttler.canSendAiAnalysis()).isFalse();
        assertThat(throttler.getDailyUsage()).isEqualTo(5);
        assertThat(throttler.getRemainingCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("일일 카운터 리셋 후 호출이 다시 허용되어야 한다")
    void shouldAllowCallsAfterDailyReset() {
        // Given: 한도까지 호출
        for (int i = 0; i < 5; i++) {
            throttler.canSendAiAnalysis();
        }
        assertThat(throttler.canSendAiAnalysis()).isFalse();

        // When: 리셋
        throttler.resetDailyCount();

        // Then
        assertThat(throttler.getDailyUsage()).isEqualTo(0);
        assertThat(throttler.canSendAiAnalysis()).isTrue();
    }

    @Test
    @DisplayName("동일 에러 패턴은 스로틀링되어야 한다")
    void shouldThrottleSameErrorPattern() {
        // Given
        String errorPattern = "TimeoutException";

        // When: 첫 번째 호출
        boolean first = throttler.shouldSendAlert(errorPattern);

        // Then: 첫 번째는 허용, 두 번째는 스로틀링
        assertThat(first).isTrue();
        assertThat(throttler.shouldSendAlert(errorPattern)).isFalse();
    }

    @Test
    @DisplayName("다른 에러 패턴은 스로틀링되지 않아야 한다")
    void shouldNotThrottleDifferentErrorPatterns() {
        // When
        boolean timeout = throttler.shouldSendAlert("TimeoutException");
        boolean connection = throttler.shouldSendAlert("ConnectionException");
        boolean redis = throttler.shouldSendAlert("RedisException");

        // Then
        assertThat(timeout).isTrue();
        assertThat(connection).isTrue();
        assertThat(redis).isTrue();
    }

    @Test
    @DisplayName("스로틀링과 일일 한도가 함께 적용되어야 한다")
    void shouldApplyBothThrottlingAndDailyLimit() {
        // Given
        String errorPattern = "TestException";

        // When: 스로틀링과 일일 한도 동시 체크
        boolean first = throttler.canSendAiAnalysisWithThrottle(errorPattern);
        boolean second = throttler.canSendAiAnalysisWithThrottle(errorPattern);
        boolean third = throttler.canSendAiAnalysisWithThrottle("DifferentException");

        // Then
        assertThat(first).isTrue(); // 첫 번째: 허용
        assertThat(second).isFalse(); // 스로틀링
        assertThat(third).isTrue(); // 다른 패턴: 허용
    }

    @Test
    @DisplayName("스로틀 캐시 정리가 동작해야 한다")
    void shouldCleanupThrottleCache() {
        // Given
        throttler.shouldSendAlert("Pattern1");
        throttler.shouldSendAlert("Pattern2");
        throttler.shouldSendAlert("Pattern3");

        // When: 캐시 정리 실행
        throttler.cleanupThrottleCache();

        // Then: 정리 후에도 정상 동작 (최근 항목은 유지)
        assertThat(throttler.shouldSendAlert("Pattern1")).isFalse(); // 아직 스로틀링 중
    }
}
