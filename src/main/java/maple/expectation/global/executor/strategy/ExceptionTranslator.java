package maple.expectation.global.executor.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.error.exception.base.BaseException;

import java.io.IOException;

/**
 * 특정 예외를 도메인 예외로 변환하는 전략
 *
 * <p>다중 catch 블록을 if-else 체인으로 대체하여 코드 평탄화를 달성합니다.
 *
 * <h3>P0 정책: Error 격리</h3>
 * <ul>
 *   <li>{@link Error}(OOM, StackOverflow 등)는 절대 변환하지 않고 그대로 throw</li>
 *   <li>{@link BaseException}은 비즈니스 예외이므로 그대로 pass-through</li>
 *   <li>기타 Exception은 도메인 예외로 변환</li>
 *   <li>원본 예외를 cause로 보존하여 스택 트레이스 유지</li>
 * </ul>
 *
 * <h3>[패턴 6] 다중 catch 블록 예시</h3>
 * <pre>{@code
 * // Before (다중 catch 블록)
 * try {
 *     return objectMapper.writeValueAsString(data);
 * } catch (JsonProcessingException e) {
 *     throw new EquipmentDataProcessingException("JSON 직렬화 실패", e);
 * } catch (IOException e) {
 *     throw new EquipmentDataProcessingException("I/O 실패", e);
 * } catch (RuntimeException e) {
 *     throw e;
 * }
 *
 * // After (ExceptionTranslator 사용)
 * return executor.executeWithTranslation(
 *     () -> objectMapper.writeValueAsString(data),
 *     ExceptionTranslator.forJson(),
 *     "serialize"
 * );
 * }</pre>
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface ExceptionTranslator {

    /**
     * 예외를 변환하여 반환
     *
     * <p><b>P0: Error 격리</b> - {@link Error}는 절대 변환하지 않음
     *
     * @param e 원본 예외
     * @return 변환된 RuntimeException
     * @throws Error Error 타입은 변환하지 않고 그대로 throw
     */
    RuntimeException translate(Throwable e);

    /**
     * JSON 처리 예외 변환기
     *
     * <p>다음 예외를 변환합니다:
     * <ul>
     *   <li>{@link JsonProcessingException} → {@link EquipmentDataProcessingException}</li>
     *   <li>{@link IOException} → {@link EquipmentDataProcessingException}</li>
     *   <li>{@link BaseException} → 그대로 재전파</li>
     *   <li>기타 → {@link InternalSystemException}으로 규격화</li>
     * </ul>
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * String json = executor.executeWithTranslation(
     *     () -> objectMapper.writeValueAsString(data),
     *     ExceptionTranslator.forJson(),
     *     "serialize"
     * );
     * }</pre>
     *
     * @return JSON 처리용 예외 변환기
     */
    static ExceptionTranslator forJson() {
        return e -> {
            // 0. ✅ P0: Error 격리 - Error는 절대 변환하지 않고 그대로 throw
            if (e instanceof Error) {
                throw (Error) e;
            }

            // 1. JSON 직렬화 예외 → EquipmentDataProcessingException (cause 보존)
            if (e instanceof JsonProcessingException) {
                return new EquipmentDataProcessingException(
                    "JSON 직렬화 실패: " + e.getMessage(),
                    e  // ✅ cause 보존
                );
            }
            // 2. I/O 예외 → EquipmentDataProcessingException (cause 보존)
            if (e instanceof IOException) {
                return new EquipmentDataProcessingException(
                    "데이터 I/O 실패: " + e.getMessage(),
                    e  // ✅ cause 보존
                );
            }
            // 3. 프로젝트 예외 계층(BaseException)은 그대로 전파
            if (e instanceof BaseException) {
                return (BaseException) e;
            }
            // 4. 관리되지 않은 예외는 InternalSystemException으로 규격화 (cause 보존)
            return new InternalSystemException("json-processing", e);
        };
    }

    /**
     * Lock 예외 변환기
     *
     * <p>다음 예외를 변환합니다:
     * <ul>
     *   <li>{@link InterruptedException} → {@link DistributedLockException} (+ 인터럽트 플래그 복원)</li>
     *   <li>{@link BaseException} → 그대로 재전파</li>
     *   <li>기타 → {@link InternalSystemException}으로 규격화</li>
     * </ul>
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * executor.executeWithTranslation(
     *     () -> this.tryLock(key, wait, lease),
     *     ExceptionTranslator.forLock(),
     *     "lockOperation"
     * );
     * }</pre>
     *
     * @return Lock 처리용 예외 변환기
     */
    static ExceptionTranslator forLock() {
        return e -> {
            // 0. ✅ P0: Error 격리 - Error는 절대 변환하지 않고 그대로 throw
            if (e instanceof Error) {
                throw (Error) e;
            }

            // 1. InterruptedException → DistributedLockException (인터럽트 플래그 복원, cause 보존)
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();  // ✅ 인터럽트 플래그 복원
                return new DistributedLockException("락 획득 중 인터럽트", e);  // ✅ cause 보존
            }
            // 2. 프로젝트 예외 계층(BaseException)은 그대로 전파
            if (e instanceof BaseException) {
                return (BaseException) e;
            }
            // 3. 관리되지 않은 예외는 InternalSystemException으로 규격화 (cause 보존)
            return new InternalSystemException("lock-operation", e);
        };
    }

    /**
     * 파일 I/O 예외 변환기
     *
     * <p>{@link IOException}을 {@link InternalSystemException}으로 변환합니다.
     *
     * <h4>사용 예시</h4>
     * <pre>{@code
     * String content = executor.executeWithTranslation(
     *     () -> Files.readString(path),
     *     ExceptionTranslator.forFileIO(),
     *     "readFile"
     * );
     * }</pre>
     *
     * @return 파일 I/O용 예외 변환기
     */
    static ExceptionTranslator forFileIO() {
        return e -> {
            // 0. ✅ P0: Error 격리 - Error는 절대 변환하지 않고 그대로 throw
            if (e instanceof Error) {
                throw (Error) e;
            }

            // 1. IOException → InternalSystemException (파일 I/O는 시스템 예외, cause 보존)
            if (e instanceof IOException) {
                return new InternalSystemException("file-io:" + e.getMessage(), e);
            }
            // 2. 프로젝트 예외 계층(BaseException)은 그대로 전파
            if (e instanceof BaseException) {
                return (BaseException) e;
            }
            // 3. 관리되지 않은 예외는 InternalSystemException으로 규격화 (cause 보존)
            return new InternalSystemException("file-operation", e);
        };
    }

    /**
     * 기본 예외 변환기
     *
     * <p>프로젝트 규격에 맞게 예외를 변환합니다:
     * <ul>
     *   <li>{@link Error} → 절대 변환하지 않고 그대로 throw</li>
     *   <li>{@link BaseException} → 그대로 전파 (비즈니스 예외 보존)</li>
     *   <li>기타 예외 → {@link InternalSystemException}으로 규격화</li>
     * </ul>
     *
     * @return 기본 예외 변환기
     */
    static ExceptionTranslator defaultTranslator() {
        return e -> {
            // 0. ✅ P0: Error 격리 - Error는 절대 변환하지 않고 그대로 throw
            if (e instanceof Error) {
                throw (Error) e;
            }

            // 1. 프로젝트 예외 계층(BaseException)은 그대로 전파
            if (e instanceof BaseException) {
                return (BaseException) e;
            }
            // 2. 관리되지 않은 모든 예외는 InternalSystemException으로 규격화 (cause 보존)
            return new InternalSystemException("default-task", e);
        };
    }
}
