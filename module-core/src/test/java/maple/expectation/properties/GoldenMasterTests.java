package maple.expectation.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 골든 마스터(Golden Master) 회귀 테스트 템플릿
 *
 * <h3>검증하는 불변식</h3>
 *
 * <ul>
 *   <li>고정 입력에 대한 출력 불변성 (회귀 방지)
 *   <li>스냅샷 저장된 값과의 일치성
 *   <li>알고리즘 변경 시 의도치 않은 동작 변경 감지
 * </ul>
 *
 * <h3>MapleStory 도메인 적용 예시</h3>
 *
 * <ul>
 *   <li>스타포스: 고정 입력에서 기대 비용 계산
 *   <li>큐브: 시드 42에서의 시뮬레이션 결과
 *   <li>플레임: 고정 스탯에서의 옵션 계산
 * </ul>
 *
 * <h3>사용법 (도메인에 맞게 커스터마이징)</h3>
 *
 * <pre>{@code
 * // 1. 골든 값 저장 (초기 실행 시)
 * @Test
 * @Tag("snapshot")
 * void golden_master_cost_calculation() {
 *     var input = new StarforceInput(10, 150); // 10성, 150렙 장비
 *     double result = calculator.calculateCost(input);
 *     // 첫 실행: 이 값이 골든 마스터
 *     assertThat(result).isEqualTo(12345678.0);
 * }
 *
 * // 2. 알고리즘 변경 후 회귀 검증
 * @Test
 * void verify_no_regression_after_refactor() {
 *     // 변경 전과 동일한 입력
 *     var input = new StarforceInput(10, 150);
 *     double result = newRefactoredCalculator.calculateCost(input);
 *     // 골든 마스터와 동일해야 함
 *     assertThat(result).isEqualTo(GOLDEN_MASTER_COST);
 * }
 * }</pre>
 *
 * <h3>주의사항</h3>
 *
 * <ul>
 *   <li>골든 값은 <strong>도메인 전문가 검증</strong> 후 설정해야 함
 *   <li>의도적인 알고리즘 변경 시 골든 값 업데이트 필요
 *   <li>부동소수점 오차 범위(epsilon) 고려 필요
 * </ul>
 */
@DisplayName("골든 마스터 회귀 테스트")
@Tag("snapshot")
@Disabled(
    "Template - wire to domain classes (StarforceCalculator, CubeCostCalculator) and verify golden master values before enabling")
class GoldenMasterTests {

  private static final double EPSILON = 1e-10;
  private static final double COST_EPSILON = 1.0; // 비용은 1메소 단위

  // ============================================================
  // 템플릿 10: 골든 마스터 스냅샷 회귀 테스트
  // ============================================================

  /**
   * 골든 마스터 1: 스타포스 강화 비용
   *
   * <p>고정 입력: 10성, 150레벨 장비, 스타캐치 미사용
   *
   * <p>예상 출력: 정확한 기대 비용 (메소)
   */
  @Test
  @DisplayName("고정 입력에서 스타포스 비용 계산")
  void golden_master_starforce_cost_calculation() {
    // Given: 고정된 입력
    int currentStar = 10;
    int itemLevel = 150;
    boolean useStarCatch = false;
    boolean useDiscount = false;

    // When: 비용 계산
    double result =
        calculateStarforceExpectedCost(currentStar, itemLevel, useStarCatch, useDiscount);

    // Then: 골든 마스터 값과 일치
    // baseCost = 1000 + 10*10*1000 + 150*100 = 116000
    final double GOLDEN_MASTER = 116000.0;
    assertThat(result).isCloseTo(GOLDEN_MASTER, within(COST_EPSILON));
  }

  /**
   * 골든 마스터 2: 큐브 시뮬레이션 결과
   *
   * <p>고정 입력: 시드 42, 3슬롯, 목표 등급 레전드
   *
   * <p>예상 출력: P(성공) 확률
   */
  @Test
  @DisplayName("고정 시드에서 큐브 시뮬레이션 결과")
  void golden_master_cube_simulation() {
    // Given: 고정된 입력
    int seed = 42;
    int slotCount = 3;
    int targetGrade = 3; // 레전드

    // When: 시뮬레이션
    double result = simulateCubeSuccessProbability(seed, slotCount, targetGrade);

    // Then: 골든 마스터 값과 일치
    // Random(42)의 nextDouble() 값들은 0.03보다 크므로 실패 -> 0.0
    final double GOLDEN_MASTER = 0.0;
    assertThat(result).isCloseTo(GOLDEN_MASTER, within(EPSILON));
  }

  /**
   * 골든 마스터 3: 플레임 옵션 계산
   *
   * <p>고정 입력: 장비 레벨 150, 직업 class
   *
   * <p>예상 출력: 기대 스탯 합
   */
  @Test
  @DisplayName("고정 입력에서 플레임 기대 스탯")
  void golden_master_flame_expected_stats() {
    // Given
    int itemLevel = 150;
    String jobClass = "warrior";

    // When
    double result = calculateFlameExpectedStats(itemLevel, jobClass);

    // Then
    // 150 * 0.3 + 5.0 = 50.0
    final double GOLDEN_MASTER = 50.0;
    assertThat(result).isCloseTo(GOLDEN_MASTER, within(EPSILON));
  }

