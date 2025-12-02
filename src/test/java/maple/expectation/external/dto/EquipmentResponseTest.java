package maple.expectation.external.dto;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CubeProbabilityRepository;
import maple.expectation.service.v2.CubeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class EquipmentResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CubeService cubeService;

    @BeforeEach
    void setUp() {
        // 1. Repository 수동 생성 및 초기화 (CSV 로딩)
        CubeProbabilityRepository repository = new CubeProbabilityRepository();
        repository.init(); // 데이터 로딩

        // 2. Service 수동 생성 (Repository 주입)
        // 스프링이 해주는 @Autowired를 우리가 직접 하는 겁니다 (생성자 주입)
        cubeService = new CubeService(repository);
    }

    @Test
    @DisplayName("에반: 드래곤 장비(dragon_equipment) 파싱 테스트")
    void evan_parsing_test() throws IOException {
        // given
        File jsonFile = new ClassPathResource("evan_equip.json").getFile();

        // when
        EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

        // then
        assertThat(response.getCharacterClass()).contains("에반");
        assertThat(response.getDragonEquipment()).isNotEmpty(); // ✅ 핵심: 드래곤 장비가 잘 들어왔나?

        log.info("드래곤 장비 개수: {}", response.getDragonEquipment().size());
        log.info("====== JsonData ======");
        log.info("{}", response);
    }

    @Test
    @DisplayName("에반: JSON 파싱 후 '내 템 얼마짜리?' 비용 계산 (통합 시뮬레이션)")
    void evan_simulation_test() throws IOException {
        // given
        File jsonFile = new ClassPathResource("evan_equip.json").getFile();

        // when
        EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

        // then
        assertThat(response.getCharacterClass()).contains("에반");

        long totalInventoryCost = 0;

        log.info("=========== [에반] 장비 감정 시작 ===========");

        for (EquipmentResponse.ItemEquipment item : response.getItemEquipment()) {
            // 잠재 등급이 없으면 계산 패스
            if (item.getPotentialOptionGrade() == null) continue;

            // 큐브 기대 비용 계산
            long cost = cubeService.calculateExpectedCost(item);

            if (cost > 0) {
                totalInventoryCost += cost;
                log.info("💎 [{}]: {} ({} | {} | {}) -> 예상 비용: 약 {}억 메소",
                        item.getItemEquipmentSlot(),
                        item.getItemName(),
                        item.getPotentialOption1(),
                        item.getPotentialOption2(),
                        item.getPotentialOption3(),
                        String.format("%,d", cost / 100_000_000) // 억 단위 표시
                );
            }
        }

        log.info("=============================================");
        log.info("💰 에반 템셋팅 총 기대값: 약 {}억 메소", String.format("%,d", totalInventoryCost / 100_000_000));
        log.info("=============================================");
    }

    @Test
    @DisplayName("메카닉: 메카닉 장비(mechanic_equipment) 파싱 테스트")
    void mechanic_parsing_test() throws IOException {
        // given
        File jsonFile = new ClassPathResource("mechanic_equip.json").getFile();

        // when
        EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

        // then
        assertThat(response.getCharacterClass()).contains("메카닉");
        assertThat(response.getMechanicEquipment()).isNotEmpty(); // ✅ 핵심: 메카닉 장비가 잘 들어왔나?

        log.info("메카닉 장비 개수: {}", response.getMechanicEquipment().size());
        log.info("====== JsonData ======");
        log.info("{}", response);
    }

}
