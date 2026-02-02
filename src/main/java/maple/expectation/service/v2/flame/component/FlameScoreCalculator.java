package maple.expectation.service.v2.flame.component;

import maple.expectation.service.v2.flame.FlameEquipCategory;
import maple.expectation.service.v2.flame.FlameOptionType;
import maple.expectation.service.v2.flame.FlameType;
import maple.expectation.service.v2.flame.config.FlameStageProbability;
import maple.expectation.service.v2.flame.config.FlameStatTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 환생의 불꽃 환산치 계산 컴포넌트
 *
 * <p>각 옵션 종류별 "1줄 환산치 PMF"를 생성한다.
 * 스케일 팩터 10을 적용하여 모든 환산치를 정수로 처리.</p>
 */
@Component
public class FlameScoreCalculator {

    private static final int SCALE = 10;

    /**
     * 직업 가중치 Record
     * 모든 값은 SCALE(10)이 적용된 정수
     */
    public record JobWeights(
        int wStr, int wDex, int wInt, int wLuk,
        int wHp, int wMp,
        int wAllstatPct,
        int wAtt, int wMag,
        int wDmgPct, int wBossDmgPct
    ) {
        /** 일반 직업 (주스탯=10, 부스탯=1, 올스탯%=100, 공=40, 뎀/보공=140) */
        public static JobWeights of(String mainStat, String subStat) {
            int wStr = 0, wDex = 0, wInt = 0, wLuk = 0;
            switch (mainStat) {
                case "STR" -> wStr = SCALE;
                case "DEX" -> wDex = SCALE;
                case "INT" -> wInt = SCALE;
                case "LUK" -> wLuk = SCALE;
            }
            if (subStat != null) {
                switch (subStat) {
                    case "STR" -> wStr = 1;
                    case "DEX" -> wDex = 1;
                    case "INT" -> wInt = 1;
                    case "LUK" -> wLuk = 1;
                }
            }
            return new JobWeights(wStr, wDex, wInt, wLuk, 0, 0,
                10 * SCALE, 4 * SCALE, 4 * SCALE, 14 * SCALE, 14 * SCALE);
        }

        /** 부스탯 2개 (섀도어, 듀얼블레이드, 카데나: LUK 주스탯, STR+DEX 부스탯) */
        public static JobWeights of(String mainStat, String subStat1, String subStat2) {
            int[] stats = buildStatWeights(mainStat, subStat1, subStat2);
            return new JobWeights(stats[0], stats[1], stats[2], stats[3], 0, 0,
                10 * SCALE, 4 * SCALE, 4 * SCALE, 14 * SCALE, 14 * SCALE);
        }

        /** 제논 (STR+DEX+LUK 3주스탯) */
        public static JobWeights xenon() {
            return new JobWeights(SCALE, SCALE, 0, SCALE, 0, 0,
                10 * SCALE, 4 * SCALE, 4 * SCALE, 14 * SCALE, 14 * SCALE);
        }

        /** 데몬어벤져 (HP=10, 공=1500) */
        public static JobWeights demonAvenger() {
            return new JobWeights(0, 0, 0, 0, SCALE, 0, 0, 150 * SCALE, 0, 0, 0);
        }

        private static int[] buildStatWeights(String mainStat, String... subStats) {
            int[] w = new int[4]; // [STR, DEX, INT, LUK]
            applyWeight(w, mainStat, SCALE);
            for (String sub : subStats) {
                applyWeight(w, sub, 1);
            }
            return w;
        }

        private static void applyWeight(int[] w, String stat, int value) {
            if (stat == null) return;
            switch (stat) {
                case "STR" -> w[0] = value;
                case "DEX" -> w[1] = value;
                case "INT" -> w[2] = value;
                case "LUK" -> w[3] = value;
            }
        }
    }

