package maple.expectation.service.v2.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import maple.expectation.util.PermutationUtil;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service("cubeServiceImpl")
@RequiredArgsConstructor
public class CubeServiceImpl implements CubeTrialsProvider {

    private final CubeRateCalculator rateCalculator;

    @Override
    // 🚀 [핵심 수정] 반환 타입을 Double로 변경합니다. (Jackson의 자동 형변환 에러 방지)
    @Cacheable(value = "cubeTrials", key = "#type.name() + '_' + #input.level + '_' + #input.part + '_' + #input.grade + '_' + #input.options")
    public Double calculateExpectedTrials(CubeCalculationInput input, CubeType type) {
        if (!input.isReady()) {
            return 0.0;
        }

        List<String> targetOptions = new ArrayList<>(input.getOptions());
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(targetOptions);
        double totalProbability = 0.0;

        for (List<String> caseOptions : permutations) {
            double caseProb = 1.0;
            for (int i = 0; i < 3; i++) {
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


        if (totalProbability > 0) {
            return Math.ceil(1.0 / totalProbability);
        }

        return 0.0;
    }
}