package maple.expectation.service.v2.cube.component;

import java.util.List;
import lombok.RequiredArgsConstructor;
import maple.expectation.global.error.exception.OptionParseException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.util.StatParser;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Component;

/**
 * 옵션 문자열에서 스탯 기여도를 추출하는 컴포넌트
 *
 * <h3>정책 B 적용</h3>
 *
 * <ul>
 *   <li>직접 매칭: STR +12% → targetStatType=STR_PERCENT면 기여 12
 *   <li>ALLSTAT 기여: 올스탯 +9% → targetStatType=STR_PERCENT면 기여 9
 *   <li>불일치: DEX +12% → targetStatType=STR_PERCENT면 기여 0
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StatValueExtractor {

  private final StatParser statParser;
  private final LogicExecutor executor;

  /**
   * 스탯 기여 정보
   *
   * @param type 스탯 타입 (단위 포함)
   * @param value 기여값 (12% → 12)
   */
  public record StatContribution(StatType type, int value) {}

  /**
   * 옵션 문자열에서 모든 기여를 추출 (복합 옵션 대응)
   *
   * <p>예시:
   *
   * <ul>
   *   <li>"STR/DEX +6%" → [StatContribution(STR_PERCENT, 6), StatContribution(DEX_PERCENT, 6)]
   *   <li>"올스탯 +9%" → [StatContribution(ALLSTAT_PERCENT, 9)]
   * </ul>
   *
   * @param optionName 옵션 문자열
   * @return 기여 정보 리스트
   * @throws OptionParseException 파싱 불가 시
   */
  public List<StatContribution> extractAll(String optionName) {
    return executor.executeWithTranslation(
        () -> doExtractAll(optionName),
        (e, context) -> new OptionParseException("파싱 실패: " + optionName, e),
        TaskContext.of("StatExtractor", "ExtractAll", optionName));
  }

  /**
   * 모든 기여 추출 (프록 옵션 필터링 + Primary stat drift 감지)
   *
   * <p>P0-1: findAllTypesOrEmpty() 사용 → 프록 옵션은 빈 리스트
   *
   * <p>P0-2: Primary stat처럼 보이는데 매칭 실패 → Fail-Fast
   */
  private List<StatContribution> doExtractAll(String optionName) {
    List<StatType> types = StatType.findAllTypesOrEmpty(optionName);

    // P0-2: Primary stat drift 감지 (Fail-Fast)
    // STR/DEX/INT/LUK/올스탯처럼 보이는데 매칭 실패 → 데이터 드리프트 가능성
    if (types.isEmpty() && StatType.looksLikePrimaryStat(optionName)) {
      throw new OptionParseException("Primary stat drift 감지: " + optionName);
    }

    // P0-1: 비타깃 옵션(프록 등)은 빈 리스트 → 기여도 0
    if (types.isEmpty()) {
      return List.of();
    }

    int value = statParser.parseNum(optionName);
    return types.stream().map(type -> new StatContribution(type, value)).toList();
  }

  /**
   * 정책 B 기반: 타깃 스탯에 기여하는 값 추출
   *
   * <h3>기여 규칙</h3>
   *
   * <ul>
   *   <li>정확히 일치하는 타입 → 해당 값
   *   <li>ALLSTAT_PERCENT → 개별 스탯(STR_PERCENT 등)에 동일 값 기여
   *   <li>불일치 → 0
   * </ul>
   *
   * @param optionName 옵션 문자열
   * @param targetType 목표 스탯 타입
   * @return 기여값 (기여 없으면 0)
   */
  public int extractContributionFor(String optionName, StatType targetType) {
    List<StatContribution> contributions = extractAll(optionName);

    // 1. 직접 매칭
    for (StatContribution c : contributions) {
      if (c.type() == targetType) {
        return c.value();
      }
    }

    // 2. ALLSTAT → 개별 스탯 기여 (정책 B)
    if (targetType.isIndividualStat()) {
      for (StatContribution c : contributions) {
        if (c.type() == StatType.ALLSTAT_PERCENT) {
          return c.value();
        }
      }
    }

    return 0; // 기여 없음
  }
}
