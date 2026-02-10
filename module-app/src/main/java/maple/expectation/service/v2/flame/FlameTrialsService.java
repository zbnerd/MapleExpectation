package maple.expectation.service.v2.flame;

import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.flame.component.FlameDpCalculator;
import maple.expectation.service.v2.flame.component.FlameScoreCalculator;
import org.springframework.stereotype.Service;

/**
 * 환생의 불꽃 기대 시도 횟수 서비스
 *
 * <h3>역할</h3>
 *
 * <p>{@link FlameDpCalculator}에 위임하여 환생의 불꽃 기대 시도 횟수를 계산합니다.
 *
 * <h3>설계 근거</h3>
 *
 * <p>DIP 준수를 위해 {@link FlameTrialsProvider} 인터페이스를 구현합니다. V4 PresetCalculationHelper는 구체 클래스가 아닌
 * 인터페이스에 의존합니다.
 *
 * @see FlameTrialsProvider 인터페이스
 * @see FlameDpCalculator DP 기반 계산 컴포넌트
 */
@Service
@RequiredArgsConstructor
public class FlameTrialsService implements FlameTrialsProvider {

  private final FlameDpCalculator dpCalculator;

  @Override
  public Double calculateExpectedTrials(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int target,
      int baseAtt,
      int baseMag) {
    return dpCalculator.calculateExpectedTrials(
        category, flameType, level, weights, target, baseAtt, baseMag);
  }
}
