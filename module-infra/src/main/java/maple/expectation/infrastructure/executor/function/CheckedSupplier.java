package maple.expectation.infrastructure.executor.function;

/**
 * Exception을 던질 수 있는 Supplier (IO 경계 전용)
 *
 * <p>{@link maple.expectation.infrastructure.executor.CheckedLogicExecutor}와 함께 사용하여 checked 예외를
 * 던지는 람다를 타입 시스템으로 제한합니다.
 *
 * <h3>컴파일 타임 경계 강제</h3>
 *
 * <ul>
 *   <li><b>Biz 레이어</b>: {@code Supplier<T>} 사용 → checked-throwing 람다 불가
 *   <li><b>IO 레이어</b>: {@code CheckedSupplier<T>} 사용 → checked-throwing 람다 허용
 * </ul>
 *
 * <h3>사용 예시</h3>
 *
 * <pre>{@code
 * // executeUnchecked: checked → runtime 변환 (try-catch 없음)
 * String content = checkedExecutor.executeUnchecked(
 *     () -> Files.readString(Path.of("data.txt")),
 *     TaskContext.of("FileService", "ReadFile", "data.txt"),
 *     e -> new FileProcessingException("Failed to read file", e)
 * );
 * }</pre>
 *
 * @param <T> 반환 타입
 * @since 2.4.0
 * @see maple.expectation.infrastructure.executor.CheckedLogicExecutor
 * @see java.util.function.Supplier
 */
@FunctionalInterface
public interface CheckedSupplier<T> {

  /**
   * 결과를 계산하거나 예외를 던집니다.
   *
   * @return 계산 결과
   * @throws Exception 작업 중 발생한 예외
   */
  T get() throws Exception;
}
