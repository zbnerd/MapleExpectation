package maple.expectation.service.v2.facade;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GameCharacterFacade {

    private final GameCharacterService gameCharacterService;
    private final GameCharacterSynchronizer gameCharacterSynchronizer;

    public GameCharacter findCharacterByUserIgn(String userIgn) {
        return gameCharacterService.getCharacterIfExist(userIgn)
                .orElseGet(() -> gameCharacterSynchronizer.synchronizeCharacter(userIgn));
    }
}