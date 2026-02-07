package maple.expectation.global.event;

import java.time.Instant;

/**
 * MySQL 장애 감지 이벤트
 *
 * <p>CircuitBreaker(likeSyncDb)가 OPEN 상태로 전이될 때 발행됩니다.
 *
 * <p>Debounce 처리 후 실제 장애로 확인되면 DynamicTTLManager가 이 이벤트를 수신합니다.
 *
 * @param timestamp 장애 감지 시각
 * @param circuitBreakerName 장애를 감지한 CircuitBreaker 이름
 * @param fromState 이전 상태 (CLOSED, HALF_OPEN 등)
 * @param toState 현재 상태 (OPEN)
 */
public record MySQLDownEvent(
    Instant timestamp, String circuitBreakerName, String fromState, String toState) {
  public static MySQLDownEvent of(String cbName, String fromState, String toState) {
    return new MySQLDownEvent(Instant.now(), cbName, fromState, toState);
  }
}
