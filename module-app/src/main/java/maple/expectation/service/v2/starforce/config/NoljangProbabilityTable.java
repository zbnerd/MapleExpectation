package maple.expectation.service.v2.starforce.config;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 놀장(스타포스 스크롤) 확률 테이블 (#240 V4)
 *
 * <h3>놀장 특성</h3>
 *
 * <ul>
 *   <li>최대 15성까지만 강화 가능
 *   <li>파괴 없음 - 실패 시 현금 비용만 발생
 *   <li>실패 비용: 9,400원 (캐시)
 *   <li>보호권 12성부터 사용 불가
 * </ul>
 *
 * <h3>성공 확률 (2024년 기준)</h3>
 *
 * <pre>
 * 0→1성: 60%
 * 1→2성: 55%
 * 2→3성: 50%
 * 3→4성: 40%
 * 4→5성: 30%
 * 5→6성: 20%
 * 6→7성: 19%
 * 7→8성: 18%
 * 8→9성: 17%
 * 9→10성: 16%
 * 10→11성: 16%
 * 11→12성: 14%
 * 12→13성: 12%
 * 13→14성: 10%
 * 14→15성: 10%
 * </pre>
 *
 * <h3>비용 구조</h3>
 *
 * <ul>
 *   <li>강화 비용: 일반 스타포스와 동일 (메소)
 *   <li>실패 비용: 9,400원 (캐시, 게임 내 약 150,000,000 메소 환산)
 * </ul>
 */
public final class NoljangProbabilityTable {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

  /** 놀장 최대 스타 */
  public static final int MAX_NOLJANG_STAR = 15;

  /** 보호권 사용 가능 최대 스타 (11성까지만 보호권 사용 가능) */
  public static final int PROTECTION_MAX_STAR = 11;

  /** 놀장 실패 비용 (캐시, 원) */
  public static final int FAIL_CASH_COST_KRW = 9400;

  /**
   * 캐시 → 메소 환산 비율 (1원 ≈ 16,000메소, 2024년 기준)
   *
   * <p>9,400원 × 16,000 = 150,400,000 메소
   */
  public static final long CASH_TO_MESO_RATE = 16000L;

  /** 놀장 실패 비용 (메소 환산) */
  public static final BigDecimal FAIL_COST_MESO =
      BigDecimal.valueOf(FAIL_CASH_COST_KRW * CASH_TO_MESO_RATE);

  /**
   * 놀장 성공 확률 테이블 (star 0~14, 스타캐치 미적용)
   *
   * <p>인덱스 n = n성에서 n+1성으로 강화할 때의 성공 확률
   */
  private static final double[] SUCCESS_RATES = {
    0.60, 0.55, 0.50, 0.40, 0.30, // 0-4성: 60%, 55%, 50%, 40%, 30%
    0.20, 0.19, 0.18, 0.17, 0.16, // 5-9성: 20%, 19%, 18%, 17%, 16%
    0.16, 0.14, 0.12, 0.10, 0.10 // 10-14성: 16%, 14%, 12%, 10%, 10%
  };

  /**
   * 비용 공식의 divisor (10성 이상)
   *
   * <p>일반 스타포스와 동일
   */
  private static final int[] COST_DIVISORS = {
    36, 36, 36, 36, 36, // 0-4성 (기본 공식)
    36, 36, 36, 36, 36, // 5-9성 (기본 공식)
    571, 314, 214, 157, 107 // 10-14성
  };

  private NoljangProbabilityTable() {
    // Utility class - no instantiation
  }

  /**
   * 놀장 성공 확률 조회
   *
   * @param currentStar 현재 스타 (0~14)
   * @return 성공 확률 (0.0 ~ 1.0)
   */
  public static double getSuccessRate(int currentStar) {
    if (currentStar < 0 || currentStar >= MAX_NOLJANG_STAR) {
      return 0.0;
    }
    return SUCCESS_RATES[currentStar];
  }

  /**
   * 스타캐치 적용된 성공 확률 조회
   *
   * @param currentStar 현재 스타 (0~14)
   * @param useStarCatch 스타캐치 사용 여부
   * @return 성공 확률 (스타캐치 시 1.05배, 최대 1.0)
   */
  public static double getSuccessRate(int currentStar, boolean useStarCatch) {
    double baseRate = getSuccessRate(currentStar);
    if (useStarCatch) {
      return Math.min(baseRate * 1.05, 1.0);
    }
    return baseRate;
  }

  /**
   * 보호권 사용 가능 여부
   *
   * @param currentStar 현재 스타
   * @return 보호권 사용 가능 여부 (11성 이하만 가능)
   */
  public static boolean canUseProtection(int currentStar) {
    return currentStar <= PROTECTION_MAX_STAR;
  }

  /**
   * 놀장 단일 강화 비용 (메소)
   *
   * @param currentStar 현재 스타 (0~14)
   * @param itemLevel 아이템 레벨
   * @return 1회 강화 비용 (메소)
   */
  public static BigDecimal getSingleEnhanceCost(int currentStar, int itemLevel) {
    if (currentStar < 0 || currentStar >= MAX_NOLJANG_STAR) {
      return BigDecimal.ZERO;
    }

    int level = Math.max(1, itemLevel);
    int starFactor = currentStar + 1;
    long levelCubed = (long) level * level * level;

    BigDecimal baseCost = BigDecimal.valueOf(1000);

    if (currentStar < 10) {
      // 0~9성: 1000 + L³(S+1)/36
      BigDecimal levelComponent =
          BigDecimal.valueOf(levelCubed * starFactor).divide(BigDecimal.valueOf(36), MC);
      return roundToNearest100(baseCost.add(levelComponent));
    } else {
      // 10성+: 1000 + L³(S+1)^2.7/divisor
      double starPower = Math.pow(starFactor, 2.7);
      int divisor = COST_DIVISORS[currentStar];

      BigDecimal levelComponent =
          BigDecimal.valueOf(levelCubed)
              .multiply(BigDecimal.valueOf(starPower))
              .divide(BigDecimal.valueOf(divisor), MC);

      return roundToNearest100(baseCost.add(levelComponent));
    }
  }

  /**
   * 놀장 기대 비용 계산 (0성 → 목표 스타)
   *
   * <h3>놀장 기대값 공식</h3>
   *
   * <p>E[비용] = Σ (강화비용 + 실패비용 × (1-p)/p) / p
   *
   * <p>여기서 p = 성공 확률, 파괴 확률 = 0
   *
   * @param targetStar 목표 스타 (1~15)
   * @param itemLevel 아이템 레벨
   * @param useStarCatch 스타캐치 사용 여부
   * @param useDiscount 30% 할인 적용 여부
   * @return 기대 비용 (메소)
   */
  public static BigDecimal getExpectedCost(
      int targetStar, int itemLevel, boolean useStarCatch, boolean useDiscount) {
    return getExpectedCostFromStar(0, targetStar, itemLevel, useStarCatch, useDiscount);
  }

  /**
   * 놀장 기대 비용 계산 (현재 스타 → 목표 스타)
   *
   * @param currentStar 현재 스타 (0~14)
   * @param targetStar 목표 스타 (1~15)
   * @param itemLevel 아이템 레벨
   * @param useStarCatch 스타캐치 사용 여부
   * @param useDiscount 30% 할인 적용 여부
   * @return 기대 비용 (메소)
   */
  public static BigDecimal getExpectedCostFromStar(
      int currentStar, int targetStar, int itemLevel, boolean useStarCatch, boolean useDiscount) {
    // 범위 검증
    if (targetStar > MAX_NOLJANG_STAR) {
      targetStar = MAX_NOLJANG_STAR;
    }
    if (currentStar >= targetStar || currentStar < 0) {
      return BigDecimal.ZERO;
    }

    BigDecimal totalExpected = BigDecimal.ZERO;

    for (int star = currentStar; star < targetStar; star++) {
      BigDecimal singleCost =
          computeExpectedCostForSingleStar(star, itemLevel, useStarCatch, useDiscount);
      totalExpected = totalExpected.add(singleCost);
    }

    return totalExpected.setScale(0, RoundingMode.HALF_UP);
  }

  /** 단일 스타 강화 기대값 계산 */
  private static BigDecimal computeExpectedCostForSingleStar(
      int star, int itemLevel, boolean useStarCatch, boolean useDiscount) {
    // 성공 확률
    double successRate = getSuccessRate(star, useStarCatch);
    if (successRate <= 0) {
      return BigDecimal.valueOf(Long.MAX_VALUE);
    }

    // 강화 비용 (메소)
    BigDecimal enhanceCost = getSingleEnhanceCost(star, itemLevel);
    if (useDiscount) {
      enhanceCost = enhanceCost.multiply(BigDecimal.valueOf(0.7));
    }

    // 실패 확률
    double failRate = 1.0 - successRate;

    // 기대 시도 횟수 = 1 / 성공확률
    BigDecimal expectedTrials = BigDecimal.ONE.divide(BigDecimal.valueOf(successRate), MC);

    // 기대 실패 횟수 = (1 - 성공확률) / 성공확률
    BigDecimal expectedFails =
        BigDecimal.valueOf(failRate).divide(BigDecimal.valueOf(successRate), MC);

    // 총 기대 비용 = (강화비용 × 기대시도횟수) + (실패비용 × 기대실패횟수)
    BigDecimal enhanceTotal = enhanceCost.multiply(expectedTrials);
    BigDecimal failTotal = FAIL_COST_MESO.multiply(expectedFails);

    return enhanceTotal.add(failTotal);
  }

  /** 100 단위로 반올림 */
  private static BigDecimal roundToNearest100(BigDecimal value) {
    return value
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }
}
