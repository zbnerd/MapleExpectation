package maple.expectation.infrastructure.executor.policy;

/**
 * Task 실행 결과 (파이프라인 훅에 전달되는 성공/실패 구분)
 *
 * <p>outcome은 "task 기준" 결과를 나타내며, 정책 훅에 전달되는 outcome은 task 결과로 고정됩니다. after 훅에서 예외가 발생해 최종적으로 메서드가
 * 실패하더라도 변하지 않습니다.
 *
 * <h3>결정 시점</h3>
 *
 * <ul>
 *   <li>outcome은 task가 성공/실패로 종료되었거나, BEFORE 단계에서 task 미시작이 확정된 시점에 결정되며, 모든 정책
 *       훅(onSuccess/onFailure/after)에 동일한 값으로 전달됩니다.
 *   <li>BEFORE 단계 실패로 task가 미시작된 경우에는 {@link #FAILURE}로 간주됩니다.
 * </ul>
 *
 * @since 2.4.0
 * @see ExecutionPolicy#onSuccess(Object, long,
 *     maple.expectation.infrastructure.executor.TaskContext)
 * @see ExecutionPolicy#onFailure(Throwable, long,
 *     maple.expectation.infrastructure.executor.TaskContext)
 * @see ExecutionPolicy#after(ExecutionOutcome, long,
 *     maple.expectation.infrastructure.executor.TaskContext)
 * @see ExecutionPipeline
 */
public enum ExecutionOutcome {

  /**
   * Task가 정상적으로 완료됨
   *
   * <p>파이프라인의 성공 경로에서 사용됩니다.
   */
  SUCCESS,

  /**
   * Task가 실패했거나 시작되지 않음
   *
   * <p>Task 실행 중 예외 발생 또는 BEFORE 단계에서 중단된 경우(task 미시작)를 포함합니다.
   *
   * <p>파이프라인의 실패 경로에서 사용됩니다.
   */
  FAILURE
}
