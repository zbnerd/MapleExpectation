package maple.expectation.service;

import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.external.dto.v1.EquipmentResponse;
import maple.expectation.util.ChecksumUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EquipmentServiceTest {

    @Autowired
    MaplestoryApiClient maplestoryApiClient;

    @Autowired
    EquipmentService equipmentService;

    @Test
    void saveEquipment() throws Exception {
        String ocid = "5a4b3a7b4fef6995d67a845e27a35d55";
//        System.out.println(maplestoryApiClient.getItemDataByOcid(ocid));
        EquipmentResponse response = maplestoryApiClient.getItemDataByOcid(ocid);
        System.out.println(ChecksumUtil.getEquipmentResponseChecksum(response));
//        System.out.println(response.getCharacterClass());
//        System.out.println(response.getCharacterGender());
//        System.out.println(response.getPresetNo());
//        response.getItemEquipment().forEach(System.out::println);
    }

    @Test
    void findById() {
    }
}