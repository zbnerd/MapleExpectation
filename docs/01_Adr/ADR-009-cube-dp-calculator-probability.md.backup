# ADR-009: CubeDpCalculator, ProbabilityConvolver, DpModeInferrer 설계

## 상태
Accepted

## 맥락 (Context)

큐브 기대값 계산 시 다음 문제가 발생했습니다:

**관찰된 문제:**
- 부동소수점 오차 누적으로 확률 합계 ≠ 1.0
- 순열 기반 계산의 O(n!) 복잡도로 타임아웃
- 복합 옵션(쿨감+스탯 등) 감지 실패로 잘못된 DP 적용

**README 정의:**
> DP Calculator: Kahan Summation으로 부동소수점 정밀도 보장

## 검토한 대안 (Options Considered)

### 옵션 A: 단순 순열 계산
```java
// O(n!) - 3개 슬롯 × 50개 옵션 = 125,000 순열
for (각 순열) { 확률 계산 }
```
- 장점: 구현 간단
- 단점: 슬롯/옵션 증가 시 폭발
- **결론: 확장성 없음**

### 옵션 B: BigDecimal 전면 사용
```java
BigDecimal prob = new BigDecimal("0.05");
prob = prob.multiply(new BigDecimal("0.03"));
```
- 장점: 정밀도 보장
- 단점: 성능 저하 (10x 느림)
- **결론: 오버헤드 과다**

### 옵션 C: DP 합성곱 + Kahan Summation
- 장점: O(slots × target × K), double 사용하되 정밀도 보장
- 단점: 구현 복잡도
- **결론: 채택**

## 결정 (Decision)

**DP 합성곱 기반 확률 계산 + Kahan Summation + 복합 옵션 자동 추론을 적용합니다.**

### 1. CubeDpCalculator (캐시 적용)
```java
// maple.expectation.service.v2.cube.component.CubeDpCalculator
@Component
public class CubeDpCalculator {

    private final SlotDistributionBuilder distributionBuilder;
    private final ProbabilityConvolver convolver;
    private final TailProbabilityCalculator tailCalculator;

    @Cacheable(
        value = "cubeTrials",
        key = "#type.name() + ':' + #input.level + ':' + #input.part + ':' + " +
              "#input.grade + ':' + #input.targetStatType + ':' + #input.minTotal + ':' + " +
              "#input.enableTailClamp + ':' + #tableVersion"
    )
    public Double calculateWithCache(CubeCalculationInput input, CubeType type, String tableVersion) {
        input.validateForDpMode();
        return doCalculate(input, type, tableVersion);
    }

    private Double doCalculate(CubeCalculationInput input, CubeType type, String tableVersion) {
        int target = input.getMinTotal();
        int slotCount = slotCountResolver.resolve(type);

        // 1. 슬롯별 확률 분포 생성
        List<SparsePmf> slotPmfs = buildSlotDistributions(input, type, tableVersion, slotCount);

        // 2. 합성곱으로 총합 분포 계산
        DensePmf totalPmf = convolver.convolveAll(slotPmfs, target, enableClamp);

        // 3. 꼬리 확률 → 기대 시도 횟수
        double tailProb = tailCalculator.calculateTailProbability(totalPmf, target, enableClamp);
        return tailCalculator.calculateExpectedTrials(tailProb);
    }
}
```

### 2. ProbabilityConvolver (DP 합성곱)
```java
// maple.expectation.service.v2.cube.component.ProbabilityConvolver
@Component
public class ProbabilityConvolver {

    private static final double MASS_TOLERANCE = 1e-12;

    public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
        return executor.execute(
            () -> doConvolveWithClamp(slotPmfs, target, enableTailClamp),
            TaskContext.of("Convolver", "ConvolveAll", "target=" + target)
        );
    }

    private double[] convolveSlot(double[] acc, SparsePmf slot, int maxIndex) {
        double[] next = new double[maxIndex + 1];

        for (int i = 0; i <= maxIndex; i++) {
            if (acc[i] == 0) continue;
            for (int k = 0; k < slot.size(); k++) {
                int value = slot.valueAt(k);
                double prob = slot.probAt(k);

                // Tail Clamp: target 초과 시 모두 target 버킷에 누적
                int targetIndex = Math.min(i + value, maxIndex);
                next[targetIndex] += acc[i] * prob;
            }
        }
        return next;
    }
}
```

