package maple.expectation.global.error;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.dto.ErrorResponse;
import maple.expectation.global.error.exception.base.BaseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * [1순위 가치] 비즈니스 예외 처리 (동적 메시지 포함)
     *
     * <p>BaseException 객체를 직접 넘겨서 가공된 메시지(예: IGN 포함)를 활용합니다.</p>
     *
     * <h4>Issue #169: 503 응답에 Retry-After 헤더 추가</h4>
     * <p>5-Agent Council Round 2 결정: ApiTimeoutException 등 503 응답 시
     * HTTP 표준 Retry-After 헤더를 포함하여 클라이언트에게 재시도 시점을 안내합니다.</p>
     */
    @ExceptionHandler(BaseException.class)
    protected ResponseEntity<ErrorResponse> handleBaseException(BaseException e) {
        log.warn("Business Exception: {} | Message: {}", e.getErrorCode().getCode(), e.getMessage());

        // Issue #169: 503 응답에 Retry-After 헤더 추가 (Red Agent P0-2)
        if (e.getErrorCode().getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "30")
                    .body(ErrorResponse.builder()
                            .status(e.getErrorCode().getStatus().value())
                            .code(e.getErrorCode().getCode())
                            .message(e.getMessage())
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        return ErrorResponse.toResponseEntity(e);
    }

    /**
     * [Issue #118 + #168] CompletionException 처리 (비동기 파이프라인 예외 unwrap)
     *
     * <p>CompletableFuture.join()에서 발생하는 CompletionException을 unwrap하여
     * 원래 예외 타입에 맞는 핸들러로 위임합니다.</p>
     *
     * <h4>처리 순서</h4>
     * <ol>
     *   <li>cause가 BaseException → handleBaseException으로 위임</li>
     *   <li>cause가 RejectedExecutionException → 503 + Retry-After 60s (Issue #168)</li>
     *   <li>cause가 TimeoutException → 503 + Retry-After 30s</li>
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

        // 2. RejectedExecutionException → 503 + Retry-After 60초 (Issue #168)
        // 5-Agent 합의: 톰캣 스레드 보호를 위해 큐 포화 시 즉시 거부 후 503 반환
        if (cause instanceof RejectedExecutionException) {
            log.warn("Task rejected (executor queue full): {}", cause.getMessage());
            return buildServiceUnavailableResponse(60);  // 60초 후 재시도 권장
        }

        // 3. TimeoutException → 503 + Retry-After 30초
        if (cause instanceof TimeoutException) {
            log.warn("Async operation timeout: {}", cause.getMessage());
            return buildServiceUnavailableResponse(30);  // 30초 후 재시도 권장
        }

        // 4. 그 외 시스템 예외 → 500 (cause를 로깅)
        log.error("CompletionException unwrapped - cause: ", cause);
        return ErrorResponse.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * [Issue #168] 503 Service Unavailable 응답 빌더 (Retry-After 헤더 포함)
     *
     * <p>HTTP 표준 Retry-After 헤더를 포함하여 클라이언트에게 재시도 시점을 안내합니다.</p>
     *
     * @param retryAfterSeconds 재시도 권장 시간 (초)
     * @return 503 응답 + Retry-After 헤더
     */
    private ResponseEntity<ErrorResponse> buildServiceUnavailableResponse(int retryAfterSeconds) {
        ErrorResponse body = ErrorResponse.builder()
                .status(CommonErrorCode.SERVICE_UNAVAILABLE.getStatus().value())
                .code(CommonErrorCode.SERVICE_UNAVAILABLE.getCode())
                .message(CommonErrorCode.SERVICE_UNAVAILABLE.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .body(body);
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