package maple.expectation.util;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Getter
public enum StatType {

    // 1. 핵심 스탯 (기존 - 하위호환)
    STR("STR", false, true),
    DEX("DEX", false, true),
    INT("INT", false, true),
    LUK("LUK", false, true),
    ALL_STAT("올스탯", false, false), // 올스탯은 계산 시 STR, DEX, INT, LUK 모두에 더해져야 함

    // 1-1. 퍼센트 스탯 (DP 엔진용 - 단위 포함)
    STR_PERCENT("STR", true, true),
    DEX_PERCENT("DEX", true, true),
    INT_PERCENT("INT", true, true),
    LUK_PERCENT("LUK", true, true),
    ALLSTAT_PERCENT("올스탯", true, false),

    // 2. 공격력/마력
    ATTACK_POWER("공격력", false, false),
    MAGIC_POWER("마력", false, false),
    ATTACK_POWER_PERCENT("공격력", true, false),
    MAGIC_POWER_PERCENT("마력", true, false),

    // 3. 특수 옵션
    BOSS_DAMAGE("보스 몬스터 공격 시 데미지", true, false), // 보공 (항상 %)
    IGNORE_DEFENSE("몬스터 방어율 무시", true, false),     // 방무 (항상 %)
    DAMAGE("데미지", true, false),
    CRITICAL_DAMAGE("크리티컬 데미지", true, false),

    // 4. 유틸 옵션
    COOLDOWN_REDUCTION("재사용 대기시간", false, false), // 쿨감 (초 단위)
    ITEM_DROP("아이템 드롭률", true, false),
    MESO_DROP("메소 획득량", true, false),
    HP("HP", false, false),
    HP_PERCENT("HP", true, false),

    // 5. 레벨당 스탯 (에디셔널 핵심 옵션) (#240 V4)
    // longest-first 매칭으로 "STR"보다 먼저 감지됨
    LEVEL_STR("캐릭터 기준 9레벨 당 STR", false, true),
    LEVEL_DEX("캐릭터 기준 9레벨 당 DEX", false, true),
    LEVEL_INT("캐릭터 기준 9레벨 당 INT", false, true),
    LEVEL_LUK("캐릭터 기준 9레벨 당 LUK", false, true),

    // 6. 기타 (판별 불가)
    UNKNOWN("기타", false, false);

    private final String keyword;
    private final boolean percent;        // 퍼센트 스탯 여부
    /**
     * -- GETTER --
     *  개별 스탯 타입 여부 (STR, DEX, INT, LUK)
     *  ALLSTAT은 개별 스탯이 아님 (복합)
     */
    private final boolean individualStat; // 개별 스탯 여부 (STR, DEX, INT, LUK)

    StatType(String keyword, boolean percent, boolean individualStat) {
        this.keyword = keyword;
        this.percent = percent;
        this.individualStat = individualStat;
    }

    // 기존 생성자 호환 (percent=false, individualStat=false)
    StatType(String keyword) {
        this(keyword, false, false);
    }

    /**
     * 문자열을 분석해서 어떤 스탯인지 찾아냅니다. (기존 방식 - 하위호환)
     * 예: "STR +12%" -> StatType.STR
     * 예: "스킬 재사용 대기시간 -2초" -> StatType.COOLDOWN_REDUCTION
     */
    public static StatType findType(String option) {
        if (option == null || option.isEmpty()) {
            return UNKNOWN;
        }

        if (option.contains("피격 시") || // 피격 시 10% 확률로 데미지 무시 등
                option.contains("오토스틸")) {  // 공격 시 x% 확률로 오토스틸
            return UNKNOWN;
        }

        return Arrays.stream(values())
                .filter(type -> type != UNKNOWN) // 기타 제외하고 검색
                .filter(type -> !type.percent)   // 기존 방식: non-percent 타입만
                .filter(type -> option.contains(type.keyword)) // 키워드 포함 여부 확인
                .findFirst()
                .orElse(UNKNOWN);
    }

