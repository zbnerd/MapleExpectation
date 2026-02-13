package maple.expectation.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatParserTest {

  @Mock private LogicExecutor executor;

  private StatParser statParser;

  @BeforeEach
  void setUp() {
    statParser = new StatParser(executor);

    // 🚀 [해결] lenient()를 추가하여, 실행기를 호출하지 않는 테스트에서도 에러가 나지 않게 합니다.
    lenient()
        .when(executor.executeOrDefault(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              try {
                // 첫 번째 인자인 ThrowingSupplier를 실행
                ThrowingSupplier<?> task = invocation.getArgument(0, ThrowingSupplier.class);
                return task.get();
              } catch (Throwable e) {
                // 예외 발생 시 두 번째 인자인 defaultValue 반환
                return invocation.getArgument(1);
              }
            });
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
    // 💡 이 테스트는 executor를 호출하지 않으므로,
    // lenient() 설정 덕분에 UnnecessaryStubbingException이 발생하지 않습니다.
    assertThat(statParser.isPercent("STR +12%")).isTrue();
    assertThat(statParser.isPercent("공격력 +10")).isFalse();
  }
}
