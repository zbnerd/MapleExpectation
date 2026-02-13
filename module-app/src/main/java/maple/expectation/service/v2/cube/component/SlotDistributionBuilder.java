package maple.expectation.service.v2.cube.component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.repository.CubeProbabilityRepository;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.error.exception.ProbabilityInvariantException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v2.cube.config.TableMassConfig;
import maple.expectation.service.v2.cube.dto.SparsePmf;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Component;

/**
 * 슬롯별 SparsePmf 분포 생성 컴포넌트
 *
 * <h3>핵심 책임</h3>
 *
 * <ul>
 *   <li>확률 테이블에서 슬롯별 분포 추출
 *   <li>테이블 버전 고정 (TOCTOU 방지)
 *   <li>질량 검증 및 정규화
 * </ul>
 *
 * <h3>불변식 (사후조건)</h3>
 *
 * <ul>
 *   <li>Σ probs = 1 ± MASS_TOLERANCE
 *   <li>모든 probs ∈ [0, 1]
 *   <li>values는 0 포함 (타깃 스탯 아닌 옵션)
 * </ul>
 *
 * <h3>안 A 채택</h3>
 *
 * <p>루프에서 contribution=0 포함 전체 merge → 추가 0버킷 보정 불필요
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotDistributionBuilder {

  private final CubeProbabilityRepository repository;
  private final StatValueExtractor extractor;
  private final LogicExecutor executor;
  private final TableMassConfig tableMassConfig;

  private static final double MASS_TOLERANCE = 1e-12;
  private static final double NEGATIVE_TOLERANCE = -1e-15;

  /**
   * 슬롯별 SparsePmf 생성 (버전 고정, TOCTOU 방지)
   *
   * <p>테이블 버전을 고정하여 계산 중 테이블 변경으로 인한 불일치를 방지합니다.
   *
   * @param cubeType 큐브 종류
   * @param level 장비 레벨
   * @param part 장비 부위
   * @param grade 잠재능력 등급
   * @param slot 슬롯 번호 (1, 2, 3)
   * @param targetStat 목표 스탯 타입
   * @param tableVersion 테이블 버전 (TOCTOU 방지)
   * @return 검증된 SparsePmf
   * @throws ProbabilityInvariantException 불변식 위반 시
   */
  public SparsePmf buildDistributionByVersion(
      CubeType cubeType,
      int level,
      String part,
      String grade,
      int slot,
      StatType targetStat,
      String tableVersion) {
    return executor.execute(
        () -> doBuildAndValidate(cubeType, level, part, grade, slot, targetStat, tableVersion),
        TaskContext.of("DistBuilder", "Build", targetStat.name()));
  }

  private SparsePmf doBuildAndValidate(
      CubeType cubeType,
      int level,
      String part,
      String grade,
      int slot,
      StatType targetStat,
      String tableVersion) {
    // P0-2: 버전 고정 조회 (TOCTOU 방지)
    List<CubeProbability> probs =
        repository.findProbabilitiesByVersion(cubeType, level, part, grade, slot, tableVersion);

    // P0-3: 빈 테이블 = 해당 부위에서 해당 스탯 미지원 → 기여도 0 확률 100% 분포 반환
    if (probs.isEmpty()) {
      log.warn(
          "[DistBuilder] 데이터 없음: cubeType={}, level={}, part={}, grade={}, slot={}, stat={} → 기여도 0 분포 반환",
          cubeType,
          level,
          part,
          grade,
          slot,
          targetStat);
      return SparsePmf.fromMap(Map.of(0, 1.0));
    }

    // 1. 전체 질량 계산 + 정책에 따른 정규화 계수
    double allTotal = calculateTotalMass(probs);
    double normFactor = validateAndGetNormalizationFactor(allTotal);

    // 2. 모든 옵션 집계 (contribution=0 포함, LENIENT면 정규화)
    //    안 A 채택: 루프에서 0 포함 전체 merge → 추가 0버킷 보정 불필요
    Map<Integer, Double> dist = buildDistributionMap(probs, targetStat, normFactor);

    // 주의: 추가 0버킷 보정 없음 (이중 계상 방지)
    // dist는 이미 전체 질량(allTotal)을 포함

    // 3. 불변식 검증 후 반환
    SparsePmf pmf = SparsePmf.fromMap(dist);
    validatePmfInvariants(pmf);
    return pmf;
  }

  private double calculateTotalMass(List<CubeProbability> probs) {
    double sum = 0.0;
    double c = 0.0; // Kahan summation 보정
    for (CubeProbability p : probs) {
      double y = p.getRate() - c;
      double t = sum + y;
      c = (t - sum) - y;
      sum = t;
    }
    return sum;
  }

  private Map<Integer, Double> buildDistributionMap(
      List<CubeProbability> probs, StatType targetStat, double normFactor) {
    Map<Integer, Double> dist = new HashMap<>();

    for (CubeProbability p : probs) {
      int contribution = extractor.extractContributionFor(p.getOptionName(), targetStat);
      // P0: LENIENT 시 rate / normFactor로 정규화
      double normalizedRate = p.getRate() / normFactor;
      dist.merge(contribution, normalizedRate, Double::sum);
    }

    return dist;
  }

  /**
   * 테이블 질량 검증 (정책에 따라 분기)
   *
   * @param allTotal 전체 질량
   * @return LENIENT 시 정규화 계수 (STRICT면 항상 1.0)
   * @throws ProbabilityInvariantException STRICT 정책에서 질량 불일치 시
   */
  private double validateAndGetNormalizationFactor(double allTotal) {
    // 빈 테이블/필터 결과 없음: 즉시 예외 (정책 무관)
    if (allTotal == 0.0) {
      throw new ProbabilityInvariantException("테이블 비어있음: Σp=0");
    }

    double deviation = Math.abs(allTotal - 1.0);
    if (deviation <= MASS_TOLERANCE) {
      return 1.0; // 정상 범위
    }

    // 정책에 따라 분기 (내부 enum 참조)
    if (tableMassConfig.getPolicy() == TableMassConfig.TableMassPolicy.STRICT) {
      throw new ProbabilityInvariantException("테이블 질량 불일치 (STRICT): Σp=" + allTotal);
    }

    // LENIENT: 경고 로그 + 정규화 계수 반환
    log.warn("테이블 질량 불일치 (LENIENT 정규화): Σp={} → 1.0", allTotal);
    return allTotal; // 호출측에서 rate / normFactor로 정규화
  }

  /**
   * PMF 불변식 검증
   *
   * <p>DoD 1e-12 기준 충족을 위해 Kahan summation 사용
   *
   * @param pmf 검증 대상
   * @throws ProbabilityInvariantException 불변식 위반 시
   */
  private void validatePmfInvariants(SparsePmf pmf) {
    // P0: Kahan summation으로 1e-12 기준 검증 (단순 누적합은 거짓 실패 가능)
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
