package maple.expectation.service.v2.cube.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import maple.expectation.domain.model.calculator.DensePmf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 꼬리 확률 및 기대값 계산 테스트
 *
 * <h3>핵심 검증 항목</h3>
 *
 * <ul>
 *   <li>P(X >= target) 계산 정확성
 *   <li>기하분포 기반 기대 시도 횟수
 *   <li>경계값 (p=0, p=1)
 *   <li>Tail Clamp 적용 시 mass[target] 반환
 * </ul>
 */
class TailProbabilityCalculatorTest {

  private TailProbabilityCalculator calculator;

  private static final double TOLERANCE = 1e-12;

  @BeforeEach
  void setUp() {
    calculator = new TailProbabilityCalculator();
  }

  @Nested
  @DisplayName("꼬리 확률 계산 테스트")
  class TailProbabilityTest {

    @Test
    @DisplayName("Tail Clamp 적용 시 mass[target] 값을 반환해야 한다")
    void tail_clamp_returns_mass_at_target() {
      // Given: mass = [0.1, 0.2, 0.3, 0.4] (합=1.0)
      // target=3인 Tail Clamp 적용 시 mass[3] = 0.4가 P(X >= 3)
      double[] mass = {0.1, 0.2, 0.3, 0.4};
      DensePmf pmf = DensePmf.fromArray(mass);
      int target = 3;

      // When
      double probability = calculator.calculateTailProbability(pmf, target, true);

      // Then
      assertThat(probability).isCloseTo(0.4, within(TOLERANCE));
    }

    @Test
    @DisplayName("Tail Clamp 미적용 시 Kahan 합산으로 계산해야 한다")
    void no_clamp_calculates_kahan_sum() {
      // Given: mass = [0.1, 0.2, 0.3, 0.25, 0.15] (합=1.0)
      // target=2면 P(X >= 2) = 0.3 + 0.25 + 0.15 = 0.7
      double[] mass = {0.1, 0.2, 0.3, 0.25, 0.15};
      DensePmf pmf = DensePmf.fromArray(mass);
      int target = 2;

      // When
      double probability = calculator.calculateTailProbability(pmf, target, false);

      // Then
      assertThat(probability).isCloseTo(0.7, within(TOLERANCE));
    }

    @Test
    @DisplayName("target이 범위를 벗어나면 0을 반환해야 한다")
    void target_out_of_range_returns_zero() {
      // Given
      double[] mass = {0.3, 0.4, 0.3};
      DensePmf pmf = DensePmf.fromArray(mass);
      int target = 10; // 범위 초과

      // When
      double probability = calculator.calculateTailProbability(pmf, target, false);

      // Then: 범위 밖이므로 0
      assertThat(probability).isCloseTo(0.0, within(TOLERANCE));
    }

    @Test
    @DisplayName("target = 0 이면 전체 질량(1.0)을 반환해야 한다")
    void target_zero_returns_total_mass() {
      // Given
      double[] mass = {0.2, 0.3, 0.5};
      DensePmf pmf = DensePmf.fromArray(mass);
      int target = 0;

      // When
      double probability = calculator.calculateTailProbability(pmf, target, false);

      // Then: 전체 질량
      assertThat(probability).isCloseTo(1.0, within(TOLERANCE));
    }
  }

  @Nested
  @DisplayName("기대 시도 횟수 계산 테스트")
  class ExpectedTrialsTest {

    @Test
    @DisplayName("p = 0.5 이면 E[N] = 2.0")
    void probability_half_returns_two() {
      // Given
      double probability = 0.5;

      // When
      double expected = calculator.calculateExpectedTrials(probability);

      // Then: E[N] = 1/0.5 = 2.0
      assertThat(expected).isCloseTo(2.0, within(TOLERANCE));
    }

    @Test
    @DisplayName("p = 0.1 이면 E[N] = 10.0")
    void probability_tenth_returns_ten() {
      // Given
      double probability = 0.1;

      // When
      double expected = calculator.calculateExpectedTrials(probability);

      // Then: E[N] = 1/0.1 = 10.0
      assertThat(expected).isCloseTo(10.0, within(TOLERANCE));
    }

    @Test
    @DisplayName("p = 1.0 이면 E[N] = 1.0 (한 번에 성공)")
    void probability_one_returns_one() {
      // Given
      double probability = 1.0;

      // When
      double expected = calculator.calculateExpectedTrials(probability);

      // Then
      assertThat(expected).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    @DisplayName("p = 0 이면 POSITIVE_INFINITY 반환")
    void probability_zero_returns_infinity() {
      // Given
      double probability = 0.0;

      // When
      double expected = calculator.calculateExpectedTrials(probability);

      // Then
      assertThat(expected).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    @DisplayName("p < 0 이면 POSITIVE_INFINITY 반환")
    void negative_probability_returns_infinity() {
      // Given
      double probability = -0.1;

      // When
      double expected = calculator.calculateExpectedTrials(probability);

      // Then: 비정상 확률은 INFINITY로 처리
      assertThat(expected).isEqualTo(Double.POSITIVE_INFINITY);
    }
  }

  @Nested
  @DisplayName("기대 시도 횟수 올림 (UI 표시용)")
  class ExpectedTrialsCeilTest {

    @Test
    @DisplayName("p = 0.5 이면 ceil(2.0) = 2")
    void probability_half_ceil() {
      // Given
      double probability = 0.5;

      // When
      long expected = calculator.calculateExpectedTrialsCeil(probability);

      // Then
      assertThat(expected).isEqualTo(2L);
    }

    @Test
    @DisplayName("p = 0.33 이면 ceil(3.03...) = 4")
    void probability_third_ceil() {
      // Given
      double probability = 0.33; // 1/0.33 = 3.0303...

      // When
      long expected = calculator.calculateExpectedTrialsCeil(probability);

      // Then
      assertThat(expected).isEqualTo(4L);
    }

    @Test
    @DisplayName("p = 0 이면 Long.MAX_VALUE 반환")
    void probability_zero_ceil() {
      // Given
      double probability = 0.0;

      // When
      long expected = calculator.calculateExpectedTrialsCeil(probability);

      // Then
      assertThat(expected).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("p = 1.0 이면 1 반환")
    void probability_one_ceil() {
      // Given
      double probability = 1.0;

      // When
      long expected = calculator.calculateExpectedTrialsCeil(probability);

      // Then
      assertThat(expected).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("정밀도 테스트")
  class PrecisionTest {

    @Test
    @DisplayName("아주 작은 확률도 정확히 계산해야 한다")
    void very_small_probability() {
      // Given: 매우 작은 확률
      double probability = 0.0001;

      // When
      double expected = calculator.calculateExpectedTrials(probability);

      // Then: E[N] = 10,000
      assertThat(expected).isCloseTo(10000.0, within(TOLERANCE));
    }

    @Test
    @DisplayName("Kahan summation으로 부동소수점 오차를 최소화해야 한다")
    void kahan_summation_minimizes_floating_point_error() {
      // Given: 많은 작은 값들의 합
      double[] mass = new double[1000];
      for (int i = 0; i < 1000; i++) {
        mass[i] = 0.001; // 총합 = 1.0
      }
      DensePmf pmf = DensePmf.fromArray(mass);
      int target = 500;

      // When
      double probability = calculator.calculateTailProbability(pmf, target, false);

      // Then: P(X >= 500) = 500 * 0.001 = 0.5
      assertThat(probability).isCloseTo(0.5, within(TOLERANCE));
    }
  }
}
