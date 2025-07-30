package maple.expectation.service;

import maple.expectation.domain.GameCharacter;
import maple.expectation.external.MaplestoryApiClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import maple.expectation.repository.GameCharacterRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class GameCharacterService {

    private final GameCharacterRepository gameCharacterRepository;
    private final MaplestoryApiClient maplestoryApiClient;

    @Transactional
    public String saveCharacter(GameCharacter character) {
        return gameCharacterRepository.save(character);
    }

    public GameCharacter findCharacterByUserIgn(String userIgn) {
        GameCharacter character = gameCharacterRepository.findByUserIgn(userIgn);

        if (character == null) {
            GameCharacter newCharacter = new GameCharacter();
            newCharacter.setOcid(maplestoryApiClient.getOcidByCharacterName(userIgn).getOcid());
            newCharacter.setUserIgn(userIgn);
            gameCharacterRepository.save(newCharacter);

            return newCharacter;
        }

        return gameCharacterRepository.findByUserIgn(userIgn);
    }
}
