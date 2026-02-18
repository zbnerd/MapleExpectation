package maple.expectation.infrastructure.executor.function

/**
 * Exception을 던질 수 있는 Supplier (IO 경계 전용)
 *
 * {@link maple.expectation.infrastructure.executor.CheckedLogicExecutor}와 함께 사용하여
 * checked 예외를 던지는 람다를 타입 시스템으로 제한합니다.
 *
 * ## 컴파일 타임 경계 강제
 * - **Biz 레이어**: {@code Supplier<T>} 사용 → checked-throwing 람다 불가
 * - **IO 레이어**: {@code CheckedSupplier<T>} 사용 → checked-throwing 람다 허용
 *
 * ## 사용 예시
 * ```kotlin
 * // executeUnchecked: checked → runtime 변환 (try-catch 없음)
 * val content = checkedExecutor.executeUnchecked(
 *     task = { Files.readString(Path.of("data.txt")) },
 *     context = TaskContext.of("FileService", "ReadFile", "data.txt"),
 *     mapper = { e -> FileProcessingException("Failed to read file", e) }
 * )
 * ```
 */
fun interface CheckedSupplier<T> {
    /**
     * 결과를 계산하거나 예외를 던집니다.
     *
     * @return 계산 결과
     * @throws Exception 작업 중 발생한 예외
     */
    fun get(): T
}
