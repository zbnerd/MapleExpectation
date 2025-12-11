package maple.expectation.exception;

/**
 * 큐브 확률 데이터 초기화(로딩) 실패 시 발생하는 예외
 * - CSV 파일 없음
 * - CSV 파싱 오류
 * - I/O 오류
 */
public class CubeDataInitializationException extends RuntimeException {

    public CubeDataInitializationException(String message) {
        super(message);
    }

    public CubeDataInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}