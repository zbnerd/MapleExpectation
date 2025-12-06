package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeProbability;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.repository.v2.CubeProbabilityRepository;
import maple.expectation.util.PermutationUtil;
import maple.expectation.util.StatParser;
import maple.expectation.util.StatType;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CubeService {

    private final CubeProbabilityRepository probabilityRepository;

    // TODO: 나중에 DB나 설정 파일에서 가져오도록 변경. 현재는 에테르넬 큐브 레전드리 재설정 기준
    private static final long CUBE_PRICE = 50_000_000; //

    /**
     * [기존 메서드 유지]
     * ItemEquipment 객체를 받아서 DTO로 변환 후 계산 메서드 호출
     * - 기존 테스트 코드나 로직 수정 없이 그대로 사용 가능
     */
    public long calculateExpectedCost(ItemEquipment item) {
        // 1. DTO로 변환 (Heavy Object -> Light DTO)
        CubeCalculationInput input = CubeCalculationInput.builder()
                .level(StatParser.parseNum(item.getBaseOption().getBaseEquipmentLevel()))
                .part(item.getItemEquipmentSlot())
                .grade(item.getPotentialOptionGrade())
                .options(Arrays.asList(
                        item.getPotentialOption1(),
                        item.getPotentialOption2(),
                        item.getPotentialOption3()
                ))
                .itemName(item.getItemName())
                .build();

        // 2. 실제 계산 로직 호출
        return calculateExpectedCost(input);
    }

/*    public long calculateTotalExpectedCost(CubeCalculationInput input) {

        return calculateExpectedCost(input);
    }

    public long calculateTotalExpectedCost(List<CubeCalculationInput> inputs) {
        long totalCost = 0;

        for (CubeCalculationInput input : inputs) {

            totalCost += calculateExpectedCost(input);
        }

        return totalCost;
    }*/

    /**
     * [신규 메서드] (핵심 로직 이동)
     * 가벼운 DTO를 받아서 실제 기대값을 계산
     * - 스트리밍 파서는 이 메서드를 직접 호출하여 메모리 낭비 방지
     */
    public long calculateExpectedCost(CubeCalculationInput input) {
        // 1. 순열 생성 (DTO의 options 사용)
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(input.getOptions());

        double totalProbability = 0.0;


        // 2. 확률 계산 로직 (기존 로직 그대로 이동)
        for (List<String> caseOptions : permutations) {
            double caseProb = 1.0;

            // DTO 필드 사용 (input.getLevel(), input.getPart() ...)
            caseProb *= findRateIfValid(input.getLevel(), input.getPart(), input.getGrade(), 1, caseOptions.get(0));
            caseProb *= findRateIfValid(input.getLevel(), input.getPart(), input.getGrade(), 2, caseOptions.get(1));
            caseProb *= findRateIfValid(input.getLevel(), input.getPart(), input.getGrade(), 3, caseOptions.get(2));

            totalProbability += caseProb;
        }

        if (totalProbability == 0) return 0;

        long expectedTryCount = (long) (1.0 / totalProbability);

/*        log.info("[기대값] {} | 조합 수: {} | 확률: {}% | 비용: {}억",
                input.getItemName(), permutations.size(),
                String.format("%.8f", totalProbability * 100),
                expectedCost / 100_000_000);*/

        return expectedTryCount * CUBE_PRICE;
    }

    private double findRate(int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) {
            return 1.0; // 옵션이 없으면 확률에 영향 없음 (x1)
        }

        // CSV 리포지토리에서 조건에 맞는 확률 검색
        // stream().filter()로 이름이 일치하는 것 찾기
        return probabilityRepository.findProbabilities(level, part, grade, slot).stream()
                .filter(p -> p.getOptionName().equals(optionName))
                .findFirst()
                .map(CubeProbability::getRate) // 0.0976 형태
                .orElse(0.0); // 데이터에 없으면 0% (불가능한 옵션)
    }

    // 💡 핵심 로직: 유효하지 않은 옵션(잡옵)이면 확률을 1.0(무시)으로 반환
    private double findRateIfValid(int level, String part, String grade, int slot, String optionName) {
        if (optionName == null || optionName.isBlank()) return 1.0;

        // 1. 유효 옵션인지 확인 (StatType 활용)
        StatType type = StatType.findType(optionName);

        // UNKNOWN(잡옵)이면 계산에서 제외 (확률 100%로 취급해서 곱하나 마나 하게 만듦)
        if (type == StatType.UNKNOWN) {
            return 1.0;
        }

        // 2. 유효 옵션이면 CSV에서 확률 조회
        return probabilityRepository.findProbabilities(level, part, grade, slot).stream()
                .filter(p -> p.getOptionName().equals(optionName))
                .findFirst()
                .map(CubeProbability::getRate)
                .orElse(0.0); // 데이터 오류 시 0
    }
}