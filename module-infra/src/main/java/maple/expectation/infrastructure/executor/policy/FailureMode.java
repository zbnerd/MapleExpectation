package maple.expectation.infrastructure.executor.policy;

/**
 * 정책 훅 실패 시 처리 방식 (Lifecycle 훅 전용)
 *
 * <p><b>적용 범위</b>: {@link HookType#BEFORE}, {@link HookType#AFTER}
 *
 * <p><b>중요</b>: 관측 훅({@link HookType#ON_SUCCESS}/{@link HookType#ON_FAILURE})은 항상 {@link
 * #SWALLOW}이며 FailureMode의 영향을 받지 않는다.
 *
 * <h3>적용 규칙</h3>
 *
 * <ul>
 *   <li><b>BEFORE/AFTER</b>: {@link ExecutionPolicy#failureMode()}를 존중한다.
 *   <li><b>ON_SUCCESS/ON_FAILURE</b>: 항상 {@link #SWALLOW} (FailureMode 무시)
 * </ul>
 *
 * <h3>예외 처리 규약</h3>
 *
 * <ul>
 *   <li>BEFORE에서 {@link #PROPAGATE}면 즉시 fail-fast로 전파된다.
 *   <li>AFTER에서 {@link #PROPAGATE}면 예외를 파이프라인으로 전파하며, 최종 예외 결합 규약은 {@link ExecutionPipeline}에 따른다.
 *   <li>{@link Error}는 FailureMode와 무관하게 최우선으로 전파된다.
 * </ul>
 *
 * @since 2.4.0
 * @see HookType
 * @see ExecutionPolicy#failureMode()
 * @see ExecutionPipeline
 */
public enum FailureMode {

  /**
   * 훅 실패를 전파하지 않고 격리한 채 계속 진행한다.
   *
   * <p>단, {@link Error}는 모드와 무관하게 전파된다.
   */
  SWALLOW,

  /**
   * 훅 실패를 파이프라인으로 전파한다(정합성/가드 성격).
   *
   * <p>BEFORE에서는 즉시 fail-fast로 전파된다.
   *
   * <p>AFTER에서는 전파되며, 최종 예외 결합 규약은 {@link ExecutionPipeline}에 따른다.
   *
   * <p>{@link Error}는 모드와 무관하게 최우선으로 전파된다.
   */
  PROPAGATE
}
