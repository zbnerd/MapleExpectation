package maple.expectation.domain.v2;

import maple.expectation.global.error.exception.InvalidPotentialGradeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PotentialGrade Enum 단위 테스트
 *
 * <p>Issue #197: CubeCostPolicy 입력값 검증을 위한 Grade Enum 테스트</p>
 */
@DisplayName("PotentialGrade Enum 테스트")
class PotentialGradeTest {

    @ParameterizedTest(name = "한글명 \"{0}\" -> {1}")
    @CsvSource({
            "레어, RARE",
            "에픽, EPIC",
            "유니크, UNIQUE",
            "레전드리, LEGENDARY"
    })
    @DisplayName("유효한 한글 등급명은 해당 Enum으로 변환된다")
    void fromKorean_validGrades_returnsCorrectEnum(String korean, PotentialGrade expected) {
        // When
        PotentialGrade result = PotentialGrade.fromKorean(korean);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("앞뒤 공백이 포함된 등급명은 trim 후 정상 변환된다")
    void fromKorean_withWhitespace_trimAndReturnsCorrectEnum() {
        // Given
        String gradeWithSpaces = "  레어  ";

        // When
        PotentialGrade result = PotentialGrade.fromKorean(gradeWithSpaces);

        // Then
        assertThat(result).isEqualTo(PotentialGrade.RARE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"레어레", "RARE", "rare", "Legendary", "없음", "테스트"})
    @DisplayName("유효하지 않은 등급명은 InvalidPotentialGradeException을 발생시킨다")
    void fromKorean_invalidGrades_throwsException(String invalidGrade) {
        // When & Then
        assertThatThrownBy(() -> PotentialGrade.fromKorean(invalidGrade))
                .isInstanceOf(InvalidPotentialGradeException.class)
                .hasMessageContaining("잠재능력 등급: " + invalidGrade);
    }

    @Test
    @DisplayName("null 등급명은 InvalidPotentialGradeException을 발생시킨다")
    void fromKorean_null_throwsException() {
        // When & Then
        assertThatThrownBy(() -> PotentialGrade.fromKorean(null))
                .isInstanceOf(InvalidPotentialGradeException.class)
                .hasMessageContaining("잠재능력 등급: null");
    }

    @Test
    @DisplayName("빈 문자열 등급명은 InvalidPotentialGradeException을 발생시킨다")
    void fromKorean_emptyString_throwsException() {
        // When & Then
        assertThatThrownBy(() -> PotentialGrade.fromKorean(""))
                .isInstanceOf(InvalidPotentialGradeException.class);
    }

    @Test
    @DisplayName("공백만 있는 등급명은 InvalidPotentialGradeException을 발생시킨다")
    void fromKorean_whitespaceOnly_throwsException() {
        // When & Then
        assertThatThrownBy(() -> PotentialGrade.fromKorean("   "))
                .isInstanceOf(InvalidPotentialGradeException.class);
    }

    @Test
    @DisplayName("getKoreanName()은 정확한 한글명을 반환한다")
    void getKoreanName_returnsCorrectKoreanName() {
        assertThat(PotentialGrade.RARE.getKoreanName()).isEqualTo("레어");
        assertThat(PotentialGrade.EPIC.getKoreanName()).isEqualTo("에픽");
        assertThat(PotentialGrade.UNIQUE.getKoreanName()).isEqualTo("유니크");
        assertThat(PotentialGrade.LEGENDARY.getKoreanName()).isEqualTo("레전드리");
    }
}
