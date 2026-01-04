package maple.expectation.util;

import maple.expectation.global.executor.LogicExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatParserTest {

    @Mock
    private LogicExecutor executor;

    private StatParser statParser;

    @BeforeEach
    void setUp() {
        statParser = new StatParser(executor);

        // ✅ [핵심] LogicExecutor가 들어오면, 람다 내부 로직을 그대로 실행하도록 Mock 설정
        // 이 설정이 있어야 executor.executeOrDefault() 내부의 파싱 로직이 실제로 돌아갑니다.
        when(executor.executeOrDefault(any(), any(), any())).thenAnswer(invocation -> {
            try {
                // 첫 번째 인자인 ThrowingSupplier를 실행한 결과를 반환
                return ((maple.expectation.global.common.function.ThrowingSupplier<?>) invocation.getArgument(0)).get();
            } catch (Throwable e) {
                // 예외 발생 시 두 번째 인자인 defaultValue 반환
                return invocation.getArgument(1);
            }
        });
    }

    @Test
    @DisplayName("다양한 옵션 문자열에서 숫자만 잘 뽑아내는지 테스트")
    void parse_test() {
        // 이제 StatParser.parseNum()이 아니라 인스턴스 메서드(statParser.parseNum)로 호출!

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