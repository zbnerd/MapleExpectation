package maple.expectation.global.common.function;

/**
 * Throwable을 던질 수 있는 Supplier
 *
 * <p>락 획득, 외부 API 호출 등 checked exception이 발생할 수 있는
 * 작업에서 사용합니다.
 *
 * <h3>정책 (ADR)</h3>
 * <ul>
 *   <li>Biz 레이어에서는 checked exception이 올라오면 안 됨</li>
 *   <li>{@link #getUnchecked()}는 checked가 발생하면 정책 위반으로
 *       {@link IllegalStateException} throw</li>
 * </ul>
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {

    /**
     * 결과를 계산하거나 예외를 던집니다.
     *
     * @return 계산 결과
     * @throws Throwable 작업 중 발생한 예외
     */
    T get() throws Throwable;

    /**
     * Checked exception을 정책 위반으로 처리하는 unchecked 실행
     *
     * <p><b>예외 처리</b>:
     * <ul>
     *   <li>Error: 즉시 throw</li>
     *   <li>RuntimeException: 즉시 throw</li>
     *   <li>Checked Exception: 정책 위반이므로 {@link IllegalStateException}으로 래핑</li>
     * </ul>
     *
     * <p><b>NOTE</b>: try-catch는 이 인프라 메서드에만 존재하며,
     * 비즈니스 로직에서는 사용되지 않습니다.
     *
     * @return 계산 결과
     * @throws RuntimeException Error, RuntimeException, 또는 정책 위반 시 IllegalStateException
     */
    default T getUnchecked() {
        try {
            return get();
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            // Biz 경계에서 checked Throwable이 올라오는 것은 설계 위반
            throw new IllegalStateException(
                    "Unexpected checked Throwable (policy violation): " + t.getClass().getName(), t);
        }
    }
}
