package maple.expectation.service.v2.cube.component;

import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.probability.ProbabilityConvolver;
import maple.expectation.core.probability.TailProbabilityCalculator;
import maple.expectation.domain.model.calculator.DensePmf;
import maple.expectation.domain.model.calculator.SparsePmf;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * DP 기반 큐브 기대값 계산 컴포넌트
 *
 * <h3>핵심 역할</h3>
 *
 * <p>@Cacheable self-invocation 방지를 위한 별도 Bean으로 분리하여 Spring 프록시 적용
 *
 * <h3>캐시 키 구성</h3>
 *
 * <ul>
 *   <li>type + level + part + grade + targetStatType + minTotal + enableTailClamp + tableVersion
 *   <li>tableVersion 포함으로 테이블 갱신 시 과거 확률 캐시 방지
 * </ul>
 *
 * <h3>핵심 가정 (이 가정이 틀리면 결과도 틀림)</h3>
 *
 * <ul>
 *   <li>각 슬롯(라인)은 독립적으로 옵션을 추첨한다
 *   <li>슬롯 간 추첨은 독립이다 (조건부 확률 아님)
 *   <li>같은 옵션이 여러 슬롯에 중복 등장 가능하다
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CubeDpCalculator {

  private final SlotDistributionBuilder distributionBuilder;
  private final ProbabilityConvolver convolver;
  private final TailProbabilityCalculator tailCalculator;
  private final CubeSlotCountResolver slotCountResolver;

  /**
   * DP 기반 기대 시도 횟수 계산 (캐시 적용)
   *
   * <p>캐시 키에 tableVersion이 포함되어 테이블 갱신 시 과거 확률 캐시를 방지합니다.
   *
   * @param input 계산 입력 (DP 모드 필드 필수)
   * @param type 큐브 타입
   * @param tableVersion 테이블 버전 (TOCTOU 방지)
   * @return 기대 시도 횟수
   * @throws IllegalArgumentException DP 필수 필드 누락 시
   */
  @Cacheable(
      value = "cubeTrials",
      key =
          "#type.name() + ':' + #input.level + ':' + #input.part + ':' + "
              + "#input.grade + ':' + #input.targetStatType + ':' + #input.minTotal + ':' + "
              + "#input.enableTailClamp + ':' + #tableVersion")
  public Double calculateWithCache(CubeCalculationInput input, CubeType type, String tableVersion) {
    input.validateForDpMode();
    return doCalculate(input, type, tableVersion);
  }

  private Double doCalculate(CubeCalculationInput input, CubeType type, String tableVersion) {
    boolean enableClamp = input.isEnableTailClamp();
    int target = input.getMinTotal();
    int slotCount = slotCountResolver.resolve(type);

    List<SparsePmf> slotPmfs = buildSlotDistributions(input, type, tableVersion, slotCount);
    DensePmf totalPmf = convolver.convolveAll(slotPmfs, target, enableClamp);
    double tailProb = tailCalculator.calculateTailProbability(totalPmf, target, enableClamp);

    return tailCalculator.calculateExpectedTrials(tailProb);
  }

  private List<SparsePmf> buildSlotDistributions(
      CubeCalculationInput input, CubeType type, String tableVersion, int slotCount) {
    return IntStream.rangeClosed(1, slotCount)
        .mapToObj(
            slot ->
                distributionBuilder.buildDistributionByVersion(
                    type,
                    input.getLevel(),
                    input.getPart(),
                    input.getGrade(),
                    slot,
                    input.getTargetStatType(),
                    tableVersion))
        .toList();
  }
}
