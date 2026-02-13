package maple.expectation.infrastructure.lock;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import maple.expectation.common.function.ThrowingSupplier;

/**
 * 분산 락 전략 인터페이스
 *
 * <h3>P0-N02 Fix: Lock Ordering 지원 (Issue #221)</h3>
 *
 * <p>다중 락 시나리오에서 Coffman Condition #4 (Circular Wait)를 방지하기 위해 {@link #executeWithOrderedLocks}
 * 메서드를 추가했습니다.
 *
 * <h3>CLAUDE.md 준수사항</h3>
 *
 * <ul>
 *   <li>Section 4: OCP 원칙 - default 메서드로 기존 구현체 호환성 유지
 *   <li>P1-BLUE-02: 복합키 방식 기본 구현 (구현체에서 Override 권장)
 * </ul>
 */
public interface LockStrategy {

  // 1. 기존: 락을 획득하고 작업을 실행 (WaitTime 대기 포함)
  <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task)
      throws Throwable;

  // 2. 기존: 기본 설정값으로 락 실행
  <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable;

  // 3. 추가: 즉시 락 획득 시도 (기다리지 않고 성공 여부만 반환)
  boolean tryLockImmediately(String key, long leaseTime);

  // 4. 추가: 락 수동 해제
  void unlock(String key);

  /**
   * [P0-N02] 다중 락 순서 보장 실행
   *
   * <p><b>Coffman Condition #4 (Circular Wait) 방지</b>: 락 키들을 알파벳순으로 정렬하여 모든 스레드가 동일한 순서로 락을 획득하도록
   * 강제합니다.
   *
   * <h4>기본 구현: 복합키 방식 (P1-BLUE-02)</h4>
   *
   * <p>키들을 정렬 후 단일 복합키로 결합하여 락을 획득합니다. 순차적 개별 락 획득이 필요한 경우 구현체에서 Override하세요.
   *
   * <h4>사용 예시</h4>
   *
   * <pre>{@code
   * // 계좌 이체: from → to 순서 보장
   * lockStrategy.executeWithOrderedLocks(
   *     List.of("account:" + fromId, "account:" + toId),
   *     30, TimeUnit.SECONDS, 60,
   *     () -> transferService.transfer(fromId, toId, amount)
   * );
   * }</pre>
   *
   * @param keys 락 키 목록 (내부에서 알파벳순 정렬됨)
   * @param totalTimeout 전체 타임아웃 값
   * @param timeUnit 타임아웃 단위
   * @param leaseTime 락 유지 시간 (초)
   * @param task 실행할 작업
   * @return 작업 결과
   * @throws Throwable 락 획득 실패 또는 작업 실행 중 예외
   */
  default <T> T executeWithOrderedLocks(
      List<String> keys,
      long totalTimeout,
      TimeUnit timeUnit,
      long leaseTime,
      ThrowingSupplier<T> task)
      throws Throwable {
    // [P1-BLUE-02] 기본 구현: 알파벳순 정렬 후 복합키로 결합
    // 진정한 다중 락 지원이 필요하면 구현체에서 Override
    String compositeKey = keys.stream().sorted().collect(Collectors.joining(":"));

    long timeoutSeconds = timeUnit.toSeconds(totalTimeout);
    return executeWithLock(compositeKey, timeoutSeconds, leaseTime, task);
  }
}
