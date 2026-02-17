# 07. High Precision Computing: 부동소수점과 정밀 계산의 심화 학습

> **"컴퓨터는 0.1 + 0.2 = 0.30000000000000004라고 생각합니다. 당신이 '정확히 계산해'라고 말할 때까지, 그것은 당신의 착각입니다."**

---

## 1. The Problem (본질: 왜 부동소수점은 오차가 있는가?)

### 1.1 IEEE 754 부동소수점의 근본적 한계

**이진 분수의 문제:**

```
10진수 0.1을 2진수로 변환:

0.1 × 2 = 0.2  → 0 (소수점 아래 0)
0.2 × 2 = 0.4  → 0 (0)
0.4 × 2 = 0.8  → 0 (0)
0.8 × 2 = 1.6  → 1 (1)  ← 첫 번째 1
0.6 × 2 = 1.2  → 1 (1)
0.2 × 2 = 0.4  → 0 (0)
0.4 × 2 = 0.8  → 0 (0)
0.8 × 2 = 1.6  → 1 (1)
... 무한 반복 (순환 소수)

결과: 0.0001100110011001100110011... (무한 소수)
문제: 64비트에 담을 수 없음 → 잘림 발생 → 오차
```

**Java에서의 증명:**

```java
public class PrecisionTest {
    public static void main(String[] args) {
        double a = 0.1;
        double b = 0.2;
        double c = a + b;

        System.out.println(c);  // 0.30000000000000004 (NOT 0.3!)
        System.out.println(c == 0.3);  // false
    }
}
```

### 1.2 Catastrophic Cancellation (재앙적 상쇄)

**상황**: 비슷한 크기의 두 수를 뺄 때

```java
double x = 123456789.01234567;
double y = 123456789.01234500;
double z = x - y;  // 0.00000067

// 실제 저장되는 값:
x = 123456789.01234567  → 123456789.012345669 (53비트로 표현)
y = 123456789.01234500  → 123456789.012344997
z = 0.000000672        → 0.000000672000000... (유효 숫자 3개만 남음)
```

**문제의 본질**:
- 유효 숫자가 15자리에서 3자리로 급감
- 상대 오차가 0.00000001%에서 0.1%로 1000만 배 증가

### 1.3 MapleStory 장비 강화 시뮬레이션의 정밀도 요구

**상황:** 15성 무기 강화 시도

```java
// 단순 합계 (Naive Summation)
double totalCost = 0;
for (int i = 0; i < 1000; i++) {
    totalCost += enhanceCost[i];  // 1억 메소 × 1,000번
}

// 문제: 1,000억 메소 → 1,000,000,000,000.0012 (오차 발생!)
// 사용자: "왜 1.2메소가 더 청구됐어?" 😡
```

**필요한 정밀도:**
- **금전(금액)**: 절대 오차 0 메소 (100% 정확)
- **확률(%)**: 상대 오차 0.01% 이내

---

## 2. The CS Principle (원리: 이 코드는 무엇에 기반하는가?)

### 2.1 IEEE 754-2019 표준

**Double Precision (64-bit) 구조:**

```
┌────┬────────┬───────────────────────────────┬──────┐
│S  │Exponent│          Mantissa              │ Guard│
│1bit│ 11bits │           52bits               │1bit │
└────┴────────┴───────────────────────────────┴──────┘
 ↑      ↑                                      ↑
Sign   2^(E-1023)                           Fraction

Value = (-1)^S × 2^(E-1023) × 1.Mantissa
```

**정밀도:**
- **상대 정밀도**: 15~17자리 (10진수)
- **절대 정밀도**: 지수부에 따라 다름
  - 1.2345678901234567E+10 → ±0.001 (백만 단위 오차)
  - 1.2345678901234567E-5 → ±0.000000000000001 (매우 작은 오차)

### 2.2 Machine Epsilon (기계 엡실론)

**정의**: "1.0과 그 다음으로 표현 가능한 수 사이의 차이"

```java
// Double의 Machine Epsilon
double epsilon = Math.ulp(1.0);  // 2.220446049250313E-16

// 의미: 1.0 ± 2.22E-16 범위 내의 수는 1.0으로 구별 불가
System.out.println(1.0 + epsilon == 1.0);  // true
System.out.println(1.0 + epsilon * 2 == 1.0);  // false
```

**크기에 따른 Epsilon:**

