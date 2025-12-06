package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import maple.expectation.util.PermutationUtil;
import maple.expectation.util.StatParser;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CubeService {

    private final CubeRateCalculator rateCalculator; // 복잡한 확률 계산은 얘가 담당

    // TODO: 추후 CubePolicy 등으로 분리하여 관리 추천
    private static final long CUBE_PRICE = 50_000_000;

    /**
     * [V2 호환용] ItemEquipment -> DTO 변환 후 계산
     */
    public long calculateExpectedCost(ItemEquipment item) {
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

        return calculateExpectedCost(input);
    }

    /**
     * [핵심 로직] 순열 조합을 통한 기댓값 계산
     */
    public long calculateExpectedCost(CubeCalculationInput input) {
        // 1. 순열 생성 (옵션 순서 섞기)
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(input.getOptions());

        double totalProbability = 0.0;

        // 2. 각 케이스별 확률 계산
        for (List<String> caseOptions : permutations) {
            double caseProb = 1.0;

            // 1, 2, 3번째 줄 각각의 확률을 가져와서 곱함 (Calculator에게 위임)
            caseProb *= rateCalculator.getOptionRate(input.getLevel(), input.getPart(), input.getGrade(), 1, caseOptions.get(0));
            caseProb *= rateCalculator.getOptionRate(input.getLevel(), input.getPart(), input.getGrade(), 2, caseOptions.get(1));
            caseProb *= rateCalculator.getOptionRate(input.getLevel(), input.getPart(), input.getGrade(), 3, caseOptions.get(2));

            totalProbability += caseProb;
        }

        // 3. 비용 산출 (기대 횟수 * 1회 가격)
        if (totalProbability == 0) return 0;

        long expectedTryCount = (long) (1.0 / totalProbability);

        return expectedTryCount * CUBE_PRICE;
    }
}