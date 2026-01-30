package maple.expectation.service.v2.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.global.error.exception.UnsupportedCalculationEngineException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.CubeProbabilityRepository;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import maple.expectation.service.v2.cube.component.CubeDpCalculator;
import maple.expectation.service.v2.cube.component.DpModeInferrer;
import maple.expectation.service.v2.cube.config.CubeEngineFeatureFlag;
import maple.expectation.util.PermutationUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 큐브 기대값 계산 서비스
 *
 * <h3>두 가지 엔진 지원</h3>
 * <ul>
 *   <li><b>v1 (순열)</b>: 기존 PermutationUtil 기반 O(N!) 방식</li>
 *   <li><b>v2 (DP)</b>: 새로운 Convolution 기반 O(slots × target × K) 방식</li>
 * </ul>
 *
 * <h3>Feature Flag 전환</h3>
 * <ul>
 *   <li>dpEnabled=false: v1 결과 반환 (shadow=true면 v2 비교 로깅)</li>
 *   <li>dpEnabled=true: v2 결과 반환 (shadow=true면 v1 drift 모니터링)</li>
 * </ul>
 */
@Slf4j
@Service("cubeServiceImpl")
@RequiredArgsConstructor
public class CubeServiceImpl implements CubeTrialsProvider {

    private final CubeRateCalculator rateCalculator;
    private final CubeDpCalculator dpCalculator;
    private final CubeProbabilityRepository repository;
    private final CubeEngineFeatureFlag featureFlag;
    private final LogicExecutor executor;
    private final DpModeInferrer dpModeInferrer;

    @Override
    public Double calculateExpectedTrials(CubeCalculationInput input, CubeType type) {
        // 1. 이미 DP 모드가 명시적으로 설정된 경우 → Feature Flag에 따라 분기
        if (input.isDpMode()) {
            // P2 Fix (PR #159 Codex 지적): dpEnabled=false면 fail fast
            // v1 엔진은 DP 입력(minTotal 등 누적 확률)을 처리할 수 없음
            if (!featureFlag.isDpEnabled()) {
                log.warn("[CubeService] DP 모드 요청이지만 dpEnabled=false. input={}", input);
                throw new UnsupportedCalculationEngineException();
            }
            return calculateWithDpEngine(input, type);
        }

        // 2. DP 모드가 아닌 경우 → 자동 추론 시도
        boolean inferred = dpModeInferrer.applyDpFields(input);

        if (inferred) {
            // 추론 성공: DP 엔진으로 누적 확률 계산 (Feature Flag 무시)
            // 이유: 자동 추론의 목적 자체가 "X% 이상" 계산이므로 DP 필수
            log.info("[CubeService] DP 모드 자동 추론 성공: {} {}% 이상",
                    input.getTargetStatType(), input.getMinTotal());
            return calculateWithDpEngine(input, type);
        }

        // 3. 추론 실패: 기존 v1 엔진으로 처리
        return calculateWithV1Engine(input, type);
    }

    /**
     * DP 엔진 활성 (flag ON)
     * - v2 결과 반환
     * - shadow=true면 v1도 계산하여 drift 모니터링
     */
    private Double calculateWithDpEngine(CubeCalculationInput input, CubeType type) {
        String tableVersion = repository.getCurrentTableVersion();

        Double v2Result = executor.execute(
                () -> dpCalculator.calculateWithCache(input, type, tableVersion),
                TaskContext.of("CubeService", "CalculateDP", input.getTargetStatType().name())
        );

        if (featureFlag.isShadowEnabled()) {
            Double v1Result = calculateWithV1Engine(input, type);
            logDrift("V2_ACTIVE", v1Result, v2Result, input, tableVersion);
        }

        return v2Result;
    }

    /**
     * v1 엔진 활성 (flag OFF)
     * - v1 결과 반환
     * - shadow=true면 v2도 계산하여 비교 로깅
     */
    private Double calculateWithV1AndShadow(CubeCalculationInput input, CubeType type) {
        Double v1Result = calculateWithV1Engine(input, type);

        if (featureFlag.isShadowEnabled()) {
            String tableVersion = repository.getCurrentTableVersion();
            Double v2Result = executor.executeOrDefault(
                    () -> dpCalculator.calculateWithCache(input, type, tableVersion),
                    null,
                    TaskContext.of("CubeService", "ShadowDP", input.getTargetStatType().name())
            );
            if (v2Result != null) {
                logDrift("V1_ACTIVE", v1Result, v2Result, input, tableVersion);
            }
        }

        return v1Result;
    }

    /**
     * v1 엔진: 순열 기반 계산 (기존 로직)
     */
    private Double calculateWithV1Engine(CubeCalculationInput input, CubeType type) {
        if (!input.isReady()) {
            return 0.0;
        }

        return executor.execute(
                () -> doCalculateV1(input, type),
                TaskContext.of("CubeService", "CalculateV1", type.name())
        );
    }

    /**
     * v1 기대 시도 횟수 계산 (순열 기반)
     *
     * <p>반환값: raw 1/p (v2와 동일한 의미)</p>
     * <p>p=0 → +∞ (v2와 의미 통일)</p>
     * <p>UI 반올림은 Controller/View 레이어에서 처리</p>
     */
    private Double doCalculateV1(CubeCalculationInput input, CubeType type) {
        List<String> targetOptions = new ArrayList<>(input.getOptions());
        Set<List<String>> permutations = PermutationUtil.generateUniquePermutations(targetOptions);
        double totalProbability = 0.0;

        for (List<String> caseOptions : permutations) {
            double caseProb = calculateCaseProbability(input, type, caseOptions);
            totalProbability += caseProb;
        }

        // P0: raw 1/p 반환 (v2와 의미 통일)
        // p=0 → +∞ (불가능한 조합 = 무한대 시도 필요)
        return (totalProbability > 0) ? (1.0 / totalProbability) : Double.POSITIVE_INFINITY;
    }

    private double calculateCaseProbability(CubeCalculationInput input, CubeType type, List<String> caseOptions) {
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
        return caseProb;
    }

    private void logDrift(String mode, Double v1, Double v2, CubeCalculationInput input, String tableVersion) {
        if (v1 == null || v2 == null) {
            log.warn("[CubeEngine:{}] drift 계산 불가: v1={}, v2={}", mode, v1, v2);
            return;
        }
        double drift = Math.abs(v1 - v2);
        log.info("[CubeEngine:{}] v1={}, v2={}, drift={}, target={}, tableVersion={}",
                mode, v1, v2, drift, input.getTargetStatType(), tableVersion);
    }
}
