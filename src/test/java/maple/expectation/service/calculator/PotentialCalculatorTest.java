package maple.expectation.service.calculator;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.calculator.PotentialCalculator;
import maple.expectation.util.StatType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class PotentialCalculatorTest {

    private final PotentialCalculator calculator = new PotentialCalculator();

    @Test
    @DisplayName("잠재능력 3줄 합산 테스트 (올스탯 포함 계산)")
    void calculate_manual_test() {
        // given
        EquipmentResponse.ItemEquipment item = new EquipmentResponse.ItemEquipment();
        item.setPotentialOption1("STR +12%");
        item.setPotentialOption2("STR +9%");
        item.setPotentialOption3("올스탯 +6%");

        // when
        Map<StatType, Integer> stats = calculator.calculateMainPotential(item);

        // then: 'getEffectiveStat'을 사용해 올스탯까지 합친 값을 검증

        // 1. STR = (12 + 9) + 6(올스탯) = 27
        assertThat(calculator.getEffectiveStat(stats, StatType.STR)).isEqualTo(27);

        // 2. LUK = 0 + 6(올스탯) = 6  <-- 이제 LUK도 값이 나옵니다!
        assertThat(calculator.getEffectiveStat(stats, StatType.LUK)).isEqualTo(6);

        // 3. 올스탯 원본 값 확인 = 6
        assertThat(stats.get(StatType.ALL_STAT)).isEqualTo(6);

        System.out.println("STR 최종: " + calculator.getEffectiveStat(stats, StatType.STR)); // 27
        System.out.println("LUK 최종: " + calculator.getEffectiveStat(stats, StatType.LUK)); // 6
    }


}