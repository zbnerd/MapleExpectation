package maple.expectation.infrastructure.executor;

import java.util.Objects;

/**
 * 메트릭 카디널리티 통제를 위한 작업 컨텍스트
 *
 * <p>TaskName을 구조화하여 동적 값과 고정 Taxonomy를 분리합니다.
 *
 * <h3>형식</h3>
 *
 * <pre>
 * "component:operation:dynamicValue"
 *
 * 예시:
 * - TaskContext.of("Observability", "track", "GameCharacterService.createNewCharacter")
 *   → "Observability:track:GameCharacterService.createNewCharacter"
 * - TaskContext.of("Trace", "execute")
 *   → "Trace:execute"
 * </pre>
 *
 * <h3>P1 정책: 메트릭 카디널리티 통제</h3>
 *
 * <ul>
 *   <li>component, operation: 메트릭 태그로 사용 (고정 값)
 *   <li>dynamicValue: 로그에만 기록 (메트릭에서 제외)
 * </ul>
 *
 * @param component 컴포넌트 이름 (예: "Observability", "Trace", "Lock")
 * @param operation 작업 유형 (예: "track", "execute", "acquire")
 * @param dynamicValue 동적 값 (예: 메서드 시그니처, 파라미터)
 * @since 1.0.0
 */
public record TaskContext(String component, String operation, String dynamicValue) {
  /** Compact constructor: null 파라미터 검증 (P1-4) */
  public TaskContext {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(operation, "operation");
    if (dynamicValue == null) {
      dynamicValue = "";
    }
  }

  /**
   * TaskContext 생성 (동적 값 포함)
   *
   * @param component 컴포넌트 이름
   * @param operation 작업 유형
   * @param dynamicValue 동적 값
   * @return TaskContext 인스턴스
   */
  public static TaskContext of(String component, String operation, String dynamicValue) {
    return new TaskContext(component, operation, dynamicValue);
  }

  /**
   * TaskContext 생성 (동적 값 없음)
   *
   * @param component 컴포넌트 이름
   * @param operation 작업 유형
   * @return TaskContext 인스턴스
   */
  public static TaskContext of(String component, String operation) {
    return new TaskContext(component, operation, "");
  }

  /**
   * TaskName 문자열로 변환
   *
   * <p>DefaultLogicExecutor의 parseTaskName()과 호환되는 형식으로 변환합니다.
   *
   * @return "component:operation:dynamicValue" 형식의 문자열
   */
  public String toTaskName() {
    if (dynamicValue.isEmpty()) {
      return component + ":" + operation;
    }
    return component + ":" + operation + ":" + dynamicValue;
  }
}
