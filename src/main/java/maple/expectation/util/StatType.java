package maple.expectation.util;

import lombok.Getter;
import java.util.Arrays;

@Getter
public enum StatType {
    
    // 1. 핵심 스탯
    STR("STR"),
    DEX("DEX"),
    INT("INT"),
    LUK("LUK"),
    ALL_STAT("올스탯"), // 올스탯은 계산 시 STR, DEX, INT, LUK 모두에 더해져야 함
    
    // 2. 공격력/마력
    ATTACK_POWER("공격력"),
    MAGIC_POWER("마력"),
    
    // 3. 특수 옵션
    BOSS_DAMAGE("보스 몬스터 공격 시 데미지"), // 보공
    IGNORE_DEFENSE("몬스터 방어율 무시"),     // 방무
    DAMAGE("데미지"),
    CRITICAL_DAMAGE("크리티컬 데미지"),
    
    // 4. 유틸 옵션
    COOLDOWN_REDUCTION("재사용 대기시간"), // 쿨감
    ITEM_DROP("아이템 드롭률"),
    MESO_DROP("메소 획득량"),
    HP("HP"),
    
    // 5. 기타 (판별 불가)
    UNKNOWN("기타");

    private final String keyword;

    StatType(String keyword) {
        this.keyword = keyword;
    }

    /**
     * 문자열을 분석해서 어떤 스탯인지 찾아냅니다.
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
                .filter(type -> option.contains(type.keyword)) // 키워드 포함 여부 확인
                .findFirst()
                .orElse(UNKNOWN);
    }
}