  /**
   * 골든 마스터 4: 다양한 레벨에서의 비용 계산
   *
   * <p>CSV 파라미터화로 여러 입력 조건 검증
   */
  @ParameterizedTest
  @CsvSource({
    "0, 150, false, 16000.0", // 0성: 1000 + 0 + 15000 = 16000
    "5, 150, false, 41000.0", // 5성: 1000 + 25000 + 15000 = 41000
    "10, 150, false, 116000.0", // 10성: 1000 + 100000 + 15000 = 116000
    "15, 150, false, 241000.0", // 15성: 1000 + 225000 + 15000 = 241000
    "10, 150, true, 110200.0" // 스타캐치 사용 (5% 할인): 116000 * 0.95 = 110200
  })
  @DisplayName("다양한 조건에서 스타포스 비용 계산")
  void golden_master_starforce_various_conditions(
      int starLevel, int itemLevel, boolean useStarCatch, double goldenValue) {

    double result = calculateStarforceExpectedCost(starLevel, itemLevel, useStarCatch, false);

    assertThat(result).isCloseTo(goldenValue, within(COST_EPSILON));
  }

  /**
   * 골든 마스터 5: 시드 결정성 확인
   *
   * <p>같은 시드는 항상 같은 결과를 생성해야 함
   */
  @Test
  @DisplayName("시드 결정성: 같은 시드는 같은 결과")
  void golden_master_seed_determinism() {
    int seed = 12345;

    double r1 = simulateWithSeed(seed);
    double r2 = simulateWithSeed(seed);
    double r3 = simulateWithSeed(seed);

    // 세 번 모두 같은 결과
    assertThat(r1).isEqualTo(r2);
    assertThat(r2).isEqualTo(r3);

    // 골든 마스터와도 일치
    // Random(12345).nextDouble() = 0.3618031071604718
    final double GOLDEN_MASTER = 0.3618031071604718;
    assertThat(r1).isCloseTo(GOLDEN_MASTER, within(EPSILON));
  }

  /**
   * 골든 마스터 6: 여러 시드에서의 결과값
   *
   * <p>값 소스 파라미터화로 여러 시드 검증
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 42, 12345, 67890, 999999})
  @DisplayName("다양한 시드에서 시뮬레이션 결과")
  void golden_master_various_seeds(int seed) {
    double result = simulateWithSeed(seed);

    // 각 시드에 대한 기대값 (Java Random.nextdouble() 결과)
    double[] goldenValues = {
      0.730967787376657, // seed 0
      0.7275636800328681, // seed 42
      0.3618031071604718, // seed 12345
      0.8069113411345074, // seed 67890
      0.37776208404219835 // seed 999999
    };

    int index = findSeedIndex(seed);
    if (index >= 0) {
      assertThat(result).isCloseTo(goldenValues[index], within(EPSILON));
    }
  }

  /**
   * 골든 마스터 7: 반복 실행으로 일관성 확인
   *
   * <p>여러 번 실행해도 같은 결과 (결정론적 동작)
   */
  @RepeatedTest(value = 5, name = "{displayName} {currentRepetition}/{totalRepetitions}")
  @DisplayName("반복 실행에서 일관된 결과")
  void golden_master_repeated_consistency() {
    int seed = 42;
    double result = simulateWithSeed(seed);

    // 골든 마스터와 항상 일치
    final double GOLDEN_MASTER = 0.7275636800328681;
    assertThat(result).isCloseTo(GOLDEN_MASTER, within(EPSILON));
  }

  /**
   * 골든 마스터 8: 복잡한 입력 조합
   *
   * <p>여러 파라미터가 조합된 경우
   */
  @Test
  @DisplayName("복잡한 조합에서의 계산 결과")
  void golden_master_complex_combination() {
    // Given: 여러 파라미터 조합
    ComplexInput input =
        new ComplexInput(
            10, // starLevel
            150, // itemLevel
            true, // useStarCatch
            true, // useDiscount
            42 // seed
            );

    // When
    ComplexResult result = calculateComplex(input);

    // Then: 골든 마스터와 일치
    // cost = 116000 * 0.95 * 0.7 = 77140
    // prob = 0.0 (seed 42 fails all rolls)
    final double GOLDEN_MASTER_COST = 77140.0;
    final double GOLDEN_MASTER_PROB = 0.0;

    assertThat(result.expectedCost()).isCloseTo(GOLDEN_MASTER_COST, within(COST_EPSILON));
    assertThat(result.successProbability()).isCloseTo(GOLDEN_MASTER_PROB, within(EPSILON));
  }

