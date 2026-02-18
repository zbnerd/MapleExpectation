package maple.expectation.infrastructure.executor.function

/**
 * 예외를 던질 수 있는 void 작업을 표현하는 함수형 인터페이스
 *
 * 표준 {@link Runnable}과 달리 Checked Exception을 던질 수 있습니다.
 *
 * ## 사용 예시
 * ```kotlin
 * val task = ThrowingRunnable {
 *     Files.deleteIfExists(tempFile) // IOException을 던질 수 있음
 * }
 *
 * // LogicExecutor와 함께 사용
 * executor.executeVoid({ cleanup() }, context)
 *
 * private fun cleanup() {
 *     Files.deleteIfExists(tempFile)
 * }
 * ```
 */
fun interface ThrowingRunnable {
    /**
     * 작업을 실행합니다.
     *
     * @throws Throwable 작업 실행 중 발생한 예외
     */
    fun run()
}
