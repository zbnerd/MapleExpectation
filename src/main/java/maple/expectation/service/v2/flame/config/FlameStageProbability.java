package maple.expectation.service.v2.flame.config;

import maple.expectation.service.v2.flame.FlameType;

import java.util.Map;

/**
 * 환생의 불꽃 단계별 확률 분포
 *
 * <p>보스 장비와 일반 장비의 단계(stage) 확률이 상이하며,
 * 불꽃 종류(POWERFUL / ETERNAL / ABYSS)에 따라 분포가 달라진다.</p>
 */
public final class FlameStageProbability {

    private FlameStageProbability() {
    }

    // Boss equipment stage probabilities
    private static final Map<Integer, Double> BOSS_POWERFUL = Map.of(3, 0.20, 4, 0.30, 5, 0.36, 6, 0.14);
    private static final Map<Integer, Double> BOSS_ETERNAL = Map.of(4, 0.29, 5, 0.45, 6, 0.25, 7, 0.01);
    private static final Map<Integer, Double> BOSS_ABYSS = Map.of(5, 0.63, 6, 0.34, 7, 0.03);

    // Other equipment stage probabilities
    private static final Map<Integer, Double> OTHER_POWERFUL = Map.of(1, 0.20, 2, 0.30, 3, 0.36, 4, 0.14);
    private static final Map<Integer, Double> OTHER_ETERNAL = Map.of(2, 0.29, 3, 0.45, 4, 0.25, 5, 0.01);
    private static final Map<Integer, Double> OTHER_ABYSS = Map.of(3, 0.63, 4, 0.34, 5, 0.03);

    public static Map<Integer, Double> getStageProbs(boolean bossDrop, FlameType flameType) {
        return switch (flameType) {
            case POWERFUL -> bossDrop ? BOSS_POWERFUL : OTHER_POWERFUL;
            case ETERNAL -> bossDrop ? BOSS_ETERNAL : OTHER_ETERNAL;
            case ABYSS -> bossDrop ? BOSS_ABYSS : OTHER_ABYSS;
        };
    }
}
