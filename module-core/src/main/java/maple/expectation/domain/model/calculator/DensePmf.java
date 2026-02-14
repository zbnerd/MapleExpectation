package maple.expectation.domain.model.calculator;

/**
 * 밀집 확률질량함수 (Dense PMF)
 *
 * <p>용도: 합성곱 결과 (인덱스 = 값)
 *
 * <p>Tail Clamp 시 size = target + 1
 *
 * <p>불변(Immutable) - 방어적 복사로 보장
 *
 * <h3>Tail Clamp 전략</h3>
 *
 * <ul>
 *   <li>인덱스는 0..target
 *   <li>합이 target 초과 시 모두 target 버킷에 누적
 *   <li>결과적으로 O(slots × target × K) 보장
 * </ul>
 *
 * <h3>P0: 불변성 보장</h3>
 *
 * <ul>
 *   <li>Canonical constructor에서 방어적 복사
 *   <li>Accessor에서 방어적 복사
 * </ul>
 */
public record DensePmf(double[] massByValue) {

  /** P0: Canonical constructor 방어적 복사 외부에서 전달된 배열 수정이 내부 상태에 영향을 주지 않도록 보장 */
  public DensePmf(double[] massByValue) {
    this.massByValue = massByValue != null ? massByValue.clone() : new double[0];
  }

  /** P0: Accessor 방어적 복사 반환된 배열 수정이 내부 상태에 영향을 주지 않도록 보장 */
  @Override
  public double[] massByValue() {
    return massByValue.clone();
  }

  /**
   * 배열에서 DensePmf 생성
   *
   * <p>Note: 생성자에서 clone하므로 추가 clone 불필요
   *
   * @param arr 확률 배열 (인덱스 = 값)
   * @return DensePmf
   */
  public static DensePmf fromArray(double[] arr) {
    return new DensePmf(arr);
  }

  /** PMF 크기 (= 최대값 + 1) */
  public int size() {
    return massByValue.length;
  }

  /**
   * 특정 값의 질량 조회
   *
   * @param value 조회할 값
   * @return 해당 값의 확률 (범위 밖이면 0.0)
   */
  public double massAt(int value) {
    if (value < 0 || value >= massByValue.length) {
      return 0.0;
    }
    return massByValue[value];
  }

  /** 총 질량 (단순 누적합) 빠른 근사 계산용. 검증 시에는 totalMassKahan() 사용 권장 */
  public double totalMass() {
    double sum = 0.0;
    for (double m : massByValue) {
      sum += m;
    }
    return sum;
  }

  /** Kahan summation으로 정밀한 총 질량 계산 DoD 1e-12 기준 충족을 위해 검증 단계에서 사용 */
  public double totalMassKahan() {
    double sum = 0.0;
    double c = 0.0;
    for (double m : massByValue) {
      double y = m - c;
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
    for (double m : massByValue) {
      if (m < tolerance) {
        return true;
      }
    }
    return false;
  }

  /** NaN 또는 무한대 존재 여부 */
  public boolean hasNaNOrInf() {
    for (double m : massByValue) {
      if (Double.isNaN(m) || !Double.isFinite(m)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 1을 초과하는 확률 존재 여부 누적/보정 실수 오차 탐지용
   *
   * <p>P0: EPS 허용 오차 적용 (부동소수점 오차 감안)
   *
   * <p>1.0 + 1e-12 이하는 정상으로 간주
   */
  public boolean hasValueExceedingOne() {
    final double EPS = 1e-12;
    for (double m : massByValue) {
      if (m > 1.0 + EPS) {
        return true;
      }
    }
    return false;
  }
}
