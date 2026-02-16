package maple.expectation.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * 확률 불변식(Property-Based) 테스트 템플릿
 *
 * <h3>검증하는 불변식 3종</h3>
 *
 * <ol>
 *   <li>확률 비음수 & 유한성 (Non-negative & Finite)
 *   <li>확률질량 보존 (Normalization: Sigma;p = 1)
 *   <li>전이 완전성 (Totality: 모든 상태에서 다음 상태 존재)
 * </ol>
 *
 * <h3>MapleStory 도메인 적용 예시</h3>
 *
 * <ul>
 *   <li>큐브: 슬롯별 옵션 추첨 확률 (레전드, 에픽, 희귀, 없음)
 *   <li>스타포스: 강화 성공/실패/파괴/유지 확률
 *   <li>플레임: 스탯 옵션 분포
 * </ul>
 *
 * <h3>사용법 (도메인에 맞게 커스터마이징)</h3>
 *
 * <pre>{@code
 * // 1. ProbVector를 도메인에 맞게 수정
 * record CubeProbVector(double[] probs) { ... }
 *
 * // 2. probVectors() 제너레이터를 도메인 데이터로 수정
 * @Provide
 * Arbitrary<CubeProbVector> cubeProbVectors() {
 *     return Arbitraries.of(
 *         new CubeProbVector(new double[] {0.03, 0.34, 0.63}), // 레전, 에픽, 희귀
 *         ...
 *     );
 * }
 *
 * // 3. 불변식 테스트를 도메인 로직에 맞게 수정
 * @Property
 * void cube_probabilities_sum_to_one(@ForAll("cubeProbVectors") CubeProbVector pv) {
 *     assertThat(pv.sum()).isCloseTo(1.0, within(1e-12));
 * }
 * }</pre>
 */
@Disabled(
    "Template - wire to domain classes (StarforceCalculator, CubeCostCalculator) before enabling")
class ProbabilityContractsProperties {

  private static final double EPSILON = 1e-12;
  private static final double NEGATIVE_TOLERANCE = -1e-15;

  // ============================================================
  // 1. 확률 비음수 & 유한성 테스트 (Non-negative & Finite)
  // ============================================================

  /**
   * 불변식 1: 모든 확률은 [0, 1] 범위 내에 있어야 한다
   *
   * <p>MapleStory 도메인 예시:
   *
   * <ul>
   *   <li>큐브 등장 확률: 0.0 ~ 1.0 (레전드 3%, 에픽 34%, 희귀 63%)
   *   <li>스타포스 성공 확률: 0.0 ~ 1.0 (0성 60%, 1성 55%, ...)
   *   <li>옵션 추첨 확률: 0.0 ~ 1.0
   * </ul>
   */
  @Property(tries = 100)
  void probability_is_between_zero_and_one(@ForAll("probVectors") ProbVector pv) {
    for (double p : pv.probs()) {
      assertThat(p).isBetween(0.0, 1.0);
    }
  }

  /**
   * 불변식 2: 모든 확률은 유한한 실수여야 한다 (NaN/Inf 금지)
   *
   * <p>부동소수점 연산 중 발생할 수 있는 오염을 방지
   *
   * <p>나눗셈, 로그, 지수 연산 후 검증 필수
   */
  @Property(tries = 100)
  void probability_is_finite(@ForAll("probVectors") ProbVector pv) {
    for (double p : pv.probs()) {
      assertThat(Double.isFinite(p)).isTrue();
      assertThat(Double.isNaN(p)).isFalse();
      assertThat(Double.isInfinite(p)).isFalse();
    }
  }

  /**
   * 불변식 3: 확률 0과 1은 경계값으로 허용
   *
   * <p>0: 불가능한 사건 (예: 15성 놀장에서 16성 강화 시도)
   *
   * <p>1: 확실한 사건 (예: 0성 스타포스에서 1성 시도 후 변화 있음)
   */
  @Property(tries = 50)
  void probability_boundaries_zero_and_one_are_valid(@ForAll("boundaryProbVectors") ProbVector pv) {
    for (double p : pv.probs()) {
      assertThat(p).isBetween(0.0, 1.0);
    }
  }

  // ============================================================
  // 2. 확률질량 보존 테스트 (Normalization: Sigma;p = 1)
  // ============================================================

  /**
   * 불변식 4: 확률분포의 총 질량은 1이어야 한다 (Normalization)
   *
   * <p>MapleStory 도메인 예시:
   *
   * <ul>
   *   <li>한 슬롯에서 가능한 모든 옵션 확률 합 = 1.0
   *   <li>스타포스 강화 결과 (성공+실패+파괴+유지) 확률 합 = 1.0
   *   <li>큐브 잠재력 변경 (상승+유지+하락) 확률 합 = 1.0
   * </ul>
   */
  @Property(tries = 100)
  void probability_mass_sums_to_one(@ForAll("probVectors") ProbVector pv) {
    double sum = pv.sum();
    assertThat(sum).isCloseTo(1.0, within(EPSILON));
  }

