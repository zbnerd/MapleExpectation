package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/characters")
public class GameCharacterControllerV2 {

    private final EquipmentService equipmentService;
    private final GameCharacterService gameCharacterService;

    /**
     * 캐릭터 장비 조회
     */
    @GetMapping("/{userIgn}/equipment")
    public ResponseEntity<EquipmentResponse> getCharacterEquipment(@PathVariable String userIgn) {
        return ResponseEntity.ok(equipmentService.getEquipmentByUserIgn(userIgn));
    }

    /**
     * 기대 비용 시뮬레이션
     * 리팩토링 성과: 내부에서 Decorator/Policy 패턴이 작동하지만 컨트롤러 코드는 매우 단순함
     */
    @GetMapping("/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> calculateTotalCost(@PathVariable String userIgn) {
        return ResponseEntity.ok(equipmentService.calculateTotalExpectationLegacy(userIgn));
    }

    /**
     * 🚀 [V2] 프록시 기반 좋아요 (Caffeine 버퍼링)
     * 리팩토링 후: @Primary 프록시가 주입된 서비스 메서드 호출
     */
    @PostMapping("/{userIgn}/like")
    public ResponseEntity<String> likeCharacterCaffeine(@PathVariable String userIgn) {
        // 인터페이스 기반 호출로 실제로는 BufferedLikeProxy가 동작
        gameCharacterService.clickLikeCache(userIgn);
        return ResponseEntity.ok("ok");
    }
}