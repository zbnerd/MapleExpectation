package maple.expectation.domain.model.calculator;

/**
 * 주사위 굴림 확률 분포
 *
 * <p>순수 도메인 모델 - JPA/인프라 의존 없음
 *
 * <h3>용도</h3>
 *
 * <ul>
 *   <li>슬롯별 주사위 확률 분포 표현
 *   <li>SparsePmf로 변환하여 합성곱 계산
 * </ul>
 */
public record DiceRollProbability(int successValue, double successProbability) {

  public DiceRollProbability {
    if (successValue < 0) {
      throw new IllegalArgumentException("successValue must be non-negative");
    }
    if (successProbability < 0.0 || successProbability > 1.0) {
      throw new IllegalArgumentException(
          "successProbability must be between 0 and 1: " + successProbability);
    }
  }

  /** 성공 확률로 SparsePmf 생성 */
  public SparsePmf toSparsePmf() {
    int[] values = {successValue, 0};
    double[] probs = {successProbability, 1.0 - successProbability};
    return new SparsePmf(values, probs);
  }
}
