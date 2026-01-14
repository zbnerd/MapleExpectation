# DP Calculator 시퀀스 다이어그램

## 개요

동적 프로그래밍(DP)으로 큐브 기대값을 계산합니다. 확률 조합 및 기대 횟수 계산에 BigDecimal을 사용하여 정밀도를 보장합니다.

## 전체 계산 시퀀스

```mermaid
sequenceDiagram
    participant CT as Controller
    participant SV as EquipmentService
    participant EP as EquipmentStreamingParser
    participant FC as ExpectationCalculatorFactory
    participant CS as CubeServiceImpl
    participant DI as DpModeInferrer
    participant RC as CubeRateCalculator
    participant DP as CubeDpCalculator
    participant PC as ProbabilityConvolver
    participant RP as CubeProbabilityRepository

    CT->>SV: calculateTotalExpectationAsync(ign)
    SV->>EP: parseCubeInputs(equipmentData)
    EP-->>SV: List<CubeCalculationInput>

    loop 각 장비 아이템
        SV->>FC: createBlackCubeCalculator(input)
        FC-->>SV: ExpectationCalculator

        SV->>CS: calculateExpectedTrials(input, BLACK)

        CS->>DI: applyDpFields(input)
        Note over DI: DP 모드 결정<br/>(Normal/Weighted)
        DI-->>CS: enrichedInput

        CS->>RC: getOptionRate(cubeType, level, part, grade, line, option)
        RC->>RP: findProbabilities(...)
        RP-->>RC: Map<String, BigDecimal>
        RC-->>CS: BigDecimal rate

        CS->>DP: calculateWithCache(input, cubeType, tableVersion)
        DP->>PC: convolve(probabilities, targetSum)
        Note over PC: O(slots × target × K)
        PC-->>DP: BigDecimal expectedTrials
        DP-->>CS: expectedTrials

        CS-->>SV: expectedTrials
        SV->>SV: 기대 비용 = 횟수 × 큐브 가격
    end

    SV-->>CT: TotalExpectationResponse
```

## DpModeInferrer

```mermaid
sequenceDiagram
    participant CS as CubeServiceImpl
    participant DI as DpModeInferrer
    participant IN as CubeCalculationInput

    CS->>DI: applyDpFields(input)

    DI->>IN: getOptions()
    Note over DI: 옵션 분석

    alt 단일 옵션 타입 (예: LUK +12%, LUK +12%, LUK +9%)
        Note over DI: NORMAL 모드
        DI->>IN: setDpMode(NORMAL)
        DI->>IN: setTargetSum(33)
        Note over IN: 12 + 12 + 9 = 33
    else 복합 옵션 타입 (예: 보스 데미지 + 공격력)
        Note over DI: WEIGHTED 모드
        DI->>IN: setDpMode(WEIGHTED)
        DI->>IN: setWeights(weightMap)
    end

    DI-->>CS: enrichedInput
```

## ProbabilityConvolver (핵심 DP)

```mermaid
sequenceDiagram
    participant DP as CubeDpCalculator
    participant PC as ProbabilityConvolver
    participant PMF as Pmf (확률 질량 함수)

    DP->>PC: convolve(lineProbabilities, targetSum)

    Note over PC: slots = 3 (잠재능력 3줄)
    Note over PC: target = 33 (목표 합계)

    loop i = 1 to slots
        PC->>PMF: convolve(current, line[i])
        Note over PMF: dp[j] = Σ dp[j-k] × prob[k]
        PMF-->>PC: convolvedPmf
    end

    PC->>PC: 1 - Σ P(X >= target)
    Note over PC: 기대 횟수 = 1 / P(success)

    PC-->>DP: BigDecimal expectedTrials
```

**복잡도:** O(slots × target × K)
- slots: 잠재능력 줄 수 (3)
- target: 목표 스탯 합계
- K: 각 줄의 가능한 옵션 수

## 확률 조회

```mermaid
sequenceDiagram
    participant RC as CubeRateCalculator
    participant RP as CubeProbabilityRepository
    participant CSV as CSV 데이터

    RC->>RP: findProbabilities(BLACK, 250, 모자, 레전드리, 1)
    RP->>CSV: get(key)
    Note over CSV: 34,488건 사전 로딩

    CSV-->>RP: Map<옵션명, 확률>
    RP-->>RC: {<br/>"LUK +12%": 0.0312,<br/>"LUK +9%": 0.0468,<br/>...}
```

## 정밀도 보장

### BigDecimal 사용

```java
// ❌ Bad (double 정밀도 손실)
double probability = 0.1 + 0.2;  // 0.30000000000000004

// ✅ Good (BigDecimal 정밀도)
BigDecimal probability = new BigDecimal("0.1")
    .add(new BigDecimal("0.2"));  // 0.3
```

### Kahan Summation

```java
// 부동소수점 합산 시 오차 누적 방지
BigDecimal sum = BigDecimal.ZERO;
BigDecimal c = BigDecimal.ZERO;  // 보정값

for (BigDecimal value : values) {
    BigDecimal y = value.subtract(c);
    BigDecimal t = sum.add(y);
    c = t.subtract(sum).subtract(y);
    sum = t;
}
```

## 캐시 키 구조

```
expectation:v3:{ocid}:{equipmentHash}:{tableVersionHash}:lv3
```

| 구성 요소 | 용도 |
|----------|------|
| ocid | 캐릭터 고유 ID |
| equipmentHash | 장비 상태 해시 |
| tableVersionHash | 확률 테이블 버전 |
| lv3 | 캐시 레벨 |

## E2E 테스트 결과

| 시나리오 | 결과 | 증거 |
|---------|------|------|
| DP-S01: 단일 옵션 확률 조회 | PASS | `CubeProbabilityRepository.findProbabilities (0 ms)` |
| DP-S03: 기대값 계산 정확성 | PASS | `totalCost: 96,588,685,000,000 메소` |
| DP Calculator 동작 | PASS | `CubeDpCalculator.calculateWithCache` 호출 확인 |

## API 응답 예시

```json
{
  "userIgn": "강은호",
  "totalCost": 96588685000000,
  "totalCostText": "96,588,685,000,000 메소",
  "items": [
    {
      "part": "모자",
      "itemName": "에테르넬 시프반다나",
      "potential": "스킬 재사용 대기시간 -2초 | 스킬 재사용 대기시간 -2초 | 스킬 재사용 대기시간 -2초",
      "expectedCost": 43076900000000,
      "expectedCount": 861538
    }
  ]
}
```

## 관련 파일

- `src/main/java/maple/expectation/service/v2/cube/component/CubeDpCalculator.java`
- `src/main/java/maple/expectation/service/v2/cube/component/ProbabilityConvolver.java`
- `src/main/java/maple/expectation/service/v2/cube/component/DpModeInferrer.java`
- `src/main/java/maple/expectation/repository/v2/CubeProbabilityRepository.java`
