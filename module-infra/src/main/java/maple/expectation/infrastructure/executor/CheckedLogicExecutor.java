package maple.expectation.infrastructure.executor;

import java.util.function.Function;
import maple.expectation.infrastructure.executor.function.CheckedRunnable;
import maple.expectation.infrastructure.executor.function.CheckedSupplier;

/**
 * Checked 예외를 처리하는 IO 경계 전용 Executor
 *
 * <p>파일 I/O, 네트워크 통신, 외부 API 호출 등 checked 예외가 발생하는 IO 경계에서 **try-catch 없이** 예외를 처리하는 템플릿을 제공합니다.
 *
 * <h3>컴파일 타임 경계 강제</h3>
 *
 * <ul>
 *   <li><b>Biz 레이어</b>: {@link LogicExecutor} + {@code Supplier<T>} → checked-throwing 람다 불가
 *   <li><b>IO 레이어</b>: {@link CheckedLogicExecutor} + {@code CheckedSupplier<T>} → checked-throwing
 *       람다 허용
 * </ul>
 *
 * <h3>LogicExecutor와의 차이</h3>
 *
 * <table>
 *   <tr><th>항목</th><th>LogicExecutor</th><th>CheckedLogicExecutor</th></tr>
 *   <tr><td>사용처</td><td>서비스/도메인 내부</td><td>IO 경계 (파일, 네트워크, 락 등)</td></tr>
 *   <tr><td>입력 타입</td><td>Supplier (unchecked only)</td><td>CheckedSupplier (checked 허용)</td></tr>
 *   <tr><td>예외 처리</td><td>내부적으로 RuntimeException 번역</td><td>Level 1: mapper로 명시적 변환<br/>Level 2: throws 전파</td></tr>
 * </table>
 *
 * <h3>핵심 계약 (ADR)</h3>
 *
 * <ul>
 *   <li><b>Error 즉시 전파</b>: VirtualMachineError 등은 매핑/복구 없이 즉시 throw
 *   <li><b>RuntimeException 통과</b>: 이미 unchecked이므로 그대로 throw
 *   <li><b>Exception → mapper 변환</b>: checked 예외만 mapper로 RuntimeException 변환
 *   <li><b>mapper 계약 방어</b>: null 반환, 계약 위반 시 IllegalStateException
 * </ul>
 *
 * <h3>사용 패턴</h3>
 *
 * <pre>{@code
 * // Level 1: checked → runtime 변환 (try-catch 완전 제거)
 * String content = checkedExecutor.executeUnchecked(
 *     () -> Files.readString(Path.of("data.txt")),
 *     TaskContext.of("FileService", "ReadFile", "data.txt"),
 *     e -> new FileProcessingException("Failed to read file", e)
 * );
 *
 * // Level 1 + finally: 락/자원 해제 보장
 * return checkedExecutor.executeWithFinallyUnchecked(
 *     () -> doWorkUnderLock(),
 *     () -> lock.unlock(),
 *     TaskContext.of("LockService", "Execute", "resource"),
 *     e -> new LockExecutionException("Failed", e)
 * );
 *
 * // Level 2: throws 전파 (상위에서 처리)
 * String content = checkedExecutor.execute(
 *     () -> Files.readString(Path.of("data.txt")),
 *     TaskContext.of("FileService", "ReadFile", "data.txt")
 * ); // throws Exception
 * }</pre>
 *
 * @since 2.4.0
 * @see LogicExecutor
 * @see CheckedSupplier
 * @see CheckedRunnable
 */
public interface CheckedLogicExecutor {

  // ========================================
  // Level 2: throws 전파 (상위에서 처리)
  // ========================================

  /**
   * Checked 예외를 그대로 전파하는 작업을 실행합니다.
   *
   * <p>호출자가 throws Exception을 선언하고 상위에서 처리하는 경우 사용합니다.
   *
   * <h4>예외 처리</h4>
   *
   * <ul>
   *   <li><b>Error</b>: 즉시 throw
   *   <li><b>RuntimeException</b>: 그대로 통과 (단, cause/suppressed에 InterruptedException이 있으면 플래그 복원)
   *   <li><b>Exception</b>: 그대로 전파 (인터럽트 플래그 복원 포함)
   * </ul>
   *
   * @param <T> 반환 타입
   * @param task 실행할 작업
   * @param context Task 실행 컨텍스트
   * @return 작업 실행 결과
   * @throws Exception 작업 중 발생한 예외
   */
  <T> T execute(CheckedSupplier<T> task, TaskContext context) throws Exception;

  /**
   * 반환값이 없는 checked 예외 작업을 실행합니다.
   *
   * @param task 실행할 작업
   * @param context Task 실행 컨텍스트
   * @throws Exception 작업 중 발생한 예외
   */
  default void executeVoid(CheckedRunnable task, TaskContext context) throws Exception {
    execute(
        () -> {
          task.run();
          return null;
        },
        context);
  }

  // ========================================
  // Level 1: checked → runtime 변환 (try-catch 제거)
  // ========================================

