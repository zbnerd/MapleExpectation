package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 포인트 부족 예외
 *
 * <p>CommonErrorCode.INSUFFICIENT_POINTS 메시지 형식: "포인트가 부족합니다 (보유: %s, 필요: %s)"
 */
public class InsufficientPointException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  /** 단일 메시지 생성자 (레거시 호환) */
  public InsufficientPointException(String msg) {
    super(CommonErrorCode.INSUFFICIENT_POINTS, msg, "N/A");
  }

  /**
   * 보유/필요 포인트 명시 생성자 (Issue #120)
   *
   * @param currentPoint 현재 보유 포인트
   * @param requiredPoint 필요 포인트
   */
  public InsufficientPointException(Long currentPoint, Long requiredPoint) {
    super(CommonErrorCode.INSUFFICIENT_POINTS, currentPoint, requiredPoint);
  }
}
