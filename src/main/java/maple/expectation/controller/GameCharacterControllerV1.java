package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/characters")
public class GameCharacterControllerV1 {

    private final GameCharacterService gameCharacterService;

    /**
     * 캐릭터 정보 조회
     */
    @GetMapping("/{userIgn}")
    public ResponseEntity<GameCharacter> findCharacterByUserIgn(@PathVariable String userIgn) {
        // 내부적으로 findByUserIgn (또는 없으면 API 생성) 로직 수행
        return ResponseEntity.ok(gameCharacterService.findCharacterByUserIgn(userIgn));
    }

    /**
     * 🔒 [V1] 비관적 락 기반 좋아요 (즉시 DB 반영)
     * 리팩토링 후: 서비스의 비관적 락 전용 진입점 호출
     */
    @PostMapping("/{userIgn}/like")
    public ResponseEntity<String> likeCharacterPessimistic(@PathVariable String userIgn) {
        // 별도로 분리된 비관적 락 전용 로직 호출 (안정성 우선)
        gameCharacterService.clickLikePessimistic(userIgn);
        return ResponseEntity.ok("ok");
    }
}