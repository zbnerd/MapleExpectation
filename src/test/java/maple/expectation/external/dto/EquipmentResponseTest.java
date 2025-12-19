package maple.expectation.external.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CubeProbabilityRepository;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.impl.CubeServiceImpl;
import maple.expectation.service.v2.policy.CubeCostPolicy;
import maple.expectation.service.v2.proxy.CubeTrialsCachingProxy;
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 API 응답 DTO 파싱 및
 * 리팩토링된 기대값 엔진(Proxy, Policy) 통합 시뮬레이션 테스트
 */
@Slf4j
public class EquipmentResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CubeTrialsProvider trialsProvider; // 프록시 계층 인터페이스
    private CubeCostPolicy costPolicy;         // 비용 정책 객체

    @BeforeEach
    void setUp() {
        // 1. 확률 데이터 리포지토리 초기화
        CubeProbabilityRepository repository = new CubeProbabilityRepository();
        repository.init();

        // 2. 무상태 계산 서비스 생성
        CubeServiceImpl serviceImpl = new CubeServiceImpl(new CubeRateCalculator(repository));

        // 3. Caffeine 캐시 프록시로 서비스 감싸기 (Spring @Primary 모사)
        this.trialsProvider = new CubeTrialsCachingProxy(serviceImpl);

        // 4. 비용 정책 객체 생성
        this.costPolicy = new CubeCostPolicy();
    }

    @Test
    @DisplayName("에반: 드래곤 장비(dragon_equipment) JSON 파싱 테스트")
    void evan_parsing_test() throws IOException {
        // given
        File jsonFile = new ClassPathResource("evan_equip.json").getFile();

        // when
        EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

        // then
        assertThat(response.getCharacterClass()).contains("에반");
        assertThat(response.getDragonEquipment()).isNotEmpty();

        log.info("에반 드래곤 장비 개수: {}", response.getDragonEquipment().size());
        log.debug("전체 데이터: {}", response);
    }

    @Test
    @DisplayName("에반: JSON 파싱 후 '내 템 얼마짜리?' 기대 시도 및 비용 계산 시뮬레이션")
    void evan_simulation_test() throws IOException {
        // given
        File jsonFile = new ClassPathResource("evan_equip.json").getFile();

        // when
        EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

        // then
        assertThat(response.getCharacterClass()).contains("에반");

        long totalInventoryCost = 0;

        log.info("=========== [에반] 장비 감정 시작 (Proxy + Policy 적용) ===========");

        for (EquipmentResponse.ItemEquipment item : response.getItemEquipment()) {
            // 잠재 옵션 등급이 없는 아이템은 계산 제외
            if (item.getPotentialOptionGrade() == null) continue;

            // 장비 레벨 파싱
            int level = Integer.parseInt(item.getBaseOption().getBaseEquipmentLevel());

            // 계산을 위한 공통 DTO로 변환
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

            // 1. 프록시 계층을 통한 기대 시도 횟수(Trials) 조회 (Caffeine 캐시 활용)
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
    }

    @Test
    @DisplayName("메카닉: 메카닉 장비(mechanic_equipment) JSON 파싱 테스트")
    void mechanic_parsing_test() throws IOException {
        // given
        File jsonFile = new ClassPathResource("mechanic_equip.json").getFile();

        // when
        EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

        // then
        assertThat(response.getCharacterClass()).contains("메카닉");
        assertThat(response.getMechanicEquipment()).isNotEmpty();

        log.info("메카닉 전용 장비 개수: {}", response.getMechanicEquipment().size());
        log.debug("전체 데이터: {}", response);
    }
}