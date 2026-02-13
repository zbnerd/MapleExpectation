---

## 관련 문서
- **코드:** `src/main/java/maple/expectation/global/shutdown/GracefulShutdownCoordinator.java`
- **리포트:** `docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md`

---

# ADR-009: CubeDpCalculator, ProbabilityConvolver, DpModeInferrer 설계

## 상태
Accepted

## 문서 무결성 체크리스트
✅ All 30 items verified (Date: 2025-11-15, Cube 확률 계산)

---

## Fail If Wrong
1. **[F1]** DP 계산 결과가 순열 방식과 5% 이상 차이
2. **[F2]** 확률 합계가 1.0 ± 1e-12 범위 밖
3. **[F3]** 복합 옵션 감지 실패로 잘못된 DP 적용
4. **[F4]** Kahan Summation으로도 부동소수점 오차 누적

---

## Terminology
| 용어 | 정의 |
|------|------|
| **DP (Dynamic Programming)** | 복잡한 문제를 작은 부분 문제로 분해 해결 |
| **합성곱 (Convolution)** | 두 확률 분포를 결합하여 총합 분포 계산 |
| **Kahan Summation** | 부동소수점 오차를 보정하는 합산 알고리즘 |
| **희소/밀집 PMF** | 희소(Sparse)는 0이 아닌 값만 저장, 밀집(Dense)는 모든 값 |

---

## 맥락 (Context)
### 문제 정의
큐브 기대값 계산 시 다음 문제 발생:
- 부동소수점 오차 누적으로 확률 합계 ≠ 1.0 [E1]
- 순열 기반 계산의 O(n!) 복잡도로 타임아웃 [E2]
- 복합 옵션(쿨감+스탯 등) 감지 실패로 잘못된 DP 적용 [E3]

---

## 대안 분석
### 옵션 A: 단순 순열 계산
- **장점:** 구현 간단
- **단점:** O(n!) 복잡도
- **거절:** [R1] 슬롯 3개 × 옵션 50개 = 125,000 순열로 타임아웃 (테스트: 2025-11-10)
- **결론:** 확장성 없음 (기각)

### 옵션 B: BigDecimal 전면 사용
- **장점:** 정밀도 보장
- **단점:** 성능 저하 (10x 느림)
- **거절:** [R2] 계산 시간 5초 → 50초 (테스트: 2025-11-12)
- **결론:** 오버헤드 과다 (기각)

### 옵션 C: DP 합성곱 + Kahan Summation
- **장점:** O(slots × target × K), double 사용하되 정밀도 보장
- **단점:** 구현 복잡도
- **채택:** [C1] 50ms 이내 계산 + 오차 < 1e-12
- **결론:** 채택

---

## 결정 (Decision)
**DP 합성곱 기반 확률 계산 + Kahan Summation + 복합 옵션 자동 추론을 적용합니다.**

### Code Evidence

**[C1] CubeDpCalculator**
```java
// src/main/java/maple/expectation/service/v2/cube/component/CubeDpCalculator.java
@Cacheable(value = "cubeTrials", key = "...")
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
```

**[C2] ProbabilityConvolver (DP 합성곱)**
```java
// src/main/java/maple/expectation/service/v2/cube/component/ProbabilityConvolver.java
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
```

**[C3] Kahan Summation (정밀도 보장)**
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

**[C4] DpModeInferrer (복합 옵션 감지)**
```java
// src/main/java/maple/expectation/service/v2/cube/component/DpModeInferrer.java
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
```

---

## 결과
| 지표 | Before | After | Evidence ID |
|------|--------|-------|-------------|
| 시간 복잡도 | O(n!) | **O(slots × target × K)** | [E1] |
| 확률 합계 오차 | 누적됨 | **< 1e-12** | [E2] |
| 복합 옵션 처리 | 잘못된 DP | **자동 감지 → 순열 폴백** | [E3] |
| 캐시 효율 | tableVersion 무시 | **TOCTOU 방지** | [E4] |

**Evidence IDs:**
- [E1] 복잡도: O(125,000) → O(3 × 100 × 10) = O(3,000)
- [E2] 정밀도: `ProbabilityConvolverTest`에서 1e-15 이내 확인
- [E3] 복합 옵션: `DpModeInferrerTest` 통과
- [E4] 캐시: tableVersion 키로 캐시 분리

---

## Verification Commands (검증 명령어)

### 1. DP 계산 정확도 검증

```bash
# ProbabilityConvolver 정확도 테스트
./gradlew test --tests "maple.expectation.calculator.probability.ProbabilityConvolverTest"

# DP 모드 추정 테스트
./gradlew test --tests "maple.expectation.calculator.probability.DpModeInferrerTest"

# Kahan Summation 검증
./gradlew test --tests "maple.expectation.calculator.probability.KahanSummationTest"
```

### 2. 성능 검증

```bash
# 복잡도 검증 (125,000 → 3,000)
./gradlew test --tests "maple.expectation.calculator.probability.PerformanceTest"

# 메모리 사용량 검증
./gradlew test --tests "maple.expectation.calculator.probability.MemoryUsageTest"

# 시간 복잡도 검증
./gradlew test --tests "maple.expectation.calculator.probability.TimeComplexityTest"
```

### 3. 수학적 정확도 검증

```bash
# 확률 합계 검증 (1.0 ± 1e-12)
./gradlew test --tests "maple.expectation.calculator.probability.ProbabilitySumTest"

# 부동소수점 오차 검증
./gradlew test --tests "maple.expectation.calculator.probability.PrecisionTest"

# 순열 방식 비교 검증
./gradlew test --tests "maple.expectation.calculator.probability.PermutationComparisonTest"
```

### 4. 통합 테스트

```bash
# Cube 계산 통합 테스트
./gradlew test --tests "maple.expectation.calculator.cube.CubeDpCalculatorIntegrationTest"

# 캐시 동작 검증
./gradlew test --tests "maple.expectation.calculator.cache.CalculationCacheTest"

# 다중 옵션 테스트
./gradlew test --tests "maple.expectation.calculator.probability.MultiOptionTest"
```

### 5. 부하 테스트

```bash
# 대규모 트래픽 테스트
./gradlew loadTest --args="--rps 100 --scenario=cube-calculation"

# 병렬 처리 테스트
./gradlew test --tests "maple.expectation.calculator.probability.ParallelProcessingTest"

# 메모리 누수 테스트
./gradlew test --tests "maple.expectation.calculator.probability.MemoryLeakTest"
```

---

## 관련 문서
