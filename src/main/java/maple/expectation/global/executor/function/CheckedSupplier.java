package maple.expectation.global.executor.function;

/**
 * Exception을 던질 수 있는 Supplier (권장: checked 예외, IO 경계용)
 *
 * <p>CheckedLogicExecutor와 함께 사용하여 원본 checked 예외를 호출자에게 전파합니다.</p>
 *
 * <h3>주의</h3>
 * <p>서비스/도메인 내부에는 {@link maple.expectation.global.executor.LogicExecutor}를 사용하고,
 * 외부 I/O 접점에서만 사용합니다.</p>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * CheckedSupplier<String, IOException> task = () -> {
 *     return Files.readString(Path.of("data.txt")); // throws IOException
 * };
 *
 * String result = checkedExecutor.execute(
 *     task,
 *     IOException.class,
 *     TaskContext.of("FileService", "ReadFile", "data.txt")
 * );
 * }</pre>
 *
 * @param <T> 반환 타입
 * @param <E> 던질 수 있는 예외 타입 (권장: {@link java.io.IOException}, {@link java.sql.SQLException} 등 checked 예외)
 * @since 2.4.0
 * @see maple.expectation.global.executor.CheckedLogicExecutor
 * @see java.util.function.Supplier
 */
@FunctionalInterface
public interface CheckedSupplier<T, E extends Exception> {

    /**
     * 결과를 계산하거나 예외를 던집니다.
     *
     * @return 계산 결과
     * @throws E 작업 중 발생한 예외(E)
     */
    T get() throws E;
}