    /**
     * 문자열을 분석해서 단위 포함 스탯 타입을 찾아냅니다. (DP 엔진용)
     * 예: "STR +12%" -> StatType.STR_PERCENT
     * 예: "올스탯 +9%" -> StatType.ALLSTAT_PERCENT
     * 예: "STR +30" -> StatType.STR (플랫)
     *
     * @param option 옵션 문자열
     * @return 단위 포함 StatType (퍼센트 여부 자동 판별)
     */
    public static StatType findTypeWithUnit(String option) {
        if (option == null || option.isEmpty()) {
            return UNKNOWN;
        }

        if (option.contains("피격 시") || option.contains("오토스틸")) {
            return UNKNOWN;
        }

        boolean isPercent = option.contains("%");

        return Arrays.stream(values())
                .filter(type -> type != UNKNOWN)
                .filter(type -> type.percent == isPercent)
                .filter(type -> option.contains(type.keyword))
                .findFirst()
                .orElse(UNKNOWN);
    }

    /**
     * 문자열에서 모든 매칭되는 스탯 타입을 찾아냅니다. (복합 옵션 대응)
     * 예: "STR/DEX +6%" -> [STR_PERCENT, DEX_PERCENT]
     * 예: "올스탯 +9%" -> [ALLSTAT_PERCENT]
     *
     * <p>P0: 키워드 오탐 방지 - longest-first 매칭 적용</p>
     * <p>예: "보스 몬스터 공격 시 데미지" → BOSS_DAMAGE만 (DAMAGE 오탐 방지)</p>
     *
     * <p>하위호환: 매칭 실패 시 UNKNOWN 반환 (기존 동작 유지)</p>
     *
     * @param option 옵션 문자열
     * @return 매칭된 StatType 리스트 (없으면 [UNKNOWN])
     */
    public static List<StatType> findAllTypes(String option) {
        List<StatType> results = findAllTypesOrEmpty(option);
        return results.isEmpty() ? List.of(UNKNOWN) : results;
    }

