package maple.expectation.global.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.function.CheckedRunnable;
import maple.expectation.global.executor.function.CheckedSupplier;
import maple.expectation.global.executor.policy.ExecutionPipeline;

import java.util.Objects;
import java.util.function.Function;

/**
 * CheckedLogicExecutor의 기본 구현체
 *
 * <p>ExecutionPipeline 기반으로 checked 예외를 처리하며,
 * try-catch 없이 예외 변환을 템플릿 내부로 중앙화합니다.</p>
 *
 * <h3>핵심 계약 (ADR)</h3>
 * <ul>
 *   <li><b>Error 즉시 전파</b>: VirtualMachineError 등은 매핑/복구 없이 즉시 throw</li>
 *   <li><b>RuntimeException 통과</b>: 이미 unchecked이므로 그대로 throw</li>
 *   <li><b>Exception → mapper 변환</b>: checked 예외만 mapper로 RuntimeException 변환</li>
 *   <li><b>mapper 계약 방어</b>: null 반환, 계약 위반 시 IllegalStateException</li>
 *   <li><b>suppressed 이관</b>: Exception→RuntimeException 변환 시 suppressed 복사</li>
 *   <li><b>인터럽트 플래그 복원</b>: InterruptedException 발생 시 Thread.currentThread().interrupt()</li>
 * </ul>
 *
 * @since 2.4.0
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultCheckedLogicExecutor implements CheckedLogicExecutor {

    private final ExecutionPipeline pipeline;

    // ========================================
    // Level 2: throws 전파 (상위에서 처리)
    // ========================================

    @Override
    public <T> T execute(CheckedSupplier<T> task, TaskContext context) throws Exception {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(context, "context must not be null");

        try {
            // 명시적 람다로 오버로드 추론 문제 방지
            return pipeline.executeRaw(() -> task.get(), context);
        } catch (Error e) {
            throw e;
        } catch (RuntimeException re) {
            // RuntimeException도 cause chain에 InterruptedException이 있으면 복원
            restoreInterruptIfNeeded(re);
            throw re;
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Unexpected Throwable (not Error/Exception): " + t.getClass().getName(), t
            );
        }
    }

    // ========================================
    // Level 1: checked → runtime 변환 (try-catch 제거)
    // ========================================

    @Override
    public <T> T executeUnchecked(
            CheckedSupplier<T> task,
            TaskContext context,
            Function<Exception, ? extends RuntimeException> mapper
    ) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        try {
            // 명시적 람다로 오버로드 추론 문제 방지
            return pipeline.executeRaw(() -> task.get(), context);
        } catch (Throwable t) {
            // Error → 즉시 throw (mapper 미호출)
            if (t instanceof Error e) throw e;

            // RuntimeException → 그대로 throw (이미 unchecked)
            // cause chain에 InterruptedException이 있으면 복원
            if (t instanceof RuntimeException re) {
                restoreInterruptIfNeeded(re);
                throw re;
            }

            // Exception이 아닌 Throwable → 계약 위반 (예: custom Throwable subclass)
            if (!(t instanceof Exception ex)) {
                throw new IllegalStateException(
                        "Task threw non-Exception Throwable: " + t.getClass().getName(), t
                );
            }

            // Exception → mapper로 변환
            restoreInterruptIfNeeded(ex);
            throw applyMapper(mapper, ex);
        }
    }

    // ========================================
    // Level 1 + finally: 자원 해제 보장
    // ========================================

    /**
     * {@inheritDoc}
     *
     * <h4>ADR: finalizer 중복 등록 금지</h4>
     * <p>이 메서드는 자체 try-finally로 finalizer를 1회 실행합니다.
     * 동일한 finalizer를 {@link maple.expectation.global.executor.policy.FinallyPolicy}에
     * 중복 등록하면 2회 실행되어 예기치 않은 동작이 발생할 수 있습니다.</p>
     */
    @Override
    public <T> T executeWithFinallyUnchecked(
            CheckedSupplier<T> task,
            CheckedRunnable finalizer,
            TaskContext context,
            Function<Exception, ? extends RuntimeException> mapper
    ) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(finalizer, "finalizer must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        T result = null;
        Throwable primary = null;

        try {
            // 명시적 람다로 오버로드 추론 문제 방지
            result = pipeline.executeRaw(() -> task.get(), context);
        } catch (Throwable t) {
            primary = t;
        } finally {
            try {
                finalizer.run();
            } catch (Throwable ft) {
                // ★ Must-fix: finalizer Error는 즉시 throw (suppressed 누적 금지)
                if (ft instanceof Error e) throw e;

                if (primary == null) {
                    primary = ft;
                } else {
                    safeAddSuppressed(primary, ft);
                }
            }
        }

        // 정상 완료
        if (primary == null) {
            return result;
        }

        // Error → 즉시 throw
        if (primary instanceof Error e) throw e;

        // RuntimeException → 그대로 throw
        // cause chain에 InterruptedException이 있으면 복원
        if (primary instanceof RuntimeException re) {
            restoreInterruptIfNeeded(re);
            throw re;
        }

        // Exception → mapper 변환 + suppressed 이관
        if (primary instanceof Exception ex) {
            restoreInterruptIfNeeded(ex);
            RuntimeException mapped = applyMapper(mapper, ex);
            copySuppressed(ex, mapped);
            throw mapped;
        }

        // Throwable (비-Exception) → 계약 위반
        throw new IllegalStateException(
                "Task threw non-Exception Throwable: " + primary.getClass().getName(), primary
        );
    }

    // ========================================
    // Private Helpers
    // ========================================

    /**
     * mapper 계약 방어 (단일 진실원)
     *
     * <p>mapper가 계약을 위반하면 IllegalStateException을 throw합니다:</p>
     * <ul>
     *   <li>null 반환 → IllegalStateException</li>
     *   <li>Error throw → 그대로 throw</li>
     *   <li>RuntimeException throw → 그대로 throw</li>
     *   <li>기타 Throwable throw → IllegalStateException</li>
     * </ul>
     */
    private RuntimeException applyMapper(
            Function<Exception, ? extends RuntimeException> mapper,
            Exception ex
    ) {
        try {
            RuntimeException mapped = mapper.apply(ex);
            if (mapped == null) {
                throw new IllegalStateException(
                        "Exception mapper returned null for: " + ex.getClass().getName()
                );
            }
            return mapped;
        } catch (Error e) {
            throw e;
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable mt) {
            throw new IllegalStateException(
                    "Exception mapper violated contract (threw non-RuntimeException): " +
                    mt.getClass().getName(), mt
            );
        }
    }

    /**
     * suppressed 예외 이관
     *
     * <p>from에 누적된 suppressed 예외들을 to로 복사합니다.
     * cleanup 실패 정보가 유실되지 않도록 합니다.</p>
     */
    private static void copySuppressed(Throwable from, Throwable to) {
        for (Throwable s : from.getSuppressed()) {
            safeAddSuppressed(to, s);
        }
    }

    /**
     * 안전한 suppressed 추가
     *
     * <p>자기 자신을 suppressed로 추가하려는 경우를 방어합니다.</p>
     */
    private static void safeAddSuppressed(Throwable primary, Throwable suppressed) {
        if (primary != suppressed && suppressed != null) {
            try {
                primary.addSuppressed(suppressed);
            } catch (Throwable ignored) {
                // addSuppressed 실패는 무시 (예: suppressed 비활성화된 예외)
            }
        }
    }

    /**
     * InterruptedException 발생 시 인터럽트 플래그 복원
     *
     * <p>cause chain과 suppressed 배열을 모두 순회하여 InterruptedException이 있으면
     * Thread.currentThread().interrupt() 호출합니다.</p>
     *
     * <h4>suppressed 스캔 필요 이유</h4>
     * <p>executeWithFinallyUnchecked에서 finalizer가 InterruptedException을 던지면
     * primary의 suppressed로 합류합니다. cause chain만 스캔하면 이를 놓칩니다.</p>
     */
    private void restoreInterruptIfNeeded(Throwable t) {
        if (containsInterrupted(t, 0)) {
            Thread.currentThread().interrupt();
            log.debug("Restored interrupt flag due to InterruptedException in throwable graph");
        }
    }

    /**
     * Throwable 그래프에서 InterruptedException 존재 여부 확인
     *
     * @param t 검사할 Throwable
     * @param depth 현재 깊이 (무한 루프 방지)
     * @return InterruptedException이 존재하면 true
     */
    private boolean containsInterrupted(Throwable t, int depth) {
        if (t == null || depth >= 32) return false;
        if (t instanceof InterruptedException) return true;

        // suppressed 배열 스캔
        for (Throwable s : t.getSuppressed()) {
            if (containsInterrupted(s, depth + 1)) return true;
        }

        // cause chain 스캔
        return containsInterrupted(t.getCause(), depth + 1);
    }
}
