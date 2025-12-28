package maple.expectation.global.error;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.dto.ErrorResponse;
import maple.expectation.global.error.exception.base.BaseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * [1순위 가치] 비즈니스 예외 처리 (동적 메시지 포함)
     * BaseException 객체를 직접 넘겨서 가공된 메시지(예: IGN 포함)를 활용합니다. [cite: 14, 15]
     */
    @ExceptionHandler(BaseException.class)
    protected ResponseEntity<ErrorResponse> handleBaseException(BaseException e) {
        log.warn("Business Exception: {} | Message: {}", e.getErrorCode().getCode(), e.getMessage());
        return ErrorResponse.toResponseEntity(e); // 💡 ErrorCode가 아닌 e 자체를 넘깁니다.
    }

    /**
     * [재앙 방지] 예측하지 못한 시스템 예외 처리 [cite: 32, 37]
     * 시스템 내부의 '약한 고리'에서 터진 재앙을 안전하게 캡슐화합니다. [cite: 40]
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        // 실제 운영 환경의 장애 회고록을 위해 스택 트레이스를 상세히 남깁니다. [cite: 34, 36]
        log.error("Unexpected System Failure: ", e);

        // 500 에러는 보안상 상세 메시지를 숨기고 규격화된 공통 코드를 넘깁니다. [cite: 44]
        return ErrorResponse.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}