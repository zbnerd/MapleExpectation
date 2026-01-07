package maple.expectation.global.executor.function;

/**
 * Exception을 던질 수 있는 Runnable (IO 경계 전용)
 *
 * <p>{@link maple.expectation.global.executor.CheckedLogicExecutor}와 함께 사용하여
 * checked 예외를 던지는 람다를 타입 시스템으로 제한합니다.</p>
 *
 * <h3>컴파일 타임 경계 강제</h3>
 * <ul>
 *   <li><b>Biz 레이어</b>: {@code Runnable} 사용 → checked-throwing 람다 불가</li>
 *   <li><b>IO 레이어</b>: {@code CheckedRunnable} 사용 → checked-throwing 람다 허용</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * // executeUncheckedVoid: checked → runtime 변환 (try-catch 없음)
 * checkedExecutor.executeUncheckedVoid(
 *     () -> Files.writeString(Path.of("data.txt"), "content"),
 *     TaskContext.of("FileService", "WriteFile", "data.txt"),
 *     e -> new FileProcessingException("Failed to write file", e)
 * );
 * }</pre>
 *
 * @since 2.4.0
 * @see maple.expectation.global.executor.CheckedLogicExecutor
 * @see java.lang.Runnable
 * @see CheckedSupplier
 */
@FunctionalInterface
public interface CheckedRunnable {

    /**
     * 작업을 실행하거나 예외를 던집니다.
     *
     * @throws Exception 작업 중 발생한 예외
     */
    void run() throws Exception;
}
