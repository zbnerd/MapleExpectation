package maple.expectation.monitoring.collector;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CircuitBreaker 상태 전이 이벤트 로거 (P1-5)
 *
 * <h3>목적</h3>
 *
 * <p>서킷브레이커 상태 전이(CLOSED→OPEN→HALF_OPEN 등)를 로그로 기록하여 운영 가시성을 확보합니다.
 *
 * <h3>기록 대상</h3>
 *
 * <ul>
 *   <li>상태 전이 (State Transition): WARN 레벨
 *   <li>예외 기록 (Error): ERROR 레벨 (WARN으로 낮춤 - 이미 handler에서 처리)
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventLogger {

  private final CircuitBreakerRegistry circuitBreakerRegistry;

  @PostConstruct
  void registerEventListeners() {
    circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::registerStateTransitionListener);

    // 동적으로 추가되는 CB도 등록
    circuitBreakerRegistry
        .getEventPublisher()
        .onEntryAdded(event -> registerStateTransitionListener(event.getAddedEntry()));
  }

  private void registerStateTransitionListener(CircuitBreaker cb) {
    cb.getEventPublisher()
        .onStateTransition(
            event ->
                log.warn(
                    "[CircuitBreaker:{}] State transition: {} → {}",
                    event.getCircuitBreakerName(),
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()))
        .onSlowCallRateExceeded(
            event ->
                log.warn(
                    "[CircuitBreaker:{}] Slow call rate exceeded: {}%",
                    event.getCircuitBreakerName(), event.getSlowCallRate()))
        .onFailureRateExceeded(
            event ->
                log.warn(
                    "[CircuitBreaker:{}] Failure rate exceeded: {}%",
                    event.getCircuitBreakerName(), event.getFailureRate()));
  }
}
