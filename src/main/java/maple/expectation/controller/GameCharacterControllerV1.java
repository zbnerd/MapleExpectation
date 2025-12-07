package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 🏛️ [V1 Controller] Legacy & Stability
 * <p>
 * 초기 단계의 구현 모델로, 성능보다는 <b>데이터 정합성(Consistency)</b>과 <b>안정성</b>을 최우선으로 합니다.
 * 트래픽이 적은 환경에 적합하며, DB의 강력한 Lock 기능을 사용하여 동시성 문제를 해결합니다.
 * </p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/characters")
public class GameCharacterControllerV1 {

    private final GameCharacterService gameCharacterService;

    /**
     * 기본 캐릭터 정보 조회 API
     * <p>
     * 단순 DB 조회 로직을 수행합니다.
     * </p>
     * @param userIgn 캐릭터 닉네임 (In-Game Name)
     * @return 캐릭터 상세 정보
     */
    @GetMapping("/{userIgn}")
    public ResponseEntity<GameCharacter> findCharacterByUserIgn(@PathVariable String userIgn) {
        return ResponseEntity.ok(gameCharacterService.findCharacterByUserIgn(userIgn));
    }

    /**
     * 🔒 [Concurrency] 비관적 락(Pessimistic Lock) 기반 좋아요 요청
     * <p>
     * <b>전략(Strategy):</b> DB의 {@code SELECT ... FOR UPDATE}를 사용하여 레코드에 직접 Lock을 겁니다.<br>
     * <b>장점:</b> 충돌이 빈번한 환경에서도 데이터 정합성을 100% 보장합니다 (Race Condition 해결).<br>
     * <b>단점:</b> DB 커넥션을 점유하는 시간이 길어지며, 동시 요청이 몰릴 경우 처리량(Throughput)이 급격히 저하됩니다.
     * </p>
     *
     * @param userIgn 대상 캐릭터 닉네임
     * @return 성공 메시지
     */
    @PostMapping("/{userIgn}/like")
    public ResponseEntity<String> likeCharacterPessimistic(@PathVariable String userIgn) {
        gameCharacterService.clickLikeWithPessimisticLock(userIgn);
        return ResponseEntity.ok("ok");
    }
}