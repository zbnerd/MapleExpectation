package maple.expectation.service.v2.calculator;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.repository.v2.CubeProbabilityRepository;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CubeRateCalculator {

    private final CubeProbabilityRepository probabilityRepository;

    /**
     * 특정 슬롯(1,2,3번째 줄)에 해당 옵션이 뜰 확률을 반환합니다.
     * - 유효하지 않은 옵션(잡옵)인 경우 1.0(100%)을 반환하여 전체 확률 곱셈에 영향을 주지 않도록 처리함.
     */
    public double getOptionRate(int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) {
            return 1.0;
        }

        // 1. 유효 옵션인지 확인 (StatType 활용)
        StatType type = StatType.findType(optionName);

        // UNKNOWN(잡옵)이면 계산 무시 (확률 1.0)
        if (type == StatType.UNKNOWN) {
            return 1.0;
        }

        // 2. CSV 리포지토리에서 확률 조회
        return probabilityRepository.findProbabilities(level, part, grade, slot).stream()
                .filter(p -> p.getOptionName().equals(optionName))
                .findFirst()
                .map(CubeProbability::getRate)
                .orElse(0.0); // 데이터에 없는 옵션이면 0% (불가능)
    }
}