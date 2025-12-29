package maple.expectation.service.v2.facade;

import lombok.RequiredArgsConstructor;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GameCharacterSynchronizer {

    private final GameCharacterService gameCharacterService;

    @Locked(key = "#userIgn")
    public GameCharacter synchronizeCharacter(String userIgn) {
        // 1. [Double-Check] 락을 획득한 직후, 그 사이 다른 쓰레드가 생성했는지 확인
        return gameCharacterService.getCharacterIfExist(userIgn)
                .orElseGet(() -> {
                    try {
                        // 2. [Create] 진짜 없으면 생성
                        return gameCharacterService.createNewCharacter(userIgn);
                    } catch (DataIntegrityViolationException e) {
                        // 3. [Last Defense] 락 타임아웃 등으로 인해 동시에 진입했을 경우,
                        // DB Unique 제약 조건이 튕겨낸 에러를 잡아서 이미 생성된 데이터를 반환
                        return gameCharacterService.getCharacterIfExist(userIgn)
                                .orElseThrow(() -> new CharacterNotFoundException("캐릭터 존재안함" + e));
                    }
                });
    }
}