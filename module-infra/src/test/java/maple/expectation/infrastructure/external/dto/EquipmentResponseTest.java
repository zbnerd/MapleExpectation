import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.policy.CubeCostPolicy;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@DisplayName("장비 응답 DTO 및 비용 계산 테스트")
class EquipmentResponseTest extends IntegrationTestSupport {

  @Autowired private ObjectMapper objectMapper;
  @Autowired private CubeTrialsProvider trialsProvider;
  @Autowired private CubeCostPolicy costPolicy;

  @Test
  @DisplayName("에반: JSON 파싱 후 기대 시도 및 비용 계산 시뮬레이션 (캐시 적용)")
  void evan_simulation_test() throws IOException {
    File jsonFile = new ClassPathResource("evan_equip.json").getFile();
    EquipmentResponse response = objectMapper.readValue(jsonFile, EquipmentResponse.class);

    assertThat(response.getCharacterClass()).contains("에반");

    long totalInventoryCost = 0;
    log.info("=========== [에반] 장비 감정 시작 (Context Sharing 적용) ===========");

    for (EquipmentResponse.ItemEquipment item : response.getItemEquipment()) {
      if (item.getPotentialOptionGrade() == null) continue;

      CubeCalculationInput input =
          CubeCalculationInput.builder()
              .itemName(item.getItemName())
              .part(item.getItemEquipmentSlot())
              .level(Integer.parseInt(item.getBaseOption().getBaseEquipmentLevel()))
              .grade(item.getPotentialOptionGrade())
              .options(
                  Arrays.asList(
                      item.getPotentialOption1(),
                      item.getPotentialOption2(),
                      item.getPotentialOption3()))
              .build();

      long trials = trialsProvider.calculateExpectedTrials(input, CubeType.BLACK).longValue();
      long costPerTrial =
          costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade());
      totalInventoryCost += (trials * costPerTrial);
    }

    log.info("💰 에반 전체 장비셋팅 총 기대 비용: 약 {}억 메소", totalInventoryCost / 100_000_000);
    assertThat(totalInventoryCost).isGreaterThan(0);
  }
}
