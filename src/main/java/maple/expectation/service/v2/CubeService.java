package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import maple.expectation.util.PermutationUtil;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@TraceLog
@RequiredArgsConstructor
public class CubeService {

    private final CubeRateCalculator rateCalculator;
    private static final long CUBE_PRICE = 50_000_000;

    // 계산 캐시 (Memoization)
    private final Map<String, Long> calculationCache = new ConcurrentHashMap<>();

    /**
     * 큐브 기대 비용 계산
     * 이제 ItemEquipment 같은 거대 DTO를 받지 않습니다.
     * 오직 계산에 필요한 POJO(CubeCalculationInput)만 받습니다.
     */
    public long calculateExpectedCost(CubeCalculationInput input) {
        // 방어 로직: 계산할 필요 없는 경우
        if (!input.isReady()) {
            return 0L;
        }

        String cacheKey = generateCacheKey(input);
        List<String> targetOptions = new ArrayList<>(input.getOptions());



        // Cache Hit
        if (calculationCache.containsKey(cacheKey)) {
            return calculationCache.get(cacheKey);
        }

        // Cache Miss -> Calculate
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(targetOptions);
        double totalProbability = 0.0;

        for (List<String> caseOptions : permutations) {
            double caseProb = 1.0;
            // Loop unrolling or simple iteration
            for (int i = 0; i < 3; i++) {
                caseProb *= rateCalculator.getOptionRate(
                        input.getLevel(),
                        input.getPart(),
                        input.getGrade(),
                        i + 1, // line number (1, 2, 3)
                        caseOptions.get(i)
                );
            }
            totalProbability += caseProb;
        }

        long resultCost = 0;
        if (totalProbability > 0) {
            long expectedTryCount = (long) (1.0 / totalProbability);
            resultCost = expectedTryCount * CUBE_PRICE;
        }

        calculationCache.put(cacheKey, resultCost);
        return resultCost;
    }

    private String generateCacheKey(CubeCalculationInput input) {
        return input.getLevel() + "_" + input.getPart() + "_" + input.getGrade() + "_" + input.getOptions();
    }
}