package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@TraceLog
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/characters")
public class GameCharacterControllerV2 {

    private final EquipmentService equipmentService;
    private final GameCharacterService gameCharacterService;

    /**
     * 캐릭터 장비 조회 (with Local Cache)
     */
    @GetMapping("/{userIgn}/equipment")
    public ResponseEntity<EquipmentResponse> getCharacterEquipment(@PathVariable String userIgn) {
        return ResponseEntity.ok(equipmentService.getEquipmentByUserIgn(userIgn));
    }

    /**
     * 기대 비용 시뮬레이션 (Basic Iteration)
     * ✅ 리팩토링 후: 복잡한 로직이 모두 사라지고 Service 호출 한 줄만 남음
     */
    @GetMapping("/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> calculateTotalCost(@PathVariable String userIgn) {
        TotalExpectationResponse response = equipmentService.calculateTotalExpectationLegacy(userIgn);
        return ResponseEntity.ok(response);
    }

    /**
     * 좋아요 기능
     */
    @PostMapping("/{userIgn}/like")
    public ResponseEntity<String> likeCharacterCaffeine(@PathVariable String userIgn) {
        gameCharacterService.clickLikeWithCache(userIgn);
        return ResponseEntity.ok("ok");
    }
}