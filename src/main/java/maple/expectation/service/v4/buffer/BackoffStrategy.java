package maple.expectation.service.v4.buffer;

import java.util.concurrent.locks.LockSupport;

/**
 * CAS 연산 실패 시 재시도 전 대기 전략 (#266 ADR 정합성)
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Yellow (QA): 인터페이스 추상화로 테스트 가능성 확보</li>
 *   <li>Green (Performance): Exponential backoff로 경합 최소화</li>
 *   <li>Blue (Architect): Strategy 패턴으로 OCP 준수</li>
 * </ul>
 *
 * <h3>테스트 시 NoOpBackoff 사용</h3>
 * <pre>
 * // 결정적 테스트를 위해 대기 없는 전략 주입
 * buffer = new ExpectationWriteBackBuffer(
 *     properties, meterRegistry, new NoOpBackoff(), executor
 * );
 * </pre>
 *
 * @see ExponentialBackoff 운영 환경용 Exponential backoff 구현
 * @see NoOpBackoff 테스트용 No-op 구현
 */
public interface BackoffStrategy {

    /**
     * 지정된 시도 횟수에 따른 대기 수행
     *
     * @param attempt 현재 시도 횟수 (0-based)
     */
    void backoff(int attempt);

    /**
     * Exponential Backoff 구현 (운영 환경용)
     *
     * <h4>Green Agent 분석</h4>
     * <ul>
     *   <li>attempt=0: 1ns</li>
     *   <li>attempt=1: 2ns</li>
     *   <li>attempt=2: 4ns</li>
     *   <li>...</li>
     *   <li>attempt=9: 512ns</li>
     * </ul>
     *
     * <p>CAS 경합 시 짧은 시간 대기 후 재시도하여
     * 스핀락 CPU 낭비와 긴 대기 시간 사이의 균형점 확보</p>
     */
    class ExponentialBackoff implements BackoffStrategy {
        @Override
        public void backoff(int attempt) {
            // 1ns, 2ns, 4ns, 8ns... 최대 512ns (attempt=9)
            LockSupport.parkNanos(1L << Math.min(attempt, 9));
        }
    }

    /**
     * No-op Backoff 구현 (테스트용)
     *
     * <h4>Yellow Agent 요구사항</h4>
     * <p>테스트에서 결정적(deterministic) 동작을 위해
     * 대기 없이 즉시 반환하여 Flaky 방지</p>
     */
    class NoOpBackoff implements BackoffStrategy {
        @Override
        public void backoff(int attempt) {
            // No-op: 테스트에서 결정적 동작을 위해 대기하지 않음
        }
    }
}
