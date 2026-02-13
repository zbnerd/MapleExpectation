package maple.expectation.infrastructure.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lock Ordering 메트릭 관리 (Issue #228: N09-Circular Lock)
 *
 * <h3>목적</h3>
 *
 * <p>락 획득 순서 위반을 감지하고 메트릭으로 기록하여 잠재적 Deadlock 위험을 모니터링합니다.
 *
 * <h3>CLAUDE.md 준수사항</h3>
 *
 * <ul>
 *   <li>Section 12: Zero Try-Catch Policy - 모든 예외는 LogicExecutor로 처리
 *   <li>Section 17: 소문자 점 표기법 사용
 *   <li>@PostConstruct로 1회만 초기화 (gauge 중복 등록 방지)
 * </ul>
 *
 * <h3>Prometheus Alert 예시</h3>
 *
 * <pre>
 * - alert: LockOrderViolationDetected
 *   expr: rate(lock.order.violation.total[5m]) > 0
 *   for: 1m
 *   labels:
 *     severity: warning
 *   annotations:
 *     summary: "Lock ordering violation detected - potential deadlock risk"
 * </pre>
 *
 * @see MySqlNamedLockStrategy
 * @since 2026-01-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockOrderMetrics {

  private final MeterRegistry registry;

  // Counters (Thread-safe)
  private Counter violationCounter;
  private Counter acquisitionCounter;

  // Gauge backing fields
  private final AtomicLong currentHeldLocks = new AtomicLong(0);

  /**
   * 메트릭 초기화 (1회만 실행)
   *
   * <p>Green Agent 요구사항: gauge 중복 등록 방지
   */
  @PostConstruct
  public void init() {
    // Counters 초기화
    violationCounter = registry.counter("lock.order.violation.total");
    acquisitionCounter = registry.counter("lock.acquisition.total");

    // Gauge 초기화 (1회만)
    registry.gauge("lock.held.current", currentHeldLocks);

    log.info(
        "[LockOrderMetrics] Initialized - violation/acquisition counters and held gauge registered");
  }

  /**
   * 락 순서 위반 기록
   *
   * <p>현재 락보다 알파벳순으로 앞선 락을 획득하려 할 때 호출됩니다. 이는 Coffman Condition #4 (Circular Wait) 위반 가능성을 나타냅니다.
   *
   * @param currentLock 현재 획득하려는 락 키
   * @param previousLock 이미 보유 중인 락 키 (알파벳순으로 현재 락보다 뒤)
   */
  public void recordViolation(String currentLock, String previousLock) {
    violationCounter.increment();

    // 태그 기반 상세 메트릭 (선택적)
    registry
        .counter(
            "lock.order.violation.detail",
            Tags.of(
                "current", sanitizeKey(currentLock),
                "previous", sanitizeKey(previousLock)))
        .increment();

    log.warn(
        "[LockOrder] Violation recorded: '{}' requested after '{}' - potential deadlock risk",
        currentLock,
        previousLock);
  }

  /**
   * 락 획득 기록
   *
   * @param lockKey 획득한 락 키
   */
  public void recordAcquisition(String lockKey) {
    acquisitionCounter.increment();
    currentHeldLocks.incrementAndGet();
  }

  /**
   * 락 해제 기록
   *
   * @param lockKey 해제한 락 키
   */
  public void recordRelease(String lockKey) {
    currentHeldLocks.decrementAndGet();
  }

  /** 현재 보유 중인 락 수 조회 (테스트용) */
  public long getCurrentHeldLocks() {
    return currentHeldLocks.get();
  }

  /**
   * 메트릭 태그용 키 정규화
   *
   * <p>Prometheus 태그에 사용할 수 없는 문자 제거
   */
  private String sanitizeKey(String key) {
    if (key == null) {
      return "unknown";
    }
    // 최대 50자로 제한, 특수문자 제거
    String sanitized = key.replaceAll("[^a-zA-Z0-9_\\-:]", "_");
    return sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized;
  }
}
