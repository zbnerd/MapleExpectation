package maple.expectation.infrastructure.executor.policy;

import java.util.regex.Pattern;
import maple.expectation.infrastructure.executor.TaskContext;

/**
 * ExecutionPolicy 로깅 유틸리티
 *
 * <p>정책들이 공통적으로 사용하는 로깅 관련 헬퍼 메서드를 제공합니다.
 *
 * @since 2.4.0
 */
public final class TaskLogSupport {

  private static final String UNKNOWN = "unknown";
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private TaskLogSupport() {}

  /**
   * TaskContext를 안전하게 문자열로 변환
   *
   * <p>로깅 실패가 본 실행 흐름에 영향을 주지 않도록 RuntimeException만 격리합니다.
   *
   * <p>Error는 심각한 JVM 레벨 문제로 간주하여 전파합니다.
   *
   * @param context Task 실행 컨텍스트
   * @return Task 이름 (실패 시 "unknown" 또는 "unknown(ContextClassName)")
   */
  public static String safeTaskName(TaskContext context) {
    if (context == null) return UNKNOWN;

    try {
      String name = context.toTaskName();
      if (name == null) return UNKNOWN;

      // 제어문자/공백 정규화 (로그 파서 안정성)
      String normalized = WHITESPACE.matcher(name).replaceAll(" ").trim();
      return normalized.isEmpty() ? UNKNOWN : normalized;
    } catch (RuntimeException e) {
      Class<?> type = context.getClass();
      String simple = type.getSimpleName();
      String typeName = (simple != null && !simple.isBlank()) ? simple : "anonymous";
      return UNKNOWN + "(" + typeName + ")";
    }
  }
}
