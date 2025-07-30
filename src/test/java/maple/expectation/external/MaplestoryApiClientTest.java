package maple.expectation.external;

import maple.expectation.external.dto.CharacterOcidResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MaplestoryApiClientTest {

    @Autowired MaplestoryApiClient maplestoryApiClient;

    @Test
    void getOcidByCharacterName() {
        CharacterOcidResponse response = maplestoryApiClient.getOcidByCharacterName("긱델");
        Assertions.assertThat(response.getOcid()).isEqualTo("5a4b3a7b4fef6995d67a845e27a35d55");
    }
}