package maple.expectation.service.v2.calculator;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.stat.StatParser;
import maple.expectation.core.domain.stat.StatType;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse.ItemEquipment;
import org.springframework.stereotype.Component;

/** 잠재능력 수치 계산기 (LogicExecutor 및 평탄화 적용) */
@Slf4j
@Component("potentialCalculatorV2")
@RequiredArgsConstructor // ✅ StatParser와 LogicExecutor 주입
public class PotentialCalculator {

  private final StatParser statParser; // ✅ Bean 주입 (static 호출 제거)
  private final LogicExecutor executor;

  /** "윗잠(잠재능력)" 합산 결과 반환 */
  public Map<StatType, Integer> calculateMainPotential(ItemEquipment item) {
    TaskContext context = TaskContext.of("Calculator", "MainPotential", item.getItemName());

    // [패턴 1] execute를 사용하여 계산 과정을 모니터링
    return executor.execute(
        () ->
            this.sumOptions(
                Stream.of(
                    item.getPotentialOption1(),
                    item.getPotentialOption2(),
                    item.getPotentialOption3())),
        context);
  }

  /** "에디(에디셔널)" 합산 결과 반환 */
  public Map<StatType, Integer> calculateAdditionalPotential(ItemEquipment item) {
    TaskContext context = TaskContext.of("Calculator", "AddPotential", item.getItemName());

    return executor.execute(
        () ->
            this.sumOptions(
                Stream.of(
                    item.getAdditionalPotentialOption1(),
                    item.getAdditionalPotentialOption2(),
                    item.getAdditionalPotentialOption3())),
        context);
  }

  /** 특정 스탯의 "최종 수치" 계산 (올스탯 포함) */
  public int getEffectiveStat(Map<StatType, Integer> stats, StatType type) {
    if (type == StatType.ALL_STAT) {
      return stats.getOrDefault(StatType.ALL_STAT, 0);
    }
    return stats.getOrDefault(type, 0) + stats.getOrDefault(StatType.ALL_STAT, 0);
  }

  /** 🚀 평탄화: 반복적인 accumulateStat 호출을 Stream으로 통합 */
  private Map<StatType, Integer> sumOptions(Stream<String> options) {
    Map<StatType, Integer> result = new EnumMap<>(StatType.class);

    options
        .filter(Objects::nonNull)
        .filter(opt -> !opt.isEmpty())
        .forEach(opt -> this.accumulateStat(result, opt));

    return result;
  }

  private void accumulateStat(Map<StatType, Integer> map, String optionStr) {
    // findTypeWithUnit()을 사용하여 퍼센트 스탯도 올바르게 매칭
    StatType type = StatType.findTypeWithUnit(optionStr);

    // 퍼센트 스탯 타입을 기본 타입으로 변환 (STR_PERCENT -> STR, ALLSTAT_PERCENT -> ALL_STAT)
    // 이렇게 하면 getEffectiveStat()에서 올바르게 합산 가능
    StatType baseType = convertToBaseType(type);

    // ✅ [해결] 주입받은 statParser 인스턴스를 통해 호출
    int value = statParser.parseNum(optionStr);

    if (baseType != StatType.UNKNOWN && value != 0) {
      map.merge(baseType, value, Integer::sum);
    }
  }

  /**
   * 퍼센트 스탯 타입을 기본 타입으로 변환
   *
   * <p>STR_PERCENT -> STR, DEX_PERCENT -> DEX, ALLSTAT_PERCENT -> ALL_STAT
   *
   * <p>이렇게 하면 잠재능력 계산 시 퍼센트/플랫 구분 없이 합산 가능
   */
  private StatType convertToBaseType(StatType type) {
    if (type == null || type == StatType.UNKNOWN) {
      return StatType.UNKNOWN;
    }

    // 퍼센트 타입을 기본 타입으로 변환
    return switch (type) {
      case STR_PERCENT, DEX_PERCENT, INT_PERCENT, LUK_PERCENT -> {
        String keyword = type.getKeyword();
        // 같은 키워드를 가진 기본 타입 찾기 (STR, DEX, INT, LUK)
        yield java.util.Arrays.stream(StatType.values())
            .filter(t -> t.getKeyword().equals(keyword) && !t.isPercent())
            .findFirst()
            .orElse(StatType.UNKNOWN);
      }
      case ALLSTAT_PERCENT -> StatType.ALL_STAT;
      case ATTACK_POWER_PERCENT -> StatType.ATTACK_POWER;
      case MAGIC_POWER_PERCENT -> StatType.MAGIC_POWER;
      case HP_PERCENT -> StatType.HP;
      default -> type; // 그 외는 그대로 반환 (BOSS_DAMAGE, IGNORE_DEFENSE 등)
    };
  }
}