  /**
   * Checked 예외를 RuntimeException으로 변환하여 실행합니다.
   *
   * <p><b>try-catch 없이</b> checked 예외를 도메인 RuntimeException으로 변환합니다. 예외 변환 로직이 템플릿 내부로 중앙화되어 호출 코드가
   * 깔끔해집니다.
   *
   * <h4>예외 처리 우선순위</h4>
   *
   * <ol>
   *   <li><b>Error</b>: 즉시 throw (mapper 미호출)
   *   <li><b>RuntimeException</b>: 그대로 throw (이미 unchecked)
   *   <li><b>Exception</b>: mapper.apply(e) 결과를 throw
   *   <li><b>Throwable (비-Exception)</b>: IllegalStateException
   * </ol>
   *
   * <h4>mapper 계약</h4>
   *
   * <ul>
   *   <li>null 반환 금지 → IllegalStateException
   *   <li>Error throw → 그대로 throw
   *   <li>RuntimeException throw → 그대로 throw
   *   <li>기타 Throwable throw → IllegalStateException
   * </ul>
   *
   * @param <T> 반환 타입
   * @param task 실행할 작업
   * @param context Task 실행 컨텍스트
   * @param mapper checked Exception → RuntimeException 변환 함수
   * @return 작업 실행 결과
   * @throws RuntimeException mapper가 변환한 예외 또는 task가 던진 RuntimeException
   */
  <T> T executeUnchecked(
      CheckedSupplier<T> task,
      TaskContext context,
      Function<Exception, ? extends RuntimeException> mapper);

  /**
   * 반환값이 없는 작업을 RuntimeException으로 변환하여 실행합니다.
   *
   * @param task 실행할 작업
   * @param context Task 실행 컨텍스트
   * @param mapper checked Exception → RuntimeException 변환 함수
   * @throws RuntimeException mapper가 변환한 예외
   */
  default void executeUncheckedVoid(
      CheckedRunnable task,
      TaskContext context,
      Function<Exception, ? extends RuntimeException> mapper) {
    executeUnchecked(
        () -> {
          task.run();
          return null;
        },
        context,
        mapper);
  }

  // ========================================
  // Level 1 + finally: 자원 해제 보장
  // ========================================

  /**
   * Checked 예외를 RuntimeException으로 변환하고, finally 블록 실행을 보장합니다.
   *
   * <p><b>분산 락, 파일 핸들, 커넥션 등</b> 반드시 해제해야 하는 자원이 있을 때 사용합니다. finalizer는 task 성공/실패와 무관하게 **정확히 1회**
   * 실행됩니다.
   *
   * <h4>예외 우선순위</h4>
   *
   * <ol>
   *   <li>finalizer에서 Error 발생 → <b>즉시 throw</b> (task 결과/예외 무관)
   *   <li>task Error → 즉시 throw
   *   <li>task RuntimeException → 그대로 throw (finalizer 예외는 suppressed)
   *   <li>task Exception → mapper 변환 후 throw (finalizer 예외는 suppressed)
   *   <li>task 성공 + finalizer 예외 → finalizer 예외가 primary
   * </ol>
   *
   * <h4>mapper 적용 범위</h4>
   *
   * <p><b>primary Exception이 task/finalizer 어디에서 발생했든, Level 1에서는 mapper로 RuntimeException으로
   * 변환됩니다.</b> 예: task 성공 + finalizer가 IOException 던짐 → mapper로 변환되어 RuntimeException throw
   *
   * <h4>suppressed 이관</h4>
   *
   * <p>task Exception을 mapper로 변환할 때, task Exception에 누적된 suppressed 예외들이 새로 생성된 RuntimeException으로
   * 복사됩니다. 이를 통해 cleanup 실패 정보가 유실되지 않습니다.
   *
   * @param <T> 반환 타입
   * @param task 실행할 작업
   * @param finalizer 반드시 실행할 정리 작업 (락 해제, 자원 반납 등)
   * @param context Task 실행 컨텍스트
   * @param mapper checked Exception → RuntimeException 변환 함수
   * @return 작업 실행 결과
   * @throws RuntimeException mapper가 변환한 예외 또는 finalizer/task의 RuntimeException
   */
  <T> T executeWithFinallyUnchecked(
      CheckedSupplier<T> task,
      CheckedRunnable finalizer,
      TaskContext context,
      Function<Exception, ? extends RuntimeException> mapper);

  /**
   * 반환값이 없는 작업을 finally 보장과 함께 실행합니다.
   *
   * @param task 실행할 작업
   * @param finalizer 반드시 실행할 정리 작업
   * @param context Task 실행 컨텍스트
   * @param mapper checked Exception → RuntimeException 변환 함수
   * @throws RuntimeException mapper가 변환한 예외
   */
  default void executeWithFinallyUncheckedVoid(
      CheckedRunnable task,
      CheckedRunnable finalizer,
      TaskContext context,
      Function<Exception, ? extends RuntimeException> mapper) {
    executeWithFinallyUnchecked(
        () -> {
          task.run();
          return null;
        },
        finalizer,
        context,
        mapper);
  }
}
