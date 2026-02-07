package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * Expectation 계산 일시 불가 예외 (Single-flight follower timeout)
 *
 * <p>Single-flight 패턴에서 follower가 leader의 결과를 기다리다 timeout되었을 때 발생합니다. HTTP 503 Service
 * Unavailable로 응답됩니다.
 *
 * <h4>발생 조건</h4>
 *
 * <ul>
 *   <li>Follower 대기 timeout (5초)
 *   <li>캐시 재조회 실패
 * </ul>
 *
 * <h4>CircuitBreakerIgnoreMarker 적용 이유</h4>
 *
 * <p>이 예외는 외부 서비스 장애가 아닌 일시적인 부하 상황을 나타내므로 서킷브레이커에 기록하지 않습니다.
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
public class ExpectationCalculationUnavailableException extends ServerBaseException
    implements CircuitBreakerIgnoreMarker {

  /**
   * 기대값 계산 일시 불가 예외 생성
   *
   * @param cacheKey 캐시 키 (마스킹된 값 권장)
   */
  public ExpectationCalculationUnavailableException(String cacheKey) {
    super(CommonErrorCode.SYSTEM_CAPACITY_EXCEEDED, "expectation:" + cacheKey);
  }

  /**
   * 원인 예외와 함께 생성
   *
   * @param cacheKey 캐시 키 (마스킹된 값 권장)
   * @param cause 원인 예외
   */
  public ExpectationCalculationUnavailableException(String cacheKey, Throwable cause) {
    super(CommonErrorCode.SYSTEM_CAPACITY_EXCEEDED, cause, "expectation:" + cacheKey);
  }
}