    /**
     * 특정 옵션의 특정 단계에서의 환산치 계산
     */
    public Integer calculateScore(FlameOptionType option, int level, int stage,
                                   JobWeights weights, boolean isWeapon, int baseAtt, int baseMag) {
        if (option.isCompositeStat()) {
            return calculateCompositeScore(option, level, stage, weights);
        }

        if (isWeapon) {
            return switch (option) {
                case ATT -> {
                    int bonus = FlameStatTable.weaponAttBonus(level, stage, baseAtt);
                    yield bonus * weights.wAtt();
                }
                case MAG -> {
                    int bonus = FlameStatTable.weaponAttBonus(level, stage, baseMag);
                    yield bonus * weights.wMag();
                }
                case BOSS_DMG_PCT -> FlameStatTable.weaponBossDmgPct(stage) * weights.wBossDmgPct();
                default -> calculateArmorScore(option, level, stage, weights);
            };
        }

        return calculateArmorScore(option, level, stage, weights);
    }

    /**
     * 옵션 종류별 1줄 환산치 PMF 생성
     *
     * @return List of PMFs (Map&lt;score, probability&gt;), one per valid option
     */
    public List<Map<Integer, Double>> buildOptionPmfs(
            FlameEquipCategory category, FlameType flameType,
            int level, JobWeights weights, int baseAtt, int baseMag) {

        Map<Integer, Double> stageProbs = FlameStageProbability.getStageProbs(category.isBossDrop(), flameType);
        FlameOptionType[] optionPool = category.isWeapon()
                ? FlameOptionType.WEAPON_OPTIONS : FlameOptionType.ARMOR_OPTIONS;

        List<Map<Integer, Double>> pmfs = new ArrayList<>();
        for (FlameOptionType option : optionPool) {
            Map<Integer, Double> pmf = buildSingleOptionPmf(
                    option, level, stageProbs, weights, category.isWeapon(), baseAtt, baseMag);
            if (pmf != null) {
                pmfs.add(pmf);
            }
        }
        return pmfs;
    }

    private Integer calculateArmorScore(FlameOptionType option, int level, int stage, JobWeights weights) {
        Integer value = FlameStatTable.getArmorValue(option, level, stage);
        if (value == null) {
            return null;
        }

        return switch (option) {
            case STR -> value * weights.wStr();
            case DEX -> value * weights.wDex();
            case INT -> value * weights.wInt();
            case LUK -> value * weights.wLuk();
            case MAX_HP -> value * weights.wHp();
            case MAX_MP -> value * weights.wMp();
            case ATT -> value * weights.wAtt();
            case MAG -> value * weights.wMag();
            case ALLSTAT_PCT -> value * weights.wAllstatPct();
            case DMG_PCT -> value * weights.wDmgPct();
            case BOSS_DMG_PCT -> value * weights.wBossDmgPct();
            default -> 0;  // DEF, LEVEL_REDUCE, SPEED, JUMP -> weight 0
        };
    }

    private Integer calculateCompositeScore(FlameOptionType option, int level, int stage, JobWeights weights) {
        Integer value = FlameStatTable.getArmorValue(option, level, stage);
        if (value == null) {
            return null;
        }

        return switch (option) {
            case STR_DEX -> value * weights.wStr() + value * weights.wDex();
            case STR_INT -> value * weights.wStr() + value * weights.wInt();
            case STR_LUK -> value * weights.wStr() + value * weights.wLuk();
            case DEX_INT -> value * weights.wDex() + value * weights.wInt();
            case DEX_LUK -> value * weights.wDex() + value * weights.wLuk();
            case INT_LUK -> value * weights.wInt() + value * weights.wLuk();
            default -> 0;
        };
    }

    private Map<Integer, Double> buildSingleOptionPmf(
            FlameOptionType option, int level, Map<Integer, Double> stageProbs,
            JobWeights weights, boolean isWeapon, int baseAtt, int baseMag) {
        Map<Integer, Double> pmf = new HashMap<>();

        for (var entry : stageProbs.entrySet()) {
            int stage = entry.getKey();
            double prob = entry.getValue();

            Integer score = calculateScore(option, level, stage, weights, isWeapon, baseAtt, baseMag);
            if (score == null) {
                return null;  // stage not available -> option invalid
            }

            pmf.merge(score, prob, Double::sum);
        }

        return pmf.isEmpty() ? null : pmf;
    }
}
