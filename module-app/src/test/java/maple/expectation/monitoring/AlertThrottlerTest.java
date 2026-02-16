package maple.expectation.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.monitoring.throttle.AlertThrottler;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 알림 스로틀러 테스트 (Issue #251, #283 P0-1)
 *
 * <p>Redis 기반 AlertThrottler의 단위 테스트. RedissonClient를 Mock하여 Redis 의존 없이 검증합니다.
 */
@DisplayName("AlertThrottler 테스트")
class AlertThrottlerTest {

  private AlertThrottler throttler;
  private RedissonClient redissonClient;
  private LogicExecutor executor;
  private RAtomicLong mockCounter;
  private RMap<String, Long> mockPatternMap;
  private AtomicLong counterValue;
  private Map<String, Long> patternMapBacking;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    redissonClient = mock(RedissonClient.class);
    executor = TestLogicExecutors.passThrough();
    mockCounter = mock(RAtomicLong.class);
    mockPatternMap = mock(RMap.class);
    counterValue = new AtomicLong(0);
    patternMapBacking = new HashMap<>();

    // RAtomicLong 동작 시뮬레이션
    given(redissonClient.getAtomicLong(anyString())).willReturn(mockCounter);
    given(mockCounter.incrementAndGet()).willAnswer(inv -> counterValue.incrementAndGet());
    given(mockCounter.decrementAndGet()).willAnswer(inv -> counterValue.decrementAndGet());
    given(mockCounter.get()).willAnswer(inv -> counterValue.get());
    given(mockCounter.isExists()).willReturn(true);
    given(mockCounter.remainTimeToLive()).willReturn(1000L);

    // RMap 동작 시뮬레이션
    given(redissonClient.<String, Long>getMap(anyString())).willReturn(mockPatternMap);
    given(mockPatternMap.get(anyString()))
        .willAnswer(inv -> patternMapBacking.get(inv.getArgument(0)));
    given(mockPatternMap.put(anyString(), any()))
        .willAnswer(
            inv -> {
              return patternMapBacking.put(inv.getArgument(0), inv.getArgument(1));
            });
    given(mockPatternMap.isExists()).willReturn(true);
    given(mockPatternMap.remainTimeToLive()).willReturn(1000L);

    throttler = new AlertThrottler(redissonClient, executor);
    ReflectionTestUtils.setField(throttler, "dailyLimit", 5);
    ReflectionTestUtils.setField(throttler, "throttleSeconds", 60);
  }

  @Test
  @DisplayName("일일 한도 내에서 AI 분석 호출이 허용되어야 한다")
  void shouldAllowAiAnalysisWithinDailyLimit() {
    assertThat(throttler.canSendAiAnalysis()).isTrue();
    assertThat(throttler.canSendAiAnalysis()).isTrue();
    assertThat(throttler.canSendAiAnalysis()).isTrue();
    assertThat(throttler.getDailyUsage()).isEqualTo(3);
    assertThat(throttler.getRemainingCalls()).isEqualTo(2);
  }

  @Test
  @DisplayName("일일 한도 초과 시 AI 분석 호출이 거부되어야 한다")
  void shouldRejectAiAnalysisWhenExceedingDailyLimit() {
    for (int i = 0; i < 5; i++) {
      throttler.canSendAiAnalysis();
    }

    assertThat(throttler.canSendAiAnalysis()).isFalse();
    assertThat(throttler.getDailyUsage()).isEqualTo(5);
    assertThat(throttler.getRemainingCalls()).isEqualTo(0);
  }

  @Test
  @DisplayName("일일 카운터 리셋 후 호출이 다시 허용되어야 한다")
  void shouldAllowCallsAfterDailyReset() {
    for (int i = 0; i < 5; i++) {
      throttler.canSendAiAnalysis();
    }
    assertThat(throttler.canSendAiAnalysis()).isFalse();

    // 리셋: Redis에서는 TTL로 자동 만료되지만, 테스트에서는 counter를 직접 리셋
    counterValue.set(0);

    assertThat(throttler.getDailyUsage()).isEqualTo(0);
    assertThat(throttler.canSendAiAnalysis()).isTrue();
  }

  @Test
  @DisplayName("동일 에러 패턴은 스로틀링되어야 한다")
  void shouldThrottleSameErrorPattern() {
    String errorPattern = "TimeoutException";

    boolean first = throttler.shouldSendAlert(errorPattern);

    assertThat(first).isTrue();
    assertThat(throttler.shouldSendAlert(errorPattern)).isFalse();
  }

  @Test
  @DisplayName("다른 에러 패턴은 스로틀링되지 않아야 한다")
  void shouldNotThrottleDifferentErrorPatterns() {
    boolean timeout = throttler.shouldSendAlert("TimeoutException");
    boolean connection = throttler.shouldSendAlert("ConnectionException");
    boolean redis = throttler.shouldSendAlert("RedisException");

    assertThat(timeout).isTrue();
    assertThat(connection).isTrue();
    assertThat(redis).isTrue();
  }

  @Test
  @DisplayName("스로틀링과 일일 한도가 함께 적용되어야 한다")
  void shouldApplyBothThrottlingAndDailyLimit() {
    String errorPattern = "TestException";

    boolean first = throttler.canSendAiAnalysisWithThrottle(errorPattern);
    boolean second = throttler.canSendAiAnalysisWithThrottle(errorPattern);
    boolean third = throttler.canSendAiAnalysisWithThrottle("DifferentException");

    assertThat(first).isTrue();
    assertThat(second).isFalse(); // 스로틀링
    assertThat(third).isTrue();
  }
}
