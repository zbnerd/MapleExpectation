package maple.expectation.controller;

import lombok.RequiredArgsConstructor;

import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.CubeService;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.util.StatParser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@TraceLog
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/characters")
public class GameCharacterControllerV2 {

    private final EquipmentService equipmentService;
    private final CubeService cubeService;
    private final GameCharacterService gameCharacterService;

    // ... (getCharacterEquipment 메서드는 동일) ...

    /**
     * 기대 비용 시뮬레이션 (Basic Iteration)
     */
    @GetMapping("/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> calculateTotalCost(@PathVariable String userIgn) {
        // 1. 데이터 조회 (Provider -> ObjectMapper 파싱)
        EquipmentResponse equipment = equipmentService.getEquipmentByUserIgn(userIgn);

        long totalCost = 0;
        List<TotalExpectationResponse.ItemExpectation> itemDetails = new ArrayList<>();

        if (equipment.getItemEquipment() != null) {
            for (EquipmentResponse.ItemEquipment item : equipment.getItemEquipment()) {
                if (item.getPotentialOptionGrade() == null) continue;

                // 2. [수정됨] 옵션 3줄을 리스트로 변환
                List<String> optionList = new ArrayList<>();
                if (item.getPotentialOption1() != null) optionList.add(item.getPotentialOption1());
                if (item.getPotentialOption2() != null) optionList.add(item.getPotentialOption2());
                if (item.getPotentialOption3() != null) optionList.add(item.getPotentialOption3());

                // 3. 레벨 파싱 (ItemEquipment 구조에 따라 다를 수 있음)
                // baseOption 안에 있을 수도 있고, itemLevel이 따로 있을 수도 있음.
                // 로그상 이미 레벨은 잘 들어가고 있으니 기존 코드 유지하되, 예시는 StatParser 사용
                int level = 0;
                if (item.getBaseOption() != null) {
                    level = StatParser.parseNum(item.getBaseOption().getBaseEquipmentLevel());
                }

                // 4. DTO 생성 (options에 리스트 주입!)
                CubeCalculationInput inputDto = CubeCalculationInput.builder()
                        .itemName(item.getItemName())
                        .level(level)
                        .part(item.getItemEquipmentSlot())
                        .grade(item.getPotentialOptionGrade())
                        .options(optionList) // ★★★ 여기가 핵심입니다! ★★★
                        .build();

                long cost = cubeService.calculateExpectedCost(inputDto);

                if (cost > 0) {
                    totalCost += cost;
                    itemDetails.add(TotalExpectationResponse.ItemExpectation.builder()
                            .part(item.getItemEquipmentSlot())
                            .itemName(item.getItemName())
                            .potential(String.join(" | ", optionList))
                            .expectedCost(cost)
                            .expectedCostText(String.format("%,d 메소", cost))
                            .build());
                }
            }
        }

        return ResponseEntity.ok(TotalExpectationResponse.builder()
                .userIgn(userIgn)
                .totalCost(totalCost)
                .totalCostText(String.format("%,d 메소", totalCost))
                .items(itemDetails)
                .build());
    }

    /**
     * 🚀 [Concurrency] Caffeine Cache 기반 좋아요 (Write-Behind)
     * <p>
     * <b>전략(Strategy):</b> Write-Behind (Write-Back) 패턴을 사용하여 요청을 메모리(AtomicLong)에 먼저 반영하고,<br>
     * 스케줄러를 통해 주기적으로 DB에 Bulk Update를 수행합니다.<br>
     * <br>
     * <b>장점:</b> DB Lock 대기 시간이 0에 수렴하여, 압도적인 처리량(High Throughput)을 보장합니다.<br>
     * <b>단점(Trade-off):</b> 서버 다운 시 메모리에 버퍼링된 좋아요 데이터가 유실될 수 있는 <b>결과적 일관성(Eventual Consistency)</b> 모델입니다.
     * </p>
     *
     * @param userIgn 캐릭터 닉네임
     * @return 성공 메시지
     */
    @PostMapping("/{userIgn}/like")
    public ResponseEntity<String> likeCharacterCaffeine(@PathVariable String userIgn) {
        gameCharacterService.clickLikeWithCache(userIgn);
        return ResponseEntity.ok("ok");
    }

    private String formatPotential(EquipmentResponse.ItemEquipment item) {
        return String.format("%s | %s | %s", item.getPotentialOption1(), item.getPotentialOption2(), item.getPotentialOption3());
    }
}