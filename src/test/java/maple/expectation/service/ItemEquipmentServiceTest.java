package maple.expectation.service;

import maple.expectation.domain.GameCharacter;
import maple.expectation.domain.ItemEquipment;
import maple.expectation.domain.dto.ItemEquipmentDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ItemEquipmentServiceTest {

    @Autowired private ItemEquipmentService itemEquipmentService;
    @Autowired private GameCharacterService gameCharacterService;
    
    @Test
    void saveItem() {

        GameCharacter gameCharacter = gameCharacterService.findCharacterByUserIgn("긱델");

    }
}