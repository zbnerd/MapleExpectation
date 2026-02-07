package maple.expectation.service.v2.calculator;

import java.util.Optional;

/** [Component] 강화 기대값 계산 표준 인터페이스 */
public interface ExpectationCalculator {
  long calculateCost(); // 최종 소모 비용 합산

  String getEnhancePath(); // 적용된 강화 경로 문자열 반환

  Optional<Long> getTrials();
}
