package maple.expectation.service.v2.impl;

import maple.expectation.aop.annotation.BufferedLike;
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
    @BufferedLike
    @Transactional
    public void processLike(String userIgn) {
        GameCharacter character = gameCharacterService.getCharacterForUpdate(userIgn);
        character.like();
    }
}