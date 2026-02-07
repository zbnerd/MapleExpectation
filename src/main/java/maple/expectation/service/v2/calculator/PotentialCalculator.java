package maple.expectation.service.v2.calculator;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.util.StatParser;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Component;

/** 잠재능력 수치 계산기 (LogicExecutor 및 평탄화 적용) */
@Slf4j
@Component
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
    StatType type = StatType.findType(optionStr);

    // ✅ [해결] 주입받은 statParser 인스턴스를 통해 호출
    int value = statParser.parseNum(optionStr);

    if (type != StatType.UNKNOWN && value != 0) {
      map.merge(type, value, Integer::sum);
    }
  }
}
