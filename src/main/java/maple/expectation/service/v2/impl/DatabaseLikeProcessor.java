package maple.expectation.service.v2.impl;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.LikeProcessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseLikeProcessor implements LikeProcessor {

    private final GameCharacterService gameCharacterService;

    // 순환 참조 방지를 위해 @Lazy 주입
    public DatabaseLikeProcessor(@Lazy GameCharacterService gameCharacterService) {
        this.gameCharacterService = gameCharacterService;
    }

    @Override
    @Transactional
    public void processLike(String userIgn) {
        // 서비스의 중앙 메서드를 통해 락이 걸린 엔티티 획득
        GameCharacter character = gameCharacterService.getCharacterForUpdate(userIgn);
        character.like();
    }
}