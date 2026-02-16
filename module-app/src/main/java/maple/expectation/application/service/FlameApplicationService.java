package maple.expectation.application.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.flame.FlameEquipCategory;
import maple.expectation.core.domain.flame.FlameType;
import maple.expectation.core.probability.FlameDpCalculator;
import maple.expectation.core.probability.FlameScoreCalculator;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Service;

/**
 * 환생의 불꽃 계산 Application Service
 *
 * <p>Core의 FlameScoreCalculator, FlameDpCalculator를 조립하여 불꽃 기대값 계산 유스케이스 수행
 *
 * <p><b>아키텍처 계층 구조:</b>
 *
 * <pre>
 * App Layer (이 클래스)     : 유스케이스 조립, Flow 제어
 *    ↓ 사용
 * Core Layer (Calculator)   : 순수 계산 로직 (의존성 없음)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlameApplicationService {

  private final FlameScoreCalculator flameScoreCalculator;
  private final FlameDpCalculator flameDpCalculator;
  private final LogicExecutor executor;

  /**
   * 불꽃 옵션별 환산치 PMF 생성
   *
   * <p>각 옵션 종류의 1줄 환산치 확률질량함수(PMF)를 생성합니다.
   *
   * @param category 장비 분류 (무기, 방어구, 보스장비 등)
   * @param flameType 불꽃 종류 (RED, BRILLIANT)
   * @param level 장비 레벨
   * @param weights 직업 가중치 (주스탯, 부스탯, 공격력, 보공 등)
   * @param baseAtt 무기 기본 공격력 (무기 아닐 경우 0)
   * @param baseMag 무기 기본 마력 (무기 아닐 경우 0)
   * @return 옵션별 PMF 리스트 (Map&lt;환산치, 확률&gt;)
   */
  public List<Map<Integer, Double>> buildOptionPmfs(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int baseAtt,
      int baseMag) {
    return executor.executeOrDefault(
        () ->
            flameScoreCalculator.buildOptionPmfs(
                category, flameType, level, weights, baseAtt, baseMag),
        List.of(),
        TaskContext.of("FlameApplicationService", "BuildOptionPmfs", category.name()));
  }

  /**
   * 기대 시도 횟수 계산
   *
   * <p>목표 환산치를 달성하기 위한 기대 불꽃 시도 횟수를 계산합니다.
   *
   * @param category 장비 분류
   * @param flameType 불꽃 종류
   * @param level 장비 레벨
   * @param weights 직업 가중치
   * @param target 목표 환산치 (스케일 10 적용된 정수)
   * @param baseAtt 무기 기본 공격력 (무기 아닐 경우 0)
   * @param baseMag 무기 기본 마력 (무기 아닐 경우 0)
   * @param optionPmfs 옵션별 PMF 리스트 (buildOptionPmfs로 생성)
   * @return 기대 시도 횟수 (1/p), 불가능하면 null
   */
  public Double calculateExpectedTrials(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int target,
      int baseAtt,
      int baseMag,
      List<Map<Integer, Double>> optionPmfs) {
    return executor.executeOrDefault(
        () ->
            flameDpCalculator.calculateExpectedTrials(
                category, flameType, level, weights, target, baseAtt, baseMag, optionPmfs),
        null,
        TaskContext.of(
            "FlameApplicationService", "CalculateExpectedTrials", category.name() + ":" + target));
  }

  /**
   * 불꽃 계산 통합 예시
   *
   * <p>1. PMF 생성 2. 기대 시도 횟수 계산
   *
   * @param category 장비 분류
   * @param flameType 불꽃 종류
   * @param level 장비 레벨
   * @param weights 직업 가중치
   * @param target 목표 환산치
   * @param baseAtt 무기 기본 공격력
   * @param baseMag 무기 기본 마력
   * @return 기대 시도 횟수, 불가능하면 null
   */
  public Double calculateFinalExpectedTrials(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int target,
      int baseAtt,
      int baseMag) {
    return executor.executeOrDefault(
        () -> {
          // 1. PMF 생성 (Core)
          List<Map<Integer, Double>> pmfs =
              flameScoreCalculator.buildOptionPmfs(
                  category, flameType, level, weights, baseAtt, baseMag);

          // 2. 기대 시도 횟수 계산 (Core)
          return flameDpCalculator.calculateExpectedTrials(
              category, flameType, level, weights, target, baseAtt, baseMag, pmfs);
        },
        null,
        TaskContext.of(
            "FlameApplicationService",
            "CalculateFinalExpectedTrials",
            category.name() + ":" + target));
  }
}
