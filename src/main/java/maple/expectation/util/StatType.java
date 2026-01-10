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

    // 5. 기타 (판별 불가)
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

        boolean isPercent = option.contains("%");
        List<StatType> results = new ArrayList<>();

        // P0: 키워드 길이 내림차순 정렬 (longest-first)
        // "보스 몬스터 공격 시 데미지"가 "데미지"보다 먼저 매칭
        List<StatType> candidates = Arrays.stream(values())
                .filter(type -> type != UNKNOWN)
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

}
