package maple.expectation.external.dto;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class EquipmentResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
