package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.CubeService;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * ⚡ [V2 Controller] Caching & Performance Optimization
 * <p>
 * V1의 성능 한계를 극복하기 위해 <b>In-Memory Caching (Caffeine)</b> 전략을 도입한 버전입니다.<br>
 * 외부 API 호출 비용을 절감하고, 쓰기 작업(좋아요)의 병목을 메모리 버퍼링으로 해결하여
 * <b>처리량(Throughput)</b>을 극대화하는 데 초점을 맞췄습니다.
 * </p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/characters")
public class GameCharacterControllerV2 {

    private final EquipmentService equipmentService;
    private final CubeService cubeService;
    private final GameCharacterService gameCharacterService;

    /**
     * 캐릭터 장비 조회 (with Local Cache)
     * <p>
     * 외부 API(MapleStory Open API)의 응답 속도 지연 문제를 해결하기 위해 캐싱을 적용했습니다.<br>
     * <b>전략:</b> TTL(Time-To-Live) 기반의 로컬 캐시를 사용하여 반복적인 요청에 대해 밀리초(ms) 단위 응답을 제공합니다.
     * </p>
     *
     * @param userIgn 캐릭터 닉네임
     * @return 장비 데이터 (Cache Hit 시 DB/API 조회 없이 즉시 반환)
     */
    @GetMapping("/{userIgn}/equipment")
    public ResponseEntity<EquipmentResponse> getCharacterEquipment(@PathVariable String userIgn) {
        return ResponseEntity.ok(equipmentService.getEquipmentByUserIgn(userIgn));
    }

    /**
     * 기대 비용 시뮬레이션 (Basic Iteration)
     * <p>
     * <b>구현 방식:</b> 조회된 장비 리스트를 순차적으로(Sequential) 순회하며 비용을 계산합니다.<br>
     * <b>한계점:</b> 장비 개수가 많거나 계산 로직이 복잡해질 경우, 전체 응답 시간이 길어지는 Blocking 이슈가 존재합니다.
     * (-> 이는 V3의 Streaming 방식에서 개선됨)
     * </p>
     */
    @GetMapping("/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> calculateTotalCost(@PathVariable String userIgn) {
        EquipmentResponse equipment = equipmentService.getEquipmentByUserIgn(userIgn);

        long totalCost = 0;
        List<TotalExpectationResponse.ItemExpectation> itemDetails = new ArrayList<>();

        if (equipment.getItemEquipment() != null) {
            for (EquipmentResponse.ItemEquipment item : equipment.getItemEquipment()) {
                if (item.getPotentialOptionGrade() == null) continue;

                // 각 아이템별 독립적인 큐브 비용 계산
                long cost = cubeService.calculateExpectedCost(item);

                if (cost > 0) {
                    totalCost += cost;
                    itemDetails.add(TotalExpectationResponse.ItemExpectation.builder()
                            .part(item.getItemEquipmentPart())
                            .itemName(item.getItemName())
                            .potential(formatPotential(item))
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