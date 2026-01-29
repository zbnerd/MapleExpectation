package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * 스타포스 테이블 미초기화 예외
 *
 * <h3>사용 사례</h3>
 * <p>서버 시작 시 StarforceLookupTable 초기화가 완료되지 않은 상태에서
 * 기대값 계산 요청이 들어온 경우</p>
 *
 * <h3>Circuit Breaker</h3>
 * <p>초기화 미완료는 시스템 장애로 분류 (CircuitBreakerRecordMarker)</p>
 */
public class StarforceNotInitializedException extends ServerBaseException implements CircuitBreakerRecordMarker {

    public StarforceNotInitializedException() {
        super(CommonErrorCode.STARFORCE_TABLE_NOT_INITIALIZED);
    }
}
