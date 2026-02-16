package maple.expectation.core.calculator;

import java.util.List;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;
import maple.expectation.core.domain.stat.StatType;

/**
 * 큐브 옵션 확률 계산 컴포넌트
 *
 * <p>특정 큐브 종류와 슬롯에 해당 옵션이 뜰 확률을 반환합니다.
 */
public class CubeRateCalculator {

  /**
   * 특정 큐브 종류와 슬롯에 해당 옵션이 뜰 확률을 반환합니다.
   *
   * @param type 큐브 종류
   * @param level 큐브 레벨
   * @param part 장비 부위
   * @param grade 등급
   * @param slot 슬롯 번호
   * @param optionName 옵션 이름
   * @param rates 큐브 확률 데이터 리스트 (Port를 통해 조회)
   * @return 확률 (0.0 ~ 1.0)
   */
  public double getOptionRate(
      CubeType type,
      int level,
      String part,
      String grade,
      int slot,
      String optionName,
      List<CubeRate> rates) {
    if (optionName == null || optionName.isBlank()) {
      return 1.0;
    }

    // 1. 유효 옵션인지 확인 (StatType 활용)
    // P0 Fix: findType()은 non-percent 타입만 검색하여 보공/크뎀 등이 UNKNOWN 처리됨
    // findTypeWithUnit()으로 변경하여 퍼센트 스탯도 올바르게 매칭
    StatType statType = StatType.findTypeWithUnit(optionName);

    // UNKNOWN(잡옵)이면 계산 무시 (확률 1.0)
    if (statType == StatType.UNKNOWN) {
      return 1.0;
    }

    // 2. 전달받은 확률 데이터에서 옵션 이름으로 필터링
    return rates.stream()
        .filter(r -> r.optionName().equals(optionName))
        .findFirst()
        .map(CubeRate::rate)
        .orElse(0.0); // 데이터에 없는 옵션이면 0% (불가능)
  }
}
