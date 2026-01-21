package maple.expectation.global.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 예외 처리 유틸리티 (PR #199, #241)
 *
 * <h3>목적</h3>
 * <p>비동기 실행 시 발생하는 래퍼 예외(CompletionException, ExecutionException)를
 * 원본 예외로 unwrap하여 정확한 예외 타입 감지를 지원합니다.</p>
 *
 * <h3>문제 상황</h3>
 * <p>{@code CompletableFuture.join()} 호출 시 예외가 CompletionException으로 래핑되어
 * {@code CharacterNotFoundException} 같은 비즈니스 예외를 직접 감지할 수 없습니다.</p>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * executor.executeOrCatch(
 *     () -> nexonApiClient.getOcid(userIgn).join().getOcid(),
 *     e -> {
 *         Throwable unwrapped = ExceptionUtils.unwrapAsyncException(e);
 *         if (unwrapped instanceof CharacterNotFoundException) {
 *             // Negative Cache 저장
 *         }
 *         throw (RuntimeException) e;
 *     },
 *     context
 * );
 * }</pre>
 *
 * <h3>CLAUDE.md 준수</h3>
 * <ul>
 *   <li>Section 4: DRY 원칙 - 3곳 이상에서 동일 로직 사용 시 유틸리티로 추출</li>
 *   <li>Section 6: Utility 클래스 → private 생성자, static 메서드</li>
 * </ul>
 *
 * @since PR #199, #241
 */
public final class ExceptionUtils {

    private static final int MAX_UNWRAP_DEPTH = 10;

    private ExceptionUtils() {
        // Utility class - private constructor
    }

    /**
     * 비동기 래퍼 예외를 원본 예외로 unwrap
     *
     * <h4>지원하는 래퍼 예외</h4>
     * <ul>
     *   <li>{@link CompletionException} - CompletableFuture.join() 시 발생</li>
     *   <li>{@link ExecutionException} - Future.get() 시 발생</li>
     * </ul>
     *
     * <h4>안전 장치</h4>
     * <p>무한 루프 방지를 위해 최대 10단계까지만 unwrap합니다.</p>
     *
     * @param throwable unwrap할 예외
     * @return 원본 예외 (래퍼 예외가 아닌 경우 입력 그대로 반환)
     */
    public static Throwable unwrapAsyncException(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        Throwable current = throwable;
        for (int i = 0; i < MAX_UNWRAP_DEPTH && current != null; i++) {
            if (current instanceof CompletionException || current instanceof ExecutionException) {
                Throwable cause = current.getCause();
                if (cause != null) {
                    current = cause;
                    continue;
                }
            }
            break;
        }

        return current != null ? current : throwable;
    }

    /**
     * 특정 예외 타입으로 unwrap 후 캐스팅
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * CharacterNotFoundException unwrapped = ExceptionUtils.unwrapAs(e, CharacterNotFoundException.class);
     * if (unwrapped != null) {
     *     // 처리
     * }
     * }</pre>
     *
     * @param throwable unwrap할 예외
     * @param targetType 목표 예외 타입
     * @return 목표 타입으로 캐스팅된 예외, 불가능하면 null
     */
    public static <T extends Throwable> T unwrapAs(Throwable throwable, Class<T> targetType) {
        Throwable unwrapped = unwrapAsyncException(throwable);
        if (targetType.isInstance(unwrapped)) {
            return targetType.cast(unwrapped);
        }
        return null;
    }

    /**
     * 예외 체인에서 특정 타입 존재 여부 확인
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * if (ExceptionUtils.containsCause(e, CharacterNotFoundException.class)) {
     *     // Negative Cache 저장
     * }
     * }</pre>
     *
     * @param throwable 검사할 예외
     * @param targetType 찾고자 하는 예외 타입
     * @return 해당 타입이 예외 체인에 존재하면 true
     */
    public static boolean containsCause(Throwable throwable, Class<? extends Throwable> targetType) {
        if (throwable == null) {
            return false;
        }

        Throwable current = throwable;
        for (int i = 0; i < MAX_UNWRAP_DEPTH && current != null; i++) {
            if (targetType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }
}
