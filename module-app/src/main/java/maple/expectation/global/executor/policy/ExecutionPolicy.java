package maple.expectation.global.executor.policy;

import maple.expectation.global.executor.TaskContext;

/**
 * 실행 전후에 공통 로직을 주입하는 정책 인터페이스 (Stateless)
 *
 * <p>비즈니스 로직 실행 전/후에 로깅, 자원 정리, 메트릭 수집 등의 횡단 관심사를 처리합니다.
 *
 * <h3>설계 원칙</h3>
 *
 * <ul>
 *   <li><b>Stateless (필수)</b>: 정책은 인스턴스 필드에 상태를 저장하지 않습니다. Per-invocation 상태가 필요하면 {@link
 *       TaskContext}에 저장합니다.
 *   <li><b>Composable</b>: 여러 정책을 {@link ExecutionPipeline}에서 순서대로 조합할 수 있습니다.
 *   <li><b>Fail-Safe</b>: 관측 훅(onSuccess/onFailure)의 실패는 실행 결과에 영향을 주지 않습니다.
 * </ul>
 *
 * <h3>훅 실행 순서</h3>
 *
 * <p>순서 규약은 {@link ExecutionPipeline}에 따르며, 일반적으로:
 *
 * <pre>
 * 1. before()
 * 2. [task 실행]
 * 3. onSuccess() 또는 onFailure()
 * 4. after()
 * </pre>
 *
 * <h3>entered pairing 규칙</h3>
 *
 * <p>{@code before()}가 성공한 정책만 {@code after()}가 호출됩니다. 따라서 정책 내부에서 자원 획득/해제 쌍을 보장할 수 있습니다.
 *
 * @since 2.4.0
 * @see ExecutionPipeline
 * @see HookType
 * @see FailureMode
 */
public interface ExecutionPolicy {

  /**
   * 훅 실패 시 처리 방식 (Lifecycle 훅 전용)
   *
   * <p>기본값은 {@link FailureMode#SWALLOW}이며, 관측 훅(onSuccess/onFailure)은 FailureMode와 무관하게 항상 {@link
   * FailureMode#SWALLOW}입니다.
   *
   * @return 실패 처리 모드
   * @see FailureMode#SWALLOW
   * @see FailureMode#PROPAGATE
   */
  default FailureMode failureMode() {
    return FailureMode.SWALLOW;
  }

  /**
   * Task 시작 전 실행 (Lifecycle 훅)
   *
   * <p>FailureMode에 따라 예외 처리 방식이 달라집니다:
   *
   * <ul>
   *   <li>{@link FailureMode#PROPAGATE}: 예외 발생 시 즉시 fail-fast로 전파됩니다.
   *   <li>{@link FailureMode#SWALLOW}: 예외를 전파하지 않고 격리합니다. 단, {@code before()}가 실패한 경우 해당 정책은
   *       entered로 간주되지 않아 {@code after()}가 호출되지 않습니다.
   * </ul>
   *
   * <p><b>entered pairing</b>: before()가 성공한 정책만 after()가 호출됩니다. 따라서 before() 실패 시 정책 내부에서 자체 정리를
   * 완료해야 합니다.
   *
   * @param context Task 실행 컨텍스트
   * @throws Exception before 로직 실패
   * @see HookType#BEFORE
   */
  default void before(TaskContext context) throws Exception {}

  /**
   * Task 성공 시 실행 (Observability 훅)
   *
   * <p>항상 best-effort로 실행되며, 이 훅의 실패는 실행 결과에 영향을 주지 않습니다.
   *
   * @param result Task 실행 결과
   * @param elapsedNanos Task 실행 시간 (나노초, task-only timing)
   * @param context Task 실행 컨텍스트
   * @param <T> 결과 타입
   * @throws Exception onSuccess 로직 실패 (전파되지 않고 격리됨)
   * @see HookType#ON_SUCCESS
   */
  default <T> void onSuccess(T result, long elapsedNanos, TaskContext context) throws Exception {}

  /**
   * Task 실패 시 실행 (Observability 훅)
   *
   * <p>항상 best-effort로 실행되며, 이 훅의 실패는 실행 결과에 영향을 주지 않습니다.
   *
   * @param error Task 실행 중 발생한 예외
   * @param elapsedNanos Task 실행 시간 (나노초, task-only timing)
   * @param context Task 실행 컨텍스트
   * @throws Exception onFailure 로직 실패 (전파되지 않고 격리됨)
   * @see HookType#ON_FAILURE
   */
  default void onFailure(Throwable error, long elapsedNanos, TaskContext context)
      throws Exception {}

  /**
   * Task 완료 후 실행 - finally 블록 (Lifecycle 훅)
   *
   * <p><b>실행 조건</b>: {@code before()}가 성공한 정책에 한해 호출되며, 그 경우 task 성공/실패와 무관하게 호출됩니다.
   *
   * <p>FailureMode에 따라 예외 처리 방식이 달라집니다:
   *
   * <ul>
   *   <li>{@link FailureMode#PROPAGATE}: 예외를 파이프라인으로 전파합니다.
   *   <li>{@link FailureMode#SWALLOW}: 예외를 전파하지 않고 격리합니다.
   * </ul>
   *
   * <p>순서 규약은 {@link ExecutionPipeline}에 따릅니다.
   *
   * @param outcome Task 실행 결과 (task 기준, {@link ExecutionOutcome})
   * @param elapsedNanos Task 실행 시간 (나노초, task-only timing)
   * @param context Task 실행 컨텍스트
   * @throws Exception after 로직 실패
   * @see HookType#AFTER
   * @see ExecutionOutcome
   */
  default void after(ExecutionOutcome outcome, long elapsedNanos, TaskContext context)
      throws Exception {}
}
