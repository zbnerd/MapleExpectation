package maple.expectation.core.probability;

import java.util.List;
import java.util.Map;
import maple.expectation.core.domain.flame.FlameEquipCategory;
import maple.expectation.core.domain.flame.FlameType;

/**
 * 환생의 불꽃 DP 기반 기대값 계산 컴포넌트
 *
 * <h3>핵심 알고리즘</h3>
 *
 * <p>"옵션 종류 중복 없이 k개 선택"을 조합-평균 DP로 처리한다.
 *
 * <p>dp[r][t] = 앞에서 처리한 옵션들 중 r개를 선택해서 캡 점수 t가 되는 확률질량의 합
 *
 * <h3>보스 장비: k=4 고정</h3>
 *
 * <p>P = dp[4][T] / C(N,4)
 *
 * <h3>그외 장비: k=1~4 균등</h3>
 *
 * <p>P = (1/4) * sum(dp[k][T] / C(N,k)) for k=1..4
 *
 * @see FlameScoreCalculator PMF 생성
 */
public class FlameDpCalculator {

  /**
   * 환생의 불꽃 기대 시도 횟수 계산
   *
   * @param category 장비 분류
   * @param flameType 불꽃 종류
   * @param level 장비 레벨
   * @param weights 직업 가중치
   * @param target 목표 환산치 (스케일 10 적용된 정수)
   * @param baseAtt 무기 기본 공격력 (무기 아닐 경우 0)
   * @param baseMag 무기 기본 마력 (무기 아닐 경우 0)
   * @param optionPmfs 옵션별 PMF 리스트 (FlameScoreCalculator.buildOptionPmfs로 생성)
   * @return 기대 시도 횟수 (1/p), 불가능하면 null
   */
  public Double calculateExpectedTrials(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int target,
      int baseAtt,
      int baseMag,
      List<Map<Integer, Double>> optionPmfs) {

    int n = optionPmfs.size();
    if (n == 0) {
      return null;
    }

    double successProb =
        category.isBossDrop()
            ? calculateProbForFixedK(optionPmfs, 4, target)
            : calculateProbForUniformK(optionPmfs, target, n);

    return successProb <= 0 ? null : 1.0 / successProb;
  }

  /** 보스 장비: k 고정일 때 P(score >= T) */
  private double calculateProbForFixedK(List<Map<Integer, Double>> optionPmfs, int k, int target) {
    int n = optionPmfs.size();
    if (k > n) {
      return 0.0;
    }

    double[][] dp = runDp(optionPmfs, k, target);
    long comb = combination(n, k);

    return comb == 0 ? 0.0 : dp[k][target] / comb;
  }

  /** 그외 장비: k=1~4 균등일 때 P(score >= T) */
  private double calculateProbForUniformK(
      List<Map<Integer, Double>> optionPmfs, int target, int n) {
    int maxK = Math.min(4, n);
    double[][] dp = runDp(optionPmfs, maxK, target);

    double totalProb = 0.0;
    for (int k = 1; k <= maxK; k++) {
      long comb = combination(n, k);
      if (comb > 0) {
        totalProb += dp[k][target] / comb;
      }
    }
    return totalProb / 4.0;
  }

  /**
   * 핵심 DP 실행
   *
   * <p>dp[r][t] = r개 옵션 선택했을 때 캡 점수 t의 확률질량 합
   *
   * <p>캡핑: t = min(T, t + val)
   */
  private double[][] runDp(List<Map<Integer, Double>> optionPmfs, int maxK, int target) {
    int n = optionPmfs.size();

    // dp[r][t]: r options selected, capped score = t
    double[][] dp = new double[maxK + 1][target + 1];
    dp[0][0] = 1.0;

    for (int i = 0; i < n; i++) {
      Map<Integer, Double> pmf = optionPmfs.get(i);

      // Copy for "don't pick option i" case
      double[][] next = new double[maxK + 1][target + 1];
      for (int r = 0; r <= maxK; r++) {
        System.arraycopy(dp[r], 0, next[r], 0, target + 1);
      }

      // "Pick option i" case: r-1 -> r
      int rMax = Math.min(i + 1, maxK);
      for (int r = rMax; r >= 1; r--) {
        for (int t = 0; t <= target; t++) {
          double base = dp[r - 1][t];
          if (base == 0.0) {
            continue;
          }

          for (var entry : pmf.entrySet()) {
            int val = entry.getKey();
            double prob = entry.getValue();
            int nt = Math.min(target, t + val);
            next[r][nt] += base * prob;
          }
        }
      }

      dp = next;
    }

    return dp;
  }

  /** 조합 C(n, k) 계산 */
  private long combination(int n, int k) {
    if (k > n || k < 0) {
      return 0;
    }
    if (k == 0 || k == n) {
      return 1;
    }

    int effectiveK = k > n - k ? n - k : k;
    long result = 1;
    for (int i = 0; i < effectiveK; i++) {
      result = result * (n - i) / (i + 1);
    }
    return result;
  }
}
