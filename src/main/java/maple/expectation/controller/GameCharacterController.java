package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GameCharacterController {

    private final GameCharacterService gameCharacterService;

    @GetMapping("/api/v1/characters/{userIgn}")
    public ResponseEntity<GameCharacter> findCharacterByUserIgn(@PathVariable String userIgn) {
        GameCharacter character = gameCharacterService.findCharacterByUserIgn(userIgn);

        return ResponseEntity.ok(character); // 200 + JSON
    }


}
