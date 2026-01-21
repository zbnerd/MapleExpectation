package maple.expectation.service.v2.starforce;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starforce 기대값 Lookup Table 구현체 (#240)
 *
 * <h3>메이플스토리 스타포스 확률 테이블 (2024년 기준)</h3>
 * <p>스타캐치 + 썬데이메이플 파괴율 30% 감소(곱적용) 기준</p>
 *
 * <h3>비용 공식</h3>
 * <ul>
 *   <li>0~9성: 1000 + L³(S+1)/36</li>
 *   <li>10성+: 1000 + L³(S+1)^2.7/divisor (divisor는 성별로 다름)</li>
 * </ul>
 *
 * <h3>옵션</h3>
 * <ul>
 *   <li>스타캐치: 성공률 1.05배</li>
 *   <li>썬데이메이플: 파괴율 30% 감소 (곱적용, 22성 이상 미적용)</li>
 *   <li>30% 할인: 강화비용 30% 할인 (파괴방지 비용 제외)</li>
 *   <li>파괴방지 (15-17성): 비용 200% 추가, 파괴율 0%</li>
 * </ul>
 *
 * @see StarforceLookupTable 인터페이스
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StarforceLookupTableImpl implements StarforceLookupTable {

    private static final int MAX_STAR = 30;
    private static final int MAX_LEVEL = 300;
    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    // 성공 확률 테이블 (star 0~29, 스타캐치 미적용 기준)
    private static final double[] BASE_SUCCESS_RATES = {
            0.95, 0.90, 0.85, 0.85, 0.80,   // 0-4성
            0.75, 0.70, 0.65, 0.60, 0.55,   // 5-9성
            0.50, 0.45, 0.40, 0.35, 0.30,   // 10-14성
            0.30, 0.30, 0.15, 0.15, 0.15,   // 15-19성 (17성부터 15%)
            0.30, 0.15, 0.15, 0.10, 0.10,   // 20-24성 (20성은 30%, 21성은 15%)
            0.10, 0.07, 0.05, 0.03, 0.01    // 25-29성
    };

    // 파괴 확률 테이블 (star 0~29, 썬데이메이플 미적용 기준)
    private static final double[] BASE_DESTROY_RATES = {
            0.0, 0.0, 0.0, 0.0, 0.0,        // 0-4성: 파괴 없음
            0.0, 0.0, 0.0, 0.0, 0.0,        // 5-9성: 파괴 없음
            0.0, 0.0, 0.0, 0.0, 0.0,        // 10-14성: 파괴 없음
            0.021, 0.021, 0.068, 0.068, 0.085, // 15-19성
            0.105, 0.1275, 0.17, 0.18, 0.18,   // 20-24성 (22성부터 썬데이 미적용)
            0.18, 0.186, 0.19, 0.194, 0.198    // 25-29성
    };

    // 비용 공식의 divisor (10성 이상)
    private static final int[] COST_DIVISORS = {
            36, 36, 36, 36, 36,    // 0-4성 (기본 공식)
            36, 36, 36, 36, 36,    // 5-9성 (기본 공식)
            571, 314, 214, 157, 107, // 10-14성
            200, 200, 150, 70, 45,   // 15-19성
            200, 125, 200, 200, 200, // 20-24성
            200, 200, 200, 200, 200  // 25-29성
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
            executor.executeVoid(
                    this::precomputeTables,
                    TaskContext.of("Starforce", "Initialize")
            );
            log.info("[Starforce] Lookup table initialized. Cache size: {}", expectedCostCache.size());
        }
    }

    private void precomputeTables() {
        // Pre-compute expected costs for common combinations
        int[] levels = {100, 110, 120, 130, 140, 150, 160, 200, 250};
        for (int level : levels) {
            int maxStar = getMaxStarForLevel(level);
            for (int star = 0; star < maxStar; star++) {
                // 기본 옵션 (스타캐치 O, 썬데이 O, 할인 O, 파괴방지 X)
                computeAndCache(star, level, true, true, true, false);
            }
        }
    }

    /**
     * 레벨별 최대 스타포스 반환
     */
    public int getMaxStarForLevel(int itemLevel) {
        for (int[] limit : LEVEL_STAR_LIMITS) {
            if (itemLevel <= limit[0]) {
                return limit[1];
            }
        }
        return 30;
    }

    @Override
    public BigDecimal getExpectedCost(int currentStar, int targetStar, int itemLevel) {
        // 기본 옵션: 스타캐치 O, 썬데이 O, 30% 할인 O, 파괴방지 X
        return getExpectedCost(currentStar, targetStar, itemLevel, true, true, true, false);
    }

    /**
     * 옵션별 기대 비용 계산
     *
     * @param currentStar 현재 스타
     * @param targetStar 목표 스타
     * @param itemLevel 아이템 레벨
     * @param useStarCatch 스타캐치 사용 여부 (성공률 1.05배)
     * @param useSundayMaple 썬데이메이플 적용 여부 (파괴율 30% 감소, 22성 미만만)
     * @param useDiscount 30% 할인 적용 여부
     * @param useDestroyPrevention 파괴방지 사용 여부 (15-17성만, 비용 200% 추가)
     * @return 기대 비용
     */
    public BigDecimal getExpectedCost(int currentStar, int targetStar, int itemLevel,
                                       boolean useStarCatch, boolean useSundayMaple,
                                       boolean useDiscount, boolean useDestroyPrevention) {
        int maxStar = getMaxStarForLevel(itemLevel);
        if (targetStar > maxStar) {
            targetStar = maxStar;
        }
        validateStarRange(currentStar, targetStar, maxStar);

        if (currentStar >= targetStar) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalExpected = BigDecimal.ZERO;
        for (int star = currentStar; star < targetStar; star++) {
            boolean applyDestroyPrevention = useDestroyPrevention && canUseDestroyPrevention(star);
            BigDecimal singleStarCost = computeExpectedCostForSingleStar(
                    star, itemLevel, useStarCatch, useSundayMaple, useDiscount, applyDestroyPrevention);
            totalExpected = totalExpected.add(singleStarCost);
        }

        return totalExpected.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 파괴방지 적용 가능 여부 (15→16, 16→17, 17→18만 가능)
     */
    private boolean canUseDestroyPrevention(int currentStar) {
        return currentStar >= 15 && currentStar <= 17;
    }

    /**
     * 단일 스타 강화 기대값 계산 (정확한 공식 적용)
     */
    private BigDecimal computeExpectedCostForSingleStar(int star, int itemLevel,
                                                         boolean useStarCatch, boolean useSundayMaple,
                                                         boolean useDiscount, boolean useDestroyPrevention) {
        // 1. 확률 계산
        double baseSuccess = BASE_SUCCESS_RATES[star];
        double baseDestroy = BASE_DESTROY_RATES[star];

        // 스타캐치: 성공률 1.05배
        double successRate = useStarCatch ? Math.min(baseSuccess * 1.05, 1.0) : baseSuccess;

        // 파괴방지 적용 시 파괴율 0%
        double destroyRate;
        if (useDestroyPrevention && canUseDestroyPrevention(star)) {
            destroyRate = 0.0;
        } else {
            // 썬데이메이플: 파괴율 30% 감소 (곱적용), 22성 이상 미적용
            destroyRate = (useSundayMaple && star < 22) ? baseDestroy * 0.7 : baseDestroy;
        }

        // 스타캐치로 인한 실패/파괴 확률 조정
        // 실패 확률 = 100% - 성공 확률 - 파괴 확률
        double failRate = 1.0 - successRate - destroyRate;
        if (failRate < 0) {
            failRate = 0;
            destroyRate = 1.0 - successRate;
        }

        // 2. 비용 계산
        BigDecimal baseCost = getSingleEnhanceCost(star, itemLevel);

        // 30% 할인 적용
        BigDecimal enhanceCost = useDiscount
                ? baseCost.multiply(BigDecimal.valueOf(0.7))
                : baseCost;

        // 파괴방지 비용 (강화비용의 200% 추가, 할인 미적용)
        BigDecimal destroyPreventionCost = BigDecimal.ZERO;
        if (useDestroyPrevention && canUseDestroyPrevention(star)) {
            destroyPreventionCost = baseCost.multiply(BigDecimal.valueOf(2.0));
        }

        BigDecimal totalSingleCost = enhanceCost.add(destroyPreventionCost);

        // 3. 기대값 계산 (기하분포)
        // E[비용] = (단일 비용) / 성공확률
        if (successRate <= 0) {
            return BigDecimal.valueOf(Long.MAX_VALUE); // 불가능한 강화
        }

        BigDecimal expectedCost = totalSingleCost.divide(
                BigDecimal.valueOf(successRate), MC);

        // 4. 파괴 시 복구 비용 (12성으로 복구)
        // 파괴 기대 횟수 = (1/성공확률) * 파괴확률
        // 복구 비용 = 0→12성 다시 올리는 비용
        if (destroyRate > 0 && star >= 12) {
            BigDecimal destroyExpected = BigDecimal.valueOf(destroyRate)
                    .divide(BigDecimal.valueOf(successRate), MC);

            // 12성까지 다시 올리는 비용 (간략화: 현재 성까지의 비용 비례)
            BigDecimal recoveryCost = getRecoveryCostTo12Star(itemLevel, useStarCatch, useSundayMaple, useDiscount);
            BigDecimal destroyPenalty = destroyExpected.multiply(recoveryCost);

            expectedCost = expectedCost.add(destroyPenalty);
        }

        return expectedCost.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 12성까지 복구 비용 계산 (파괴 시)
     */
    private BigDecimal getRecoveryCostTo12Star(int itemLevel, boolean useStarCatch,
                                                boolean useSundayMaple, boolean useDiscount) {
        BigDecimal total = BigDecimal.ZERO;
        for (int s = 0; s < 12; s++) {
            BigDecimal cost = getSingleEnhanceCost(s, itemLevel);
            if (useDiscount) {
                cost = cost.multiply(BigDecimal.valueOf(0.7));
            }
            double success = useStarCatch
                    ? Math.min(BASE_SUCCESS_RATES[s] * 1.05, 1.0)
                    : BASE_SUCCESS_RATES[s];
            total = total.add(cost.divide(BigDecimal.valueOf(success), MC));
        }
        return total;
    }

    @Override
    public BigDecimal getSuccessProbability(int currentStar) {
        if (currentStar < 0 || currentStar >= MAX_STAR) {
            throw new IllegalArgumentException("Invalid star: " + currentStar + " (valid: 0-29)");
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
        int level = Math.max(1, itemLevel);
        int starFactor = currentStar + 1;

        long levelCubed = (long) level * level * level;

        BigDecimal baseCost = BigDecimal.valueOf(1000);

        if (currentStar < 10) {
            // 0~9성: 1000 + L³(S+1)/36
            BigDecimal levelComponent = BigDecimal.valueOf(levelCubed * starFactor)
                    .divide(BigDecimal.valueOf(36), MC);
            return roundToNearest10(baseCost.add(levelComponent));
        } else {
            // 10성+: 1000 + L³(S+1)^2.7/divisor
            double starPower = Math.pow(starFactor, 2.7);
            int divisor = COST_DIVISORS[currentStar];

            BigDecimal levelComponent = BigDecimal.valueOf(levelCubed)
                    .multiply(BigDecimal.valueOf(starPower))
                    .divide(BigDecimal.valueOf(divisor), MC);

            return roundToNearest10(baseCost.add(levelComponent));
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized.get();
    }

    @Override
    public BigDecimal getExpectedDestroyCount(int currentStar, int targetStar,
                                               boolean useStarCatch, boolean useSundayMaple,
                                               boolean useDestroyPrevention) {
        int maxStar = 30;
        if (targetStar > maxStar) {
            targetStar = maxStar;
        }
        if (currentStar >= targetStar) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalExpectedDestroys = BigDecimal.ZERO;

        for (int star = currentStar; star < targetStar; star++) {
            // 파괴방지 적용 시 15-17성에서 파괴 없음
            boolean applyDestroyPrevention = useDestroyPrevention && canUseDestroyPrevention(star);

            double baseSuccess = BASE_SUCCESS_RATES[star];
            double baseDestroy = BASE_DESTROY_RATES[star];

            // 스타캐치: 성공률 1.05배
            double successRate = useStarCatch ? Math.min(baseSuccess * 1.05, 1.0) : baseSuccess;

            // 파괴율 계산
            double destroyRate;
            if (applyDestroyPrevention) {
                destroyRate = 0.0;
            } else {
                // 썬데이메이플: 파괴율 30% 감소 (22성 미만만)
                destroyRate = (useSundayMaple && star < 22) ? baseDestroy * 0.7 : baseDestroy;
            }

            // 기대 파괴 횟수 = (1/성공확률) * 파괴확률
            if (successRate > 0 && destroyRate > 0) {
                BigDecimal expectedDestroy = BigDecimal.valueOf(destroyRate)
                        .divide(BigDecimal.valueOf(successRate), MC);
                totalExpectedDestroys = totalExpectedDestroys.add(expectedDestroy);
            }
        }

        return totalExpectedDestroys.setScale(2, RoundingMode.HALF_UP);
    }

    private void computeAndCache(int star, int level, boolean starCatch,
                                  boolean sunday, boolean discount, boolean destroyPrev) {
        String key = cacheKey(star, level, starCatch, sunday, discount, destroyPrev);
        BigDecimal cost = computeExpectedCostForSingleStar(star, level, starCatch, sunday, discount, destroyPrev);
        expectedCostCache.put(key, cost);
    }

    private void validateStarRange(int currentStar, int targetStar, int maxStar) {
        if (currentStar < 0 || currentStar > maxStar) {
            throw new IllegalArgumentException("Invalid current star: " + currentStar + " (valid: 0-" + maxStar + ")");
        }
        if (targetStar < 0 || targetStar > maxStar) {
            throw new IllegalArgumentException("Invalid target star: " + targetStar + " (valid: 0-" + maxStar + ")");
        }
        if (targetStar < currentStar) {
            throw new IllegalArgumentException("Target star must be >= current star");
        }
    }

    private String cacheKey(int star, int level, boolean starCatch, boolean sunday,
                            boolean discount, boolean destroyPrev) {
        return String.format("%d-%d-%b-%b-%b-%b", star, level, starCatch, sunday, discount, destroyPrev);
    }

    private BigDecimal roundToNearest10(BigDecimal value) {
        return value.divide(BigDecimal.valueOf(10), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(10));
    }
}
