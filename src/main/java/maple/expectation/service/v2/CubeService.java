package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment; // import 추가
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import maple.expectation.util.PermutationUtil;
import maple.expectation.util.StatParser; // import 추가
import org.springframework.stereotype.Service;

import java.util.Arrays; // import 추가
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CubeService {

    private final CubeRateCalculator rateCalculator;
    private static final long CUBE_PRICE = 50_000_000;

    // 🏆 여기가 핵심: 계산된 비용을 저장하는 캐시 (메모이제이션)
    private final Map<String, Long> calculationCache = new ConcurrentHashMap<>();

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
     * [핵심 로직] 순열 조합을 통한 기댓값 계산 (캐싱 적용)
     */
    public long calculateExpectedCost(CubeCalculationInput input) {
        // 1. 캐시 키 생성 (비용 계산의 유니크한 조건들)
        String cacheKey = generateCacheKey(input);

        // 2. 이미 풀어본 문제라면? 정답지에서 바로 리턴 (0.00001초 소요)
        if (calculationCache.containsKey(cacheKey)) {
            return calculationCache.get(cacheKey);
        }

        // --- 여기서부터는 처음 보는 문제일 때만 실행됨 (CPU 사용) ---

        // 3. 순열 생성
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(input.getOptions());

        double totalProbability = 0.0;

        for (List<String> caseOptions : permutations) {
            double caseProb = 1.0;
            // 각 줄의 확률 조회
            caseProb *= rateCalculator.getOptionRate(input.getLevel(), input.getPart(), input.getGrade(), 1, caseOptions.get(0));
            caseProb *= rateCalculator.getOptionRate(input.getLevel(), input.getPart(), input.getGrade(), 2, caseOptions.get(1));
            caseProb *= rateCalculator.getOptionRate(input.getLevel(), input.getPart(), input.getGrade(), 3, caseOptions.get(2));

            totalProbability += caseProb;
        }

        long resultCost = 0;
        if (totalProbability > 0) {
            long expectedTryCount = (long) (1.0 / totalProbability);
            resultCost = expectedTryCount * CUBE_PRICE;
        }

        // 4. 고생해서 푼 답을 정답지에 기록
        calculationCache.put(cacheKey, resultCost);

        return resultCost;
    }

    // 캐시 키 생성 메서드
    private String generateCacheKey(CubeCalculationInput input) {
        // 예: "160_모자_레전드리_[STR : +12%, STR : +9%, DEX : +9%]"
        return input.getLevel() + "_" +
                input.getPart() + "_" +
                input.getGrade() + "_" +
                input.getOptions().toString();
    }
}