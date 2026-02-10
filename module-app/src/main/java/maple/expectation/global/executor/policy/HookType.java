package maple.expectation.global.executor.policy;

/**
 * ExecutionPolicy 훅 타입 (타입 안전 훅 식별)
 *
 * <p>문자열 기반 분기를 제거하고 컴파일 타임 안전성을 보장합니다.
 *
 * <h3>분류</h3>
 *
 * <ul>
 *   <li><b>Lifecycle 훅</b>: {@link #BEFORE}, {@link #AFTER} - FailureMode 적용
 *   <li><b>Observability 훅</b>: {@link #ON_SUCCESS}, {@link #ON_FAILURE} - FailureMode 무시(항상 {@link
 *       FailureMode#SWALLOW})
 * </ul>
 *
 * @since 2.4.0
 * @see ExecutionPolicy
 * @see ExecutionPolicy#failureMode()
 * @see FailureMode
 * @see ExecutionPipeline
 */
public enum HookType {
  /**
   * 작업 시작 전 (Lifecycle)
   *
   * <p>FailureMode 적용: {@link FailureMode#PROPAGATE}면 즉시 fail-fast
   */
  BEFORE,

  /**
   * 작업 성공 시 (Observability)
   *
   * <p>항상 SWALLOW: 훅 실패는 실행 결과에 영향을 주지 않습니다.
   */
  ON_SUCCESS,

  /**
   * 작업 실패 시 (Observability)
   *
   * <p>항상 SWALLOW: 훅 실패는 실행 결과에 영향을 주지 않으며 관측용으로만 처리됩니다.
   */
  ON_FAILURE,

  /**
   * 작업 완료 후 - finally 블록 (Lifecycle)
   *
   * <p>FailureMode 적용: {@link FailureMode#PROPAGATE}면 예외를 파이프라인으로 전파(throw)합니다.
   */
  AFTER;

  /**
   * Lifecycle 훅 여부 (FailureMode 적용 대상)
   *
   * <p>Lifecycle과 Observability는 상호배타다.
   *
   * @return BEFORE 또는 AFTER이면 true
   */
  public boolean isLifecycleHook() {
    return this == BEFORE || this == AFTER;
  }

  /**
   * Observability 훅 여부 (항상 SWALLOW)
   *
   * <p>Lifecycle과 Observability는 상호배타다.
   *
   * @return ON_SUCCESS 또는 ON_FAILURE이면 true
   */
  public boolean isObservabilityHook() {
    return this == ON_SUCCESS || this == ON_FAILURE;
  }
}
