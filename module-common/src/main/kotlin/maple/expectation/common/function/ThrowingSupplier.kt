package maple.expectation.common.function

/**
 * Throwable을 던질 수 있는 Supplier
 *
 * <p>락 획득, 외부 API 호출 등 checked exception이 발생할 수 있는 작업에서 사용합니다.
 *
 * <h3>정책 (ADR)</h3>
 *
 * <ul>
 *   <li>Biz 레이어에서는 checked exception이 올라오면 안 됨
 *   <li>`getUnchecked()`는 checked가 발생하면 정책 위반으로 `IllegalStateException` throw
 * </ul>
 */
@FunctionalInterface
fun interface ThrowingSupplier<T> {

    /**
     * 결과를 계산하거나 예외를 던집니다.
     *
     * @return 계산 결과
     * @throws Throwable 작업 중 발생한 예외
     */
    @Throws(Throwable::class)
    fun get(): T
}
