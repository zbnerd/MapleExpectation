package maple.expectation.global.executor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.error.exception.base.BaseException;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * LogicExecutor 기본 구현체
 *
 * <ul>
 *   <li>Checked Exception → Runtime Exception 자동 변환</li>
 *   <li>Micrometer 메트릭 자동 수집 (선택적)</li>
 *   <li>코드 평탄화 최우선 설계</li>
 *   <li><b>P0: Error 격리</b> - Error(OOM 등)는 절대 캐치하지 않고 상위로 폭발</li>
 *   <li><b>P1: 메트릭 카디널리티 통제</b> - 동적 값은 로그에만 기록, 메트릭 태그는 고정된 Taxonomy만 사용</li>
 * </ul>
 *
 * <p>모든 메서드는 코드 평탄화 원칙에 따라 설계되었습니다.
 * 비즈니스 로직은 별도 메서드로 분리하여 메서드 참조를 활용하세요.
 *
 * <h3>TaskName 형식</h3>
 * <pre>
 * "component:operation:dynamicValue"
 *
 * 예시:
 * - "CharacterSync:lock:maple123" → component=CharacterSync, operation=lock (maple123은 메트릭에서 제외)
 * - "NexonCache:fetch:ocid123" → component=NexonCache, operation=fetch
 * </pre>
 *
 * @see LogicExecutor
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultLogicExecutor implements LogicExecutor {

    private final MeterRegistry meterRegistry;

    @Override
    public <T> T execute(ThrowingSupplier<T> task, String taskName) {
        return executeWithMetrics(task, taskName, null);
    }

    @Override
    public <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, String taskName) {
        try {
            return executeWithMetrics(task, taskName, null);
        } catch (Exception e) {
            log.debug("[{}] 예외 발생, 기본값 반환: {}", taskName, e.getMessage());
            return defaultValue;
        }
    }

    @Override
    public <T> T executeWithRecovery(
        ThrowingSupplier<T> task,
        Function<Throwable, T> recovery,
        String taskName
    ) {
        try {
            return executeWithMetrics(task, taskName, null);
        } catch (Exception e) {
            log.warn("[{}] 예외 발생, 복구 로직 실행: {}", taskName, e.getMessage());
            return recovery.apply(e);
        }
    }

    @Override
    public void executeVoid(ThrowingRunnable task, String taskName) {
        execute(() -> {
            task.run();
            return null;
        }, taskName);
    }

    @Override
    public <T> T executeWithFinally(
        ThrowingSupplier<T> task,
        Runnable finallyBlock,
        String taskName
    ) {
        try {
            return executeWithMetrics(task, taskName, null);
        } finally {
            finallyBlock.run();
        }
    }

    @Override
    public <T> T executeWithTranslation(
        ThrowingSupplier<T> task,
        ExceptionTranslator translator,
        String taskName
    ) {
        return executeWithMetrics(task, taskName, translator);
    }

    /**
     * 내부 핵심 로직: 메트릭 수집 + 예외 변환
     *
     * <p>코드 평탄화를 위해 메트릭 수집과 예외 처리를 별도 메서드로 분리합니다.
     *
     * <p><b>P0: Error 격리</b> - {@link Error}(OOM, StackOverflow 등)는 절대 캐치하지 않고 상위로 즉시 폭발시킵니다.
     *
     * @param task 실행할 작업
     * @param taskName 작업 이름
     * @param translator 예외 변환기 (null이면 기본 변환기 사용)
     * @param <T> 작업 결과 타입
     * @return 작업 결과
     */
    private <T> T executeWithMetrics(
        ThrowingSupplier<T> task,
        String taskName,
        ExceptionTranslator translator
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            T result = task.get();
            recordSuccess(sample, taskName);
            return result;

        } catch (Throwable t) {
            // ✅ P0: Error 격리 - Error는 절대 캐치하지 않고 상위로 즉시 폭발
            if (t instanceof Error) {
                throw (Error) t;
            }

            Exception e = (Exception) t;
            recordFailure(sample, taskName, e);
            logError(taskName, e);
            throw translateException(e, taskName, translator);
        }
    }

    /**
     * 성공 메트릭 기록 (평탄화: 별도 메서드로 분리)
     *
     * <p><b>P1: 메트릭 카디널리티 통제</b> - taskName에서 동적 값을 제거하고 고정된 Taxonomy만 사용
     */
    private void recordSuccess(Timer.Sample sample, String taskName) {
        MetricTags tags = parseTaskName(taskName);
        sample.stop(Timer.builder("logic.executor")
            .tag("component", tags.component)
            .tag("operation", tags.operation)
            .tag("result", "success")
            .register(meterRegistry));
    }

    /**
     * 실패 메트릭 기록 (평탄화: 별도 메서드로 분리)
     *
     * <p><b>P1: 메트릭 카디널리티 통제</b> - 예외 타입은 클래스 이름이므로 안전
     */
    private void recordFailure(Timer.Sample sample, String taskName, Exception e) {
        MetricTags tags = parseTaskName(taskName);
        sample.stop(Timer.builder("logic.executor")
            .tag("component", tags.component)
            .tag("operation", tags.operation)
            .tag("result", "failure")
            .tag("exception", e.getClass().getSimpleName())
            .register(meterRegistry));
    }

    /**
     * TaskName 파싱 (메트릭 카디널리티 통제)
     *
     * <p>형식: "component:operation:dynamicValue"
     * <p>동적 값은 메트릭 태그에서 제거하고 로그에만 기록
     *
     * @param taskName 작업 이름
     * @return 파싱된 메트릭 태그
     */
    private MetricTags parseTaskName(String taskName) {
        String[] parts = taskName.split(":", 3);
        return new MetricTags(
            parts.length > 0 ? parts[0] : "unknown",
            parts.length > 1 ? parts[1] : "unknown"
        );
    }

    /**
     * 메트릭 태그 (카디널리티 통제)
     *
     * @param component 컴포넌트 (예: CharacterSync, NexonCache)
     * @param operation 작업 유형 (예: lock, cache, io)
     */
    private record MetricTags(String component, String operation) {}

    /**
     * 에러 로깅 (평탄화: 별도 메서드로 분리)
     */
    private void logError(String taskName, Throwable e) {
        log.error("[{}] 실행 중 예외 발생", taskName, e);
    }

    /**
     * 예외 변환 (평탄화: 별도 메서드로 분리)
     *
     * <p>예외 변환 전략:
     * <ol>
     *   <li>ExceptionTranslator가 있으면 우선 사용</li>
     *   <li>없으면 기본 변환기로 래핑 (BaseException은 그대로 전파)</li>
     * </ol>
     *
     * @param e 원본 예외
     * @param taskName 작업 이름
     * @param translator 예외 변환기 (null이면 기본 변환기 사용)
     * @return 변환된 RuntimeException
     */
    private RuntimeException translateException(Throwable e, String taskName, ExceptionTranslator translator) {
        if (translator != null) {
            return translator.translate(e);
        }
        return wrapAsRuntimeException(e, taskName);
    }

    /**
     * 예외를 프로젝트 규격에 맞게 변환
     *
     * <p><strong>변환 규칙</strong>:
     * <ul>
     *   <li>{@link BaseException} → 그대로 전파 (비즈니스 예외 보존)</li>
     *   <li>기타 Checked/Unchecked Exception → {@link InternalSystemException}으로 규격화</li>
     * </ul>
     *
     * <h3>Before (RuntimeException 직접 사용)</h3>
     * <pre>{@code
     * if (e instanceof RuntimeException) {
     *     return (RuntimeException) e;
     * }
     * return new RuntimeException("실행 중 예외 발생: " + e.getMessage(), e);
     * }</pre>
     *
     * <h3>After (InternalSystemException 사용)</h3>
     * <pre>{@code
     * if (e instanceof BaseException) {
     *     return (BaseException) e; // 비즈니스 예외는 그대로 전파
     * }
     * return new InternalSystemException(taskName, e); // 시스템 예외로 규격화
     * }</pre>
     *
     * @param e 원본 예외
     * @param taskName 작업 이름 (에러 추적용)
     * @return 변환된 RuntimeException
     */
    private RuntimeException wrapAsRuntimeException(Throwable e, String taskName) {
        // 1. 프로젝트 예외 계층(BaseException)은 그대로 전파
        if (e instanceof BaseException) {
            return (BaseException) e;
        }

        // 2. 관리되지 않은 모든 예외는 InternalSystemException으로 규격화
        return new InternalSystemException(taskName, e);
    }
}
