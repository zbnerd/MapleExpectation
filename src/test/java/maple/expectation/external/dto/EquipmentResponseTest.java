package maple.expectation.external.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.policy.CubeCostPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest // 💡 이제 스프링 컨테이너를 띄워 AOP 프록시를 주입받습니다.
public class EquipmentResponseTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CubeTrialsProvider trialsProvider; // 💡 스프링이 만든 @Cacheable 프록시가 주입됨!

    @Autowired
    private CubeCostPolicy costPolicy;

    // 💡 [변경 포인트] 더 이상 @BeforeEach에서 수동으로 객체를 조립(wiring)하지 않습니다.
    // 스프링이 빈 후처리기(BeanPostProcessor)를 통해 이미 조립된 '완제품'을 줍니다.

    @Test
    @DisplayName("에반: JSON 파싱 후 '내 템 얼마짜리?' 기대 시도 및 비용 계산 시뮬레이션")
    void evan_simulation_test() throws IOException {
        // given
        File jsonFile = new ClassPathResource("evan_equip.json").getFile();
        EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

        assertThat(response.getCharacterClass()).contains("에반");

        long totalInventoryCost = 0;
        log.info("=========== [에반] 장비 감정 시작 (스프링 AOP 기반 캐시 적용) ===========");

        for (EquipmentResponse.ItemEquipment item : response.getItemEquipment()) {
            if (item.getPotentialOptionGrade() == null) continue;

            int level = Integer.parseInt(item.getBaseOption().getBaseEquipmentLevel());

            CubeCalculationInput input = CubeCalculationInput.builder()
                    .itemName(item.getItemName())
                    .part(item.getItemEquipmentSlot())
                    .level(level)
                    .grade(item.getPotentialOptionGrade())
                    .options(Arrays.asList(
                            item.getPotentialOption1(),
                            item.getPotentialOption2(),
                            item.getPotentialOption3()
                    ))
                    .build();

            // 1. 스프링 AOP 프록시를 통한 기대 시도 횟수 조회
            // 💡 최초 호출 시엔 계산 로직이 실행되고, 동일 조건 재호출 시엔 캐시에서 바로 나옵니다.
            long trials = trialsProvider.calculateExpectedTrials(input, CubeType.BLACK);

            // 2. 비용 정책 객체를 통한 1회당 소모 메소 조회
            long costPerTrial = costPolicy.getCubeCost(CubeType.BLACK, level, input.getGrade());

            // 3. 최종 아이템 강화 비용 산출
            long totalItemCost = trials * costPerTrial;

            if (totalItemCost > 0) {
                totalInventoryCost += totalItemCost;
                log.info("💎 [{}]: {} -> 기대 횟수: {}회 | 예상 비용: 약 {}억 메소",
                        item.getItemEquipmentSlot(),
                        item.getItemName(),
                        String.format("%,d", trials),
                        String.format("%,d", totalItemCost / 100_000_000)
                );
            }
        }

        log.info("=============================================");
        log.info("💰 에반 전체 장비셋팅 총 기대 비용: 약 {}억 메소", String.format("%,d", totalInventoryCost / 100_000_000));
        log.info("=============================================");

        assertThat(totalInventoryCost).isGreaterThan(0);
    }

    // (evan_parsing_test, mechanic_parsing_test 등은 그대로 유지하거나 @Autowired 활용)
}