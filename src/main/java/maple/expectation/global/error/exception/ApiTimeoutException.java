package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * 외부 API 호출 타임아웃 예외 (서킷브레이커 기록됨)
 *
 * <h4>5-Agent Council Round 2 결정 (Issue #169, #173)</h4>
 * <ul>
 *   <li><b>ApiTimeoutException</b>: API 레벨 timeout → 서킷브레이커 기록 (CircuitBreakerRecordMarker)</li>
 *   <li><b>ExpectationCalculationUnavailableException</b>: Follower timeout → 서킷브레이커 무시</li>
 * </ul>
 *
 * <h4>사용 시나리오</h4>
 * <ul>
 *   <li>Nexon API 호출 타임아웃 (EquipmentDataResolver)</li>
 *   <li>외부 서비스 응답 지연</li>
 * </ul>
 *
 * <h4>서킷브레이커 동작</h4>
 * <p>CircuitBreakerRecordMarker를 구현하여 Resilience4j 서킷브레이커가 이 예외를 기록합니다.
 * 반복적인 API 타임아웃 발생 시 서킷브레이커가 OPEN 상태로 전환되어
 * 불필요한 API 호출을 차단합니다.</p>
 *
 * @see CircuitBreakerRecordMarker
 * @see ExpectationCalculationUnavailableException
 * @since 2.6.0
 */
public class ApiTimeoutException extends ServerBaseException
        implements CircuitBreakerRecordMarker {

    /**
     * API 이름과 원인 예외를 포함하는 생성자
     *
     * @param apiName 타임아웃이 발생한 API 이름 (예: "NexonEquipmentAPI")
     * @param cause 원인 예외 (TimeoutException 등)
     */
    public ApiTimeoutException(String apiName, Throwable cause) {
        super(CommonErrorCode.API_TIMEOUT, cause, apiName);
    }

    /**
     * API 이름만 포함하는 생성자
     *
     * @param apiName 타임아웃이 발생한 API 이름
     */
    public ApiTimeoutException(String apiName) {
        super(CommonErrorCode.API_TIMEOUT, apiName);
    }
}
