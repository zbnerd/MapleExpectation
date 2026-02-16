package maple.expectation.util;

import static org.assertj.core.api.Assertions.assertThat;

import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatParserTest {

  private LogicExecutor executor;
  private StatParser statParser;

  @BeforeEach
  void setUp() {
    executor = TestLogicExecutors.passThrough();
    statParser = new StatParser(executor);
  }

  @Test
  @DisplayName("다양한 옵션 문자열에서 숫자만 잘 뽑아내는지 테스트")
  void parse_test() {
    // 1. 일반 숫자
    assertThat(statParser.parseNum("450")).isEqualTo(450);

    // 2. 플러스 기호
    assertThat(statParser.parseNum("+450")).isEqualTo(450);

    // 3. 잠재능력 (퍼센트)
    assertThat(statParser.parseNum("STR +12%")).isEqualTo(12);

    // 4. 올스탯
    assertThat(statParser.parseNum("올스탯 +6%")).isEqualTo(6);

    // 5. 쿨타임 (마이너스 처리)
    assertThat(statParser.parseNum("스킬 재사용 대기시간 -2초")).isEqualTo(-2);

    // 6. null or 빈 문자열
    assertThat(statParser.parseNum(null)).isEqualTo(0);
    assertThat(statParser.parseNum("")).isEqualTo(0);
  }

  @Test
  @DisplayName("퍼센트 여부 확인")
  void percent_check_test() {
    assertThat(statParser.isPercent("STR +12%")).isTrue();
    assertThat(statParser.isPercent("공격력 +10")).isFalse();
  }
}