  /**
   * 불변식 5: 빈 분포가 아닌 경우 모든 확률이 0이면 안 된다
   *
   * <p>적어도 하나의 결과는 발생해야 함
   */
  @Property(tries = 100)
  void non_empty_distribution_has_at_least_one_positive_probability(
      @ForAll("nonEmptyProbVectors") ProbVector pv) {
    Assume.that(pv.probs().length > 0);

    boolean hasPositive = false;
    for (double p : pv.probs()) {
      if (p > EPSILON) {
        hasPositive = true;
        break;
      }
    }
    assertThat(hasPositive).isTrue();
  }

  /**
   * 불변식 6: 정규화된 분포는 모든 확률이 [0, 1] 범위
   *
   * <p>단, 부동소수점 오차로 1.0 + epsilon은 허용
   */
  @Property(tries = 100)
  void normalized_distribution_all_probabilities_in_range(@ForAll("probVectors") ProbVector pv) {
    for (double p : pv.probs()) {
      assertThat(p).isBetween(0.0, 1.0 + EPSILON);
    }
  }

  /**
   * 불변식 7: 음수 확률은 허용되지 않음 (부동소수점 오차 범위 내)
   *
   * <p>합성곱, 보정 연산 후 음수 누적 오류 방지
   */
  @Property(tries = 100)
  void probability_is_never_negative(@ForAll("probVectors") ProbVector pv) {
    for (double p : pv.probs()) {
      assertThat(p).isGreaterThanOrEqualTo(NEGATIVE_TOLERANCE);
    }
  }

  // ============================================================
  // 3. 전이 완전성 테스트 (Totality)
  // ============================================================

  /**
   * 불변식 8: 모든 유효한 상태에서 다음 상태가 정의되어야 한다
   *
   * <p>MapleStory 도메인 예시:
   *
   * <ul>
   *   <li>모든 스타 레벨 (0~15성)에서 강화 결과가 정의됨
   *   <li>모든 잠재력 등급에서 큐브 결과가 정의됨
   *   <li>모든 아이템 레벨에서 강화 비용이 계산됨
   * </ul>
   */
  @Property(tries = 50)
  void transition_is_defined_for_all_valid_states(@ForAll("validStates") State state) {
    TransitionResult result = computeTransition(state);

    assertThat(result).isNotNull();
    assertThat(result.isValid()).isTrue();
    assertThat(result.probabilities()).isNotEmpty();
  }

  /**
   * 불변식 9: 전이 결과의 확률분포는 정규화되어야 한다
   *
   * <p>상태 전이 후 가능한 모든 결과의 확률 합 = 1.0
   */
  @Property(tries = 50)
  void transition_probability_distribution_is_normalized(@ForAll("validStates") State state) {
    TransitionResult result = computeTransition(state);

    double sum = result.probabilitySum();
    assertThat(sum).isCloseTo(1.0, within(EPSILON));
  }

  /**
   * 불변식 10: 동일 입력에 동일 출력 (결정론적 부분 검증)
   *
   * <p>시드가 같으면 결과가 같아야 함
   */
  @Property(tries = 50)
  void transition_is_consistent_with_same_seed(
      @ForAll("validStates") State state, @ForAll @IntRange(min = 0, max = 10000) int seed) {

    TransitionResult r1 = computeTransitionWithSeed(state, seed);
    TransitionResult r2 = computeTransitionWithSeed(state, seed);

    assertThat(r1.nextStates()).isEqualTo(r2.nextStates());
    assertThat(r1.probabilities()).isEqualTo(r2.probabilities());
  }

  // ============================================================
  // jqwik Generators (테스트 데이터 생성기)
  // ============================================================

  /** 유효한 확률벡터 생성기 (자동 정규화) */
  @Provide
  Arbitrary<ProbVector> probVectors() {
    Arbitrary<List<Double>> raw =
        Arbitraries.doubles().between(0.0, 1.0).list().ofMinSize(1).ofMaxSize(10);

    return raw.map(list -> normalizeProbVector(list));
  }

  /** 경계값 확률벡터 생성기 (0, 1, epsilon 등) */
  @Provide
  Arbitrary<ProbVector> boundaryProbVectors() {
    return Arbitraries.of(
        new ProbVector(new double[] {0.0, 1.0}),
        new ProbVector(new double[] {1.0}),
        new ProbVector(new double[] {0.5, 0.5}),
        new ProbVector(new double[] {EPSILON, 1.0 - EPSILON}),
        new ProbVector(new double[] {0.0, 0.0, 1.0}),
        new ProbVector(new double[] {1.0 - EPSILON, EPSILON}),
        new ProbVector(new double[] {0.25, 0.75}),
        new ProbVector(new double[] {0.1, 0.9}));
  }

