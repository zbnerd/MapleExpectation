package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 지원하지 않는 계산 엔진 요청 예외
 *
 * <p>DP 계산 엔진이 비활성화 상태에서 DP 모드 계산을 요청한 경우 발생합니다.
 *
 * <h3>Circuit Breaker</h3>
 *
 * <p>사용자 요청 오류이므로 서킷브레이커에 영향 없음 (CircuitBreakerIgnoreMarker)
 */
public class UnsupportedCalculationEngineException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public UnsupportedCalculationEngineException() {
    super(
        CommonErrorCode.INVALID_INPUT_VALUE,
        "DP 계산 엔진이 비활성화 상태입니다. 현재 누적 확률 계산(X% 이상)을 지원하지 않습니다.");
  }
}