  /**
   * 골든 마스터 9: 경계값 입력
   *
   * <p>최소/최대 입력에서의 결과
   */
  @ParameterizedTest
  @CsvSource({
    "0, 100, false, 11000.0", // 최소 레벨: 1000 + 0 + 10000 = 11000
    "25, 300, false, 656000.0", // 최대 레벨: 1000 + 625000 + 30000 = 656000
    "15, 200, true, 233700.0" // 중간: (1000 + 225000 + 20000) * 0.95 = 233700
  })
  @DisplayName("경계값 입력에서의 계산")
  void golden_master_boundary_values(
      int starLevel, int itemLevel, boolean useStarCatch, double goldenValue) {

    double result = calculateStarforceExpectedCost(starLevel, itemLevel, useStarCatch, false);

    assertThat(result).isCloseTo(goldenValue, within(COST_EPSILON));
  }

  /**
   * 골든 마스터 10: 알고리즘 최적화 후 회귀 확인
   *
   * <p>성능 최적화로 알고리즘을 변경한 경우, 결과는 동일해야 함
   */
  @Test
  @DisplayName("알고리즘 변경 후 회귀 없음")
  void golden_master_no_regression_after_optimization() {
    int starLevel = 10;
    int itemLevel = 150;

    // 기존 알고리즘 결과
    double baseline = calculateStarforceExpectedCost(starLevel, itemLevel, false, false);

    // 최적화된 알고리즘 결과 (구현 후 교체)
    double optimized = calculateOptimizedStarforceExpectedCost(starLevel, itemLevel, false, false);

    // 두 결과는 동일해야 함
    assertThat(optimized).isCloseTo(baseline, within(EPSILON));

    // 골든 마스터와도 일치
    final double GOLDEN_MASTER = 116000.0;
    assertThat(optimized).isCloseTo(GOLDEN_MASTER, within(COST_EPSILON));
  }

  // ============================================================
  // 시뮬레이션 메서드 (도메인 로직으로 교체)
  // ============================================================

  /** 스타포스 기대 비용 계산 (도메인 로직으로 교체) */
  private double calculateStarforceExpectedCost(
      int currentStar, int itemLevel, boolean useStarCatch, boolean useDiscount) {

    // 도메인 로직으로 교체
    // 예시 시뮬레이션:
    double baseCost = 1000.0 + currentStar * currentStar * 1000.0 + itemLevel * 100.0;

    if (useStarCatch) {
      baseCost *= 0.95; // 5% 할인
    }
    if (useDiscount) {
      baseCost *= 0.7; // 30% 할인
    }

    return baseCost;
  }

  /** 큐브 성공 확률 시뮬레이션 (도메인 로직으로 교체) */
  private double simulateCubeSuccessProbability(int seed, int slotCount, int targetGrade) {
    Random random = new Random(seed);
    double successProbability = 0.03; // 레전드 3%

    // 슬롯 수에 따른 확률 계산 (도메인 로직으로 교체)
    for (int i = 0; i < slotCount; i++) {
      double roll = random.nextDouble();
      if (roll < successProbability) {
        return 1.0; // 성공
      }
    }

    return 0.0; // 실패
  }

  /** 플레임 기대 스탯 계산 (도메인 로직으로 교체) */
  private double calculateFlameExpectedStats(int itemLevel, String jobClass) {
    // 도메인 로직으로 교체
    return itemLevel * 0.3 + 5.0; // 예시
  }

  /** 시드 기반 시뮬레이션 (도메인 로직으로 교체) */
  private double simulateWithSeed(int seed) {
    Random random = new Random(seed);
    return random.nextDouble();
  }

  /** 복잡한 계산 (도메인 로직으로 교체) */
  private ComplexResult calculateComplex(ComplexInput input) {
    double cost =
        calculateStarforceExpectedCost(
            input.starLevel(), input.itemLevel(),
            input.useStarCatch(), input.useDiscount());

    double prob = simulateCubeSuccessProbability(input.seed(), 3, 3);

    return new ComplexResult(cost, prob);
  }

  /** 최적화된 비용 계산 (도메인 로직으로 교체) */
  private double calculateOptimizedStarforceExpectedCost(
      int currentStar, int itemLevel, boolean useStarCatch, boolean useDiscount) {

    // 최적화된 버전 (기존과 동일한 결과여야 함)
    return calculateStarforceExpectedCost(currentStar, itemLevel, useStarCatch, useDiscount);
  }

  /** 시드 인덱스 찾기 (헬퍼) */
  private int findSeedIndex(int seed) {
    int[] seeds = {0, 42, 12345, 67890, 999999};
    for (int i = 0; i < seeds.length; i++) {
      if (seeds[i] == seed) return i;
    }
    return -1;
  }

  // ============================================================
  // 헬퍼 클래스 (도메인에 맞게 수정)
  // ============================================================

  /** 복잡한 입력 */
  record ComplexInput(
      int starLevel, int itemLevel, boolean useStarCatch, boolean useDiscount, int seed) {}

  /** 복잡한 결과 */
  record ComplexResult(double expectedCost, double successProbability) {}
}
