package maple.expectation.global.executor.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import maple.expectation.global.error.exception.AtomicFetchException;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.error.exception.MapleDataProcessingException;
import maple.expectation.global.error.exception.base.BaseException;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.util.ExceptionUtils;
import org.springframework.cache.Cache;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * 특정 예외를 도메인 예외로 변환하는 전략
 */
@FunctionalInterface
public interface ExceptionTranslator {

    /**
     * 예외를 변환하여 반환
     * * @param e 원본 예외
     * @param context 작업 컨텍스트 (수정됨: TaskContext 추가)
     * @return 변환된 RuntimeException
     */
    RuntimeException translate(Throwable e, TaskContext context);

    /**
     * JSON 처리 예외 변환기
     */
    static ExceptionTranslator forJson() {
        return (e, context) -> {
            if (e instanceof Error) {
                throw (Error) e;
            }

            Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);

            if (unwrapped instanceof JsonProcessingException) {
                return new EquipmentDataProcessingException(
                        "JSON 직렬화 실패 [" + context.toTaskName() + "]: " + unwrapped.getMessage(),
                        unwrapped
                );
            }
            if (unwrapped instanceof IOException) {
                return new EquipmentDataProcessingException(
                        "데이터 I/O 실패 [" + context.toTaskName() + "]: " + unwrapped.getMessage(),
                        unwrapped
                );
            }
            if (unwrapped instanceof BaseException be) {
                return be;
            }
            return new InternalSystemException("json-processing:" + context.operation(), e);
        };
    }

    /**
     * Lock 예외 변환기
     */
    static ExceptionTranslator forLock() {
        return (e, context) -> {
            if (e instanceof Error) {
                throw (Error) e;
            }

            Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);

            if (unwrapped instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new DistributedLockException("락 획득 중 인터럽트 [" + context.toTaskName() + "]", unwrapped);
            }
            if (unwrapped instanceof BaseException be) {
                return be;
            }
            return new InternalSystemException("lock-operation:" + context.operation(), e);
        };
    }

    /**
     * 파일 I/O 예외 변환기
     */
    static ExceptionTranslator forFileIO() {
        return (e, context) -> {
            if (e instanceof Error) {
                throw (Error) e;
            }

            Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);

            if (unwrapped instanceof IOException) {
                return new InternalSystemException("file-io:" + context.toTaskName(), unwrapped);
            }
            if (unwrapped instanceof BaseException be) {
                return be;
            }
            return new InternalSystemException("file-operation:" + context.operation(), e);
        };
    }

    /**
     * 기본 예외 변환기
     *
     * <p>CompletionException/ExecutionException unwrap 후 BaseException 감지</p>
     */
    static ExceptionTranslator defaultTranslator() {
        return (e, context) -> {
            if (e instanceof Error) {
                throw (Error) e;
            }

            Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);
            if (unwrapped instanceof BaseException be) {
                return be;
            }
            return new InternalSystemException("default-task:" + context.toTaskName(), e);
        };
    }

    /**
     * 메이플스토리 데이터 처리 전용 번역기
     * 기술적 예외(IOException 등)를 도메인 예외(MapleDataProcessingException)로 변환합니다.
     */
    static ExceptionTranslator forMaple() {
        return (ex, context) -> {
            if (ex instanceof Error) {
                throw (Error) ex;
            }

            Throwable unwrapped = ExceptionUtils.unwrapAsyncException(ex);

            if (unwrapped instanceof java.io.IOException) {
                return new MapleDataProcessingException(
                        "메이플 데이터 파싱 중 기술적 오류 발생: " + unwrapped.getMessage(), unwrapped);
            }
            if (unwrapped instanceof BaseException be) {
                return be;
            }
            return new InternalSystemException(context.toTaskName(), ex);
        };
    }

    static ExceptionTranslator forCache(Object key, Callable<?> loader) {
        return (e, context) -> {
            if (e instanceof Error) throw (Error) e;

            // Spring의 표준 규약에 따른 예외 반환
            return new Cache.ValueRetrievalException(key, loader, e);
        };
    }

    /**
     * Redis Lua Script 예외 변환기 (Context7 Best Practice)
     *
     * <p>금융수준 안전 설계:
     * <ul>
     *   <li>Error는 즉시 폭발 (OOM 등)</li>
     *   <li>BaseException은 그대로 전파</li>
     *   <li>기타 예외는 AtomicFetchException으로 변환</li>
     * </ul>
     * </p>
     */
    static ExceptionTranslator forRedisScript() {
        return (e, context) -> {
            if (e instanceof Error) {
                throw (Error) e;
            }

            Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);

            if (unwrapped instanceof BaseException be) {
                return be;
            }

            return new AtomicFetchException(
                    context.operation(),
                    context.dynamicValue(),
                    e
            );
        };
    }

    /**
     * 애플리케이션 시작 시 초기화 작업용 예외 변환기 (#240)
     *
     * <h3>사용 사례</h3>
     * <ul>
     *   <li>Lookup Table 초기화 실패</li>
     *   <li>Cache Warmup 실패</li>
     *   <li>Configuration 로딩 실패</li>
     * </ul>
     *
     * @param componentName 초기화 중인 컴포넌트 이름
     * @return 시작 전용 예외 변환기
     */
    static ExceptionTranslator forStartup(String componentName) {
        return (e, context) -> {
            if (e instanceof Error) {
                throw (Error) e;
            }

            Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);

            if (unwrapped instanceof BaseException be) {
                return be;
            }

            return new InternalSystemException(
                    "startup:" + componentName + ":" + context.operation(),
                    e
            );
        };
    }

}