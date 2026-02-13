package maple.expectation.service.v2.cube.component;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Component;

/**
 * 옵션 리스트에서 DP 모드 필드를 자동 추론하는 컴포넌트
 *
 * <h3>동작 방식</h3>
 *
 * <ol>
 *   <li>옵션 3줄에서 주요 스탯 타입별 기여도 합산
 *   <li>가장 높은 기여도를 가진 스탯을 targetStatType으로 선택
 *   <li>해당 스탯의 합계를 minTotal로 설정
 * </ol>
 *
 * <h3>올스탯 처리</h3>
 *
 * <p>올스탯 9%는 STR/DEX/INT/LUK 각각에 9%씩 기여
 *
 * <h3>지원 스탯 타입</h3>
 *
 * <ul>
 *   <li>STR_PERCENT, DEX_PERCENT, INT_PERCENT, LUK_PERCENT
 *   <li>ATTACK_POWER_PERCENT, MAGIC_POWER_PERCENT
 *   <li>BOSS_DAMAGE, IGNORE_DEFENSE, CRITICAL_DAMAGE
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DpModeInferrer {

  private final StatValueExtractor statValueExtractor;
  private final LogicExecutor executor;

  /**
   * DP 모드 추론 결과
   *
   * @param targetStatType 목표 스탯 타입 (null이면 DP 불가)
   * @param minTotal 목표 합계
   * @param confidence 추론 신뢰도 (0.0 ~ 1.0)
   */
  public record InferenceResult(StatType targetStatType, int minTotal, double confidence) {
    public boolean isValid() {
      return targetStatType != null && targetStatType != StatType.UNKNOWN && minTotal > 0;
    }
  }

  /**
   * 옵션 리스트에서 DP 필드 추론
   *
   * @param options 옵션 3줄 리스트
   * @return 추론 결과 (valid하지 않으면 DP 모드 불가)
   */
  public InferenceResult infer(List<String> options) {
    if (options == null || options.isEmpty()) {
      return new InferenceResult(null, 0, 0.0);
    }

    // 스탯 타입별 기여도 합산
    Map<StatType, Integer> contributions = new EnumMap<>(StatType.class);

    for (String option : options) {
      if (option == null || option.isBlank()) continue;

      List<StatValueExtractor.StatContribution> extracted = extractSafely(option);
      for (StatValueExtractor.StatContribution c : extracted) {
        // 개별 스탯 (STR_PERCENT 등)에 직접 기여
        contributions.merge(c.type(), c.value(), Integer::sum);

        // ALLSTAT_PERCENT는 4개 스탯에 각각 기여
        if (c.type() == StatType.ALLSTAT_PERCENT) {
          contributions.merge(StatType.STR_PERCENT, c.value(), Integer::sum);
          contributions.merge(StatType.DEX_PERCENT, c.value(), Integer::sum);
          contributions.merge(StatType.INT_PERCENT, c.value(), Integer::sum);
          contributions.merge(StatType.LUK_PERCENT, c.value(), Integer::sum);
        }
      }
    }

    // ALLSTAT_PERCENT 자체는 target으로 선택하지 않음 (개별 스탯으로 환산됨)
    contributions.remove(StatType.ALLSTAT_PERCENT);

    if (contributions.isEmpty()) {
      return new InferenceResult(null, 0, 0.0);
    }

    // P1-5 Fix: CLAUDE.md Section 4 - Optional Chaining Best Practice
    // .orElse(null) 대신 Optional 패턴으로 안전하게 처리
    return contributions.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .filter(best -> best.getValue() > 0)
        .map(best -> createInferenceResult(best, contributions))
        .orElse(new InferenceResult(null, 0, 0.0));
  }

  /** 추론 결과 생성 (Optional 체이닝에서 분리) */
  private InferenceResult createInferenceResult(
      Map.Entry<StatType, Integer> best, Map<StatType, Integer> contributions) {

    // 신뢰도 계산: 선택된 스탯이 전체 기여도에서 차지하는 비율
    int totalContribution = contributions.values().stream().mapToInt(Integer::intValue).sum();
    double confidence = (double) best.getValue() / totalContribution;

    log.debug(
        "[DpModeInferrer] 추론 결과: targetStatType={}, minTotal={}, confidence={}",
        best.getKey(),
        best.getValue(),
        confidence);

    return new InferenceResult(best.getKey(), best.getValue(), confidence);
  }

  /**
   * CubeCalculationInput에 DP 필드 자동 설정
   *
   * @param input 계산 입력 (수정됨)
   * @return DP 모드 활성화 여부
   */
  public boolean applyDpFields(CubeCalculationInput input) {
    if (input == null || input.getOptions() == null) {
      return false;
    }

    // 이미 DP 모드가 설정되어 있으면 건드리지 않음
    if (input.isDpMode()) {
      return true;
    }

    // DP 모드는 part/grade/level 필수 — 없으면 v1 엔진 폴백
    if (input.getPart() == null || input.getGrade() == null || input.getLevel() <= 0) {
      return false;
    }

    // #240 V4: 복합 옵션 감지 (쿨감+스탯, 보공+스탯, 크뎀+스탯 등)
    // 서로 다른 유효 카테고리가 2개 이상이면 DP 모드 비활성화 → v1 순열 계산
    if (isCompoundOption(input.getOptions())) {
      log.debug("[DpModeInferrer] 복합 옵션 감지, DP 모드 비활성화: {}", input.getItemName());
      return false;
    }

    InferenceResult result = infer(input.getOptions());

    if (!result.isValid()) {
      log.debug("[DpModeInferrer] DP 모드 불가: {}", input.getItemName());
      return false;
    }

    // 신뢰도가 낮으면 (복합 옵션 등) DP 모드 적용하지 않음
    // 예: 공격력 + 보공 혼합 → 단일 target으로 표현 불가
    if (result.confidence() < 0.5) {
      log.debug(
          "[DpModeInferrer] 신뢰도 낮음 ({}), DP 모드 미적용: {}", result.confidence(), input.getItemName());
      return false;
    }

    input.setTargetStatType(result.targetStatType());
    input.setMinTotal(result.minTotal());

    log.debug(
        "[DpModeInferrer] DP 모드 적용: {} -> {}% 이상", result.targetStatType(), result.minTotal());

    return true;
  }

  /**
   * 복합 옵션 여부 판별 (#240 V4)
   *
   * <p>서로 다른 유효 카테고리(STAT, BOSS_IED, ATK_MAG, CRIT_DMG, COOLDOWN)가 2개 이상 존재하면 복합 옵션으로 판정합니다.
   *
   * <h3>복합 옵션 예시</h3>
   *
   * <ul>
   *   <li>쿨감 + 스탯: "스킬 재사용 대기시간 -2초 | STR +12%"
   *   <li>보공 + 스탯: "보스 데미지 +40% | STR +12%"
   *   <li>크뎀 + 스탯: "크리티컬 데미지 +8% | DEX +12%"
   *   <li>보공 + 방무: "보스 데미지 +40% | 방어율 무시 +35%"
   * </ul>
   *
   * @param options 옵션 3줄 리스트
   * @return 복합 옵션이면 true
   */
  private boolean isCompoundOption(List<String> options) {
    if (options == null || options.isEmpty()) {
      return false;
    }

    Set<StatType.OptionCategory> categories = new HashSet<>();

    for (String option : options) {
      if (option == null || option.isBlank()) continue;

      List<StatType> types = StatType.findAllTypesOrEmpty(option);
      for (StatType type : types) {
        if (type.isValidCategory()) {
          categories.add(type.getCategory());
        }
      }
    }

    // 유효 카테고리가 2개 이상이면 복합 옵션
    boolean isCompound = categories.size() >= 2;
    if (isCompound) {
      log.debug("[DpModeInferrer] 복합 옵션 카테고리 감지: {}", categories);
    }
    return isCompound;
  }

  /**
   * 안전한 기여도 추출 (예외 발생 시 빈 리스트 반환)
   *
   * <p>CLAUDE.md Section 12: LogicExecutor 패턴 적용
   */
  private List<StatValueExtractor.StatContribution> extractSafely(String option) {
    return executor.executeOrDefault(
        () -> statValueExtractor.extractAll(option),
        List.of(),
        TaskContext.of("DpModeInferrer", "ExtractSafely", option));
  }
}
