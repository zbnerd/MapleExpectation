package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * 캐시 영속화(DB 저장) 실패 예외
 *
 * <p>비동기 캐시 저장 과정에서 발생한 예외를 래핑합니다. RuntimeException 대신 도메인 맥락이 담긴 예외를 사용하여 디버깅 가시성을 확보합니다.
 *
 * <h3>Circuit Breaker</h3>
 *
 * <p>캐시 영속화 실패는 시스템 장애로 분류 (CircuitBreakerRecordMarker)
 */
public class CachePersistenceException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  public CachePersistenceException(String ocid, Throwable cause) {
    super(CommonErrorCode.DATA_PROCESSING_ERROR, cause, "캐시 영속화 실패 (ocid: " + ocid + ")");
  }
}