  /** 비어있지 않은 확률벡터 생성기 */
  @Provide
  Arbitrary<ProbVector> nonEmptyProbVectors() {
    Arbitrary<List<Double>> raw =
        Arbitraries.doubles()
            .between(0.01, 1.0) // 최소 0.01으로 0 방지
            .list()
            .ofMinSize(1)
            .ofMaxSize(10);

    return raw.map(list -> normalizeProbVector(list));
  }

  /**
   * 유효한 상태 생성기
   *
   * <p>도메인에 맞게 수정: 스타 레벨, 아이템 레벨, 잠재력 등급 등
   */
  @Provide
  Arbitrary<State> validStates() {
    return Arbitraries.integers()
        .between(0, 100) // 도메인에 맞게 수정
        .map(State::new);
  }

  // ============================================================
  // 헬퍼 메서드
  // ============================================================

  /** 확률 리스트를 정규화하여 ProbVector 생성 */
  private ProbVector normalizeProbVector(List<Double> probs) {
    double sum = probs.stream().mapToDouble(Double::doubleValue).sum();

    if (sum == 0.0) {
      // 모두 0인 경우 균등 분포로
      double uniform = 1.0 / probs.size();
      double[] normalized = new double[probs.size()];
      java.util.Arrays.fill(normalized, uniform);
      return new ProbVector(normalized);
    }

    double[] normalized = new double[probs.size()];
    for (int i = 0; i < probs.size(); i++) {
      normalized[i] = probs.get(i) / sum;
    }
    return new ProbVector(normalized);
  }

  /**
   * 전이 연산 시뮬레이션 (도메인 로직으로 교체)
   *
   * <p>예: 스타포스 강화, 큐브 사용, 플레임 적용 등
   */
  private TransitionResult computeTransition(State state) {
    // 도메인 로직으로 교체
    return computeTransitionWithSeed(state, 42);
  }

  /** 시드 기반 전이 연산 (도메인 로직으로 교체) */
  private TransitionResult computeTransitionWithSeed(State state, int seed) {
    // 도메인 로직으로 교체: 실제 확률 계산
    // 예시: 간단한 모의 전이
    java.util.Random random = new java.util.Random(seed);
    int nextStateCount = Math.min(5, state.value() + 1);
    double[] probs = new double[nextStateCount];
    double uniform = 1.0 / nextStateCount;
    java.util.Arrays.fill(probs, uniform);

    int[] nextStates = new int[nextStateCount];
    for (int i = 0; i < nextStateCount; i++) {
      nextStates[i] = state.value() + i;
    }

    return new TransitionResult(nextStates, probs);
  }

  // ============================================================
  // 헬퍼 클래스 (도메인에 맞게 수정하여 사용)
  // ============================================================

  /**
   * 확률벡터 레코드
   *
   * <p>MapleStory 적용 예:
   *
   * <ul>
   *   <li>큐브: {레전드, 에픽, 희귀, 없음} 확률
   *   <li>스타포스: {성공, 실패, 파괴, 유지} 확률
   *   <li>플레임: {상승, 유지, 하락} 확률
   * </ul>
   */
  record ProbVector(double[] probs) {
    double sum() {
      double s = 0.0;
      for (double p : probs) {
        s += p;
      }
      return s;
    }
  }

  /**
   * 상태 레코드
   *
   * <p>MapleStory 적용 예:
   *
   * <ul>
   *   <li>스타포스: 현재 스타 레벨 (0~15성)
   *   <li>잠재력: 현재 등급 (0=없음, 1=희귀, 2=에픽, 3=레전드)
   *   <li>플레임: 현재 스탯 합
   * </ul>
   */
  record State(int value) {}

  /**
   * 전이 결과 레코드
   *
   * <p>MapleStory 적용 예:
   *
   * <ul>
   *   <li>스타포스: {성공(+1), 실패(0), 파괴(0), 유지(0)}
   *   <li>큐브: {레전드(3), 에픽(2), 희귀(1), 없음(0)}
   * </ul>
   */
  static class TransitionResult {
    private final int[] nextStates;
    private final double[] probabilities;

    TransitionResult(int[] nextStates, double[] probabilities) {
      this.nextStates = nextStates;
      this.probabilities = probabilities;
    }

    boolean isValid() {
      return nextStates != null
          && probabilities != null
          && nextStates.length == probabilities.length
          && probabilities.length > 0;
    }

    double probabilitySum() {
      double sum = 0.0;
      for (double p : probabilities) {
        sum += p;
      }
      return sum;
    }

    int[] nextStates() {
      return nextStates.clone();
    }

    double[] probabilities() {
      return probabilities.clone();
    }
  }
}
