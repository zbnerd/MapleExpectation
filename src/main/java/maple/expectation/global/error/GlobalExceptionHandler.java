package maple.expectation.global.error;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.dto.ErrorResponse;
import maple.expectation.global.error.exception.base.BaseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * [1순위 가치] 비즈니스 예외 처리 (동적 메시지 포함)
     * BaseException 객체를 직접 넘겨서 가공된 메시지(예: IGN 포함)를 활용합니다.
     */
    @ExceptionHandler(BaseException.class)
    protected ResponseEntity<ErrorResponse> handleBaseException(BaseException e) {
        log.warn("Business Exception: {} | Message: {}", e.getErrorCode().getCode(), e.getMessage());
        return ErrorResponse.toResponseEntity(e);
    }

    /**
     * [Issue #118] CompletionException 처리 (비동기 파이프라인 예외 unwrap)
     *
     * <p>CompletableFuture.join()에서 발생하는 CompletionException을 unwrap하여
     * 원래 예외 타입에 맞는 핸들러로 위임합니다.</p>
     *
     * <h4>처리 순서</h4>
     * <ol>
     *   <li>cause가 BaseException → handleBaseException으로 위임</li>
     *   <li>cause가 TimeoutException → 503 Service Unavailable</li>
     *   <li>그 외 → 500 Internal Server Error</li>
     * </ol>
     */
    @ExceptionHandler(CompletionException.class)
    protected ResponseEntity<ErrorResponse> handleCompletionException(CompletionException e) {
        Throwable cause = e.getCause();

        // 1. BaseException (비즈니스 예외) → 기존 핸들러로 위임
        if (cause instanceof BaseException be) {
            return handleBaseException(be);
        }

        // 2. TimeoutException → 503 Service Unavailable
        if (cause instanceof TimeoutException) {
            log.warn("Async operation timeout: {}", cause.getMessage());
            return ErrorResponse.toResponseEntity(CommonErrorCode.SERVICE_UNAVAILABLE);
        }

        // 3. 그 외 시스템 예외 → 500 (cause를 로깅)
        log.error("CompletionException unwrapped - cause: ", cause);
        return ErrorResponse.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * [재앙 방지] 예측하지 못한 시스템 예외 처리
     * 시스템 내부의 '약한 고리'에서 터진 재앙을 안전하게 캡슐화합니다.
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        // 실제 운영 환경의 장애 회고록을 위해 스택 트레이스를 상세히 남깁니다.
        log.error("Unexpected System Failure: ", e);

        // 500 에러는 보안상 상세 메시지를 숨기고 규격화된 공통 코드를 넘깁니다.
        return ErrorResponse.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}