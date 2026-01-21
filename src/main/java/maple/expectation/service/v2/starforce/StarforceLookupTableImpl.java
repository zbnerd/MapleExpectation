package maple.expectation.service.v2.starforce;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starforce 기대값 Lookup Table 구현체 (#240)
 *
 * <h3>메이플스토리 스타포스 확률 테이블 (2024년 기준)</h3>
 * <table>
 *   <tr><th>Star</th><th>Success</th><th>Maintain</th><th>Drop</th><th>Destroy</th></tr>
 *   <tr><td>0~2</td><td>95/90/85%</td><td>5/10/15%</td><td>-</td><td>-</td></tr>
 *   <tr><td>3~10</td><td>85~45%</td><td>varies</td><td>-</td><td>-</td></tr>
 *   <tr><td>11~14</td><td>45~30%</td><td>varies</td><td>-</td><td>0.6~2.1%</td></tr>
 *   <tr><td>15~24</td><td>30~3%</td><td>varies</td><td>varies</td><td>2.1~7.0%</td></tr>
 * </table>
 *
 * <h3>비용 공식</h3>
 * <pre>
 * cost = round(1000 + (Level³ × (Star + 1)) / 25 + (Level² × (Star + 1)² × 0.1))
 * </pre>
 *
 * @see StarforceLookupTable 인터페이스
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StarforceLookupTableImpl implements StarforceLookupTable {

    private static final int MAX_STAR = 25;
    private static final int MAX_LEVEL = 300;
    private static final int LEVEL_BRACKETS = 7; // 0-99, 100-109, 110-119, 120-129, 130-139, 140-149, 150+

    // 성공 확률 테이블 (star 0~24)
    private static final double[] SUCCESS_RATES = {
            0.95, 0.90, 0.85, 0.85, 0.80,   // 0-4
            0.75, 0.70, 0.65, 0.60, 0.55,   // 5-9
            0.50, 0.45, 0.40, 0.35, 0.30,   // 10-14
            0.30, 0.30, 0.30, 0.30, 0.30,   // 15-19
            0.30, 0.30, 0.03, 0.02, 0.01    // 20-24
    };

    // 파괴 확률 테이블 (star 0~24, 12성부터 파괴 확률 존재)
    private static final double[] DESTROY_RATES = {
            0.0, 0.0, 0.0, 0.0, 0.0,        // 0-4
            0.0, 0.0, 0.0, 0.0, 0.0,        // 5-9
            0.0, 0.0, 0.006, 0.013, 0.014,  // 10-14 (12성부터 파괴)
            0.021, 0.021, 0.021, 0.028, 0.028, // 15-19
            0.07, 0.07, 0.194, 0.294, 0.396 // 20-24
    };

    private final LogicExecutor executor;

    // Pre-computed lookup table: [star][levelBracket] -> expected cost to reach star+1
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
        // Pre-compute expected costs for all combinations
        for (int star = 0; star < MAX_STAR; star++) {
            for (int level = 100; level <= MAX_LEVEL; level += 10) {
                String key = cacheKey(star, star + 1, level);
                BigDecimal expected = computeExpectedCostForSingleStar(star, level);
                expectedCostCache.put(key, expected);
            }
        }
    }

    @Override
    public BigDecimal getExpectedCost(int currentStar, int targetStar, int itemLevel) {
        validateStarRange(currentStar, targetStar);
        validateLevel(itemLevel);

        if (currentStar >= targetStar) {
            return BigDecimal.ZERO;
        }

        // Sum expected costs for each star level
        BigDecimal totalExpected = BigDecimal.ZERO;
        for (int star = currentStar; star < targetStar; star++) {
            BigDecimal singleStarCost = getExpectedCostForSingleStar(star, itemLevel);
            totalExpected = totalExpected.add(singleStarCost);
        }

        return totalExpected.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 단일 스타 강화 기대값 계산 (Markov Chain 기반)
     *
     * <p>기대 시도 횟수 = 1 / 성공확률 (기하분포)</p>
     * <p>파괴 고려 시 추가 비용 = 파괴확률 × 복구비용 × 기대시도횟수</p>
     */
    private BigDecimal getExpectedCostForSingleStar(int star, int itemLevel) {
        String key = cacheKey(star, star + 1, normalizeLevel(itemLevel));
        return expectedCostCache.computeIfAbsent(key, k -> computeExpectedCostForSingleStar(star, itemLevel));
    }

    private BigDecimal computeExpectedCostForSingleStar(int star, int itemLevel) {
        BigDecimal successRate = getSuccessProbability(star);
        BigDecimal destroyRate = getDestroyProbability(star);
        BigDecimal singleCost = getSingleEnhanceCost(star, itemLevel);

        // 기하분포 기대값: E[X] = 1 / p
        BigDecimal expectedTrials = BigDecimal.ONE.divide(successRate, 10, RoundingMode.HALF_UP);

        // 기본 비용 = 시도 횟수 × 단가
        BigDecimal baseCost = singleCost.multiply(expectedTrials);

        // 파괴 고려 (간략화된 모델)
        // 파괴 시 이전 스타까지 다시 올려야 함 (실제로는 12성으로 복구됨)
        if (destroyRate.compareTo(BigDecimal.ZERO) > 0) {
            // 파괴 시 추가 비용 = 파괴확률 × 기대시도횟수 × (0~star까지 다시 올리는 비용의 30%)
            // 간략화: 파괴 비용을 기본 비용의 일정 비율로 추정
            BigDecimal destroyPenalty = baseCost.multiply(destroyRate)
                    .multiply(BigDecimal.valueOf(star * 0.5));
            baseCost = baseCost.add(destroyPenalty);
        }

        return baseCost.setScale(0, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal getSuccessProbability(int currentStar) {
        if (currentStar < 0 || currentStar >= MAX_STAR) {
            throw new IllegalArgumentException("Invalid star: " + currentStar + " (valid: 0-24)");
        }
        return BigDecimal.valueOf(SUCCESS_RATES[currentStar]);
    }

    @Override
    public BigDecimal getDestroyProbability(int currentStar) {
        if (currentStar < 0 || currentStar >= MAX_STAR) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(DESTROY_RATES[currentStar]);
    }

    @Override
    public BigDecimal getSingleEnhanceCost(int currentStar, int itemLevel) {
        // 메이플스토리 공식: cost = 1000 + (level³ × (star+1)) / 25 + (level² × (star+1)² × 0.1)
        int level = Math.max(1, itemLevel);
        int starFactor = currentStar + 1;

        long levelCubed = (long) level * level * level;
        long levelSquared = (long) level * level;

        BigDecimal baseCost = BigDecimal.valueOf(1000);
        BigDecimal levelComponent = BigDecimal.valueOf(levelCubed * starFactor / 25);
        BigDecimal starComponent = BigDecimal.valueOf(levelSquared * starFactor * starFactor)
                .multiply(BigDecimal.valueOf(0.1));

        // 100의 배수로 반올림
        BigDecimal totalCost = baseCost.add(levelComponent).add(starComponent);
        return roundToNearest100(totalCost);
    }

    @Override
    public boolean isInitialized() {
        return initialized.get();
    }

    private void validateStarRange(int currentStar, int targetStar) {
        if (currentStar < 0 || currentStar > MAX_STAR) {
            throw new IllegalArgumentException("Invalid current star: " + currentStar + " (valid: 0-25)");
        }
        if (targetStar < 0 || targetStar > MAX_STAR) {
            throw new IllegalArgumentException("Invalid target star: " + targetStar + " (valid: 0-25)");
        }
        if (targetStar < currentStar) {
            throw new IllegalArgumentException("Target star must be >= current star");
        }
    }

    private void validateLevel(int itemLevel) {
        if (itemLevel < 0 || itemLevel > MAX_LEVEL) {
            throw new IllegalArgumentException("Invalid item level: " + itemLevel + " (valid: 0-300)");
        }
    }

    private int normalizeLevel(int level) {
        // 레벨을 10단위로 정규화 (캐시 효율화)
        if (level < 100) return 100;
        return Math.min((level / 10) * 10, 300);
    }

    private String cacheKey(int currentStar, int targetStar, int normalizedLevel) {
        return currentStar + "-" + targetStar + "-" + normalizedLevel;
    }

    private BigDecimal roundToNearest100(BigDecimal value) {
        return value.divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
