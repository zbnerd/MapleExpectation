package maple.expectation.util

import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * 예외 처리 유틸리티 (PR #199, #241)
 *
 * <h3>목적</h3>
 *
 * <p>비동기 실행 시 발생하는 래퍼 예외(CompletionException, ExecutionException)를 원본 예외로 unwrap하여 정확한 예외 타입 감지를
 * 지원합니다.
 *
 * <h3>문제 상황</h3>
 *
 * <p>{@code CompletableFuture.join()} 호출 시 예외가 CompletionException으로 래핑되어 {@code
 * CharacterNotFoundException} 같은 비즈니스 예외를 직접 감지할 수 없습니다.
 *
 * <h3>사용 예시</h3>
 *
 * <pre>{@code
 * executor.executeOrCatch(
 *     () -> nexonApiClient.getOcid(userIgn).join().getOcid(),
 *     e -> {
 *         val unwrapped = ExceptionUtils.unwrapAsyncException(e)
 *         if (unwrapped is CharacterNotFoundException) {
 *             // Negative Cache 저장
 *         }
 *         throw e
 *     },
 *     context
 * );
 * }</pre>
 *
 * <h3>CLAUDE.md 준수</h3>
 *
 * <ul>
 *   <li>Section 4: DRY 원칙 - 3곳 이상에서 동일 로직 사용 시 유틸리티로 추출
 *   <li>Section 6: Utility 클래스 → private 생성자, static 메서드
 * </ul>
 *
 * @since PR #199, #241
 */

/** Throwable chain 순회 최대 깊이 (무한 루프 방지) */
const val MAX_CHAIN_DEPTH = 32

/**
 * 비동기 래퍼 예외를 원본 예외로 unwrap
 *
 * <h4>지원하는 래퍼 예외</h4>
 *
 * <ul>
 *   <li>{@link CompletionException} - CompletableFuture.join() 시 발생
 *   <li>{@link ExecutionException} - Future.get() 시 발생
 *   <li>{@link UndeclaredThrowableException} - Proxy/AOP 래핑 시 발생
 * </ul>
 *
 * <h4>안전 장치</h4>
 *
 * <p>무한 루프 방지를 위해 최대 10단계까지만 unwrap합니다.
 *
 * @param throwable unwrap할 예외
 * @return 원본 예외 (래퍼 예외가 아닌 경우 입력 그대로 반환)
 */
fun unwrapAsyncException(throwable: Throwable?): Throwable? {
    return throwable?.let { current ->
        for (i in 0 until MAX_CHAIN_DEPTH) {
            when (current) {
                is CompletionException,
                is ExecutionException,
                is UndeclaredThrowableException -> {
                    val cause = current.cause
                    if (cause != null) {
                        current.cause?.let { cause -> return@let cause }
                        break
                    }
                }
                else -> break
            }
        }
        current
    }
}

/**
 * 특정 예외 타입으로 unwrap 후 캐스팅
 *
 * <h4>사용 예시</h4>
 *
 * <pre>{@code
 * val unwrapped = ExceptionUtils.unwrapAs(e, CharacterNotFoundException::class.java)
 * if (unwrapped != null) {
 *     // 처리
 * }
 * }</pre>
 *
 * @param throwable unwrap할 예외
 * @param targetType 목표 예외 타입
 * @return 목표 타입으로 캐스팅된 예외, 불가능하면 null
 */
inline fun <reified T : Throwable> unwrapAs(throwable: Throwable?): T? {
    return unwrapAsyncException(throwable)?.takeIf { it is T }?.let { it as T }
}

/**
 * 예외 체인에서 특정 타입 존재 여부 확인
 *
 * <h4>사용 예시</h4>
 *
 * <pre>{@code
 * if (ExceptionUtils.containsCause(e, CharacterNotFoundException::class.java)) {
 *     // Negative Cache 저장
 * }
 * }</pre>
 *
 * @param throwable 검사할 예외
 * @param targetType 찾고자 하는 예외 타입
 * @return 해당 타입이 예외 체인에 존재하면 true
 */
fun containsCause(throwable: Throwable?, targetType: Class<out Throwable>): Boolean {
    return throwable?.let { current ->
        var t: Throwable? = current
        for (i in 0 until MAX_CHAIN_DEPTH) {
            t?.let { if (targetType.isInstance(it)) return true else it.cause?.let { cause -> t = cause } }
        }
        false
    } ?: false
}