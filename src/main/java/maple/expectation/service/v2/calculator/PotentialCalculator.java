package maple.expectation.service.v2.calculator;

import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.util.StatParser;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class PotentialCalculator {

    /**
     * 아이템의 "윗잠(잠재능력)" 3줄을 분석해서 합산 결과를 반환합니다.
     * 결과 예시: { STR: 21, ALL_STAT: 6, ... }
     */
    public Map<StatType, Integer> calculateMainPotential(ItemEquipment item) {
        // EnumMap은 키가 Enum일 때 성능이 아주 빠릅니다.
        Map<StatType, Integer> result = new EnumMap<>(StatType.class);

        // 잠재 1, 2, 3줄 분석 및 합산
        accumulateStat(result, item.getPotentialOption1());
        accumulateStat(result, item.getPotentialOption2());
        accumulateStat(result, item.getPotentialOption3());

        return result;
    }

    /**
     * 아이템의 "에디(에디셔널)" 3줄을 분석해서 합산 결과를 반환합니다.
     */
    public Map<StatType, Integer> calculateAdditionalPotential(ItemEquipment item) {
        Map<StatType, Integer> result = new EnumMap<>(StatType.class);

        accumulateStat(result, item.getAdditionalPotentialOption1());
        accumulateStat(result, item.getAdditionalPotentialOption2());
        accumulateStat(result, item.getAdditionalPotentialOption3());

        return result;
    }

    /**
     * 특정 스탯의 "최종 수치"를 계산합니다. (올스탯 포함)
     * 예: getEffectiveStat(stats, StatType.STR) -> STR값 + 올스탯값 반환
     */
    public int getEffectiveStat(Map<StatType, Integer> stats, StatType type) {
        // 1. 해당 스탯의 값 (없으면 0)
        int rawValue = stats.getOrDefault(type, 0);

        // 2. 올스탯 값 (없으면 0)
        int allStatValue = stats.getOrDefault(StatType.ALL_STAT, 0);

        // 3. 두 값을 더해서 반환 (단, 요청한 타입이 ALL_STAT이면 중복 더하기 방지)
        if (type == StatType.ALL_STAT) {
            return allStatValue;
        }

        return rawValue + allStatValue;
    }

    // 헬퍼 메서드: 옵션 문자열을 분석해서 Map에 더하기
    private void accumulateStat(Map<StatType, Integer> map, String optionStr) {
        if (optionStr == null || optionStr.isEmpty()) return;

        // 1. 무슨 스탯인지 판별 (예: "STR")
        StatType type = StatType.findType(optionStr);
        
        // 2. 수치 추출 (예: 12)
        int value = StatParser.parseNum(optionStr);

        // 3. 알 수 없는 옵션이나 수치가 0이면 무시
        if (type == StatType.UNKNOWN || value == 0) return;

        // 4. Map에 누적 (기존 값 + 새 값)
        map.merge(type, value, Integer::sum);
    }
}