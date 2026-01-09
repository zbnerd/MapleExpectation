package maple.expectation.service.v2.cube.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 밀집 확률질량함수(DensePmf) 테스트
 *
 * <h3>핵심 검증 항목</h3>
 * <ul>
 *   <li>배열에서 생성 (방어적 복사)</li>
 *   <li>인덱스 기반 접근</li>
 *   <li>불변식 검증 메서드</li>
 *   <li>Kahan summation 정밀도</li>
 * </ul>
 */
class DensePmfTest {

    private static final double TOLERANCE = 1e-12;
    private static final double NEGATIVE_TOLERANCE = -1e-15;

    @Nested
    @DisplayName("생성 테스트")
    class CreationTest {

        @Test
        @DisplayName("fromArray는 방어적 복사를 수행해야 한다")
        void from_array_creates_defensive_copy() {
            // Given
            double[] original = {0.3, 0.4, 0.3};
            DensePmf pmf = DensePmf.fromArray(original);

            // When: 원본 배열 수정
            original[0] = 0.0;

            // Then: PMF는 영향받지 않음
            assertThat(pmf.massAt(0)).isCloseTo(0.3, within(TOLERANCE));
        }

        @Test
        @DisplayName("빈 배열에서 생성하면 빈 PMF가 되어야 한다")
        void from_empty_array_creates_empty_pmf() {
            // Given
            double[] arr = {};

            // When
            DensePmf pmf = DensePmf.fromArray(arr);

            // Then
            assertThat(pmf.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("단일 요소 배열에서 정상 생성")
        void from_single_element_array() {
            // Given
            double[] arr = {1.0};

            // When
            DensePmf pmf = DensePmf.fromArray(arr);

            // Then
            assertThat(pmf.size()).isEqualTo(1);
            assertThat(pmf.massAt(0)).isCloseTo(1.0, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("접근자 테스트")
    class AccessorTest {

        @Test
        @DisplayName("size는 배열 길이를 반환해야 한다")
        void size_returns_array_length() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.2, 0.3, 0.5});

            // Then
            assertThat(pmf.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("massAt은 해당 값의 질량을 반환해야 한다")
        void mass_at_returns_correct_value() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.2, 0.3, 0.5});

            // Then
            assertThat(pmf.massAt(0)).isCloseTo(0.2, within(TOLERANCE));
            assertThat(pmf.massAt(1)).isCloseTo(0.3, within(TOLERANCE));
            assertThat(pmf.massAt(2)).isCloseTo(0.5, within(TOLERANCE));
        }

        @Test
        @DisplayName("massAt은 범위 밖 인덱스에 0을 반환해야 한다")
        void mass_at_returns_zero_for_out_of_range() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.5, 0.5});

            // Then: 범위 밖
            assertThat(pmf.massAt(-1)).isEqualTo(0.0);
            assertThat(pmf.massAt(10)).isEqualTo(0.0);
            assertThat(pmf.massAt(100)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("질량 계산 테스트")
    class MassCalculationTest {

        @Test
        @DisplayName("totalMass는 질량 합을 반환해야 한다")
        void total_mass_returns_sum() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.2, 0.3, 0.5});

            // Then
            assertThat(pmf.totalMass()).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("totalMassKahan은 Kahan summation으로 정밀하게 계산해야 한다")
        void total_mass_kahan_uses_kahan_summation() {
            // Given: 많은 작은 확률들
            double[] mass = new double[1000];
            for (int i = 0; i < 1000; i++) {
                mass[i] = 0.001;
            }
            DensePmf pmf = DensePmf.fromArray(mass);

            // Then: 정밀한 합계
            assertThat(pmf.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("빈 PMF의 질량은 0이어야 한다")
        void empty_pmf_has_zero_mass() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{});

            // Then
            assertThat(pmf.totalMass()).isEqualTo(0.0);
            assertThat(pmf.totalMassKahan()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("불변식 검증 테스트")
    class InvariantValidationTest {

        @Test
        @DisplayName("hasNegative는 음수 질량을 감지해야 한다")
        void has_negative_detects_negative_mass() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.5, -0.1, 0.6});

            // Then
            assertThat(pmf.hasNegative(NEGATIVE_TOLERANCE)).isTrue();
        }

        @Test
        @DisplayName("hasNegative는 정상 질량에서 false를 반환해야 한다")
        void has_negative_returns_false_for_valid_pmf() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.3, 0.4, 0.3});

            // Then
            assertThat(pmf.hasNegative(NEGATIVE_TOLERANCE)).isFalse();
        }

        @Test
        @DisplayName("hasNaNOrInf는 NaN을 감지해야 한다")
        void has_nan_or_inf_detects_nan() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.5, Double.NaN});

            // Then
            assertThat(pmf.hasNaNOrInf()).isTrue();
        }

        @Test
        @DisplayName("hasNaNOrInf는 Infinity를 감지해야 한다")
        void has_nan_or_inf_detects_infinity() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.5, Double.POSITIVE_INFINITY});

            // Then
            assertThat(pmf.hasNaNOrInf()).isTrue();
        }

        @Test
        @DisplayName("hasNaNOrInf는 정상 질량에서 false를 반환해야 한다")
        void has_nan_or_inf_returns_false_for_valid_pmf() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.5, 0.5});

            // Then
            assertThat(pmf.hasNaNOrInf()).isFalse();
        }

        @Test
        @DisplayName("hasValueExceedingOne는 1 초과 질량을 감지해야 한다")
        void has_value_exceeding_one_detects_invalid_mass() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.5, 1.5});

            // Then
            assertThat(pmf.hasValueExceedingOne()).isTrue();
        }

        @Test
        @DisplayName("hasValueExceedingOne는 정상 질량에서 false를 반환해야 한다")
        void has_value_exceeding_one_returns_false_for_valid_pmf() {
            // Given
            DensePmf pmf = DensePmf.fromArray(new double[]{0.5, 0.5});

            // Then
            assertThat(pmf.hasValueExceedingOne()).isFalse();
        }

        @Test
        @DisplayName("hasValueExceedingOne는 정확히 1.0은 허용해야 한다")
        void has_value_exceeding_one_allows_exactly_one() {
            // Given: 확률이 정확히 1.0인 경우
            DensePmf pmf = DensePmf.fromArray(new double[]{0.0, 1.0, 0.0});

            // Then: 1.0은 유효
            assertThat(pmf.hasValueExceedingOne()).isFalse();
        }
    }

    @Nested
    @DisplayName("Tail Clamp 시나리오 테스트")
    class TailClampScenarioTest {

        @Test
        @DisplayName("Tail Clamp 적용 후 마지막 버킷이 꼬리 확률을 포함해야 한다")
        void tail_clamp_bucket_contains_tail_probability() {
            // Given: target=5인 경우, 인덱스 0~5 (크기 6)
            // 마지막 버킷(5)에 P(X >= 5)가 누적된다고 가정
            double[] mass = {0.1, 0.1, 0.1, 0.1, 0.1, 0.5};  // 합=1.0
            DensePmf pmf = DensePmf.fromArray(mass);

            // Then
            assertThat(pmf.size()).isEqualTo(6);  // target + 1
            assertThat(pmf.massAt(5)).isCloseTo(0.5, within(TOLERANCE));
            assertThat(pmf.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));
        }
    }
}
