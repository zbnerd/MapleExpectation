package maple.expectation.service.v2.cube.dto;

import java.util.List;
import java.util.Map;

/**
 * 희소 확률질량함수 (Sparse PMF)
 *
 * <p>용도: 슬롯별 분포 (non-zero 항이 적음, K가 작음)</p>
 * <p>불변(Immutable) - 방어적 복사로 보장</p>
 *
 * <h3>핵심 가정</h3>
 * <ul>
 *   <li>각 슬롯(라인)은 독립적으로 옵션을 추첨한다</li>
 *   <li>슬롯 간 추첨은 독립이다 (조건부 확률 아님)</li>
 *   <li>같은 옵션이 여러 슬롯에 중복 등장 가능하다</li>
 * </ul>
 *
 * <h3>P0: 불변성 보장</h3>
 * <ul>
 *   <li>Canonical constructor에서 방어적 복사</li>
 *   <li>Accessor에서 방어적 복사</li>
 * </ul>
 */
public record SparsePmf(int[] values, double[] probs) {

    /**
     * P0: Canonical constructor 방어적 복사
     * 외부에서 전달된 배열 수정이 내부 상태에 영향을 주지 않도록 보장
     */
    public SparsePmf(int[] values, double[] probs) {
        this.values = values != null ? values.clone() : new int[0];
        this.probs = probs != null ? probs.clone() : new double[0];
    }

    /**
     * P0: Accessor 방어적 복사 (values)
     */
    @Override
    public int[] values() {
        return values.clone();
    }

    /**
     * P0: Accessor 방어적 복사 (probs)
     */
    @Override
    public double[] probs() {
        return probs.clone();
    }

    /**
     * Map에서 SparsePmf 생성 (값 기준 정렬)
     *
     * <p>Note: 생성자에서 clone하므로 추가 clone 불필요</p>
     *
     * @param dist 값 → 확률 맵
     * @return 정렬된 SparsePmf
     */
    public static SparsePmf fromMap(Map<Integer, Double> dist) {
        List<Map.Entry<Integer, Double>> sorted = dist.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        int[] values = sorted.stream().mapToInt(Map.Entry::getKey).toArray();
        double[] probs = sorted.stream().mapToDouble(Map.Entry::getValue).toArray();
        return new SparsePmf(values, probs);
    }

    /**
     * non-zero 항 개수
     */
    public int size() {
        return values.length;
    }

    /**
     * 인덱스로 값 조회
     */
    public int valueAt(int idx) {
        return values[idx];
    }

    /**
     * 인덱스로 확률 조회
     */
    public double probAt(int idx) {
        return probs[idx];
    }

    /**
     * 최대 값 (정렬되어 있으므로 마지막 원소)
     */
    public int maxValue() {
        return values.length > 0 ? values[values.length - 1] : 0;
    }

    /**
     * 총 질량 (단순 누적합)
     * 빠른 근사 계산용. 검증 시에는 totalMassKahan() 사용 권장
     */
    public double totalMass() {
        double sum = 0.0;
        for (double p : probs) {
            sum += p;
        }
        return sum;
    }

    /**
     * Kahan summation으로 정밀한 총 질량 계산
     * DoD 1e-12 기준 충족을 위해 검증 단계에서 사용
     */
    public double totalMassKahan() {
        double sum = 0.0;
        double c = 0.0;  // 오차 보정
        for (double p : probs) {
            double y = p - c;
            double t = sum + y;
            c = (t - sum) - y;
            sum = t;
        }
        return sum;
    }

    /**
     * 음수 확률 존재 여부
     *
     * @param tolerance 허용 오차 (예: -1e-15)
     */
    public boolean hasNegative(double tolerance) {
        for (double p : probs) {
            if (p < tolerance) {
                return true;
            }
        }
        return false;
    }

    /**
     * NaN 또는 무한대 존재 여부
     */
    public boolean hasNaNOrInf() {
        for (double p : probs) {
            if (Double.isNaN(p) || Double.isInfinite(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 1을 초과하는 확률 존재 여부
     * 누적/보정 실수 조기 탐지용
     *
     * <p>P0: EPS 허용 오차 적용 (부동소수점 오차 감안)</p>
     * <p>1.0 + 1e-12 이하는 정상으로 간주</p>
     */
    public boolean hasValueExceedingOne() {
        final double EPS = 1e-12;
        for (double p : probs) {
            if (p > 1.0 + EPS) {
                return true;
            }
        }
        return false;
    }
}