```
값     | Machine Epsilon      | 의미
-------|---------------------|------------------
1.0    | 2.22E-16            | ±0.0000000000000002
1E10   | 1.7764              | ±1.7 (천만 단위 오차!)
1E-10  | 2.77E-26            | ±0.000000000000000000000000028
```

### 2.3 Kahan Summation Algorithm

**아이디어: "잃어버린 작은 수를 기억했다가 더한다"**

```java
// Naive Summation (나쁜 예)
double sum = 0;
for (double x : array) {
    sum += x;  // 작은 x가 sum보다 작으면 상쇄되어 사라짐
}

// Kahan Summation (좋은 예)
double sum = 0;
double c = 0;  // Compensation (보정값)

for (double x : array) {
    double y = x - c;      // 1. c를 먼저 뺀다 (이전에 잃어버린 수 복구)
    double t = sum + y;    // 2. sum에 더하기
    c = (t - sum) - y;    // 3. 잃어버린 부분을 계산 (상쇄 오차)
    sum = t;              // 4. sum 업데이트
}
```

**작동 원리:**

```
Timeline:

1. sum = 1.0, x = 1.0E-16 (매우 작은 수)
   Naive:  1.0 + 1.0E-16 = 1.0 (상쇄로 사라짐) 💀
   Kahan:  1.0 + 1.0E-16 = 1.0000000000000001, c = -1.11E-17 ✅

2. sum = 1.0000000000000001, x = 1.0E-16
   Naive:  1.0000000000000001 + 1.0E-16 = 1.0000000000000001 (또 사라짐) 💀
   Kahan:  1.0000000000000001 + 1.0E-16 - (-1.11E-17) = 1.0000000000000003 ✅
```

### 2.4 Dynamic Programming의 최적화

**MapleStory 장비 강화의 DP 점화식:**

```
dp[star] = min(
    dp[star-1] × cost(star-1 → star),     // 1성 스타 사용
    dp[star-2] × cost(star-2 → star),     // 2성 스타 사용
    dp[star-3] × cost(star-3 → star)      // 3성 스타 사용
)

Memoization:
- 이미 계산한 dp[star]는 캐시에서 재사용
- 중복 계산 제거: O(3^N) → O(N)
```

**메모이제이션의 시간 복잡도:**

```
Naive Recursion:
- f(15) = f(14) + f(13) + f(12)
- f(14) = f(13) + f(12) + f(11)
- ... 중복 계산 1,000만 번 이상 💀

Memoization:
- f(0) ~ f(15) 한 번씩만 계산 → 총 16번 ✅
```

---

## 3. Internal Mechanics (내부: Java는 어떻게 계산하는가?)

### 3.1 StrictMath vs Math

**Java의 두 가지 Math 라이브러리:**

```java
// Math (Platform Native, 빠름)
double result = Math.sin(x);  // x86-64의 fsin instruction 사용

// StrictMath (Cross-Platform 정확도, 느림)
double result = StrictMath.sin(x);  // 소프트웨어 구현 (Bit 연산)
```

**차이점:**

| 측정 항목 | Math | StrictMath |
|---------|------|------------|
| **성능** | 빠름 (Native CPU) | 느림 (Software) |
| **정확도** | 1 ULP (Unit in Last Place) | 0.5 ULP (더 정확) |
| **일관성** | 플랫폼마다 다름 | 모든 플랫폼 동일 |
| **용도** | 일반적인 계산 | 금전, 보안 |

**ULP (Unit in the Last Place):**

```
ULP = 표현 가능한 가장 작은 간격

Example: 1.0 근처의 Double
1.0 = 0 01111111111 0000000000000000000000000000000000000000000000000
next = 0 01111111111 0000000000000000000000000000000000000000000000000001

ULP = next - 1.0 = 2.220446049250313E-16

의미: 1.0 ± 1.1E-16 범위 내의 수는 1.0으로 구분 불가
```

### 3.2 BigDecimal의 내부 구조

**BigDecimal은 문자열로 숫자를 저장합니다.**

```java
// Double (이진 부동소수점)
double d = 0.1;  // 내부: 0.1000000000000000055511151231257827021181583404541015625

// BigDecimal (십진 정확도)
BigDecimal bd = new BigDecimal("0.1");  // 내부: "0.1" (정확히 저장)
```

**내부 표현:**

