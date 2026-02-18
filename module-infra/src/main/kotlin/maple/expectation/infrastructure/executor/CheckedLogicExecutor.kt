package maple.expectation.infrastructure.executor

import maple.expectation.infrastructure.executor.function.CheckedRunnable
import maple.expectation.infrastructure.executor.function.CheckedSupplier

/**
 * Checked 예외를 처리하는 IO 경계 전용 Executor
 *
 * 파일 I/O, 네트워크 통신, 외부 API 호출 등 checked 예외가 발생하는 IO 경계에서
 * **try-catch 없이** 예외를 처리하는 템플릿을 제공합니다.
 *
 * ## 컴파일 타임 경계 강제
 * - **Biz 레이어**: LogicExecutor + `Supplier<T>` → checked-throwing 람다 불가
 * - **IO 레이어**: CheckedLogicExecutor + `CheckedSupplier<T>` → checked-throwing 람다 허용
 *
 * ## LogicExecutor와의 차이
 * | 항목 | LogicExecutor | CheckedLogicExecutor |
 * |------|---------------|----------------------|
 * | 사용처 | 서비스/도메인 내부 | IO 경계 (파일, 네트워크, 락 등) |
 * | 입력 타입 | Supplier (unchecked only) | CheckedSupplier (checked 허용) |
 * | 예외 처리 | 내부적으로 RuntimeException 번역 | Level 1: mapper로 명시적 변환<br/>Level 2: throws 전파 |
 *
 * ## 핵심 계약 (ADR)
 * - **Error 즉시 전파**: VirtualMachineError 등은 매핑/복구 없이 즉시 throw
 * - **RuntimeException 통과**: 이미 unchecked이므로 그대로 throw
 * - **Exception → mapper 변환**: checked 예외만 mapper로 RuntimeException 변환
 * - **mapper 계약 방어**: null 반환, 계약 위반 시 IllegalStateException
 *
 * ## 사용 패턴
 * ```kotlin
 * // Level 1: checked → runtime 변환 (try-catch 완전 제거)
 * val content = checkedExecutor.executeUnchecked(
 *     task = { Files.readString(Path.of("data.txt")) },
 *     context = TaskContext.of("FileService", "ReadFile", "data.txt"),
 *     mapper = { e -> FileProcessingException("Failed to read file", e) }
 * )
 *
 * // Level 1 + finally: 락/자원 해제 보장
 * return checkedExecutor.executeWithFinallyUnchecked(
 *     task = { doWorkUnderLock() },
 *     finalizer = { lock.unlock() },
 *     context = TaskContext.of("LockService", "Execute", "resource"),
 *     mapper = { e -> LockExecutionException("Failed", e) }
 * )
 *
 * // Level 2: throws 전파 (상위에서 처리)
 * val content = checkedExecutor.execute(
 *     task = { Files.readString(Path.of("data.txt")) },
 *     context = TaskContext.of("FileService", "ReadFile", "data.txt")
 * ) // throws Exception
 * ```
 */
interface CheckedLogicExecutor {

    // ========================================
    // Level 2: throws 전파 (상위에서 처리)
    // ========================================

    /**
     * Checked 예외를 그대로 전파하는 작업을 실행합니다.
     *
     * 호출자가 throws Exception을 선언하고 상위에서 처리하는 경우 사용합니다.
     *
     * ## 예외 처리
     * - **Error**: 즉시 throw
     * - **RuntimeException**: 그대로 통과 (단, cause/suppressed에 InterruptedException이 있으면 플래그 복원)
     * - **Exception**: 그대로 전파 (인터럽트 플래그 복원 포함)
     */
    @Throws(Exception::class)
    fun <T> execute(task: CheckedSupplier<T>, context: TaskContext): T

    /**
     * 반환값이 없는 checked 예외 작업을 실행합니다.
     */
    @Throws(Exception::class)
    fun executeVoid(task: CheckedRunnable, context: TaskContext) {
        execute(
            task = {
                task.run()
                null
            },
            context = context
        )
    }

