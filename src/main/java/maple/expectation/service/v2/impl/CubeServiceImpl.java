package maple.expectation.service.v2.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import maple.expectation.util.PermutationUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service("cubeServiceImpl")
@TraceLog
@RequiredArgsConstructor
public class CubeServiceImpl implements CubeTrialsProvider {

    private final CubeRateCalculator rateCalculator;

    @Override
    public Long calculateExpectedTrials(CubeCalculationInput input, CubeType type) {
        if (!input.isReady()) {
            return 0L;
        }

        // 순열 기반 확률 계산 (기존 로직 유지)
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

        return (totalProbability > 0) ? (long) Math.ceil(1.0 / totalProbability) : 0L;
    }
}