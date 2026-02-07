package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.response.CharacterResponse;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 캐릭터 API V1 (레거시)
 *
 * <p>Note: 좋아요 API는 V2로 이관됨 (인증 필요, Self-Like/중복 방지)
 *
 * <p>Issue #128: Entity 직접 노출 방지, DTO로 응답 크기 최적화
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
   *
   * <p>Issue #128: Entity → DTO 변환으로 응답 크기 최적화 (350KB → 4KB)
   */
  @GetMapping("/{userIgn}")
  public ResponseEntity<CharacterResponse> findCharacterByUserIgn(@PathVariable String userIgn) {
    GameCharacter character = gameCharacterFacade.findCharacterByUserIgn(userIgn);
    return ResponseEntity.ok(CharacterResponse.from(character));
  }
}
