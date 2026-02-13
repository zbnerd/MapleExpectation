package maple.expectation.service.v2.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.error.exception.InvalidPotentialGradeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CubeCostPolicy 단위 테스트
 *
 * <p>Issue #197: 잘못된 등급 입력 시 Silent Failure(0원 반환) 대신 명시적 예외 발생 검증
 */
@DisplayName("CubeCostPolicy 테스트")
class CubeCostPolicyTest {

  private CubeCostPolicy cubeCostPolicy;

  @BeforeEach
  void setUp() {
    cubeCostPolicy = new CubeCostPolicy();
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

    @Test
    @DisplayName("RED 큐브는 모든 등급에서 1을 반환한다 (시세 곱셈용)")
    void getCubeCost_redCube_returnsOne() {
      assertThat(cubeCostPolicy.getCubeCost(CubeType.RED, 200, "레어")).isEqualTo(1L);
      assertThat(cubeCostPolicy.getCubeCost(CubeType.RED, 200, "에픽")).isEqualTo(1L);
      assertThat(cubeCostPolicy.getCubeCost(CubeType.RED, 200, "유니크")).isEqualTo(1L);
      assertThat(cubeCostPolicy.getCubeCost(CubeType.RED, 200, "레전드리")).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("레벨 구간 테스트")
  class LevelBracketTest {

    @ParameterizedTest(name = "레벨 {0} -> 레벨 구간 {1}의 비용 적용")
    @CsvSource({
      "100, 4000000", // 0-159 구간 -> 0레벨 테이블
      "159, 4000000", // 0-159 구간
      "160, 4250000", // 160-199 구간
      "199, 4250000", // 160-199 구간
      "200, 4500000", // 200-249 구간
      "249, 4500000", // 200-249 구간
      "250, 5000000", // 250+ 구간
      "300, 5000000" // 250+ 구간
    })
    @DisplayName("레벨에 맞는 하위 구간 비용이 적용된다")
    void getCubeCost_levelBrackets_appliesCorrectCost(int level, long expectedCost) {
      // When
      long result = cubeCostPolicy.getCubeCost(CubeType.BLACK, level, "레어");

      // Then
      assertThat(result).isEqualTo(expectedCost);
    }
  }

  @Nested
  @DisplayName("잘못된 등급 테스트 (Issue #197)")
  class InvalidGradeTest {

    @ParameterizedTest
    @ValueSource(strings = {"레어레", "RARE", "rare", "Legendary", "없음", "테스트"})
    @DisplayName("잘못된 등급명은 InvalidPotentialGradeException을 발생시킨다")
    void getCubeCost_invalidGrade_throwsException(String invalidGrade) {
      // When & Then
      assertThatThrownBy(() -> cubeCostPolicy.getCubeCost(CubeType.BLACK, 200, invalidGrade))
          .isInstanceOf(InvalidPotentialGradeException.class);
    }

    @Test
    @DisplayName("null 등급은 InvalidPotentialGradeException을 발생시킨다")
    void getCubeCost_nullGrade_throwsException() {
      // When & Then
      assertThatThrownBy(() -> cubeCostPolicy.getCubeCost(CubeType.BLACK, 200, null))
          .isInstanceOf(InvalidPotentialGradeException.class);
    }

    @Test
    @DisplayName("빈 문자열 등급은 InvalidPotentialGradeException을 발생시킨다")
    void getCubeCost_emptyGrade_throwsException() {
      // When & Then
      assertThatThrownBy(() -> cubeCostPolicy.getCubeCost(CubeType.BLACK, 200, ""))
          .isInstanceOf(InvalidPotentialGradeException.class);
    }

    @Test
    @DisplayName("잘못된 등급이 Silent Failure(0원)를 반환하지 않는다")
    void getCubeCost_invalidGrade_doesNotReturnZero() {
      // Given: 이전 코드는 getOrDefault(grade, 0L)로 0을 반환했음
      String invalidGrade = "존재하지않는등급";

      // When & Then: 0원 대신 예외가 발생해야 함
      assertThatThrownBy(() -> cubeCostPolicy.getCubeCost(CubeType.BLACK, 200, invalidGrade))
          .isInstanceOf(InvalidPotentialGradeException.class);
    }
  }

  @Nested
  @DisplayName("앞뒤 공백 처리 테스트")
  class WhitespaceTrimTest {

    @Test
    @DisplayName("등급명 앞뒤 공백은 trim되어 정상 처리된다")
    void getCubeCost_gradeWithWhitespace_trimAndProcess() {
      // Given
      String gradeWithSpaces = "  레어  ";

      // When
      long result = cubeCostPolicy.getCubeCost(CubeType.BLACK, 200, gradeWithSpaces);

      // Then
      assertThat(result).isEqualTo(4500000L);
    }
  }
}
