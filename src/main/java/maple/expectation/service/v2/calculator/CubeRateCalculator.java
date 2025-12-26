package maple.expectation.service.v2.calculator;

import lombok.RequiredArgsConstructor;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.repository.v2.CubeProbabilityRepository;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CubeRateCalculator {

    private final CubeProbabilityRepository probabilityRepository;

    /**
     * 특정 큐브 종류와 슬롯에 해당 옵션이 뜰 확률을 반환합니다.
     * ✅ 수정: 첫 번째 인자로 CubeType type을 추가했습니다.
     */
    public double getOptionRate(CubeType type, int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) {
            return 1.0;
        }

        // 1. 유효 옵션인지 확인 (StatType 활용)
        StatType statType = StatType.findType(optionName);

        // UNKNOWN(잡옵)이면 계산 무시 (확률 1.0)
        if (statType == StatType.UNKNOWN) {
            return 1.0;
        }

        // 2. CSV 리포지토리에서 큐브 종류별 확률 조회
        // ✅ 수정: probabilityRepository 호출 시 type을 함께 전달합니다.
        return probabilityRepository.findProbabilities(type, level, part, grade, slot).stream()
                .filter(p -> p.getOptionName().equals(optionName))
                .findFirst()
                .map(CubeProbability::getRate)
                .orElse(0.0); // 데이터에 없는 옵션이면 0% (불가능)
    }
}