    // ========================================
    // Level 1: checked → runtime 변환 (try-catch 제거)
    // ========================================

    /**
     * Checked 예외를 RuntimeException으로 변환하여 실행합니다.
     *
     * **try-catch 없이** checked 예외를 도메인 RuntimeException으로 변환합니다.
     * 예외 변환 로직이 템플릿 내부로 중앙화되어 호출 코드가 깔끔해집니다.
     *
     * ## 예외 처리 우선순위
     * 1. **Error**: 즉시 throw (mapper 미호출)
     * 2. **RuntimeException**: 그대로 throw (이미 unchecked)
     * 3. **Exception**: mapper.apply(e) 결과를 throw
     * 4. **Throwable (비-Exception)**: IllegalStateException
     *
     * ## mapper 계약
     * - null 반환 금지 → IllegalStateException
     * - Error throw → 그대로 throw
     * - RuntimeException throw → 그대로 throw
     * - 기타 Throwable throw → IllegalStateException
     */
    fun <T> executeUnchecked(
        task: CheckedSupplier<T>,
        context: TaskContext,
        mapper: java.util.function.Function<Exception, RuntimeException>
    ): T

    /**
     * 반환값이 없는 작업을 RuntimeException으로 변환하여 실행합니다.
     */
    fun executeUncheckedVoid(
        task: CheckedRunnable,
        context: TaskContext,
        mapper: java.util.function.Function<Exception, RuntimeException>
    ) {
        executeUnchecked(
            task = {
                task.run()
                null
            },
            context = context,
            mapper = mapper
        )
    }

    // ========================================
    // Level 1 + finally: 자원 해제 보장
    // ========================================

    /**
     * Checked 예외를 RuntimeException으로 변환하고, finally 블록 실행을 보장합니다.
     *
     * **분산 락, 파일 핸들, 커넥션 등** 반드시 해제해야 하는 자원이 있을 때 사용합니다.
     * finalizer는 task 성공/실패와 무관하게 **정확히 1회** 실행됩니다.
     *
     * ## 예외 우선순위
     * 1. finalizer에서 Error 발생 → **즉시 throw** (task 결과/예외 무관)
     * 2. task Error → 즉시 throw
     * 3. task RuntimeException → 그대로 throw (finalizer 예외는 suppressed)
     * 4. task Exception → mapper 변환 후 throw (finalizer 예외는 suppressed)
     * 5. task 성공 + finalizer 예외 → finalizer 예외가 primary
     *
     * ## mapper 적용 범위
     * **primary Exception이 task/finalizer 어디에서 발생했든, Level 1에서는 mapper로
     * RuntimeException으로 변환됩니다.**
     *
     * 예: task 성공 + finalizer가 IOException 던짐 → mapper로 변환되어 RuntimeException throw
     *
     * ## suppressed 이관
     * task Exception을 mapper로 변환할 때, task Exception에 누적된 suppressed 예외들이
     * 새로 생성된 RuntimeException으로 복사됩니다. 이를 통해 cleanup 실패 정보가 유실되지 않습니다.
     */
    fun <T> executeWithFinallyUnchecked(
        task: CheckedSupplier<T>,
        finalizer: CheckedRunnable,
        context: TaskContext,
        mapper: java.util.function.Function<Exception, RuntimeException>
    ): T

    /**
     * 반환값이 없는 작업을 finally 보장과 함께 실행합니다.
     */
    fun executeWithFinallyUncheckedVoid(
        task: CheckedRunnable,
        finalizer: CheckedRunnable,
        context: TaskContext,
        mapper: java.util.function.Function<Exception, RuntimeException>
    ) {
        executeWithFinallyUnchecked(
            task = {
                task.run()
                null
            },
            finalizer = finalizer,
            context = context,
            mapper = mapper
        )
    }
}
