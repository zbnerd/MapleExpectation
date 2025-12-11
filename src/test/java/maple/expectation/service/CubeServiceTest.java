package maple.expectation.service;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeService;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTestWithTimeLogging
@TestPropertySource(properties = "app.optimization.use-compression=false")
class CubeServiceTest {

    @Autowired
    private CubeService cubeService;

    @Test
    @DisplayName("실전 테스트: 200제 모자, STR 3줄(12%, 9%, 9%) 띄우는 비용 계산")
    void calculate_real_cost_test() {
        // 1. given: DTO 생성 (List<String> 사용)
        CubeCalculationInput input = CubeCalculationInput.builder()
                .itemName("하이네스 워리어헬름")
                .part("모자")
                .level(200)
                .grade("레전드리") // 필드명: currentGrade -> grade 확인
                // List.of는 불변 리스트를 만듭니다 (null 포함 불가)
                .options(List.of("STR +12%", "STR +9%", "STR +9%"))
                .build();

        // 2. when
        long expectedCost = cubeService.calculateExpectedCost(input);

        // 3. then
        assertThat(expectedCost).isGreaterThan(0);

        log.info("=============================================");
        log.info("아이템: {}", input.getItemName());
        log.info("옵션: {}", input.getOptions());
        log.info("---------------------------------------------");
        log.info("💰 총 기대 비용: 약 {}억 메소", String.format("%,d", expectedCost / 100_000_000));
        log.info("=============================================");
    }


    @Test
    @DisplayName("쿨감 4초(-2초, -2초, 아무거나) 띄우는 비용 계산")
    void calculate_cooldown_cost_test() {
        // given
        // ★ 핵심: '아무거나'를 표현하기 위해 null을 사용하는 경우, List.of() 대신 Arrays.asList() 사용
        // (List.of는 null을 허용하지 않아 에러 발생함)
        CubeCalculationInput input = CubeCalculationInput.builder()
                .itemName("에테르넬 나이트헬름")
                .part("모자")
                .level(250)
                .grade("레전드리")
                // 첫 줄은 null (상관없음), 나머지 두 줄은 쿨감
                .options(Arrays.asList(null, "스킬 재사용 대기시간 -2초", "스킬 재사용 대기시간 -2초"))
                .build();

        // when
        long cost = cubeService.calculateExpectedCost(input);

        // then
        assertThat(cost).isGreaterThan(0);

        log.info("=============================================");
        log.info("목표: 쿨감 4초 (나머지 한 줄 무관)");
        log.info("옵션 리스트: {}", input.getOptions());
        log.info("💰 기대 비용: 약 {}억 메소", String.format("%,d", cost / 100_000_000));
        log.info("=============================================");
    }
}