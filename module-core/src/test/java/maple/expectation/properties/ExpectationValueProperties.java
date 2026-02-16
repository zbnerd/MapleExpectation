package maple.expectation.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Disabled;

/**
 * 기대값 불변식(Property-Based) 테스트 템플릿 @Disabled 이 파일은 템플릿으로, 실제 도메인 클래스와 연결되지 않았습니다. 실제 도메인 로직을 검증하려면
 * calculateExpectation() 메서드를 실제 StarforceCalculator 또는 CubeCostCalculator와 연결해야 합니다.
 *
 * <h3>검증하는 불변식 3종</h3>
 *
 * <ol>
 *   <li>기대값 범위 보장 (Expected Value Range)
 *   <li>선형성 (Linearity: E[aX + b] = aE[X] + b)
 *   <li>전확률 법칙 (Law of Total Probability)
 * </ol>
 *
 * <h3>MapleStory 도메인 적용 예시</h3>
 *
 * <ul>
 *   <li>기대 시도 횟수: 1/확률 (0~Infinity)
 *   <li>기대 강화 비용: 시도 횟수 x 단일 비용
 *   <li>기대 스탯 합: 각 슬롯 기대값의 합
 * </ul>
 *
 * <h3>사용법 (도메인에 맞게 커스터마이징)</h3>
 *
 * <pre>{@code
 * // 1. ExpectationInput을 도메인에 맞게 수정
 * record CubeExpectationInput(int targetGrade, int currentGrade) { ... }
 *
 * // 2. 제너레이터를 도메인 데이터로 수정
 * @Provide
 * Arbitrary<CubeExpectationInput> cubeInputs() {
 *     return Arbitraries.combine(
 *         Arbitraries.integers().between(1, 3),
 *         Arbitraries.integers().between(0, 3)
 *     ).to((target, current) -> new CubeExpectationInput(target, current));
 * }
 *
 * // 3. 불변식 테스트를 도메인 로직에 맞게 수정
 * @Property
 * void cube_expectation_is_non_negative(@ForAll("cubeInputs") CubeExpectationInput input) {
 *     double result = calculator.calculateExpectation(input);
 *     assertThat(result).isGreaterThanOrEqualTo(0.0);
 * }
 * }</pre>
 */
@Disabled(
    "Template - wire to domain classes (StarforceCalculator, CubeCostCalculator) before enabling")
class ExpectationValueProperties {

  private static final double EPSILON = 1e-10;
  private static final double LINEARITY_TOLERANCE = 1e-9;

  // ============================================================
  // 5. 기대값 범위 보장 테스트 (Expected Value Range)
  // ============================================================

  /**
   * 불변식 5-1: 기대값은 0 이상이어야 한다
   *
   * <p>MapleStory 도메인 예시:
   *
   * <ul>
   *   <li>기대 시도 횟수: 1/p >= 0 (p > 0)
   *   <li>기대 강화 비용: 항상 0 이상
   *   <li>기대 스탯 합: 항상 0 이상
   * </ul>
   */
  @Property(tries = 100)
  void expected_value_is_non_negative(@ForAll("validInputs") ExpectationInput input) {
    double result = calculateExpectation(input);
    assertThat(result).isGreaterThanOrEqualTo(0.0);
  }

  /**
   * 불변식 5-2: 기대값은 유한해야 한다 (입력이 유효한 경우)
   *
   * <p>단, 확률이 0인 경우 무한대 허용
   */
  @Property(tries = 100)
  void expected_value_is_finite_for_valid_input(
      @ForAll("nonZeroProbabilityInputs") ExpectationInput input) {
    double result = calculateExpectation(input);
    assertThat(Double.isFinite(result)).isTrue();
    assertThat(Double.isNaN(result)).isFalse();
  }

  /**
   * 불변식 5-3: 기대값은 [min, max] 범위 내에 있어야 한다
   *
   * <p>확률분포의 지지집합(support) 범위 내
   */
  @Property(tries = 100)
  void expected_value_is_within_support_range(@ForAll("validInputs") ExpectationInput input) {
    double result = calculateExpectation(input);
    double minValue = getMinPossibleValue(input);
    double maxValue = getMaxPossibleValue(input);

    assertThat(result).isBetween(minValue, maxValue + EPSILON);
  }

