package maple.expectation.global.error;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.dto.ErrorResponse;
import maple.expectation.global.error.exception.base.BaseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import maple.expectation.global.ratelimit.exception.RateLimitExceededException;

import java.time.LocalDateTime;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * [Issue #152] Rate Limit 초과 예외 처리 (429 Too Many Requests)
     *
     * <p>5-Agent Council 합의: 429 응답에 Retry-After 헤더 포함</p>
     *
     * @param e RateLimitExceededException
     * @return 429 응답 + Retry-After 헤더
     */
    @ExceptionHandler(RateLimitExceededException.class)
    protected ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException e) {
        log.warn("Rate limit exceeded: retryAfter={}s", e.getRetryAfterSeconds());

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .header("X-RateLimit-Remaining", "0")
                .body(ErrorResponse.builder()
                        .status(HttpStatus.TOO_MANY_REQUESTS.value())
                        .code(e.getErrorCode().getCode())
                        .message(e.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
    }

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

    // ==================== Issue #168: Executor 관련 예외 처리 ====================

    /**
     * [Issue #168 + PR #176 Codex Fix] RejectedExecutionException 직접 처리
     *
     * <p>AbortPolicy가 던지는 RejectedExecutionException은 동기 예외로 발생하여
     * CompletionException으로 감싸지지 않음. 이 경우 generic Exception 핸들러가
     * 처리하여 500을 반환하는 버그가 있었음.</p>
     *
     * <h4>P1 Fix (PR #176 Codex 지적)</h4>
     * <ul>
     *   <li>변경 전: RejectedExecutionException → generic handler → 500</li>
     *   <li>변경 후: 전용 핸들러 추가 → 503 + Retry-After 60s</li>
     * </ul>
     *
     * @return 503 Service Unavailable + Retry-After 헤더
     */
    @ExceptionHandler(RejectedExecutionException.class)
    protected ResponseEntity<ErrorResponse> handleRejectedExecution(RejectedExecutionException e) {
        log.warn("Task rejected (executor queue full - direct throw): {}", e.getMessage());
        return buildServiceUnavailableResponse(60);  // 60초 후 재시도 권장
    }

    // ==================== Issue #151: Bean Validation 처리 ====================

    /**
     * [Issue #151] Bean Validation 검증 실패 처리 (@RequestBody + @Valid)
     *
     * <p>@Valid 어노테이션이 적용된 DTO의 검증 실패 시 발생</p>
     *
     * <h4>5-Agent Council Round 2 결정</h4>
     * <ul>
     *   <li><b>Blue Agent</b>: Controller 책임 - DTO 형식 검증</li>
     *   <li><b>Yellow Agent</b>: 필드명 + 메시지 동적 생성</li>
     *   <li><b>Purple Agent</b>: ErrorResponse(C001) 표준 형식 준수</li>
     * </ul>
     *
     * @return 400 Bad Request + C001 에러코드
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e) {

        // 검증 실패 필드 정보 수집
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("Validation failed: {}", errorMessage);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .code(CommonErrorCode.INVALID_INPUT_VALUE.getCode())
                        .message(String.format(CommonErrorCode.INVALID_INPUT_VALUE.getMessage(), errorMessage))
                        .timestamp(LocalDateTime.now())
                        .build());
    }

    /**
     * [Issue #151] @PathVariable, @RequestParam 검증 실패 처리
     *
     * <p>@Validated 클래스의 @NotBlank 등 검증 실패 시 발생</p>
     *
     * <h4>적용 대상</h4>
     * <ul>
     *   <li>@PathVariable + @NotBlank</li>
     *   <li>@RequestParam + @Min/@Max</li>
     *   <li>Controller 클래스에 @Validated 필수</li>
     * </ul>
     *
     * @return 400 Bad Request + C001 에러코드
     */
    @ExceptionHandler(ConstraintViolationException.class)
    protected ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException e) {

        String errorMessage = e.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining(", "));

        log.warn("Constraint violation: {}", errorMessage);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .code(CommonErrorCode.INVALID_INPUT_VALUE.getCode())
                        .message(String.format(CommonErrorCode.INVALID_INPUT_VALUE.getMessage(), errorMessage))
                        .timestamp(LocalDateTime.now())
                        .build());
    }

    // ==================== Issue #266: JSON 파싱 보안 처리 ====================

    /**
     * [Issue #266 P1-4] JSON 파싱 예외 처리 (DoS 방어)
     *
     * <h3>5-Agent Council 합의</h3>
     * <ul>
     *   <li>Purple (Auditor): StreamConstraintsException 감지 시 명확한 메시지 반환</li>
     *   <li>Red (SRE): JSON Bomb 공격 로그 기록</li>
     * </ul>
     *
     * <h3>처리 대상</h3>
     * <ul>
     *   <li>StreamConstraintsException: 깊이/크기 제한 초과</li>
     *   <li>기타 JSON 파싱 오류: 잘못된 형식</li>
     * </ul>
     *
     * @return 400 Bad Request + C001 에러코드
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e) {

        String message = "Invalid JSON format";
        Throwable cause = e.getCause();

        // StreamConstraintsException: JSON 깊이/크기 제한 초과 (DoS 공격 의심)
        if (cause instanceof StreamConstraintsException) {
            message = "JSON exceeds size/depth limits";
            log.warn("[Security] JSON constraints violation - potential DoS attack: {}",
                    cause.getMessage());
        } else {
            log.warn("JSON parsing failed: {}", e.getMessage());
        }

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .code(CommonErrorCode.INVALID_INPUT_VALUE.getCode())
                        .message(message)
                        .timestamp(LocalDateTime.now())
                        .build());
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