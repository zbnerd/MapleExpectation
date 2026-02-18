package maple.expectation.infrastructure.executor.strategy

import com.fasterxml.jackson.core.JsonProcessingException
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.util.ExceptionUtils
import org.springframework.cache.Cache
import java.io.IOException
import java.util.concurrent.Callable

/**
 * 특정 예외를 도메인 예외로 변환하는 전략
 *
 * TODO: This is a temporary simplified version pending proper error hierarchy migration (task #9)
 */
fun interface ExceptionTranslator {
    /**
     * 예외를 변환하여 반환
     *
     * @param e 원본 예외
     * @param context 작업 컨텍스트
     * @return 변환된 RuntimeException
     */
    fun translate(e: Throwable, context: TaskContext): RuntimeException

    companion object {
        /**
         * Error guard + async unwrap을 선행 적용하는 Decorator
         *
         * 모든 팩토리 메서드에서 반복되는 패턴을 DRY로 통합합니다:
         * 1. Error → 즉시 rethrow (VirtualMachineError 등)
         * 2. CompletionException/ExecutionException → 원본으로 unwrap
         * 3. unwrap된 예외를 내부 translator에 위임
         */
        @JvmStatic
        fun withErrorGuardAndUnwrap(inner: ExceptionTranslator): ExceptionTranslator =
            ExceptionTranslator { e, context ->
                when (e) {
                    is Error -> throw e
                    else -> {
                        val unwrapped = ExceptionUtils.unwrapAsyncException(e)
                        // RuntimeException pass-through for now
                        when (unwrapped) {
                            is RuntimeException -> unwrapped
                            else -> inner.translate(unwrapped, context)
                        }
                    }
                }
            }

        /** JSON 처리 예외 변환기 */
        @JvmStatic
        fun forJson(): ExceptionTranslator = withErrorGuardAndUnwrap { unwrapped, context ->
            when (unwrapped) {
                is JsonProcessingException -> RuntimeException(
                    "JSON 직렬화 실패 [${context.toTaskName()}]: ${unwrapped.message}",
                    unwrapped
                )
                else -> RuntimeException("json-operation:${context.toTaskName()}", unwrapped)
            }
        }

        /** Lock 예외 변환기 */
        @JvmStatic
        fun forLock(): ExceptionTranslator = withErrorGuardAndUnwrap { unwrapped, context ->
            when (unwrapped) {
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    RuntimeException("락 획득 중 인터럽트 [${context.toTaskName()}]", unwrapped)
                }
                else -> RuntimeException("lock-operation:${context.operation}", unwrapped)
            }
        }

        /** 파일 I/O 예외 변환기 */
        @JvmStatic
        fun forFileIO(): ExceptionTranslator = withErrorGuardAndUnwrap { unwrapped, context ->
            when (unwrapped) {
                is IOException -> RuntimeException("file-io:${context.toTaskName()}", unwrapped)
                else -> RuntimeException("file-operation:${context.operation}", unwrapped)
            }
        }

        /**
         * 기본 예외 변환기
         *
         * CompletionException/ExecutionException unwrap 후 RuntimeException 감지
         */
        @JvmStatic
        fun defaultTranslator(): ExceptionTranslator = withErrorGuardAndUnwrap { unwrapped, context ->
            RuntimeException("default-task:${context.toTaskName()}", unwrapped)
        }

        /** 메이플스토리 데이터 처리 전용 번역기 */
        @JvmStatic
        fun forMaple(): ExceptionTranslator = withErrorGuardAndUnwrap { unwrapped, context ->
            when (unwrapped) {
                is IOException -> RuntimeException(
                    "메이플 데이터 파싱 중 기술적 오류 발생: ${unwrapped.message}",
                    unwrapped
                )
                else -> RuntimeException(context.toTaskName(), unwrapped)
            }
        }

        @JvmStatic
        fun forCache(key: Any, loader: Callable<*>): ExceptionTranslator =
            ExceptionTranslator { e, context ->
                when (e) {
                    is Error -> throw e
                    else -> Cache.ValueRetrievalException(key, loader, e)
                }
            }

        /**
         * Redis Lua Script 예외 변환기 (Context7 Best Practice)
         *
         * 금융수준 안전 설계:
         * - Error는 즉시 폭발 (OOM 등)
         * - RuntimeException은 그대로 전파
         * - 기타 예외는 RuntimeException으로 변환
         */
        @JvmStatic
        fun forRedisScript(): ExceptionTranslator = withErrorGuardAndUnwrap { unwrapped, context ->
            RuntimeException("redis-script:${context.operation}:${context.dynamicValue}", unwrapped)
        }

        /**
         * 애플리케이션 시작 시 초기화 작업용 예외 변환기 (#240)
         *
         * ## 사용 사례
         * - Lookup Table 초기화 실패
         * - Cache Warmup 실패
         * - Configuration 로딩 실패
         */
        @JvmStatic
        fun forStartup(componentName: String): ExceptionTranslator =
            withErrorGuardAndUnwrap { unwrapped, context ->
                RuntimeException(
                    "startup:$componentName:${context.operation}",
                    unwrapped
                )
            }
    }
}