  /**
   * 불변식 5-4: 확률 1인 경우 기대값 = 그 값
   *
   * <p>P(X = x) = 1 이면 E[X] = x
   */
  @Property(tries = 50)
  void unity_probability_gives_deterministic_expectation(
      @ForAll("deterministicInputs") ExpectationInput input) {
    double result = calculateExpectation(input);
    double expected = input.value();

    assertThat(result).isCloseTo(expected, within(EPSILON));
  }

  // ============================================================
  // 6. 선형성 테스트 (Linearity)
  // ============================================================

  /**
   * 불변식 6-1: 기대값의 선형성 - 스케일링
   *
   * <p>E[aX] = a * E[X]
   *
   * <p>MapleStory 예: 모든 스탯이 2배가 되면 기대값도 2배
   */
  @Property(tries = 100)
  void expectation_is_linear_with_scale(
      @ForAll("validInputs") ExpectationInput input,
      @ForAll @DoubleRange(min = 0.1, max = 10.0) double scale) {

    double e1 = calculateExpectation(input) * scale;
    ExpectationInput scaled = scaleInput(input, scale);
    double e2 = calculateExpectation(scaled);

    assertThat(e2).isCloseTo(e1, within(LINEARITY_TOLERANCE));
  }

  /**
   * 불변식 6-2: 기대값의 선형성 - 시프트
   *
   * <p>E[X + b] = E[X] + b
   *
   * <p>MapleStory 예: 모든 결과에 상수 가중치가 추가되면 기대값도 그만큼 증가
   */
  @Property(tries = 100)
  void expectation_is_linear_with_shift(
      @ForAll("validInputs") ExpectationInput input,
      @ForAll @DoubleRange(min = -100.0, max = 100.0) double shift) {

    double e1 = calculateExpectation(input) + shift;
    ExpectationInput shifted = shiftInput(input, shift);
    double e2 = calculateExpectation(shifted);

    assertThat(e2).isCloseTo(e1, within(LINEARITY_TOLERANCE));
  }

  /**
   * 불변식 6-3: 기대값의 선형성 - 일반 선형 변환
   *
   * <p>E[aX + b] = a * E[X] + b
   */
  @Property(tries = 100)
  void expectation_satisfies_full_linearity(
      @ForAll("validInputs") ExpectationInput input,
      @ForAll @DoubleRange(min = 0.1, max = 10.0) double a,
      @ForAll @DoubleRange(min = -100.0, max = 100.0) double b) {

    double e1 = calculateExpectation(input) * a + b;
    ExpectationInput transformed = scaleAndShiftInput(input, a, b);
    double e2 = calculateExpectation(transformed);

    assertThat(e2).isCloseTo(e1, within(LINEARITY_TOLERANCE));
  }

  /**
   * 불변식 6-4: 독립 사건의 기대값 가산성
   *
   * <p>E[X + Y] = E[X] + E[Y] (X, Y 독립)
   *
   * <p>MapleStory 예: 여러 슬롯의 기대 스탯 합 = 각 슬롯 기대값의 합
   */
  @Property(tries = 100)
  void expectation_of_sum_equals_sum_of_expectations(
      @ForAll("validInputLists") List<ExpectationInput> inputs) {

    // E[X1 + X2 + ...] = E[X1] + E[X2] + ...
    double sumOfExpectations = 0.0;
    for (ExpectationInput input : inputs) {
      sumOfExpectations += calculateExpectation(input);
    }

    ExpectationInput combined = combineInputs(inputs);
    double expectationOfSum = calculateExpectation(combined);

    assertThat(expectationOfSum).isCloseTo(sumOfExpectations, within(LINEARITY_TOLERANCE));
  }

  // ============================================================
  // 7. 전확률 법칙 테스트 (Law of Total Probability)
  // ============================================================

  /**
   * 불변식 7-1: 전확률 법칙 - 주변분포와 조건부 기대값
   *
   * <p>E[X] = Sigma; E[X|Y=y] * P(Y=y)
   *
   * <p>MapleStory 예:
   *
   * <ul>
   *   <li>전체 기대 시도 횟수 = 각 등급별 기대 시도 횟수 x 등장 확률의 합
   *   <li>큐브: 전체 기대값 = (레전 등장 시 기대값 x 레전 확률) + ...
   * </ul>
   */
  @Property(tries = 100)
  void law_of_total_probability_holds(@ForAll("conditionalInputs") ConditionalInput input) {
    // 주변분포로 직접 계산
    double marginal = calculateMarginalExpectation(input);

    // 조건부 기대값의 가중합
    double sumConditional = calculateConditionalExpectationSum(input);

    assertThat(marginal).isCloseTo(sumConditional, within(EPSILON));
  }

