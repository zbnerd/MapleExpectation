package maple.expectation.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatTypeTest {

    @Test
    @DisplayName("옵션 문자열에서 스탯 타입을 정확히 찾아내는지 테스트")
    void findType_test() {
        // 1. 기본 스탯
        assertThat(StatType.findType("STR +12%")).isEqualTo(StatType.STR);
        assertThat(StatType.findType("올스탯 +9%")).isEqualTo(StatType.ALL_STAT);

        // 2. 특수 스탯
        assertThat(StatType.findType("보스 몬스터 공격 시 데미지 +40%")).isEqualTo(StatType.BOSS_DAMAGE);
        assertThat(StatType.findType("모든 스킬의 재사용 대기시간 -2초")).isEqualTo(StatType.COOLDOWN_REDUCTION);
        
        // 3. 헷갈리는 것 (HP vs MP)
        assertThat(StatType.findType("최대 HP +10%")).isEqualTo(StatType.HP);
        
        // 4. 없는 것
        assertThat(StatType.findType("쓸만한 샤프 아이즈 사용 가능")).isEqualTo(StatType.UNKNOWN);
    }
}