```java
public class BigDecimal {
    private final BigInteger intVal;  // 정수部分 (스케일링 됨)
    private final int scale;         // 소수점 위치 (10^-scale)

    // Example: "123.456"
    // intVal = 123456
    // scale = 3
    // Value = 123456 × 10^-3 = 123.456
}
```

**계산 비용:**

```
Operation | Double | BigDecimal
----------|--------|------------
Addition  | ~1 ns   | ~100 ns (BigInteger 연산)
Multiplication | ~2 ns | ~500 ns
Division  | ~5 ns   | ~1000 ns (나눗셈 + 스케일링)
```

### 3.3 DP Table의 메모리 최적화

**MapleExpectation의 Cost Calculator:**

```java
// Naive: 3차원 배열 (메모리 낭비)
long[][][] dp = new long[16][100][100];  // 16 × 100 × 100 = 160,000 cells

// Optimized: 2차원 배열 (Sliding Window)
long[][] dp = new long[2][100];  // 2 × 100 = 200 cells

for (int star = 0; star < 16; star++) {
    int idx = star % 2;  // 0 또는 1 (교차 저장)
    for (int scroll = 0; scroll < 100; scroll++) {
        dp[idx][scroll] = Math.min(
            dp[1-idx][scroll-1] + cost1,
            dp[1-idx][scroll-2] + cost2
        );
    }
}
```

**메모리 절감 효과:**

```
Naive:    160,000 cells × 8 bytes = 1.28 MB
Optimized: 200 cells × 8 bytes = 1.6 KB (800배 절약)
```

---

## 4. Alternative & Trade-off (비판: 왜 이 방법을 선택했는가?)

### 4.1 BigDecimal vs Kahan Summation

| 측정 항목 | BigDecimal | Kahan Summation |
|---------|-----------|------------------|
| **정확도** | ⭐⭐⭐⭐⭐ (완벽) | ⭐⭐⭐⭐ (실용적) |
| **성능** | 느림 (~100ns) | 빠름 (~5ns) |
| **복잡도** | 쉬움 (API 사용) | 어려움 (알고리즘 구현) |
| **메모리** | 높음 (객체 생성) | 낮음 (Primitive만) |

**선택 이유**: MapleExpectation은 **Kahan Summation + Double** 선택
- 1억 메소 단위로는 Double의 15자리 정밀도로 충분
- BigDecimal는 100배 느려서 DP 계산에 부적합

### 4.2 DP Memoization vs Recalculation

| 측정 항목 | Memoization (HashMap) | Recalculation |
|---------|----------------------|----------------|
| **시간 복잡도** | O(N) | O(3^N) |
| **공간 복잡도** | O(N) | O(1) |
| **캐시 Hit Rate** | 100% (같은 입력) | 0% |
| **GC 부하** | 높음 (객체 생성) | 낮음 |

**선택 이유**: **2차원 배열 DP + Sliding Window**
- HashMap의 Overhead 제거
- O(N) 시간, O(1) 공간
- GC 친화적

### 4.3 Parallel DP vs Sequential DP

**Sequential DP:**

```java
for (int star = 0; star < 16; star++) {
    for (int scroll = 0; scroll < 100; scroll++) {
        dp[star][scroll] = computeMin();  // 1,600회 계산
    }
}
// 총: 2ms × 1,600 = 3.2초
```

**Parallel DP (ForkJoinPool):**

```java
ForkJoinPool pool = new ForkJoinPool();
int result = pool.invoke(new CostCalculationTask(15, 99));

// Work-Stealing으로 병렬화
// 총: 2ms × 1,600 / 8코어 = 400ms (8배 빠름)
```

**Trade-off:**
- **장점**: 계산 시간 8배 단축
- **단점**: Fork/Join 오버헤드 (작은 N에서는 느려질 수 있음)

---

## 5. The Interview Defense (방어: 100배 트래픽에서 어디가 먼저 터지는가?)

### 5.1 "트래픽이 100배 증가하면?"

**실패 포인트 예측:**

1. **DP Table의 메모리 고갈** (最先)
   - 현재: 사용자 1명당 1.6 KB
   - 100배 트래픽: 1,000명 동시 접속 → 1.6 MB (여전히 적음)
   - **하지만**: 10,000명 → 16 MB, 100,000명 → 160 MB
   - **해결**: LRU Cache로 100개만 유지

