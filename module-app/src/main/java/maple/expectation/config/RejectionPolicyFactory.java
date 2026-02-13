package maple.expectation.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejection Policy Factory - Thread Pool Rejection Policy 생성 전담 클래스
 *
 * <h4>책임</h4>
 *
 * <ul>
 *   <li>Alert Executor용 LOGGING_ABORT_POLICY 생성 (샘플링 로그 포함)
 *   <li>Expectation Executor용 EXPECTATION_ABORT_POLICY 생성 (Issue #168 수정사항 반영)
 *   <li>Log Storm 방지를 위한 샘플링 카운터 관리
 *   <li>Micrometer rejected Counter 등록
 * </ul>
 *
 * <h4>Issue #168 수정사항</h4>
 *
 * <ul>
 *   <li>CallerRunsPolicy → AbortPolicy (톰캣 스레드 고갈 방지)
 *   <li>503 응답 + Retry-After 헤더 반환
 *   <li>rejected Counter 추가 (ExecutorServiceMetrics 미제공)
 * </ul>
 */
public class RejectionPolicyFactory {

  private static final Logger log = LoggerFactory.getLogger(RejectionPolicyFactory.class);

  /** 로그 샘플링 간격: 1초에 1회만 WARN 로그 (log storm 방지) */
  private static final long REJECT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  /**
   * Alert Executor용 로그 샘플링 카운터 (#271 V5 P1 검토 완료)
   *
   * <h4>Stateless 검토 결과</h4>
   *
   * <p>이 카운터들은 <b>로그 샘플링 용도</b>로, 정확한 클러스터 집계가 필요 없습니다:
   *
   * <ul>
   *   <li>목적: log storm 방지 (1초에 1회만 WARN)
   *   <li>영향: 인스턴스별 독립 샘플링 → 정상 동작
   *   <li>결론: Micrometer Counter로 교체 불필요 (이미 rejected Counter 별도 존재)
   * </ul>
   *
   * <p>실제 rejected 메트릭은 {@code executor.rejected} Counter로 Micrometer에 집계됩니다.
   */
  private static final AtomicLong lastRejectLogNanos = new AtomicLong(0);

  private static final AtomicLong rejectedSinceLastLog = new AtomicLong(0);

  /** Expectation Executor용 샘플링 카운터 (AlertExecutor와 분리) */
  private static final AtomicLong expectationLastRejectNanos = new AtomicLong(0);

  private static final AtomicLong expectationRejectedSinceLastLog = new AtomicLong(0);

  private final MeterRegistry meterRegistry;

  public RejectionPolicyFactory(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * Best-effort + Future 완료 보장 정책 (Alert Executor용)
   *
   * <h4>핵심 계약</h4>
   *
   * <ul>
   *   <li><b>드롭 허용</b>: 알림은 best-effort이므로 큐 포화 시 드롭 가능
   *   <li><b>Future 완료 보장</b>: CompletableFuture.runAsync가 "영원히 pending" 되는 것을 방지하기 위해
   *       RejectedExecutionException을 throw하여 Future가 exceptionally 완료되도록 함
   *   <li><b>Log storm 방지</b>: 샘플링으로 1초에 1회만 WARN 로그
   *   <li><b>Shutdown 시나리오</b>: 종료 중 거절은 정상이므로 로그 레벨 낮춤
   * </ul>
   *
   * <h4>DiscardPolicy와의 차이</h4>
   *
   * <p>DiscardPolicy는 조용히 드롭하여 runAsync Future가 영원히 pending됨 → 메모리 누수/관측성 누락
   *
   * <p>이 핸들러는 throw하여 Future가 완료되고, exceptionally()에서 처리 가능
   *
   * @return RejectedExecutionHandler 인스턴스
   */
  public RejectedExecutionHandler createAlertAbortPolicy() {
    // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
    Counter alertRejectedCounter =
        Counter.builder("executor.rejected")
            .tag("name", "alert")
            .description("Number of tasks rejected due to queue full")
            .register(meterRegistry);

    return (r, executor) -> {
      alertRejectedCounter.increment();

      // 종료 중 거절은 정상 시나리오이므로 로그 없이 즉시 throw
      if (executor.isShutdown() || executor.isTerminating()) {
        throw new RejectedExecutionException("AlertExecutor rejected (shutdown in progress)");
      }

      // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
      long dropped = rejectedSinceLastLog.incrementAndGet();
      long now = System.nanoTime();
      long prev = lastRejectLogNanos.get();

      if (now - prev >= REJECT_LOG_INTERVAL_NANOS && lastRejectLogNanos.compareAndSet(prev, now)) {
        long count = rejectedSinceLastLog.getAndSet(0);
        log.warn(
            "[AlertExecutor] Task rejected (queue full). droppedInLastWindow={}, taskClass={}, poolSize={}, activeCount={}, queueSize={}",
            count,
            r.getClass().getName(),
            executor.getPoolSize(),
            executor.getActiveCount(),
            executor.getQueue().size());
      }

      // ★ 핵심: throw하여 runAsync Future가 exceptionally 완료되도록 함
      throw new RejectedExecutionException("AlertExecutor queue full (dropped=" + dropped + ")");
    };
  }

  /**
   * Expectation 계산 전용 AbortPolicy (Issue #168)
   *
   * <h4>CallerRunsPolicy 제거 이유</h4>
   *
   * <ul>
   *   <li><b>톰캣 스레드 고갈</b>: 큐 포화 시 톰캣 스레드에서 작업 실행 → 전체 API 마비
   *   <li><b>메트릭 불가</b>: rejected count = 0으로 보임 (서킷브레이커 동작 불가)
   *   <li><b>SLA 위반</b>: 요청 처리 시간 비정상 증가
   * </ul>
   *
   * <h4>AbortPolicy 적용 효과</h4>
   *
   * <ul>
   *   <li><b>즉시 거부</b>: O(1) 시간 복잡도, 톰캣 스레드 보호
   *   <li><b>503 응답</b>: GlobalExceptionHandler에서 Retry-After 헤더와 함께 반환
   *   <li><b>메트릭 가시성</b>: Micrometer executor.rejected Counter로 모니터링
   * </ul>
   *
   * <h4>⚠️ Write-Behind 패턴 주의</h4>
   *
   * <p>이 정책은 <b>읽기 전용 작업에만</b> 적용하세요. DB 저장 등 쓰기 작업에 적용하면 데이터 유실 위험!
   *
   * @return RejectedExecutionHandler 인스턴스
   */
  public RejectedExecutionHandler createExpectationAbortPolicy() {
    // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
    Counter expectationRejectedCounter =
        Counter.builder("executor.rejected")
            .tag("name", "expectation.compute")
            .description("Number of tasks rejected due to queue full")
            .register(meterRegistry);

    return (r, executor) -> {
      expectationRejectedCounter.increment();

      // 종료 중 거절은 정상 시나리오
      if (executor.isShutdown() || executor.isTerminating()) {
        throw new RejectedExecutionException("ExpectationExecutor rejected (shutdown in progress)");
      }

      // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
      long dropped = expectationRejectedSinceLastLog.incrementAndGet();
      long now = System.nanoTime();
      long prev = expectationLastRejectNanos.get();

      if (now - prev >= REJECT_LOG_INTERVAL_NANOS
          && expectationLastRejectNanos.compareAndSet(prev, now)) {
        long count = expectationRejectedSinceLastLog.getAndSet(0);
        log.warn(
            "[ExpectationExecutor] Task rejected (queue full). "
                + "droppedInLastWindow={}, poolSize={}, activeCount={}, queueSize={}",
            count,
            executor.getPoolSize(),
            executor.getActiveCount(),
            executor.getQueue().size());
      }

      // Future 완료 보장을 위해 예외 throw
      throw new RejectedExecutionException("ExpectationExecutor queue full (capacity exceeded)");
    };
  }
}
