package maple.expectation.service.v2.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.error.exception.InvalidPotentialGradeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatchers;

/**
 * CubeCostPolicy 단위 테스트
 *
 * <p>Issue #197: 잘못된 등급 입력 시 Silent Failure(0원 반환) 대신 명시적 예외 발생 검증
 */
@DisplayName("CubeCostPolicy 테스트")
class CubeCostPolicyTest {

  private CubeCostPolicy cubeCostPolicy;
  private CostCalculationStrategy costStrategy;

  @BeforeEach
  void setUp() {
    costStrategy = mock(CostCalculationStrategy.class);
    given(costStrategy.calculateCost(ArgumentMatchers.any(), anyInt(), anyString())).willReturn(0L);
    cubeCostPolicy = new CubeCostPolicy(costStrategy);
  }

  @Nested
  @DisplayName("유효한 등급 테스트")
  class ValidGradeTest {

    @ParameterizedTest(name = "BLACK 큐브, 레벨 {0}, 등급 {1} -> {2}원")
    @CsvSource({
      "200, 레어, 4500000",
      "200, 에픽, 18000000",
      "200, 유니크, 38250000",
      "200, 레전드리, 45000000"
    })
    @DisplayName("BLACK 큐브 유효 등급은 정확한 비용을 반환한다")
    void getCubeCost_blackCube_validGrades(int level, String grade, long expectedCost) {
      // When
      long result = cubeCostPolicy.getCubeCost(CubeType.BLACK, level, grade);

      // Then
      assertThat(result).isEqualTo(expectedCost);
    }

    @ParameterizedTest(name = "ADDITIONAL 큐브, 레벨 {0}, 등급 {1} -> {2}원")
    @CsvSource({
      "200, 레어, 14625000",
      "200, 에픽, 40950000",
      "200, 유니크, 49725000",
      "200, 레전드리, 58500000"
    })
    @DisplayName("ADDITIONAL 큐브 유효 등급은 정확한 비용을 반환한다")
    void getCubeCost_additionalCube_validGrades(int level, String grade, long expectedCost) {
      // When
      long result = cubeCostPolicy.getCubeCost(CubeType.ADDITIONAL, level, grade);

      // Then
      assertThat(result).isEqualTo(expectedCost);
    }
  }

  @Nested
  @DisplayName("잘못된 등급 테스트")
  class InvalidGradeTest {

    @ParameterizedTest(name = "BLACK 큐브, 레벨 {0}, 등급 {1} -> {2}원")
    @CsvSource({
      "200, 레어, 4500000",
      "200, 에픽, 18000000",
      "200, 유니크, 38250000",
      "200, 레전드리, 45000000"
    })
    @DisplayName("BLACK 큐브 잘못된 등급은 Silent Failure(0원 반환) 대신 예외 발생")
    void getCubeCost_blackCube_invalidGrades_throws(int level, String grade) {
      // When & Then
      assertThatThrownBy(() -> cubeCostPolicy.getCubeCost(CubeType.BLACK, level, grade))
          .isInstanceOf(InvalidPotentialGradeException.class);
    }

    @ParameterizedTest(name = "ADDITIONAL 큐브, 레벨 {0}, 등급 {1} -> {2}원")
    @CsvSource({"200, 레어, 1462500", "200, 에픽, 4095000", "200, 유니크, 4972500", "200, 레전드리, 58500000"})
    @DisplayName("ADDITIONAL 큐브 잘못된 등급은 Silent Failure(0원 반환) 대신 예외 발생")
    void getCubeCost_additionalCube_invalidGrades_throws(int level, String grade) {
      // When & Then
      assertThatThrownBy(() -> cubeCostPolicy.getCubeCost(CubeType.ADDITIONAL, level, grade))
          .isInstanceOf(InvalidPotentialGradeException.class);
    }
  }
}