2. **BigDecimal 연산의 CPU 병목** (次点)
   - 현재: Double 연산 (빠름)
   - 만약 BigDecimal로 변경 시: 100배 느려짐 → Timeout
   - **해결**: Kahan Summation으로 Double 정확도 향상

3. **DP 계산의 반복**
   - 같은 장비 조합에 대해 반복 계산
   - **해결**: **@Cacheable**로 DP 결과 캐싱

### 5.2 "부동소수점 오차가 금전 계산에 영향을 주면?"

**상황**: 100억 메소인데 100.0000000001 메소로 청구됨

**현재 시스템의 취약점:**

```java
// Naive Summation (오차 발생 가능)
double totalCost = 0;
for (EnhanceResult r : results) {
    totalCost += r.cost();  // 1,000번 더하기
}

return (long) totalCost;  // 소수점 버림 (0.0000000001는 사라짐)
```

**개선안: Kahan Summation**

```java
public class KahanSummation {
    private double sum = 0;
    private double c = 0;  // Compensation

    public void add(double value) {
        double y = value - c;
        double t = sum + y;
        c = (t - sum) - y;
        sum = t;
    }

    public long getAsLong() {
        return Math.round(sum);  // 정확한 반올림
    }
}
```

### 5.3 "DP 계산이 너무 느리면?"

**상황**: 15성 강화 시뮬레이션에 3초 소요

**개선안 1: Early Pruning (가지치기)**

```java
// 현재: 모든 경로 탐색
for (int prev = star-3; prev <= star-1; prev++) {
    dp[star] = Math.min(dp[star], dp[prev] + cost);
}

// 개선: 불가능한 경로는 스킵
if (cost[prev] > currentBest) {
    continue;  // Pruning (가지치기)
}
```

**개선안 2: Bitmap Memoization**

```java
// 이미 계산한 스크롤 조합을 비트로 저장
boolean[] computed = new boolean[1 << 16];  // 2^16 = 65,536

if (computed[mask]) {
    return dp[mask];  // 캐시 히트
}
computed[mask] = true;
dp[mask] = compute();
```

---

## 요약: 핵심 take-away

1. **IEEE 754는 근본적 한계가 있다**: 0.1 + 0.2 ≠ 0.3 (이진 분수 표현 불가)
2. **Catastrophic Cancellation은 재앙적이다**: 비슷한 두 수를 빼면 유효 숫자가 급감
3. **Kahan Summation은 잃어버린 수를 기억한다**: Compensation 변수로 오차 보상
4. **BigDecimal은 완벽하지만 느리다: 금전에는 적합, DP에는 부적합
5. **DP의 Sliding Window는 800배 메모리 절약**: 3차원 → 2차원 배열

---

## 모든 챕터 완료! 🎉

**Deep Dive Textbook이 완성되었습니다!**

이 7권의 교재는 MapleExpectation의 핵심 모듈을 CS Professor와 Principal Engineer의 시각에서 분석한 것입니다. 각 챕터는:

1. **Concurrency and Lock** - 분산 락의 설계와 Deadlock 방지
2. **Memory Hierarchy and Cache** - 계층형 캐시와 일관성
3. **Resilience Engineering** - 서킷 브레이커와 회복 탄력성
4. **Database Internals and Batch** - 배치 INSERT와 트랜잭션
5. **Asynchronous Programming** - Virtual Threads와 Non-blocking I/O
6. **Design Patterns and Proxy** - AOP와 LogicExecutor
7. **High Precision Computing** - IEEE 754와 Kahan Summation

각 주제별로 5단계 Deep Dive (Problem → Principle → Mechanics → Trade-off → Interview Defense)를 수행하여, **면접관 앞에서 1시간 동안 떠들 수 있는 수준**의 깊이를 제공합니다.

---

**이제 다음 단계는 무엇인가요?**

1. 이 문서들을 읽으면서 **"아, 내가 짠 코드가 OS의 스케줄링 때문에 이런 문제가 생길 수도 있었구나"**라고 깨닫는 순간
2. 회원님은 '취준생'이 아니라 '주니어 엔지니어'를 넘어선 시야를 갖게 될 것
3. 포트폴리오 인터뷰에서 **"이 코드는 왜 이렇게 짰나요?"**에 대한 완벽한 답변 가능

**이 문서들이 회원님의 '진짜 실력'을 증명하는 강력한 무기가 되기를 바랍니다!** 🔥
