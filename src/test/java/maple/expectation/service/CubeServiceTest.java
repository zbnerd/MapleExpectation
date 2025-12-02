package maple.expectation.service;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse.ItemOption;
import maple.expectation.service.v2.CubeService;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTestWithTimeLogging
class CubeServiceTest {

    @Autowired
    private CubeService cubeService;

    @Test
    @DisplayName("실전 테스트: 200제 모자, STR 3줄(12%, 9%, 9%) 띄우는 비용 계산")
    void calculate_real_cost_test() {
        // 1. given: 가상의 아이템 생성 (아델의 모자라고 가정)
        ItemEquipment item = new ItemEquipment();
        item.setItemName("하이네스 워리어헬름");
        item.setItemEquipmentSlot("모자");
        item.setPotentialOptionGrade("레전드리");

        // 레벨 설정 (기본 옵션 객체 안에 있음)
        ItemOption baseOption = new ItemOption();
        baseOption.setBaseEquipmentLevel("200");
        item.setBaseOption(baseOption);

        // 목표 옵션 세팅 (CSV에 있는 정확한 명칭이어야 함)
        item.setPotentialOption1("STR +12%");
        item.setPotentialOption2("STR +9%");
        item.setPotentialOption3("STR +9%");

        // 2. when: 서비스 호출
        long expectedCost = cubeService.calculateExpectedCost(item);

        // 3. then: 결과 검증 및 로그 확인
        assertThat(expectedCost).isGreaterThan(0); // 0원이면 안 됨 (데이터 못 찾은 것)

        // 보기 좋게 출력 (억 단위)
        log.info("=============================================");
        log.info("아이템: {}", item.getItemName());
        log.info("옵션: {}, {}, {}", item.getPotentialOption1(), item.getPotentialOption2(), item.getPotentialOption3());
        log.info("---------------------------------------------");
        log.info("💰 총 기대 비용: 약 {}억 메소", String.format("%,d", expectedCost / 100_000_000));
        log.info("=============================================");
    }
    
    @Test
    @DisplayName("데이터가 없는 경우(이상한 옵션) 0원 반환 테스트")
    void calculate_fail_test() {
        // given
        ItemEquipment item = new ItemEquipment();
        item.setItemName("망한 아이템");
        item.setItemEquipmentSlot("모자");
        item.setPotentialOptionGrade("레전드리");
        
        ItemOption baseOption = new ItemOption();
        baseOption.setBaseEquipmentLevel("200");
        item.setBaseOption(baseOption);

        item.setPotentialOption1("존재하지 않는 옵션 123%"); // 없는 옵션

        // when
        long cost = cubeService.calculateExpectedCost(item);

        // then
        assertThat(cost).isEqualTo(0);
        log.info("없는 옵션 조회 시 비용: {}", cost);
    }

    @Test
    @DisplayName("쿨감 4초(-2초, -2초, 아무거나) 띄우는 비용 계산")
    void calculate_cooldown_cost_test() {
        // given
        ItemEquipment item = new ItemEquipment();
        item.setItemName("에테르넬 나이트헬름");
        item.setItemEquipmentSlot("모자");
        item.setPotentialOptionGrade("레전드리");

        ItemOption baseOption = new ItemOption();
        baseOption.setBaseEquipmentLevel("250"); // 250제
        item.setBaseOption(baseOption);

        // ★ 핵심: 3번째 줄은 비워둡니다 (null) -> "아무거나"
        item.setPotentialOption1(null);
        item.setPotentialOption2("스킬 재사용 대기시간 -2초");
        item.setPotentialOption3("스킬 재사용 대기시간 -2초");

        // when
        long cost = cubeService.calculateExpectedCost(item);

        // then
        assertThat(cost).isGreaterThan(0);

        log.info("=============================================");
        log.info("목표: 쿨감 4초 (3번째 줄 무관)");
        log.info("💰 기대 비용: 약 {}억 메소", String.format("%,d", cost / 100_000_000));
        log.info("=============================================");
    }
}