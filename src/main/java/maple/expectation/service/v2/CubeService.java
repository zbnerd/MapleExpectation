package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CubeType;
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

    // 계산 캐시 (Memoization): 이제 비용(Meso)이 아닌 '기대 시도 횟수(Trials)'를 캐싱합니다.
    private final Map<String, Long> trialsCache = new ConcurrentHashMap<>();

    /**
     * [Decorator용] 기대 시도 횟수 계산
     * @param input  강화 설정값
     * @param type   큐브 종류 (BLACK, RED 등)
     * @return       목표 옵션 도달을 위한 평균 시도 횟수
     */
    public long calculateExpectedTrials(CubeCalculationInput input, CubeType type) {
        // 1. 방어 로직
        if (!input.isReady()) {
            return 0L;
        }

        // 2. 캐시 확인 (Key에 CubeType 포함)
        String cacheKey = generateTrialsCacheKey(input, type);
        if (trialsCache.containsKey(cacheKey)) {
            return trialsCache.get(cacheKey);
        }

        // 3. 순열 기반 확률 계산 (기존 로직 유지)
        List<String> targetOptions = new ArrayList<>(input.getOptions());
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(targetOptions);
        double totalProbability = 0.0;

        for (List<String> caseOptions : permutations) {
            double caseProb = 1.0;
            for (int i = 0; i < 3; i++) {
                // 어떤 큐브인지(type)를 전달하여 정확한 확률 데이터 조회
                caseProb *= rateCalculator.getOptionRate(
                        type,
                        input.getLevel(),
                        input.getPart(),
                        input.getGrade(),
                        i + 1,
                        caseOptions.get(i)
                );
            }
            totalProbability += caseProb;
        }

        // 4. 기댓값 산출 (1 / 성공확률)
        long expectedTryCount = 0;
        if (totalProbability > 0) {
            expectedTryCount = (long) Math.ceil(1.0 / totalProbability);
        }

        // 5. 캐시 저장 후 반환
        trialsCache.put(cacheKey, expectedTryCount);
        return expectedTryCount;
    }

    /**
     * (V2 호환/레거시용) 기대 비용 계산
     * @deprecated 이제 Decorator와 Policy를 사용하는 calculateExpectedTrials 사용을 권장합니다.
     */
    @Deprecated
    public long calculateExpectedCost(CubeCalculationInput input) {
        // 기존 5천만 메소 고정 가격 정책 유지 (하위 호환용)
        long trials = calculateExpectedTrials(input, CubeType.BLACK);
        return trials * 50_000_000L;
    }

    private String generateTrialsCacheKey(CubeCalculationInput input, CubeType type) {
        return type.name() + "_" + input.getLevel() + "_" + input.getPart() + "_" + input.getGrade() + "_" + input.getOptions();
    }
}