    /**
     * Strict 버전: 매칭 실패 시 예외 (DP 엔진 Fail-Fast 전용)
     *
     * @param option 옵션 문자열
     * @return 매칭된 StatType 리스트
     * @throws IllegalArgumentException 매칭 결과 없을 시
     */
    public static List<StatType> findAllTypesStrict(String option) {
        List<StatType> results = findAllTypesOrEmpty(option);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("스탯 타입 매칭 실패: " + option);
        }
        return results;
    }

    /**
     * 문자열에서 모든 매칭되는 스탯 타입을 찾습니다. (빈 리스트 허용)
     *
     * <p>비-핵심 경로용 (프록 옵션 등 무시 가능한 경우)</p>
     * <p>P0: 키워드 오탐 방지 - longest-first 매칭 적용</p>
     * <p>#240 V4: 레벨당 스탯 최우선 감지</p>
     *
     * @param option 옵션 문자열
     * @return 매칭된 StatType 리스트 (없으면 빈 리스트)
     */
    public static List<StatType> findAllTypesOrEmpty(String option) {
        if (option == null || option.isEmpty()) {
            return List.of();
        }

        // 프록 옵션: 빈 리스트 (기여도 0으로 처리)
        if (option.contains("피격 시") || option.contains("오토스틸")) {
            return List.of();
        }

        // #240 V4: 레벨당 스탯 최우선 감지 (STR +21 같은 깡스탯보다 먼저)
        // "캐릭터 기준 9레벨 당 STR +1" → LEVEL_STR로 감지 (STR 아님)
        StatType levelStat = detectLevelBasedStat(option);
        if (levelStat != null) {
            return List.of(levelStat);
        }

        boolean isPercent = option.contains("%");
        List<StatType> results = new ArrayList<>();

        // P0: 키워드 길이 내림차순 정렬 (longest-first)
        // "보스 몬스터 공격 시 데미지"가 "데미지"보다 먼저 매칭
        List<StatType> candidates = Arrays.stream(values())
                .filter(type -> type != UNKNOWN)
                .filter(type -> !isLevelBasedStat(type))  // 레벨당 스탯은 위에서 처리됨
                .filter(type -> type.percent == isPercent)
                .sorted(Comparator.comparingInt((StatType t) -> t.keyword.length()).reversed())
                .toList();

        // 매칭된 키워드 위치 추적 (오탐 방지)
        String remaining = option;
        for (StatType type : candidates) {
            if (remaining.contains(type.keyword)) {
                results.add(type);
                // 매칭된 키워드 제거 (동일 위치 재매칭 방지)
                remaining = remaining.replace(type.keyword, "");
            }
        }

        return results;
    }

    /**
     * 레벨당 스탯 감지 (#240 V4)
     *
     * <p>"캐릭터 기준 9레벨 당" 패턴을 최우선으로 감지하여
     * "STR +21" 같은 깡스탯으로 오인식되는 것을 방지합니다.</p>
     *
     * @param option 옵션 문자열
     * @return 레벨당 스탯 타입 (없으면 null)
     */
    private static StatType detectLevelBasedStat(String option) {
        if (!option.contains("캐릭터 기준") || !option.contains("레벨 당")) {
            return null;
        }

        if (option.contains("STR")) return LEVEL_STR;
        if (option.contains("DEX")) return LEVEL_DEX;
        if (option.contains("INT")) return LEVEL_INT;
        if (option.contains("LUK")) return LEVEL_LUK;

        return null;
    }

    /**
     * 레벨당 스탯 타입 여부 (#240 V4)
     */
    private static boolean isLevelBasedStat(StatType type) {
        return type == LEVEL_STR || type == LEVEL_DEX ||
               type == LEVEL_INT || type == LEVEL_LUK;
    }

    /**
     * Primary stat 계열 키워드 포함 여부 (Drift 감지용)
     *
     * <p>STR, DEX, INT, LUK, 올스탯 계열이 포함되어 있는지 휴리스틱 판별</p>
     *
     * @param option 옵션 문자열
     * @return primary stat 계열로 보이면 true
     */
    public static boolean looksLikePrimaryStat(String option) {
        if (option == null || option.isEmpty()) {
            return false;
        }
        // 주 스탯 키워드 패턴 (대소문자 무관)
        String upper = option.toUpperCase();
        return upper.contains("STR") || upper.contains("DEX") ||
               upper.contains("INT") || upper.contains("LUK") ||
               option.contains("올스탯") || option.contains("올 스탯");
    }

    // ==================== 복합 옵션 카테고리 (#240 V4) ====================

    /**
     * 옵션 카테고리 Enum (#240 V4)
     *
     * <p>복합 옵션 감지용: 서로 다른 카테고리의 옵션이 2개 이상이면 복합 옵션</p>
     * <ul>
     *   <li>STAT: STR, DEX, INT, LUK, 올스탯 (주 스탯)</li>
     *   <li>BOSS_IED: 보스 데미지, 방어율 무시</li>
     *   <li>ATK_MAG: 공격력, 마력</li>
     *   <li>CRIT_DMG: 크리티컬 데미지</li>
     *   <li>COOLDOWN: 스킬 재사용 대기시간 감소</li>
     *   <li>OTHER: 기타 (메소, 아이템 드롭 등)</li>
     * </ul>
     */
    public enum OptionCategory {
        STAT,       // STR, DEX, INT, LUK, 올스탯
        BOSS_IED,   // 보공, 방무
        ATK_MAG,    // 공격력, 마력
        CRIT_DMG,   // 크리티컬 데미지
        COOLDOWN,   // 쿨감
        OTHER       // 기타
    }

    /**
     * 해당 StatType의 옵션 카테고리 반환
     */
    public OptionCategory getCategory() {
        return switch (this) {
            case STR, DEX, INT, LUK, ALL_STAT,
                 STR_PERCENT, DEX_PERCENT, INT_PERCENT, LUK_PERCENT, ALLSTAT_PERCENT,
                 LEVEL_STR, LEVEL_DEX, LEVEL_INT, LEVEL_LUK -> OptionCategory.STAT;  // #240 V4: 레벨당 스탯 추가
            case BOSS_DAMAGE, IGNORE_DEFENSE -> OptionCategory.BOSS_IED;
            case ATTACK_POWER, MAGIC_POWER, ATTACK_POWER_PERCENT, MAGIC_POWER_PERCENT -> OptionCategory.ATK_MAG;
            case CRITICAL_DAMAGE -> OptionCategory.CRIT_DMG;
            case COOLDOWN_REDUCTION -> OptionCategory.COOLDOWN;
            default -> OptionCategory.OTHER;
        };
    }

    /**
     * 유효 옵션 카테고리 여부 (복합 옵션 감지용)
     *
     * <p>OTHER 카테고리는 복합 옵션 판정에서 제외</p>
     */
    public boolean isValidCategory() {
        return getCategory() != OptionCategory.OTHER;
    }

}

