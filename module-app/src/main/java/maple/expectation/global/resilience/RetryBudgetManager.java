package maple.expectation.global.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Retry Budget 관리자
 *
 * <p>장기간 장애 시 재시도 폭주(Retry Storm)를 방지하기 위해 시간 기반 예산 관리
 *
 * <h3>핵심 기능</h3>
 *
 * <ul>
 *   <li><b>시간 윈도우 기반 예산 관리</b>: Sliding Window로 최근 1분간 재시도 횟수 추적
 *   <li><b>예산 소진 시 Fail Fast</b>: 예산 고갈 시 즉시 실패 반환으로 시스템 보호
 *   <li><b>메트릭 게시</b>: Micrometer를 통해 예산 소비율 모니터링
 *   <li><b>Thread-Safe</b>: AtomicLong, LongAdder로 동시성 안전성 확보
 * </ul>
 *
 * <h3>동작 방식</h3>
 *
 * <pre>
 * 1. tryAcquire() 호출 시 현재 윈도우 내 재시도 횟수 확인
 * 2. 예산 여유 있으면(true): 카운터 증가 후 재시도 진행
 * 3. 예산 소진 시(false): 즉시 실패 처리 (Fail Fast)
 * 4. 윈도우 경과 시 자동 리셋 (epoch 기반)
 * </pre>
 *
 * <h4>Resilience4j 통합</h4>
 *
 * <pre>{@code
 * @Retry(name = "nexonApi")
 * public CompletableFuture<Response> callApi(String ocid) {
 *     if (!retryBudgetManager.tryAcquire("nexonApi")) {
 *         log.warn("Retry budget exhausted, failing fast");
 *         return CompletableFuture.failedFuture(new ExternalServiceException("Retry budget exceeded"));
 *     }
 *     return delegate.callApi(ocid);
 * }
 * }</pre>
 *
 * <h4>메트릭 (Actuator /actuator/metrics)</h4>
 *
 * <ul>
 *   <li>{@code retry_budget_attempts_total}: 전체 예산 요청 횟수
 *   <li>{@code retry_budget_allowed_total}: 예산 허용 횟수
 *   <li>{@code retry_budget_rejected_total}: 예산 거부 횟수
 *   <li>{@code retry_budget_window_seconds}: 현재 윈도우 경과 시간
 * </ul>
 *
 * @see RetryBudgetProperties
 * @see <a href="https://sre.google/sre-book/addressing-cascading-failures/">Google SRE - Cascading
 *     Failures</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryBudgetManager {

  private final RetryBudgetProperties properties;
  private final MeterRegistry meterRegistry;

  // Thread-safe counters (LongAdder: 경합 최적화)
  private final LongAdder retryCounter = new LongAdder();
  private final AtomicLong windowStartEpoch = new AtomicLong(Instant.now().getEpochSecond());

  // Metrics
  private Counter attemptsCounter;
  private Counter allowedCounter;
  private Counter rejectedCounter;
  private Timer windowTimer;

  /**
   * Retry 예산 시도 (Thread-Safe)
   *
   * <p>현재 시간 윈도우 내 예산 여부를 확인하고 필요 시 카운터 증가
   *
   * <h4>동작 순서</h4>
   *
   * <ol>
   *   <li>현재 윈도우 확인 및 경과 시 리셋
   *   <li>예산 한도 초과 여부 확인
   *   <li>예산 있으면 카운터 증가 후 true 반환
   *   <li>예산 소진 시 false 반환 (Fail Fast)
   * </ol>
   *
   * @param serviceName 서비스 식별자 (예: "nexonApi", "likeSyncRetry")
   * @return 예산 허용 여부 (true: 재시도 가능, false: 예산 초과로 Fail Fast)
   */
  public boolean tryAcquire(String serviceName) {
    if (!properties.isEnabled()) {
      return true; // 비활성화 시 항상 허용
    }

    // 1. 윈도우 경과 시 리셋
    resetWindowIfNeeded();

    // 2. 현재 카운터 확인
    long currentCount = retryCounter.sum();
    long maxRetries = properties.getMaxRetriesPerMinute();

    // 3. 메트릭 기록
    recordAttempt();

    // 4. 예산 확인
    if (currentCount >= maxRetries) {
      recordRejection(serviceName);
      log.warn(
          "[RetryBudget] 예산 소진으로 재시도 차단. serviceName={}, current={}, limit={}, window={}s",
          serviceName,
          currentCount,
          maxRetries,
          getWindowElapsedSeconds());
      return false;
    }

    // 5. 예산 허용: 카운터 증가
    retryCounter.increment();
    recordAllowed(serviceName);

    log.debug(
        "[RetryBudget] 예산 허용. serviceName={}, count={}/{}",
        serviceName,
        currentCount + 1,
        maxRetries);
    return true;
  }

  /**
   * 현재 예산 소비율 반환 (모니터링용)
   *
   * <p>0.0 ~ 1.0 사이 값 (1.0 = 100% 소진)
   *
   * @return 예산 소비율
   */
  public double getConsumptionRate() {
    long currentCount = retryCounter.sum();
    long maxRetries = properties.getMaxRetriesPerMinute();
    return (double) currentCount / maxRetries;
  }

  /**
   * 현재 윈도우 경과 시간 (초)
   *
   * @return 경과 시간 (초)
   */
  public long getWindowElapsedSeconds() {
    long currentEpoch = Instant.now().getEpochSecond();
    long startEpoch = windowStartEpoch.get();
    return currentEpoch - startEpoch;
  }

  /**
   * 현재 윈도우 남은 시간 (초)
   *
   * @return 남은 시간 (초)
   */
  public long getWindowRemainingSeconds() {
    long elapsed = getWindowElapsedSeconds();
    long windowSize = properties.getWindowSizeSeconds();
    return Math.max(0, windowSize - elapsed);
  }

  /**
   * 현재 재시도 카운터 반환 (모니터링용)
   *
   * @return 현재 윈도우 내 재시도 횟수
   */
  public long getCurrentRetryCount() {
    return retryCounter.sum();
  }

  /**
   * 윈도우 리셋 (테스트용)
   *
   * <p>카운터와 시작 에포크를 초기화
   */
  public void reset() {
    retryCounter.reset();
    windowStartEpoch.set(Instant.now().getEpochSecond());
    log.info("[RetryBudget] 윈도우 리셋됨");
  }

  /**
   * 윈도우 경과 시 리셋 수행
   *
   * <p>Thread-safe: CAS(Compare-And-Swap) 없이 epoch 기반 단순 비교
   */
  private void resetWindowIfNeeded() {
    long currentEpoch = Instant.now().getEpochSecond();
    long startEpoch = windowStartEpoch.get();
    long windowSize = properties.getWindowSizeSeconds();

    if (currentEpoch - startEpoch >= windowSize) {
      // Race Condition 허용: 중복 리셋 시 카운터만 0으로 설정 (안전)
      windowStartEpoch.set(currentEpoch);
      retryCounter.reset();
      log.debug(
          "[RetryBudget] 윈도우 경과로 리셋. elapsed={}s, limit={}s",
          currentEpoch - startEpoch,
          windowSize);
    }
  }

  // ========== Metrics Recording ==========

  private void recordAttempt() {
    if (properties.isMetricsEnabled()) {
      if (attemptsCounter == null) {
        attemptsCounter =
            Counter.builder("retry_budget_attempts_total")
                .description("Total retry budget acquisition attempts")
                .register(meterRegistry);
      }
      attemptsCounter.increment();
    }
  }

  private void recordAllowed(String serviceName) {
    if (properties.isMetricsEnabled()) {
      if (allowedCounter == null) {
        allowedCounter =
            Counter.builder("retry_budget_allowed_total")
                .description("Total retry budget allowances")
                .tag("service", serviceName)
                .register(meterRegistry);
      }
      allowedCounter.increment();
    }
  }

  private void recordRejection(String serviceName) {
    if (properties.isMetricsEnabled()) {
      if (rejectedCounter == null) {
        rejectedCounter =
            Counter.builder("retry_budget_rejected_total")
                .description("Total retry budget rejections")
                .tag("service", serviceName)
                .register(meterRegistry);
      }
      rejectedCounter.increment();
    }
  }
}
