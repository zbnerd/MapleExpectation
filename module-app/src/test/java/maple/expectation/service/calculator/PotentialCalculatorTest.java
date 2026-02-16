package maple.expectation.service.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.stat.StatParser;
import maple.expectation.core.domain.stat.StatType;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.service.v2.calculator.PotentialCalculator;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Slf4j
@ExtendWith(MockitoExtension.class) // ✅ Mockito 활성화
class PotentialCalculatorTest {

  private LogicExecutor executor;

  @Mock private StatParser statParser;

  private PotentialCalculator calculator;

  @BeforeEach
  void setUp() {
    executor = TestLogicExecutors.passThrough();

    // 1. 의존성 주입하여 생성
    calculator = new PotentialCalculator(statParser, executor);

    // 2. StatParser.parseNum 호출 시 숫자를 추출하도록 Mock 설정
    // 실제 StatParser 로직을 태우고 싶다면 Mock 대신 실제 객체를 주입해도 되지만,
    // 단위 테스트에서는 아래처럼 특정 입력에 대한 출력을 정의하는 게 정석입니다.
    when(statParser.parseNum(anyString()))
        .thenAnswer(
            inv -> {
              String arg = inv.getArgument(0);
              if (arg.contains("12")) return 12;
              if (arg.contains("9")) return 9;
              if (arg.contains("6")) return 6;
              return 0;
            });
  }

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

    // 2. LUK = 0 + 6(올스탯) = 6
    assertThat(calculator.getEffectiveStat(stats, StatType.LUK)).isEqualTo(6);

    // 3. 올스탯 원본 값 확인 = 6
    assertThat(stats.get(StatType.ALL_STAT)).isEqualTo(6);

    log.info("STR 최종: {}", calculator.getEffectiveStat(stats, StatType.STR)); // 27
    log.info("LUK 최종: {}", calculator.getEffectiveStat(stats, StatType.LUK)); // 6
  }
}
