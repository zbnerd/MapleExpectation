package maple.expectation.service;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v1.ItemEquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ItemEquipmentServiceTest {

    @Autowired private ItemEquipmentService itemEquipmentService;
    @Autowired private GameCharacterService gameCharacterService;
    
    @Test
    void saveItem() {

        GameCharacter gameCharacter = gameCharacterService.findCharacterByUserIgn("긱델");

    }
}