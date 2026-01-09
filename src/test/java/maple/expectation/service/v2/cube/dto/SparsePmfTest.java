package maple.expectation.service.v2.cube.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 희소 확률질량함수(SparsePmf) 테스트
 *
 * <h3>핵심 검증 항목</h3>
 * <ul>
 *   <li>Map에서 생성 및 값 정렬</li>
 *   <li>불변식 검증 메서드</li>
 *   <li>Kahan summation 정밀도</li>
 * </ul>
 */
class SparsePmfTest {

    private static final double TOLERANCE = 1e-12;
    private static final double NEGATIVE_TOLERANCE = -1e-15;

    @Nested
    @DisplayName("생성 테스트")
    class CreationTest {

        @Test
        @DisplayName("Map에서 생성 시 키 기준 정렬되어야 한다")
        void from_map_sorts_by_key() {
            // Given: 정렬되지 않은 Map
            Map<Integer, Double> dist = Map.of(9, 0.1, 0, 0.7, 6, 0.2);

            // When
            SparsePmf pmf = SparsePmf.fromMap(dist);

            // Then: 키(값) 기준 오름차순 정렬
            assertThat(pmf.valueAt(0)).isEqualTo(0);
            assertThat(pmf.valueAt(1)).isEqualTo(6);
            assertThat(pmf.valueAt(2)).isEqualTo(9);
        }

        @Test
        @DisplayName("빈 Map에서 생성하면 빈 PMF가 되어야 한다")
        void from_empty_map_creates_empty_pmf() {
            // Given
            Map<Integer, Double> dist = Map.of();

            // When
            SparsePmf pmf = SparsePmf.fromMap(dist);

            // Then
            assertThat(pmf.size()).isEqualTo(0);
            assertThat(pmf.maxValue()).isEqualTo(0);
        }

        @Test
        @DisplayName("단일 항목 Map에서 정상 생성")
        void from_single_entry_map() {
            // Given
            Map<Integer, Double> dist = Map.of(12, 1.0);

            // When
            SparsePmf pmf = SparsePmf.fromMap(dist);

            // Then
            assertThat(pmf.size()).isEqualTo(1);
            assertThat(pmf.valueAt(0)).isEqualTo(12);
            assertThat(pmf.probAt(0)).isCloseTo(1.0, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("접근자 테스트")
    class AccessorTest {

        @Test
        @DisplayName("size는 non-zero 항 개수를 반환해야 한다")
        void size_returns_entry_count() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));

            // Then
            assertThat(pmf.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("valueAt은 인덱스의 값을 반환해야 한다")
        void value_at_returns_correct_value() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));

            // Then
            assertThat(pmf.valueAt(0)).isEqualTo(0);
            assertThat(pmf.valueAt(1)).isEqualTo(6);
            assertThat(pmf.valueAt(2)).isEqualTo(12);
        }

        @Test
        @DisplayName("probAt은 인덱스의 확률을 반환해야 한다")
        void prob_at_returns_correct_probability() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));

            // Then
            assertThat(pmf.probAt(0)).isCloseTo(0.5, within(TOLERANCE));
            assertThat(pmf.probAt(1)).isCloseTo(0.3, within(TOLERANCE));
            assertThat(pmf.probAt(2)).isCloseTo(0.2, within(TOLERANCE));
        }

        @Test
        @DisplayName("maxValue는 최대 값을 반환해야 한다")
        void max_value_returns_largest_value() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));

            // Then
            assertThat(pmf.maxValue()).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("질량 계산 테스트")
    class MassCalculationTest {

        @Test
        @DisplayName("totalMass는 확률 합을 반환해야 한다")
        void total_mass_returns_sum() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));

            // Then
            assertThat(pmf.totalMass()).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("totalMassKahan은 Kahan summation으로 정밀하게 계산해야 한다")
        void total_mass_kahan_uses_kahan_summation() {
            // Given: 많은 작은 확률들 (부동소수점 오차 유발 가능)
            Map<Integer, Double> dist = Map.of(
                    0, 0.1, 1, 0.1, 2, 0.1, 3, 0.1, 4, 0.1,
                    5, 0.1, 6, 0.1, 7, 0.1, 8, 0.1, 9, 0.1
            );
            SparsePmf pmf = SparsePmf.fromMap(dist);

            // Then: 정밀한 합계
            assertThat(pmf.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("빈 PMF의 질량은 0이어야 한다")
        void empty_pmf_has_zero_mass() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of());

            // Then
            assertThat(pmf.totalMass()).isEqualTo(0.0);
            assertThat(pmf.totalMassKahan()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("불변식 검증 테스트")
    class InvariantValidationTest {

        @Test
        @DisplayName("hasNegative는 음수 확률을 감지해야 한다")
        void has_negative_detects_negative_probability() {
            // Given: 음수 확률 포함
            int[] values = {0, 6, 12};
            double[] probs = {0.5, -0.1, 0.6};
            SparsePmf pmf = new SparsePmf(values, probs);

            // Then
            assertThat(pmf.hasNegative(NEGATIVE_TOLERANCE)).isTrue();
        }

        @Test
        @DisplayName("hasNegative는 정상 확률에서 false를 반환해야 한다")
        void has_negative_returns_false_for_valid_pmf() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));

            // Then
            assertThat(pmf.hasNegative(NEGATIVE_TOLERANCE)).isFalse();
        }

        @Test
        @DisplayName("hasNaNOrInf는 NaN을 감지해야 한다")
        void has_nan_or_inf_detects_nan() {
            // Given
            int[] values = {0, 6};
            double[] probs = {0.5, Double.NaN};
            SparsePmf pmf = new SparsePmf(values, probs);

            // Then
            assertThat(pmf.hasNaNOrInf()).isTrue();
        }

        @Test
        @DisplayName("hasNaNOrInf는 Infinity를 감지해야 한다")
        void has_nan_or_inf_detects_infinity() {
            // Given
            int[] values = {0, 6};
            double[] probs = {0.5, Double.POSITIVE_INFINITY};
            SparsePmf pmf = new SparsePmf(values, probs);

            // Then
            assertThat(pmf.hasNaNOrInf()).isTrue();
        }

        @Test
        @DisplayName("hasNaNOrInf는 정상 확률에서 false를 반환해야 한다")
        void has_nan_or_inf_returns_false_for_valid_pmf() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.5));

            // Then
            assertThat(pmf.hasNaNOrInf()).isFalse();
        }

        @Test
        @DisplayName("hasValueExceedingOne는 1 초과 확률을 감지해야 한다")
        void has_value_exceeding_one_detects_invalid_probability() {
            // Given
            int[] values = {0, 6};
            double[] probs = {0.5, 1.5};
            SparsePmf pmf = new SparsePmf(values, probs);

            // Then
            assertThat(pmf.hasValueExceedingOne()).isTrue();
        }

        @Test
        @DisplayName("hasValueExceedingOne는 정상 확률에서 false를 반환해야 한다")
        void has_value_exceeding_one_returns_false_for_valid_pmf() {
            // Given
            SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.5));

            // Then
            assertThat(pmf.hasValueExceedingOne()).isFalse();
        }
    }
}
