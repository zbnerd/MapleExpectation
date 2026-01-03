package maple.expectation.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.stereotype.Component;

/**
 * ObjectMapper 래퍼 유틸리티
 *
 * <ul>
 *   <li>JsonProcessingException, IOException → 도메인 예외 자동 변환</li>
 *   <li>LogicExecutor 활용하여 코드 평탄화</li>
 *   <li>기본값 반환 메서드 제공</li>
 * </ul>
 *
 * <h3>사용 예시 (코드 평탄화)</h3>
 * <pre>{@code
 * // Before (try-catch 보일러플레이트)
 * private String serialize(Object obj) {
 *     try {
 *         return objectMapper.writeValueAsString(obj);
 *     } catch (JsonProcessingException e) {
 *         log.error("직렬화 실패", e);
 *         throw new EquipmentDataProcessingException("직렬화 실패");
 *     }
 * }
 *
 * // After (JsonMapper 사용)
 * private String serialize(Object obj) {
 *     return jsonMapper.writeValueAsString(obj);
 * }
 * }</pre>
 *
 * @see LogicExecutor
 * @see ExceptionTranslator#forJson()
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonMapper {

    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;

    private static final ExceptionTranslator JSON_TRANSLATOR = ExceptionTranslator.forJson();

    /**
     * JSON → 객체 변환
     *
     * @param json JSON 문자열
     * @param clazz 변환할 클래스
     * @param <T> 변환 타입
     * @return 변환된 객체
     * @throws maple.expectation.global.error.exception.EquipmentDataProcessingException 변환 실패 시
     */
    public <T> T readValue(String json, Class<T> clazz) {
        return executor.executeWithTranslation(
            () -> objectMapper.readValue(json, clazz),
            JSON_TRANSLATOR,
            "readValue:" + clazz.getSimpleName()
        );
    }

    /**
     * JSON → 객체 변환 (기본값 반환 버전)
     *
     * <p>변환 실패 시 기본값을 반환합니다.
     *
     * @param json JSON 문자열
     * @param clazz 변환할 클래스
     * @param defaultValue 실패 시 반환할 기본값
     * @param <T> 변환 타입
     * @return 변환된 객체 또는 기본값
     */
    public <T> T readValueOrDefault(String json, Class<T> clazz, T defaultValue) {
        return executor.executeOrDefault(
            () -> objectMapper.readValue(json, clazz),
            defaultValue,
            "readValueOrDefault:" + clazz.getSimpleName()
        );
    }

    /**
     * 객체 → JSON 문자열 변환
     *
     * @param value 변환할 객체
     * @return JSON 문자열
     * @throws maple.expectation.global.error.exception.EquipmentDataProcessingException 변환 실패 시
     */
    public String writeValueAsString(Object value) {
        return executor.executeWithTranslation(
            () -> objectMapper.writeValueAsString(value),
            JSON_TRANSLATOR,
            "writeValueAsString"
        );
    }

    /**
     * 객체 → JSON 바이트 배열 변환
     *
     * @param value 변환할 객체
     * @return JSON 바이트 배열
     * @throws maple.expectation.global.error.exception.EquipmentDataProcessingException 변환 실패 시
     */
    public byte[] writeValueAsBytes(Object value) {
        return executor.executeWithTranslation(
            () -> objectMapper.writeValueAsBytes(value),
            JSON_TRANSLATOR,
            "writeValueAsBytes"
        );
    }

    /**
     * Pretty print JSON
     *
     * <p>포맷팅된 JSON 문자열을 반환합니다.
     *
     * @param value 변환할 객체
     * @return 포맷팅된 JSON 문자열
     * @throws maple.expectation.global.error.exception.EquipmentDataProcessingException 변환 실패 시
     */
    public String writeValueAsPrettyString(Object value) {
        return executor.executeWithTranslation(
            () -> objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
            JSON_TRANSLATOR,
            "writeValueAsPrettyString"
        );
    }

    /**
     * 객체 → JSON 문자열 변환 (기본값 반환 버전)
     *
     * <p>변환 실패 시 기본값을 반환합니다.
     *
     * @param value 변환할 객체
     * @param defaultValue 실패 시 반환할 기본값
     * @return JSON 문자열 또는 기본값
     */
    public String writeValueAsStringOrDefault(Object value, String defaultValue) {
        return executor.executeOrDefault(
            () -> objectMapper.writeValueAsString(value),
            defaultValue,
            "writeValueAsStringOrDefault"
        );
    }
}
