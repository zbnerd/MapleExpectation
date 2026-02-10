package maple.expectation.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.stereotype.Component;

/**
 * ObjectMapper 래퍼 유틸리티
 *
 * <ul>
 *   <li>JsonProcessingException, IOException → 도메인 예외 자동 변환
 *   <li>LogicExecutor 활용하여 코드 평탄화
 *   <li>기본값 반환 메서드 제공
 * </ul>
 *
 * <h3>사용 예시 (코드 평탄화)</h3>
 *
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

  public <T> T readValue(String json, Class<T> clazz) {
    return executor.executeWithTranslation(
        () -> objectMapper.readValue(json, clazz),
        JSON_TRANSLATOR,
        TaskContext.of("Json", "ReadValue", clazz.getSimpleName()) // ✅ TaskContext 적용
        );
  }

  public <T> T readValueOrDefault(String json, Class<T> clazz, T defaultValue) {
    return executor.executeOrDefault(
        () -> objectMapper.readValue(json, clazz),
        defaultValue,
        TaskContext.of("Json", "ReadValueOrDefault", clazz.getSimpleName()) // ✅ TaskContext 적용
        );
  }

  public String writeValueAsString(Object value) {
    return executor.executeWithTranslation(
        () -> objectMapper.writeValueAsString(value),
        JSON_TRANSLATOR,
        TaskContext.of(
            "Json", "WriteValueAsString", value.getClass().getSimpleName()) // ✅ TaskContext 적용
        );
  }

  public byte[] writeValueAsBytes(Object value) {
    return executor.executeWithTranslation(
        () -> objectMapper.writeValueAsBytes(value),
        JSON_TRANSLATOR,
        TaskContext.of(
            "Json", "WriteValueAsBytes", value.getClass().getSimpleName()) // ✅ TaskContext 적용
        );
  }

  public String writeValueAsPrettyString(Object value) {
    return executor.executeWithTranslation(
        () -> objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
        JSON_TRANSLATOR,
        TaskContext.of(
            "Json",
            "WriteValueAsPrettyString",
            value.getClass().getSimpleName()) // ✅ TaskContext 적용
        );
  }

  public String writeValueAsStringOrDefault(Object value, String defaultValue) {
    return executor.executeOrDefault(
        () -> objectMapper.writeValueAsString(value),
        defaultValue,
        TaskContext.of(
            "Json",
            "WriteValueAsStringOrDefault",
            value.getClass().getSimpleName()) // ✅ TaskContext 적용
        );
  }
}
