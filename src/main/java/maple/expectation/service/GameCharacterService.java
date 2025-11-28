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

    // ❌ 1. [방어 없음] 일반적인 조회 -> 수정
    // 동시에 100명이 들어오면 서로 덮어써서 숫자가 씹힘 (Race Condition)
    @Transactional
    public void clickLikeWithOutLock(String userIgn) {
        GameCharacter character = gameCharacterRepository.findByUserIgn(userIgn);
        character.like();
    }

    // ✅ 2. [비관적 락] 조회 시점부터 잠금
    // 동시에 100명이 들어와도 줄을 서서(Sequential) 처리됨 -> 데이터 정합성 보장
    @Transactional
    public void clickLikeWithLock(String userIgn) {
        GameCharacter character = gameCharacterRepository.findByUserIgnWithPessimisticLock(userIgn);
        character.like();
    }

}
