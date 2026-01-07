package maple.expectation.global.executor;

import maple.expectation.global.executor.function.CheckedRunnable;
import maple.expectation.global.executor.function.CheckedSupplier;

/**
 * Checked 예외를 그대로 전파하는 Executor (IO 경계 전용)
 *
 * <p>파일 I/O, 네트워크 통신, 외부 API 호출 등 checked 예외가 필수인 경계에서
 * 예외를 RuntimeException으로 번역하지 않고 **계약 타입 그대로** 호출자에게 전파합니다.</p>
 *
 * <h3>LogicExecutor와의 차이</h3>
 * <table>
 *   <tr>
 *     <th>항목</th>
 *     <th>LogicExecutor</th>
 *     <th>CheckedLogicExecutor</th>
 *   </tr>
 *   <tr>
 *     <td>사용처</td>
 *     <td>서비스/도메인 내부</td>
 *     <td>IO 경계 (파일, 네트워크 등)</td>
 *   </tr>
 *   <tr>
 *     <td>예외 처리</td>
 *     <td>RuntimeException으로 번역</td>
 *     <td>계약 타입 그대로 전파</td>
 *   </tr>
 *   <tr>
 *     <td>호출자 책임</td>
 *     <td>예외 처리 선택적</td>
 *     <td>예외 처리 필수 (컴파일 강제)</td>
 *   </tr>
 * </table>
 *
 * <h3>금융급 계약</h3>
 * <ul>
 *   <li><b>계약 타입 보존</b>: 선언된 예외 타입(E)만 전파</li>
 *   <li><b>계약 위반 탐지</b>: 다른 checked 예외 발생 시 IllegalStateException</li>
 *   <li><b>Error 즉시 전파</b>: VirtualMachineError 등은 번역 없이 rethrow</li>
 *   <li><b>인터럽트 플래그 복원</b>: InterruptedException 발생 시 Thread.currentThread().interrupt()</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * // IO 경계: 파일 읽기
 * String content = checkedExecutor.execute(
 *     () -> Files.readString(Path.of("data.txt")),
 *     IOException.class,
 *     TaskContext.of("FileService", "ReadFile", "data.txt")
 * ); // throws IOException
 *
 * // 계약 위반 예시 (IllegalStateException 발생)
 * checkedExecutor.execute(
 *     () -> { throw new SQLException("..."); }, // IOException이 아닌 SQLException 발생
 *     IOException.class,  // 계약: IOException만 허용
 *     context
 * ); // throws IllegalStateException("Contract violation: expected IOException, got SQLException")
 * }</pre>
 *
 * @since 2.4.0
 * @see LogicExecutor
 * @see CheckedSupplier
 * @see CheckedRunnable
 */
public interface CheckedLogicExecutor {

    /**
     * Checked 예외를 던질 수 있는 작업을 실행합니다.
     *
     * <p><b>계약 위반 시</b>: 선언된 예외 타입(expectedExceptionType)과 다른 checked 예외가 발생하면
     * {@link IllegalStateException}을 던집니다.</p>
     *
     * @param <T> 반환 타입
     * @param <E> 계약 예외 타입 (checked exception)
     * @param task 실행할 작업
     * @param expectedExceptionType 계약 예외 클래스 (예: IOException.class)
     * @param context Task 실행 컨텍스트
     * @return 작업 실행 결과
     * @throws E 계약 타입의 checked 예외
     * @throws IllegalStateException 계약 위반 (다른 checked 예외 발생)
     */
    <T, E extends Exception> T execute(
            CheckedSupplier<T, E> task,
            Class<E> expectedExceptionType,
            TaskContext context
    ) throws E;

    /**
     * 반환값이 없는 checked 예외 작업을 실행합니다.
     *
     * @param <E> 계약 예외 타입
     * @param task 실행할 작업
     * @param expectedExceptionType 계약 예외 클래스
     * @param context Task 실행 컨텍스트
     * @throws E 계약 타입의 checked 예외
     * @throws IllegalStateException 계약 위반
     */
    <E extends Exception> void executeVoid(
            CheckedRunnable<E> task,
            Class<E> expectedExceptionType,
            TaskContext context
    ) throws E;
}
