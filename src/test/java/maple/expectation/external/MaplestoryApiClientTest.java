package maple.expectation.external;

import maple.expectation.external.dto.CharacterOcidResponse;
import maple.expectation.external.dto.EquipmentResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MaplestoryApiClientTest {

    @Autowired MaplestoryApiClient maplestoryApiClient;

    @Test
    void getOcidByCharacterName() {
        CharacterOcidResponse response = maplestoryApiClient.getOcidByCharacterName("긱델");
        Assertions.assertThat(response.getOcid()).isEqualTo("5a4b3a7b4fef6995d67a845e27a35d55");
    }

    @Test
    void getItemDataByOcid() {
        String ocid = "5a4b3a7b4fef6995d67a845e27a35d55";
//        System.out.println(maplestoryApiClient.getItemDataByOcid(ocid));
        EquipmentResponse response = maplestoryApiClient.getItemDataByOcid(ocid);
        System.out.println(response.getCharacterClass());
        System.out.println(response.getCharacterGender());
        System.out.println(response.getPresetNo());
        response.getItemEquipment().forEach(System.out::println);
    }
}