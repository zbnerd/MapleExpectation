package maple.expectation.infrastructure.ratelimit.exception;

import lombok.Getter;
import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * Rate Limit 초과 예외 (429 Too Many Requests)
 *
 * <p>CLAUDE.md 섹션 11 준수: 비즈니스 예외 (4xx)
 *
 * <ul>
 *   <li>ClientBaseException 상속
 *   <li>CircuitBreakerIgnoreMarker 구현 (서킷브레이커 무시)
 * </ul>
 *
 * <h4>응답 예시</h4>
 *
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
public class RateLimitExceededException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  /**
   * 재시도까지 대기 시간 (초)
   *
   * <p>HTTP Retry-After 헤더 값으로 사용
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

  /**
   * Rate Limit 초과 예외 생성 (상세 포함)
   *
   * @param userId 사용자 ID
   * @param retryAfterSeconds 재시도까지 대기 시간 (초)
   * @param capacity 버킷 용량
   * @param refillRate 리필 속도
   */
  public RateLimitExceededException(
      String userId, int retryAfterSeconds, int capacity, int refillRate) {
    super(CommonErrorCode.RATE_LIMIT_EXCEEDED, retryAfterSeconds);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * Rate Limit 초과 예외 생성 (원인 포함)
   *
   * @param userId 사용자 ID
   * @param retryAfterSeconds 재시도까지 대기 시간 (초)
   * @param capacity 버킷 용량
   * @param refillRate 리필 속도
   * @param cause 원인 예외
   */
  public RateLimitExceededException(
      String userId, int retryAfterSeconds, int capacity, int refillRate, Throwable cause) {
    super(CommonErrorCode.RATE_LIMIT_EXCEEDED, retryAfterSeconds, cause);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
