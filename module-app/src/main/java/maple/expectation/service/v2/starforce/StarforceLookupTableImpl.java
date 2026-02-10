package maple.expectation.service.v2.starforce;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * Starforce 기대값 Lookup Table 구현체 (#240)
 *
 * <h3>2025년 3월 스타포스 개편 기준 (30성 확장)</h3>
 *
 * <ul>
 *   <li>실패 시 하락 없음 (유지 또는 파괴)
 *   <li>파괴 시 12성으로 리셋 (전승)
 *   <li>마르코프 체인 기대값 계산
 * </ul>
 *
 * <h3>기대값 계산식</h3>
 *
 * <pre>
 * E[s] = (C[s] + p*E[s+1] + d*E[12]) / (p+d)
 *
 * 순환참조 해결: E[s] = a[s]*E[12] + b[s]
 * E[12] = b[12] / (1 - a[12])
 * </pre>
 *
 * <h3>옵션</h3>
 *
 * <ul>
 *   <li>스타캐치: 성공률 1.05배
 *   <li>썬데이메이플: 파괴율 30% 감소 (15-21성만)
 *   <li>30% 할인: 강화비용 30% 할인
 *   <li>파괴방지 (15-17성): 비용 3배, 파괴율 0%
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StarforceLookupTableImpl implements StarforceLookupTable {

  private static final int MAX_STAR = 30;
  private static final int DESTROY_RESET_STAR = 12;

  // 성공 확률 테이블 (0~29성, 스타캐치 미적용 기준)
  private static final double[] BASE_SUCCESS_RATES = {
    0.95, 0.90, 0.85, 0.85, 0.80, // 0-4성
    0.75, 0.70, 0.65, 0.60, 0.55, // 5-9성
    0.50, 0.45, 0.40, 0.35, 0.30, // 10-14성
    0.30, 0.30, 0.15, 0.15, 0.15, // 15-19성
    0.30, 0.15, 0.15, 0.10, 0.10, // 20-24성
    0.10, 0.07, 0.05, 0.03, 0.01 // 25-29성
  };

  // 파괴 확률 테이블 (0~29성, 썬데이메이플 미적용 기준)
  private static final double[] BASE_DESTROY_RATES = {
    0.0, 0.0, 0.0, 0.0, 0.0, // 0-4성: 파괴 없음
    0.0, 0.0, 0.0, 0.0, 0.0, // 5-9성: 파괴 없음
    0.0, 0.0, 0.0, 0.0, 0.0, // 10-14성: 파괴 없음
    0.021, 0.021, 0.068, 0.068, 0.085, // 15-19성
    0.105, 0.1275, 0.17, 0.18, 0.18, // 20-24성
    0.18, 0.186, 0.19, 0.194, 0.198 // 25-29성
  };

  // 비용 공식의 divisor (10성 이상)
  private static final int[] COST_DIVISORS = {
    36, 36, 36, 36, 36, // 0-4성 (기본 공식)
    36, 36, 36, 36, 36, // 5-9성 (기본 공식)
    571, 314, 214, 157, 107, // 10-14성
    200, 200, 150, 70, 45, // 15-19성
    200, 125, 200, 200, 200, // 20-24성
    200, 200, 200, 200, 200 // 25-29성
  };

  // 레벨별 최대 스타
  private static final int[][] LEVEL_STAR_LIMITS = {
    {94, 5}, {107, 8}, {117, 10}, {127, 15}, {137, 20}, {Integer.MAX_VALUE, 30}
  };

  private final LogicExecutor executor;

  private final ConcurrentHashMap<String, BigDecimal> expectedCostCache = new ConcurrentHashMap<>();
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  @Override
  public void initialize() {
    if (initialized.compareAndSet(false, true)) {
      executor.executeVoid(this::precomputeTables, TaskContext.of("Starforce", "Initialize"));
      log.info(
          "[Starforce] Lookup table initialized (Markov Chain). Cache size: {}",
          expectedCostCache.size());
    }
  }

  private void precomputeTables() {
    // Pre-compute expected costs for common combinations
    int[] levels = {100, 110, 120, 130, 140, 150, 160, 200, 250};
    for (int level : levels) {
      int maxStar = getMaxStarForLevel(level);
      // 기본 옵션 (스타캐치 O, 썬데이 O, 할인 O, 파괴방지 X)
      computeAndCache(0, maxStar, level, true, true, true, false);
      // 파괴방지 O
      computeAndCache(0, maxStar, level, true, true, true, true);
    }
  }

  @Override
  public int getMaxStarForLevel(int itemLevel) {
    for (int[] limit : LEVEL_STAR_LIMITS) {
      if (itemLevel <= limit[0]) {
        return limit[1];
      }
    }
    return MAX_STAR;
  }

  @Override
  public BigDecimal getExpectedCost(int currentStar, int targetStar, int itemLevel) {
    return getExpectedCost(currentStar, targetStar, itemLevel, true, true, true, false);
  }

  /**
   * 마르코프 체인 기대값 계산 (#240 V4: 2025년 3월 개편 기준)
   *
   * <h3>핵심 로직</h3>
   *
   * <pre>
   * E[s] = (C[s] + p*E[s+1] + d*E[12]) / (p+d)
   *
   * 순환참조 해결:
   * E[s] = a[s]*E[12] + b[s]
   * E[12] = b[12] / (1 - a[12])
   * </pre>
   */
  @Override
  public BigDecimal getExpectedCost(
      int currentStar,
      int targetStar,
      int itemLevel,
      boolean useStarCatch,
      boolean useSundayMaple,
      boolean useDiscount,
      boolean useDestroyPrevention) {
    int maxStar = getMaxStarForLevel(itemLevel);
    if (targetStar > maxStar) {
      targetStar = maxStar;
    }
    validateStarRange(currentStar, targetStar, maxStar);

    if (currentStar >= targetStar) {
      return BigDecimal.ZERO;
    }

    // 캐시 키
    String key =
        cacheKey(
            currentStar,
            targetStar,
            itemLevel,
            useStarCatch,
            useSundayMaple,
            useDiscount,
            useDestroyPrevention);
    BigDecimal cached = expectedCostCache.get(key);
    if (cached != null) {
      return cached;
    }

    // 마르코프 체인 기대값 계산
    BigDecimal result =
        computeMarkovExpectedCost(
            currentStar,
            targetStar,
            itemLevel,
            useStarCatch,
            useSundayMaple,
            useDiscount,
            useDestroyPrevention);

    expectedCostCache.put(key, result);
    return result;
  }

  /**
   * 마르코프 체인 기대값 계산 (순환참조 해결)
   *
   * <p>E[s] = a[s]*E[12] + b[s] 형태로 표현 후, E[12] = b[12] / (1 - a[12])로 닫아서 해결
   */
  private BigDecimal computeMarkovExpectedCost(
      int currentStar,
      int targetStar,
      int itemLevel,
      boolean useStarCatch,
      boolean useSundayMaple,
      boolean useDiscount,
      boolean useDestroyPrevention) {
    int T = targetStar;

    // a[s], b[s] 배열: E[s] = a[s]*E[12] + b[s]
    double[] a = new double[T + 1];
    double[] b = new double[T + 1];
    // E[T] = 0 → a[T] = 0, b[T] = 0 (이미 초기화됨)

    // T-1부터 0까지 역순으로 계산
    for (int s = T - 1; s >= 0; s--) {
      double[] params =
          getStageParams(
              s, itemLevel, useStarCatch, useSundayMaple, useDiscount, useDestroyPrevention);
      double p = params[0]; // 성공확률
      double m = params[1]; // 유지확률 (사용 안함, p+d로 계산)
      double d = params[2]; // 파괴확률
      double c = params[3]; // 비용

      double aNext = (s + 1 >= T) ? 0.0 : a[s + 1];
      double bNext = (s + 1 >= T) ? 0.0 : b[s + 1];

      double denom = p + d;
      if (denom < 1e-12) {
        // 불가능한 경우 (성공+파괴 = 0)
        a[s] = 0;
        b[s] = Double.MAX_VALUE;
      } else {
        // a[s] = (p*a[s+1] + d*1) / (p+d)
        // b[s] = (c + p*b[s+1]) / (p+d)
        a[s] = (p * aNext + d) / denom;
        b[s] = (c + p * bNext) / denom;
      }
    }

    // E[12] 해결
    double E12;
    if (T <= DESTROY_RESET_STAR) {
      E12 = 0.0;
    } else {
      // E[12] = a[12]*E[12] + b[12]
      // E[12] * (1 - a[12]) = b[12]
      // E[12] = b[12] / (1 - a[12])
      double a12 = a[DESTROY_RESET_STAR];
      double b12 = b[DESTROY_RESET_STAR];
      if (Math.abs(1 - a12) < 1e-12) {
        E12 = Double.MAX_VALUE;
      } else {
        E12 = b12 / (1 - a12);
      }
    }

    // E[currentStar] = a[currentStar]*E[12] + b[currentStar]
    double result = a[currentStar] * E12 + b[currentStar];

    return BigDecimal.valueOf(result).setScale(0, RoundingMode.HALF_UP);
  }

  /**
   * 단계별 파라미터 계산 (확률, 비용)
   *
   * @return [성공확률, 유지확률, 파괴확률, 비용]
   */
  private double[] getStageParams(
      int star,
      int itemLevel,
      boolean useStarCatch,
      boolean useSundayMaple,
      boolean useDiscount,
      boolean useDestroyPrevention) {
    double p = BASE_SUCCESS_RATES[star];
    double d = BASE_DESTROY_RATES[star];

    // 썬데이메이플: 15-21성에서 파괴율 30% 감소 (곱적용)
    if (useSundayMaple && star >= 15 && star <= 21) {
      d *= 0.7;
    }

    double m = 1.0 - p - d;

    // 파괴방지 (15-17성): 파괴율 0, 비용 3배
    double costMult = 1.0;
    if (useDestroyPrevention && star >= 15 && star <= 17) {
      d = 0.0;
      m = 1.0 - p;
      costMult = 3.0; // 기본비용 + 200% = 3배
    }

    // 스타캐치: 성공률 1.05배, 나머지 비율 유지
    if (useStarCatch) {
      double[] adjusted = applyStarCatch(p, m, d);
      p = adjusted[0];
      m = adjusted[1];
      d = adjusted[2];
    }

    // 비용 계산
    double baseCost = getSingleEnhanceCostRaw(star, itemLevel);
    double cost = baseCost * costMult;

    // 30% 할인
    if (useDiscount) {
      cost *= 0.7;
    }

    // 10단위 반올림
    cost = roundToNearest10(cost);

    return new double[] {p, m, d, cost};
  }

  /** 스타캐치 적용 (성공률 1.05배, 나머지 비율 유지) */
  private double[] applyStarCatch(double p, double m, double d) {
    double p2 = Math.min(1.0, p * 1.05);
    double rest = 1.0 - p2;

    if (m + d < 1e-12) {
      return new double[] {p2, rest, 0.0};
    }

    // 유지:파괴 비율 유지
    double m2 = rest * (m / (m + d));
    double d2 = rest * (d / (m + d));

    return new double[] {p2, m2, d2};
  }

  /** 단일 강화 비용 (반올림 전) */
  private double getSingleEnhanceCostRaw(int star, int itemLevel) {
    long L = itemLevel;
    long levelCubed = L * L * L;
    int starFactor = star + 1;

    if (star <= 9) {
      // 0~9성: 1000 + L³(S+1)/36
      return 1000.0 + (double) (levelCubed * starFactor) / 36.0;
    } else {
      // 10성+: 1000 + L³(S+1)^2.7/divisor
      double starPower = Math.pow(starFactor, 2.7);
      int divisor = COST_DIVISORS[star];
      return 1000.0 + (double) levelCubed * starPower / divisor;
    }
  }

  @Override
  public BigDecimal getSuccessProbability(int currentStar) {
    if (currentStar < 0 || currentStar >= MAX_STAR) {
      throw new IllegalArgumentException("Invalid star: " + currentStar);
    }
    return BigDecimal.valueOf(BASE_SUCCESS_RATES[currentStar]);
  }

  @Override
  public BigDecimal getDestroyProbability(int currentStar) {
    if (currentStar < 0 || currentStar >= MAX_STAR) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(BASE_DESTROY_RATES[currentStar]);
  }

  @Override
  public BigDecimal getSingleEnhanceCost(int currentStar, int itemLevel) {
    double raw = getSingleEnhanceCostRaw(currentStar, itemLevel);
    return BigDecimal.valueOf(roundToNearest10(raw));
  }

  @Override
  public boolean isInitialized() {
    return initialized.get();
  }

  /**
   * 기대 파괴 횟수 계산 (마르코프 체인)
   *
   * <p>B[s] = (p*B[s+1] + d*(1 + B[12])) / (p+d) B[s] = a[s]*B[12] + b[s] B[12] = b[12] / (1 -
   * a[12])
   */
  @Override
  public BigDecimal getExpectedDestroyCount(
      int currentStar,
      int targetStar,
      boolean useStarCatch,
      boolean useSundayMaple,
      boolean useDestroyPrevention) {
    int maxStar = MAX_STAR;
    if (targetStar > maxStar) {
      targetStar = maxStar;
    }
    if (currentStar >= targetStar) {
      return BigDecimal.ZERO;
    }

    int T = targetStar;
    double[] a = new double[T + 1];
    double[] b = new double[T + 1];

    for (int s = T - 1; s >= 0; s--) {
      double[] params =
          getStageParams(s, 200, useStarCatch, useSundayMaple, false, useDestroyPrevention);
      double p = params[0];
      double d = params[2];

      double aNext = (s + 1 >= T) ? 0.0 : a[s + 1];
      double bNext = (s + 1 >= T) ? 0.0 : b[s + 1];

      double denom = p + d;
      if (denom < 1e-12) {
        a[s] = 0;
        b[s] = 0;
      } else {
        // B[s] = (p*B[s+1] + d*(1 + B[12])) / (p+d)
        // = (p*B[s+1] + d + d*B[12]) / (p+d)
        // a[s] = (p*a[s+1] + d) / (p+d)
        // b[s] = (p*b[s+1] + d) / (p+d)
        a[s] = (p * aNext + d) / denom;
        b[s] = (p * bNext + d) / denom;
      }
    }

    // B[12] 해결
    double B12;
    if (T <= DESTROY_RESET_STAR) {
      B12 = 0.0;
    } else {
      double a12 = a[DESTROY_RESET_STAR];
      double b12 = b[DESTROY_RESET_STAR];
      if (Math.abs(1 - a12) < 1e-12) {
        B12 = Double.MAX_VALUE;
      } else {
        B12 = b12 / (1 - a12);
      }
    }

    double result = a[currentStar] * B12 + b[currentStar];
    return BigDecimal.valueOf(result).setScale(2, RoundingMode.HALF_UP);
  }

  private void computeAndCache(
      int currentStar,
      int targetStar,
      int level,
      boolean starCatch,
      boolean sunday,
      boolean discount,
      boolean destroyPrev) {
    String key = cacheKey(currentStar, targetStar, level, starCatch, sunday, discount, destroyPrev);
    BigDecimal cost =
        computeMarkovExpectedCost(
            currentStar, targetStar, level, starCatch, sunday, discount, destroyPrev);
    expectedCostCache.put(key, cost);
  }

  private void validateStarRange(int currentStar, int targetStar, int maxStar) {
    if (currentStar < 0 || currentStar > maxStar) {
      throw new IllegalArgumentException("Invalid current star: " + currentStar);
    }
    if (targetStar < 0 || targetStar > maxStar) {
      throw new IllegalArgumentException("Invalid target star: " + targetStar);
    }
  }

  private String cacheKey(
      int currentStar,
      int targetStar,
      int level,
      boolean starCatch,
      boolean sunday,
      boolean discount,
      boolean destroyPrev) {
    return String.format(
        "%d-%d-%d-%b-%b-%b-%b",
        currentStar, targetStar, level, starCatch, sunday, discount, destroyPrev);
  }

  /** 10 단위로 반올림 (메이플스토리 스타포스 비용 표시 기준) */
  private double roundToNearest10(double value) {
    return Math.floor((value + 5) / 10.0) * 10;
  }
}
