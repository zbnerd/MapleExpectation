package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 캐릭터 API V1 (레거시)
 *
 * <p>Note: 좋아요 API는 V2로 이관됨 (인증 필요, Self-Like/중복 방지)</p>
 *
 * @see maple.expectation.controller.GameCharacterControllerV2
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/characters")
public class GameCharacterControllerV1 {

    private final GameCharacterFacade gameCharacterFacade;

    /**
     * 캐릭터 정보 조회
     */
    @GetMapping("/{userIgn}")
    public ResponseEntity<GameCharacter> findCharacterByUserIgn(@PathVariable String userIgn) {
        return ResponseEntity.ok(gameCharacterFacade.findCharacterByUserIgn(userIgn));
    }
}