package maple.expectation.service.v2.cube.component;

import java.util.List;
import lombok.RequiredArgsConstructor;
import maple.expectation.error.exception.ProbabilityInvariantException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v2.cube.dto.DensePmf;
import maple.expectation.service.v2.cube.dto.SparsePmf;
import org.springframework.stereotype.Component;

/**
 * DP 합성곱 기반 확률 계산 컴포넌트
 *
 * <h3>핵심 가정 (이 가정이 틀리면 결과도 틀림)</h3>
 *
 * <ul>
 *   <li>각 슬롯(라인)은 독립적으로 옵션을 추첨한다
 *   <li>슬롯 간 추첨은 독립이다 (조건부 확률 아님)
 *   <li>같은 옵션이 여러 슬롯에 중복 등장 가능하다
 * </ul>
 *
 * <h3>타입 분리 설계</h3>
 *
 * <ul>
 *   <li>입력: List&lt;SparsePmf&gt; (희소, K가 작음)
 *   <li>출력: DensePmf (밀집, 인덱스=값)
 * </ul>
 *
 * <h3>Tail Clamp 전략</h3>
 *
 * <ul>
 *   <li>인덱스는 0..target
 *   <li>합이 target 초과 시 모두 target 버킷에 누적
 *   <li>결과적으로 O(slots × target × K) 보장
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProbabilityConvolver {

  private final LogicExecutor executor;

  private static final double MASS_TOLERANCE = 1e-12;
  private static final double NEGATIVE_TOLERANCE = -1e-15;

  /**
   * 슬롯 SparsePmf들을 합성하여 총합 DensePmf 생성
   *
   * <p>사후조건:
   *
   * <ul>
   *   <li>질량 보존: Σ=1 ± MASS_TOLERANCE
   *   <li>NaN/Inf 없음
   *   <li>enableTailClamp=true면 상태 크기 = target+1
   * </ul>
   *
   * @param slotPmfs 슬롯별 SparsePmf 리스트
   * @param target 목표 합계
   * @param enableTailClamp Tail Clamp 활성화 여부
   * @return 합성된 DensePmf
   * @throws ProbabilityInvariantException 불변식 위반 시
   */
  public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
    return executor.execute(
        () -> doConvolveWithClamp(slotPmfs, target, enableTailClamp),
        TaskContext.of("Convolver", "ConvolveAll", "target=" + target));
  }

  private DensePmf doConvolveWithClamp(
      List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
    int maxIndex = enableTailClamp ? target : calculateMaxSum(slotPmfs);
    double[] acc = initializeAccumulator(maxIndex);

    for (SparsePmf slot : slotPmfs) {
      acc = convolveSlot(acc, slot, maxIndex);
    }

    DensePmf result = DensePmf.fromArray(acc);
    validateInvariants(result);
    return result;
  }

  private double[] initializeAccumulator(int maxIndex) {
    double[] acc = new double[maxIndex + 1];
    acc[0] = 1.0; // 초기 상태: 합=0일 확률 100%
    return acc;
  }

  private double[] convolveSlot(double[] acc, SparsePmf slot, int maxIndex) {
    double[] next = new double[maxIndex + 1];

    for (int i = 0; i <= maxIndex; i++) {
      if (acc[i] == 0) continue;
      accumulateSlotContributions(acc, slot, next, i, maxIndex);
    }

    return next;
  }

  private void accumulateSlotContributions(
      double[] acc, SparsePmf slot, double[] next, int currentIndex, int maxIndex) {
    for (int k = 0; k < slot.size(); k++) {
      int value = slot.valueAt(k);
      double prob = slot.probAt(k);

      // P2 Fix (PR #159 Codex 지적): 음수 contribution 가드
      // 상위 파서/추출기 버그 시 ArrayIndexOutOfBoundsException 방지
      if (value < 0) {
        throw new ProbabilityInvariantException(
            "음수 contribution 감지: value=" + value + " (slot index=" + k + ")");
      }

      int targetIndex = Math.min(currentIndex + value, maxIndex); // Tail Clamp
      next[targetIndex] += acc[currentIndex] * prob;
    }
  }

  private int calculateMaxSum(List<SparsePmf> slotPmfs) {
    return slotPmfs.stream().mapToInt(SparsePmf::maxValue).sum();
  }

  /**
   * DensePmf 불변식 검증
   *
   * <p>DoD 1e-12 기준 충족을 위해 Kahan summation 사용
   *
   * @param pmf 검증 대상
   * @throws ProbabilityInvariantException 불변식 위반 시
   */
  private void validateInvariants(DensePmf pmf) {
    double sum = pmf.totalMassKahan();
    if (Math.abs(sum - 1.0) > MASS_TOLERANCE) {
      throw new ProbabilityInvariantException("질량 보존 위반: Σp=" + sum);
    }
    if (pmf.hasNegative(NEGATIVE_TOLERANCE)) {
      throw new ProbabilityInvariantException("음수 확률 감지");
    }
    if (pmf.hasNaNOrInf()) {
      throw new ProbabilityInvariantException("NaN/Inf 감지");
    }
    if (pmf.hasValueExceedingOne()) {
      throw new ProbabilityInvariantException("확률 > 1 감지");
    }
  }
}
