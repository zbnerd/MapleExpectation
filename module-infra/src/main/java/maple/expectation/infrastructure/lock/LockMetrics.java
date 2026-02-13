package maple.expectation.infrastructure.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lock Instrumentation Metrics (Issue #310 Phase 0)
 *
 * <h3>목적</h3>
 *
 * <p>락 획득 성능을 모니터링하여 병목 현상을 조기에 감지합니다.
 *
 * <h3>메트릭</h3>
 *
 * <ul>
 *   <li><b>Lock Wait Time (Timer)</b>: p50, p95, p99 백분위수
 *   <li><b>Lock Failures (Counter)</b>: 구현체별(redis/mysql) 실패 횟수
 *   <li><b>Active Locks (Gauge)</b>: 현재 획득 중인 락 수
 * </ul>
 *
 * <h3>CLAUDE.md 준수사항</h3>
 *
 * <ul>
 *   <li>Section 12: Zero Try-Catch Policy - LogicExecutor 패턴 사용
 *   <li>Section 17: 소문자 점 표기법 사용 (lock.wait.time 등)
 *   <li>@PostConstruct로 1회만 초기화 (gauge 중복 등록 방지)
 * </ul>
 *
 * <h3>Prometheus 메트릭 이름</h3>
 *
 * <pre>
 * - lock_wait_time_seconds          : Timer (p50, p95, p99)
 * - lock_acquisition_failure_total  : Counter (tag: implementation=redis|mysql)
 * - lock_active_current             : Gauge
 * </pre>
 *
 * <h3>Prometheus Alert 예시</h3>
 *
 * <pre>
 * - alert: LockWaitTimeHigh
 *   expr: histogram_quantile(0.99, rate(lock_wait_time_seconds_bucket[5m])) > 1
 *   for: 2m
 *   labels:
 *     severity: warning
 *   annotations:
 *     summary: "P99 lock wait time exceeds 1 second"
 *
 * - alert: LockFailureRateHigh
 *   expr: rate(lock_acquisition_failure_total[5m]) > 0.1
 *   for: 2m
 *   labels:
 *     severity: critical
 *   annotations:
 *     summary: "Lock failure rate exceeds 10%"
 * </pre>
 *
 * @since 2026-02-06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockMetrics {

  private final MeterRegistry registry;

  // Timer (lock wait time percentiles)
  private Timer lockWaitTimer;

  // Counters (thread-safe)
  private Counter redisFailureCounter;
  private Counter mysqlFailureCounter;

  // Gauge backing fields
  private final AtomicLong redisActiveLocks = new AtomicLong(0);
  private final AtomicLong mysqlActiveLocks = new AtomicLong(0);

  /**
   * 메트릭 초기화 (1회만 실행)
   *
   * <p>Green Agent 요구사항: gauge 중복 등록 방지
   */
  @PostConstruct
  public void init() {
    // Timer 초기화 (lock wait time percentiles)
    lockWaitTimer =
        Timer.builder("lock.wait.time")
            .description("Time spent waiting for lock acquisition")
            .tags("implementation", "all")
            .register(registry);

    // Counters 초기화 (tag: implementation)
    redisFailureCounter =
        Counter.builder("lock.acquisition.failure.total")
            .description("Total lock acquisition failures")
            .tag("implementation", "redis")
            .register(registry);

    mysqlFailureCounter =
        Counter.builder("lock.acquisition.failure.total")
            .description("Total lock acquisition failures")
            .tag("implementation", "mysql")
            .register(registry);

    // Gauges 초기화 (1회만)
    Gauge.builder("lock.active.current", redisActiveLocks, AtomicLong::get)
        .description("Currently active locks")
        .tag("implementation", "redis")
        .register(registry);

    Gauge.builder("lock.active.current", mysqlActiveLocks, AtomicLong::get)
        .description("Currently active locks")
        .tag("implementation", "mysql")
        .register(registry);

    log.info(
        "[LockMetrics] Initialized - Timer (p50/p95/p99), Counters (redis/mysql failures), Gauges (active locks) registered");
  }

  /**
   * 락 대기 시간 기록
   *
   * <p>락 획득 시작 전과 성공 후에 호출하여 대기 시간을 측정합니다.
   *
   * @param waitTimeMs 대기 시간 (밀리초)
   * @param implementation 구현체 (redis/mysql)
   */
  public void recordWaitTime(long waitTimeMs, String implementation) {
    lockWaitTimer.record(waitTimeMs, TimeUnit.MILLISECONDS);
    log.debug("[LockMetrics] Recorded wait time: {}ms for {}", waitTimeMs, implementation);
  }

  /**
   * 락 획득 실패 기록
   *
   * @param implementation 구현체 (redis/mysql)
   */
  public void recordFailure(String implementation) {
    switch (implementation.toLowerCase()) {
      case "redis" -> redisFailureCounter.increment();
      case "mysql" -> mysqlFailureCounter.increment();
      default -> log.warn("[LockMetrics] Unknown implementation: {}", implementation);
    }
    log.debug("[LockMetrics] Recorded failure for {}", implementation);
  }

  /**
   * 락 활성화 기록 (획득 성공 시 호출)
   *
   * @param implementation 구현체 (redis/mysql)
   */
  public void recordLockAcquired(String implementation) {
    switch (implementation.toLowerCase()) {
      case "redis" -> redisActiveLocks.incrementAndGet();
      case "mysql" -> mysqlActiveLocks.incrementAndGet();
      default -> log.warn("[LockMetrics] Unknown implementation: {}", implementation);
    }
    log.debug("[LockMetrics] Recorded lock acquired for {}", implementation);
  }

  /**
   * 락 비활성화 기록 (해제 시 호출)
   *
   * @param implementation 구현체 (redis/mysql)
   */
  public void recordLockReleased(String implementation) {
    switch (implementation.toLowerCase()) {
      case "redis" -> redisActiveLocks.decrementAndGet();
      case "mysql" -> mysqlActiveLocks.decrementAndGet();
      default -> log.warn("[LockMetrics] Unknown implementation: {}", implementation);
    }
    log.debug("[LockMetrics] Recorded lock released for {}", implementation);
  }

  /** 현재 활성 락 수 조회 (테스트용) */
  public long getActiveLocks(String implementation) {
    return switch (implementation.toLowerCase()) {
      case "redis" -> redisActiveLocks.get();
      case "mysql" -> mysqlActiveLocks.get();
      default -> 0L;
    };
  }
}
