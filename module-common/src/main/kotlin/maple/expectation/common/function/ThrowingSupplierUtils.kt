package maple.expectation.common.function

/**
 * ThrowingSupplier 유틸리티 메서드
 */
object ThrowingSupplierUtils {

    /**
     * Checked exception을 정책 위반으로 처리하는 unchecked 실행
     *
     * <p><b>예외 처리</b>:
     *
     * <ul>
     *   <li>Error: 즉시 throw
     *   <li>RuntimeException: 즉시 throw
     *   <li>Checked Exception: 정책 위반이므로 `IllegalStateException`으로 래핑
     * </ul>
     *
     * <p><b>NOTE</b>: try-catch는 이 인프라 메서드에만 존재하며, 비즈니스 로직에서는 사용되지 않습니다.
     *
     * @param supplier 실행할 supplier
     * @return 계산 결과
     * @throws RuntimeException Error, RuntimeException, 또는 정책 위반 시 IllegalStateException
     */
    @JvmStatic
    fun <T> getUnchecked(supplier: ThrowingSupplier<T>): T {
        return try {
            supplier.get()
        } catch (e: Error) {
            throw e
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            // Biz 경계에서 checked Throwable이 올라오는 것은 설계 위반
            throw IllegalStateException(
                "Unexpected checked Throwable (policy violation): " + t.javaClass.name, t
            )
        }
    }
}