### 3. Kahan Summation (정밀도 보장)
```java
// maple.expectation.service.v2.cube.dto.DensePmf
public double totalMassKahan() {
    double sum = 0.0, c = 0.0;  // Kahan Summation
    for (double value : probabilities) {
        double y = value - c;
        double t = sum + y;
        c = (t - sum) - y;  // 오차 보정
        sum = t;
    }
    return sum;
}
```

### 4. 불변식 검증
```java
private void validateInvariants(DensePmf pmf) {
    double sum = pmf.totalMassKahan();

    // 질량 보존: Σ=1 ± 1e-12
    if (Math.abs(sum - 1.0) > MASS_TOLERANCE) {
        throw new ProbabilityInvariantException("질량 보존 위반: Σp=" + sum);
    }

    // 음수 확률 감지
    if (pmf.hasNegative(NEGATIVE_TOLERANCE)) {
        throw new ProbabilityInvariantException("음수 확률 감지");
    }

    // NaN/Inf 감지
    if (pmf.hasNaNOrInf()) {
        throw new ProbabilityInvariantException("NaN/Inf 감지");
    }
}
```

### 5. DpModeInferrer (복합 옵션 감지)
```java
// maple.expectation.service.v2.cube.component.DpModeInferrer
@Component
public class DpModeInferrer {

    /**
     * 복합 옵션 여부 판별 (#240 V4)
     *
     * 서로 다른 유효 카테고리가 2개 이상이면 DP 모드 비활성화 → v1 순열 계산
     *
     * 복합 옵션 예시:
     * - 쿨감 + 스탯: "스킬 재사용 대기시간 -2초 | STR +12%"
     * - 보공 + 스탯: "보스 데미지 +40% | STR +12%"
     */
    private boolean isCompoundOption(List<String> options) {
        Set<StatType.OptionCategory> categories = new HashSet<>();

        for (String option : options) {
            List<StatType> types = StatType.findAllTypesOrEmpty(option);
            for (StatType type : types) {
                if (type.isValidCategory()) {
                    categories.add(type.getCategory());
                }
            }
        }

        // 유효 카테고리가 2개 이상이면 복합 옵션
        return categories.size() >= 2;
    }

    public boolean applyDpFields(CubeCalculationInput input) {
        // 복합 옵션이면 DP 비활성화
        if (isCompoundOption(input.getOptions())) {
            log.debug("[DpModeInferrer] 복합 옵션 감지, DP 모드 비활성화");
            return false;
        }

        InferenceResult result = infer(input.getOptions());

        // 신뢰도가 낮으면 DP 모드 미적용
        if (result.confidence() < 0.5) {
            return false;
        }

        input.setTargetStatType(result.targetStatType());
        input.setMinTotal(result.minTotal());
        return true;
    }
}
```

### 6. 타입 분리 설계
```
입력: List<SparsePmf> (희소, K가 작음)
  ↓
[합성곱]
  ↓
출력: DensePmf (밀집, 인덱스=값)
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| 시간 복잡도 | O(n!) | **O(slots × target × K)** |
| 확률 합계 오차 | 누적됨 | **< 1e-12** |
| 복합 옵션 처리 | 잘못된 DP | **자동 감지 → 순열 폴백** |
| 캐시 효율 | tableVersion 무시 | **TOCTOU 방지** |

## 참고 자료
- `maple.expectation.service.v2.cube.component.CubeDpCalculator`
- `maple.expectation.service.v2.cube.component.ProbabilityConvolver`
- `maple.expectation.service.v2.cube.component.DpModeInferrer`
- `maple.expectation.service.v2.cube.dto.DensePmf`
- `maple.expectation.service.v2.cube.dto.SparsePmf`
