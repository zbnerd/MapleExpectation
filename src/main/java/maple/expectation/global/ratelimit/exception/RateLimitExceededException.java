package maple.expectation.global.ratelimit.exception;

import lombok.Getter;
import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * Rate Limit 초과 예외 (429 Too Many Requests)
 *
 * <p>CLAUDE.md 섹션 11 준수: 비즈니스 예외 (4xx)
 * <ul>
 *   <li>ClientBaseException 상속</li>
 *   <li>CircuitBreakerIgnoreMarker 구현 (서킷브레이커 무시)</li>
 * </ul>
 * </p>
 *
 * <h4>응답 예시</h4>
 * <pre>
 * HTTP/1.1 429 Too Many Requests
 * Retry-After: 30
 *
 * {
 *   "code": "R001",
 *   "message": "요청 한도를 초과했습니다. 30초 후 다시 시도해주세요."
 * }
 * </pre>
 *
 * @since Issue #152
 */
@Getter
public class RateLimitExceededException extends ClientBaseException implements CircuitBreakerIgnoreMarker {

    /**
     * 재시도까지 대기 시간 (초)
     *
     * <p>HTTP Retry-After 헤더 값으로 사용</p>
     */
    private final long retryAfterSeconds;

    /**
     * Rate Limit 초과 예외 생성
     *
     * @param retryAfterSeconds 재시도까지 대기 시간 (초)
     */
    public RateLimitExceededException(long retryAfterSeconds) {
        super(CommonErrorCode.RATE_LIMIT_EXCEEDED, retryAfterSeconds);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
