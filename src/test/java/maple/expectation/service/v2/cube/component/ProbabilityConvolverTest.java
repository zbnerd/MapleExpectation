package maple.expectation.service.v2.cube.component;

import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.cube.dto.DensePmf;
import maple.expectation.service.v2.cube.dto.SparsePmf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DP Convolution 기반 확률 계산 테스트
 *
 * <h3>핵심 검증 항목</h3>
 * <ul>
 *   <li>브루트포스 교차검증 (2슬롯, 3슬롯)</li>
 *   <li>Clamp ON/OFF 결과 일치</li>
 *   <li>불변식 (질량 보존, NaN/Inf 없음)</li>
 *   <li>경계값 테스트</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProbabilityConvolverTest {

    @Mock
    private LogicExecutor executor;

    private ProbabilityConvolver convolver;
    private TailProbabilityCalculator tailCalculator;

    private static final double TOLERANCE = 1e-12;

    @BeforeEach
    void setUp() {
        // LogicExecutor passthrough 설정 (CLAUDE.md 정책: doAnswer)
        when(executor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
                .thenAnswer(invocation -> {
                    ThrowingSupplier<?> supplier = invocation.getArgument(0);
                    return supplier.get();
                });

        convolver = new ProbabilityConvolver(executor);
        tailCalculator = new TailProbabilityCalculator();
    }

    @Nested
    @DisplayName("2슬롯 브루트포스 교차검증")
    class TwoSlotBruteForceTest {

        @Test
        @DisplayName("균등 분포 - DP 결과는 브루트포스와 일치해야 한다")
        void dp_matches_brute_force_uniform_distribution() {
            // Given: 균등 분포
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 12;

            // When: DP 계산
            DensePmf totalPmf = convolver.convolveAll(List.of(slot1, slot2), target, true);
            double dpResult = tailCalculator.calculateTailProbability(totalPmf, target, true);

            // Then: 브루트포스와 비교
            double bruteForce = calculateBruteForce2(slot1, slot2, target);
            assertThat(dpResult).isCloseTo(bruteForce, within(TOLERANCE));
        }

        @Test
        @DisplayName("비균등 분포 - DP 결과는 브루트포스와 일치해야 한다")
        void dp_matches_brute_force_skewed_distribution() {
            // Given: 비균등 분포 (희귀 옵션 포함)
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.9, 6, 0.08, 12, 0.02));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.85, 9, 0.1, 12, 0.05));
            int target = 15;

            // When
            DensePmf totalPmf = convolver.convolveAll(List.of(slot1, slot2), target, true);
            double dpResult = tailCalculator.calculateTailProbability(totalPmf, target, true);

            // Then
            double bruteForce = calculateBruteForce2(slot1, slot2, target);
            assertThat(dpResult).isCloseTo(bruteForce, within(TOLERANCE));
        }

        @Test
        @DisplayName("단일 값만 있는 분포 - DP 결과는 브루트포스와 일치해야 한다")
        void dp_matches_brute_force_single_value() {
            // Given: 각 슬롯이 확정 값만 가짐
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(9, 1.0));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(12, 1.0));
            int target = 21;

            // When
            DensePmf totalPmf = convolver.convolveAll(List.of(slot1, slot2), target, true);
            double dpResult = tailCalculator.calculateTailProbability(totalPmf, target, true);

            // Then: 9 + 12 = 21 >= 21 이므로 확률 1.0
            double bruteForce = calculateBruteForce2(slot1, slot2, target);
            assertThat(dpResult).isCloseTo(1.0, within(TOLERANCE));
            assertThat(dpResult).isCloseTo(bruteForce, within(TOLERANCE));
        }

        private double calculateBruteForce2(SparsePmf s1, SparsePmf s2, int target) {
            double sum = 0.0;
            for (int i = 0; i < s1.size(); i++) {
                for (int j = 0; j < s2.size(); j++) {
                    int total = s1.valueAt(i) + s2.valueAt(j);
                    if (total >= target) {
                        sum += s1.probAt(i) * s2.probAt(j);
                    }
                }
            }
            return sum;
        }
    }

    @Nested
    @DisplayName("3슬롯 브루트포스 교차검증 (실제 엔진 수준)")
    class ThreeSlotBruteForceTest {

        @Test
        @DisplayName("동일 분포 3슬롯 - DP 결과는 브루트포스와 일치해야 한다")
        void dp_matches_brute_force_identical_slots() {
            // Given: 실제 엔진과 동일한 3슬롯
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot3 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 21;

            // When: DP 계산
            DensePmf totalPmf = convolver.convolveAll(List.of(slot1, slot2, slot3), target, true);
            double dpResult = tailCalculator.calculateTailProbability(totalPmf, target, true);

            // Then: 브루트포스와 비교
            double bruteForce = calculateBruteForce3(slot1, slot2, slot3, target);
            assertThat(dpResult).isCloseTo(bruteForce, within(TOLERANCE));
        }

        @Test
        @DisplayName("서로 다른 분포 3슬롯 - DP 결과는 브루트포스와 일치해야 한다")
        void dp_matches_brute_force_different_slots() {
            // Given: 서로 다른 분포
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.6, 6, 0.3, 12, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.75, 9, 0.2, 12, 0.05));
            SparsePmf slot3 = SparsePmf.fromMap(Map.of(0, 0.8, 6, 0.15, 9, 0.05));
            int target = 18;

            // When
            DensePmf totalPmf = convolver.convolveAll(List.of(slot1, slot2, slot3), target, true);
            double dpResult = tailCalculator.calculateTailProbability(totalPmf, target, true);

            // Then
            double bruteForce = calculateBruteForce3(slot1, slot2, slot3, target);
            assertThat(dpResult).isCloseTo(bruteForce, within(TOLERANCE));
        }

        @Test
        @DisplayName("희귀 옵션 포함 3슬롯 - DP 결과는 브루트포스와 일치해야 한다")
        void dp_matches_brute_force_rare_options() {
            // Given: 희귀 옵션 (낮은 확률)
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.95, 6, 0.04, 12, 0.01));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.95, 6, 0.04, 12, 0.01));
            SparsePmf slot3 = SparsePmf.fromMap(Map.of(0, 0.95, 6, 0.04, 12, 0.01));
            int target = 30;

            // When
            DensePmf totalPmf = convolver.convolveAll(List.of(slot1, slot2, slot3), target, true);
            double dpResult = tailCalculator.calculateTailProbability(totalPmf, target, true);

            // Then
            double bruteForce = calculateBruteForce3(slot1, slot2, slot3, target);
            assertThat(dpResult).isCloseTo(bruteForce, within(TOLERANCE));
        }

        private double calculateBruteForce3(SparsePmf s1, SparsePmf s2, SparsePmf s3, int target) {
            double sum = 0.0;
            for (int i = 0; i < s1.size(); i++) {
                for (int j = 0; j < s2.size(); j++) {
                    for (int k = 0; k < s3.size(); k++) {
                        int total = s1.valueAt(i) + s2.valueAt(j) + s3.valueAt(k);
                        if (total >= target) {
                            sum += s1.probAt(i) * s2.probAt(j) * s3.probAt(k);
                        }
                    }
                }
            }
            return sum;
        }
    }

    @Nested
    @DisplayName("Clamp ON/OFF 결과 일치 테스트")
    class ClampOnOffConsistencyTest {

        @Test
        @DisplayName("2슬롯 - Clamp ON/OFF 결과는 동일해야 한다")
        void clamp_on_off_consistent_two_slots() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 12;

            // When: Clamp ON
            DensePmf clampOn = convolver.convolveAll(List.of(slot1, slot2), target, true);
            double resultOn = tailCalculator.calculateTailProbability(clampOn, target, true);

            // When: Clamp OFF
            DensePmf clampOff = convolver.convolveAll(List.of(slot1, slot2), target, false);
            double resultOff = tailCalculator.calculateTailProbability(clampOff, target, false);

            // Then: 결과 일치
            assertThat(resultOn).isCloseTo(resultOff, within(TOLERANCE));
        }

        @Test
        @DisplayName("3슬롯 - Clamp ON/OFF 결과는 동일해야 한다")
        void clamp_on_off_consistent_three_slots() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot3 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 15;

            // When: Clamp ON
            DensePmf clampOn = convolver.convolveAll(List.of(slot1, slot2, slot3), target, true);
            double resultOn = tailCalculator.calculateTailProbability(clampOn, target, true);

            // When: Clamp OFF
            DensePmf clampOff = convolver.convolveAll(List.of(slot1, slot2, slot3), target, false);
            double resultOff = tailCalculator.calculateTailProbability(clampOff, target, false);

            // Then: 결과 일치
            assertThat(resultOn).isCloseTo(resultOff, within(TOLERANCE));
        }

        @Test
        @DisplayName("Clamp ON 시 상태 크기는 target + 1이어야 한다")
        void clamp_on_state_size_equals_target_plus_one() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 15;

            // When: Clamp ON
            DensePmf clampOn = convolver.convolveAll(List.of(slot1, slot2), target, true);

            // Then: 상태 크기 = target + 1
            assertThat(clampOn.size()).isEqualTo(target + 1);
        }
    }

    @Nested
    @DisplayName("불변식 테스트")
    class InvariantTest {

        @Test
        @DisplayName("합성곱 후 질량 합은 1이어야 한다")
        void total_mass_equals_one_after_convolution() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 15;

            // When
            DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);

            // Then: 질량 보존
            assertThat(result.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("합성곱 후 NaN/Inf가 없어야 한다")
        void no_nan_or_inf_after_convolution() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 9, 0.2));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.6, 9, 0.4));
            int target = 12;

            // When
            DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);

            // Then
            assertThat(result.hasNaNOrInf()).isFalse();
        }

        @Test
        @DisplayName("합성곱 후 음수 확률이 없어야 한다")
        void no_negative_probability_after_convolution() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 9, 0.2));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.6, 9, 0.4));
            int target = 12;

            // When
            DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);

            // Then
            assertThat(result.hasNegative(-1e-15)).isFalse();
        }

        @Test
        @DisplayName("합성곱 후 1을 초과하는 확률이 없어야 한다")
        void no_probability_exceeding_one_after_convolution() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 9, 0.2));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.6, 9, 0.4));
            int target = 12;

            // When
            DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);

            // Then
            assertThat(result.hasValueExceedingOne()).isFalse();
        }
    }

    @Nested
    @DisplayName("경계값 테스트")
    class BoundaryTest {

        @Test
        @DisplayName("target = 0 이면 모든 결과가 성공이므로 확률 1.0")
        void target_zero_returns_probability_one() {
            // Given
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 0;

            // When
            DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);
            double probability = tailCalculator.calculateTailProbability(result, target, true);

            // Then: target=0이면 모든 합이 0 이상이므로 확률 1.0
            assertThat(probability).isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("target > maxSum 이면 확률 0.0 (Clamp OFF)")
        void target_exceeds_max_sum_returns_zero_clamp_off() {
            // Given: maxSum = 9 + 9 = 18
            SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
            int target = 100;  // maxSum (18) 초과

            // When (Clamp OFF)
            DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, false);
            double probability = tailCalculator.calculateTailProbability(result, target, false);

            // Then: 도달 불가능하므로 0.0
            assertThat(probability).isCloseTo(0.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("단일 슬롯 합성곱 - 원본과 동일")
        void single_slot_convolution_returns_original() {
            // Given
            SparsePmf slot = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));
            int target = 6;

            // When
            DensePmf result = convolver.convolveAll(List.of(slot), target, true);
            double probability = tailCalculator.calculateTailProbability(result, target, true);

            // Then: P(X >= 6) = P(6) + P(12) = 0.3 + 0.2 = 0.5
            assertThat(probability).isCloseTo(0.5, within(TOLERANCE));
        }
    }
}
