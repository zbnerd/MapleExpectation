package maple.expectation.infrastructure.executor.function;

/**
 * 예외를 던질 수 있는 void 작업을 표현하는 함수형 인터페이스
 *
 * <p>표준 {@link Runnable}과 달리 Checked Exception을 던질 수 있습니다.
 *
 * <h3>사용 예시</h3>
 *
 * <pre>{@code
 * ThrowingRunnable task = () -> {
 *     Files.deleteIfExists(tempFile); // IOException을 던질 수 있음
 * };
 *
 * // LogicExecutor와 함께 사용
 * executor.executeVoid(this::cleanup, "cleanup");
 *
 * private void cleanup() throws IOException {
 *     Files.deleteIfExists(tempFile);
 * }
 * }</pre>
 *
 * @see java.lang.Runnable
 * @see maple.expectation.global.common.function.ThrowingSupplier
 * @since 1.0.0
 */
@FunctionalInterface
public interface ThrowingRunnable {

  /**
   * 작업을 실행합니다.
   *
   * @throws Throwable 작업 실행 중 발생한 예외
   */
  void run() throws Throwable;
}
