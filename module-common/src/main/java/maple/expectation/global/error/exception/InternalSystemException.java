package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;

/**
 * LogicExecutor 전용 시스템 예외
 *
 * <p>LogicExecutor에서 처리하지 못한 관리되지 않은 예외를 프로젝트 규격에 맞게 래핑합니다.
 *
 * <h3>Before (RuntimeException 직접 사용)</h3>
 *
 * <pre>{@code
 * private RuntimeException wrapAsRuntimeException(Throwable e) {
 *     if (e instanceof RuntimeException) {
 *         return (RuntimeException) e;
 *     }
 *     return new RuntimeException("작업 실행 중 예외 발생: " + e.getMessage(), e);
 * }
 * }</pre>
 *
 * <h3>After (InternalSystemException 사용)</h3>
 *
 * <pre>{@code
 * private RuntimeException wrapAsRuntimeException(String taskName, Throwable e) {
 *     if (e instanceof BaseException) {
 *         return (BaseException) e; // 비즈니스 예외는 그대로 전파
 *     }
 *     return new InternalSystemException(taskName, e); // 시스템 예외로 규격화
 * }
 * }</pre>
 *
 * <h3>개선 효과</h3>
 *
 * <ul>
 *   <li>전역 핸들러에서 "어디서 터진 에러인지" 명확히 구분 가능
 *   <li>taskName으로 에러 발생 지점 추적 용이
 *   <li>CommonErrorCode.INTERNAL_SERVER_ERROR로 통일된 에러 코드 관리
 *   <li>Micrometer 메트릭에 정확한 예외 이름 기록
 * </ul>
 *
 * @since 1.0.0
 */
public class InternalSystemException extends ServerBaseException {

  /**
   * LogicExecutor 작업 실행 중 발생한 예외를 래핑
   *
   * @param taskName 작업 이름 (예: "executeWithLock:ocid:12345")
   * @param cause 원본 예외
   */
  public InternalSystemException(String taskName, Throwable cause) {
    super(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, taskName);
  }

  /**
   * 단순 메시지만 있는 시스템 예외 생성
   *
   * @param taskName 작업 이름
   */
  public InternalSystemException(String taskName) {
    super(CommonErrorCode.INTERNAL_SERVER_ERROR, taskName);
  }
}
