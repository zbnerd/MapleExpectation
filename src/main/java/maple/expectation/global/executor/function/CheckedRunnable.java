package maple.expectation.global.executor.function;

/**
 * Exception을 던질 수 있는 Runnable (권장: checked 예외, IO 경계용)
 *
 * <p>{@link maple.expectation.global.executor.CheckedLogicExecutor}와 함께 사용하여
 * 원본 예외(E)를 호출자에게 전파합니다.</p>
 *
 * <h3>주의</h3>
 * <p>서비스/도메인 내부에는 {@link maple.expectation.global.executor.LogicExecutor}를 사용하고,
 * 외부 I/O 접점에서만 사용합니다.</p>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * CheckedRunnable<java.io.IOException> task = () -> {
 *     Files.writeString(Path.of("data.txt"), "content"); // throws IOException
 * };
 *
 * checkedExecutor.executeVoid(
 *     task,
 *     java.io.IOException.class,
 *     TaskContext.of("FileService", "WriteFile", "data.txt")
 * );
 * }</pre>
 *
 * @param <E> 던질 수 있는 예외 타입 (권장: {@link java.io.IOException}, {@link java.sql.SQLException} 등)
 * @since 2.4.0
 * @see maple.expectation.global.executor.CheckedLogicExecutor
 * @see java.lang.Runnable
 * @see CheckedSupplier
 */
@FunctionalInterface
public interface CheckedRunnable<E extends Exception> {

    /**
     * 작업을 실행하거나 예외를 던집니다.
     *
     * @throws E 작업 중 발생한 예외(E)
     */
    void run() throws E;
}