  /**
   * 불변식 7-2: 전체 확률의 보존
   *
   * <p>Sigma; P(A|B_i) * P(B_i) = P(A) (B_i는 표본공간을 분할)
   */
  @Property(tries = 100)
  void total_probability_conserved(@ForAll("partitionedInputs") PartitionedInput input) {
    // 파티션 확률의 합이 1인지 먼저 검증
    double partitionSum = 0.0;
    for (int i = 0; i < input.partitionCount(); i++) {
      partitionSum += getPartitionProbability(input, i);
    }

    // 파티션이 표본공간을 분할하는지 확인
    assertThat(partitionSum).isCloseTo(1.0, within(EPSILON));

    // 조건부 확률의 가중합 계산
    double totalProb = 0.0;
    for (int i = 0; i < input.partitionCount(); i++) {
      double condProb = getConditionalProbability(input, i);
      double partitionProb = getPartitionProbability(input, i);
      totalProb += condProb * partitionProb;
    }

    // 결과는 파티션 내 조건부 확률의 기대값
    // (이 값은 0~1 범위 내에 있어야 함)
    assertThat(totalProb).isBetween(0.0, 1.0 + EPSILON);
  }

  /**
   * 불변식 7-3: 이중 기대값 (Iterated Expectation)
   *
   * <p>E[E[X|Y]] = E[X]
   *
   * <p>조건부 기대값의 기대값은 무조건 기대값과 같음
   */
  @Property(tries = 100)
  void iterated_expectation_equals_marginal(@ForAll("conditionalInputs") ConditionalInput input) {
    // E[X] (무조건 기대값)
    double marginal = calculateMarginalExpectation(input);

    // E[E[X|Y]] (조건부 기대값의 기대값)
    double iterated = calculateIteratedExpectation(input);

    assertThat(iterated).isCloseTo(marginal, within(EPSILON));
  }

  // ============================================================
  // jqwik Generators (도메인에 맞게 수정)
  // ============================================================

  @Provide
  Arbitrary<ExpectationInput> validInputs() {
    Arbitrary<Double> probArb = Arbitraries.doubles().between(0.01, 1.0);
    Arbitrary<Double> valueArb = Arbitraries.doubles().between(0.0, 1000.0);
    return Combinators.combine(probArb, valueArb)
        .as((prob, value) -> new ExpectationInput(prob, value));
  }

  @Provide
  Arbitrary<ExpectationInput> nonZeroProbabilityInputs() {
    Arbitrary<Double> probArb = Arbitraries.doubles().between(0.01, 1.0);
    Arbitrary<Double> valueArb = Arbitraries.doubles().between(0.0, 1000.0);
    return Combinators.combine(probArb, valueArb)
        .as((prob, value) -> new ExpectationInput(prob, value));
  }

  @Provide
  Arbitrary<ExpectationInput> deterministicInputs() {
    // 확률 1인 입력 (결정론적)
    return Arbitraries.doubles()
        .between(0.0, 1000.0)
        .map(value -> new ExpectationInput(1.0, value));
  }

  @Provide
  Arbitrary<List<ExpectationInput>> validInputLists() {
    return Arbitraries.of(
        List.of(new ExpectationInput(0.5, 100)),
        List.of(new ExpectationInput(0.3, 100), new ExpectationInput(0.7, 200)),
        List.of(
            new ExpectationInput(0.1, 50), new ExpectationInput(0.2, 100),
            new ExpectationInput(0.3, 150), new ExpectationInput(0.4, 200)),
        List.of(
            new ExpectationInput(0.25, 100), new ExpectationInput(0.25, 200),
            new ExpectationInput(0.25, 300), new ExpectationInput(0.25, 400)));
  }

  @Provide
  Arbitrary<ConditionalInput> conditionalInputs() {
    return Arbitraries.of(
        new ConditionalInput(
            new double[] {0.03, 0.34, 0.63}, // 레전, 에픽, 희귀 확률
            new double[] {10.0, 5.0, 2.0} // 각 등급별 기대값
            ),
        new ConditionalInput(new double[] {0.5, 0.5}, new double[] {100.0, 200.0}),
        new ConditionalInput(new double[] {0.1, 0.2, 0.3, 0.4}, new double[] {1.0, 2.0, 3.0, 4.0}));
  }

