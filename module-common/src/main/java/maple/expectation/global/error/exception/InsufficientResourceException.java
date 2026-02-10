package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * 리소스 부족 예외 (#240)
 *
 * <h3>사용 사례</h3>
 *
 * <ul>
 *   <li>Heap 메모리 부족으로 Lookup Table 초기화 불가
 *   <li>Thread Pool 리소스 고갈
 *   <li>디스크 공간 부족
 * </ul>
 *
 * <h3>Circuit Breaker</h3>
 *
 * <p>시스템 리소스 부족은 서킷브레이커에 기록되어야 함 (CircuitBreakerRecordMarker)
 *
 * @see maple.expectation.config.LookupTableInitializer 사용 예시
 */
public class InsufficientResourceException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  /**
   * 리소스 부족 예외 생성
   *
   * @param resourceDescription 부족한 리소스 설명 (예: "Heap memory", "Thread pool")
   */
  public InsufficientResourceException(String resourceDescription) {
    super(CommonErrorCode.INSUFFICIENT_RESOURCE, resourceDescription);
  }

  /**
   * 원인 예외를 포함한 리소스 부족 예외 생성
   *
   * @param resourceDescription 부족한 리소스 설명
   * @param cause 원인 예외
   */
  public InsufficientResourceException(String resourceDescription, Throwable cause) {
    super(CommonErrorCode.INSUFFICIENT_RESOURCE, cause, resourceDescription);
  }
}