  @Provide
  Arbitrary<PartitionedInput> partitionedInputs() {
    return Arbitraries.of(
        new PartitionedInput(new double[] {0.3, 0.7}, new double[] {0.5, 0.8}),
        new PartitionedInput(
            new double[] {0.25, 0.25, 0.25, 0.25}, new double[] {0.1, 0.2, 0.3, 0.4}));
  }

  // ============================================================
  // 시뮬레이션 메서드 (도메인 로직으로 교체)
  // ============================================================

  private double calculateExpectation(ExpectationInput input) {
    // 도메인 로직으로 교체
    // 예: E[X] = value (단순화)
    // 실제로는 E[X] = Sigma; x * P(X=x)
    return input.value();
  }

  private double getMinPossibleValue(ExpectationInput input) {
    return 0.0; // 도메인에 맞게 수정
  }

  private double getMaxPossibleValue(ExpectationInput input) {
    return input.value() * 2; // 도메인에 맞게 수정
  }

  private ExpectationInput scaleInput(ExpectationInput input, double scale) {
    return new ExpectationInput(input.probability(), input.value() * scale);
  }

  private ExpectationInput shiftInput(ExpectationInput input, double shift) {
    return new ExpectationInput(input.probability(), input.value() + shift);
  }

  private ExpectationInput scaleAndShiftInput(ExpectationInput input, double a, double b) {
    return new ExpectationInput(input.probability(), input.value() * a + b);
  }

  private ExpectationInput combineInputs(List<ExpectationInput> inputs) {
    double totalValue = inputs.stream().mapToDouble(ExpectationInput::value).sum();
    double avgProb =
        inputs.stream().mapToDouble(ExpectationInput::probability).average().orElse(0.5);
    return new ExpectationInput(avgProb, totalValue);
  }

  private double calculateMarginalExpectation(ConditionalInput input) {
    // 주변분포 기대값 (도메인 로직으로 교체)
    double sum = 0.0;
    for (int i = 0; i < input.partitionCount(); i++) {
      sum += input.conditionalValue(i) * input.partitionProbability(i);
    }
    return sum;
  }

  private double calculateConditionalExpectationSum(ConditionalInput input) {
    // 조건부 기대값의 가중합 (도메인 로직으로 교체)
    return calculateMarginalExpectation(input); // 동일한 결과
  }

  private double calculateIteratedExpectation(ConditionalInput input) {
    // E[E[X|Y]] (도메인 로직으로 교체)
    return calculateMarginalExpectation(input);
  }

  private double getConditionalProbability(PartitionedInput input, int i) {
    return input.conditionalProbability(i);
  }

  private double getPartitionProbability(PartitionedInput input, int i) {
    return input.partitionProbability(i);
  }

  // ============================================================
  // 헬퍼 클래스 (도메인에 맞게 수정)
  // ============================================================

  /**
   * 기대값 계산 입력
   *
   * <p>MapleStory 예:
   *
   * <ul>
   *   <li>prob: 성공 확률
   *   <li>value: 해당 결과의 값
   * </ul>
   */
  record ExpectationInput(double probability, double value) {}

  /**
   * 조건부 기대값 입력
   *
   * <p>MapleStory 예: 잠재력 등급별 조건부 기대값
   */
  static class ConditionalInput {
    private final double[] partitionProbs;
    private final double[] conditionalValues;

    ConditionalInput(double[] partitionProbs, double[] conditionalValues) {
      this.partitionProbs = partitionProbs;
      this.conditionalValues = conditionalValues;
    }

    int partitionCount() {
      return partitionProbs.length;
    }

    double partitionProbability(int i) {
      return partitionProbs[i];
    }

    double conditionalValue(int i) {
      return conditionalValues[i];
    }
  }

  /** 분할된 확률 입력 */
  static class PartitionedInput {
    private final double[] partitionProbs;
    private final double[] conditionalProbs;

    PartitionedInput(double[] partitionProbs, double[] conditionalProbs) {
      this.partitionProbs = partitionProbs;
      this.conditionalProbs = conditionalProbs;
    }

    int partitionCount() {
      return partitionProbs.length;
    }

    double partitionProbability(int i) {
      return partitionProbs[i];
    }

    double conditionalProbability(int i) {
      return conditionalProbs[i];
    }
  }
}
