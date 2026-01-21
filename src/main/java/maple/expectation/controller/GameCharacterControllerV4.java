package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * V4 캐릭터 컨트롤러 (#240)
 *
 * <h3>5-Agent Council 합의사항</h3>
 * <ul>
 *   <li>🔴 Red (SRE): 비동기 처리 (CompletableFuture)</li>
 *   <li>🟣 Purple (Auditor): BigDecimal 기반 응답</li>
 *   <li>🟢 Green (Performance): InnoDB Buffer Pool 오염 방지</li>
 * </ul>
 *
 * <h3>V3와의 차이</h3>
 * <ul>
 *   <li>비용 상세 분류: 블랙큐브, 레드큐브, 에디셔널, 스타포스</li>
 *   <li>프리셋별 기대값: 프리셋 1, 2, 3 개별 조회 가능</li>
 *   <li>정밀 계산: BigDecimal 기반 (long truncation 방지)</li>
 *   <li>캐시 전략: DB 저장으로 Buffer Pool 오염 방지</li>
 * </ul>
 *
 * <h3>API Endpoints</h3>
 * <ul>
 *   <li>GET /api/v4/characters/{userIgn}/expectation - 전체 기대값 조회</li>
 *   <li>GET /api/v4/characters/{userIgn}/expectation/{presetNo} - 특정 프리셋 조회</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v4/characters")
public class GameCharacterControllerV4 {

    private final EquipmentExpectationServiceV4 expectationService;

    /**
     * 전체 기대값 조회 (비동기)
     *
     * <h4>쿼리 파라미터</h4>
     * <ul>
     *   <li>force=true: 캐시 무시하고 강제 재계산 (아이템 상세 포함)</li>
     *   <li>force=false (기본): 캐시 응답 사용 (요약만, 아이템 상세 없음)</li>
     * </ul>
     *
     * <h4>응답 예시</h4>
     * <pre>
     * {
     *   "userIgn": "홍길동",
     *   "calculatedAt": "2026-01-21T10:30:00",
     *   "fromCache": false,
     *   "totalExpectedCost": 1500000000,
     *   "totalCostBreakdown": {
     *     "blackCubeCost": 500000000,
     *     "redCubeCost": 0,
     *     "additionalCubeCost": 300000000,
     *     "starforceCost": 700000000
     *   },
     *   "presets": [...]
     * }
     * </pre>
     *
     * @param userIgn 캐릭터 IGN
     * @param force 강제 재계산 여부 (기본값: false)
     * @return V4 기대값 응답
     */
    @GetMapping("/{userIgn}/expectation")
    public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> getExpectation(
            @PathVariable String userIgn,
            @RequestParam(defaultValue = "false") boolean force) {

        log.info("[V4] Calculating expectation for: {} (force={})", maskIgn(userIgn), force);

        return expectationService.calculateExpectationAsync(userIgn, force)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 특정 프리셋 기대값 조회 (비동기)
     *
     * @param userIgn 캐릭터 IGN
     * @param presetNo 프리셋 번호 (1, 2, 3)
     * @return V4 기대값 응답 (해당 프리셋만)
     */
    @GetMapping("/{userIgn}/expectation/preset/{presetNo}")
    public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> getExpectationByPreset(
            @PathVariable String userIgn,
            @PathVariable Integer presetNo) {

        log.info("[V4] Calculating expectation for {} preset {}", maskIgn(userIgn), presetNo);

        // 현재는 전체 계산 후 프리셋 필터링 (추후 최적화 가능)
        return expectationService.calculateExpectationAsync(userIgn)
                .thenApply(response -> filterByPreset(response, presetNo))
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 기대값 재계산 (캐시 무효화)
     *
     * @param userIgn 캐릭터 IGN
     * @return V4 기대값 응답 (새로 계산된 결과)
     */
    @PostMapping("/{userIgn}/expectation/recalculate")
    public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> recalculateExpectation(
            @PathVariable String userIgn) {

        log.info("[V4] Force recalculating expectation for: {}", maskIgn(userIgn));

        // TODO: 캐시 무효화 후 재계산 구현
        return expectationService.calculateExpectationAsync(userIgn)
                .thenApply(ResponseEntity::ok);
    }

    // ==================== 유틸리티 ====================

    private EquipmentExpectationResponseV4 filterByPreset(
            EquipmentExpectationResponseV4 response, Integer presetNo) {
        var filteredPresets = response.getPresets().stream()
                .filter(p -> p.getPresetNo() == presetNo)
                .toList();

        return EquipmentExpectationResponseV4.builder()
                .userIgn(response.getUserIgn())
                .calculatedAt(response.getCalculatedAt())
                .fromCache(response.isFromCache())
                .totalExpectedCost(filteredPresets.isEmpty() ?
                        java.math.BigDecimal.ZERO :
                        filteredPresets.get(0).getTotalExpectedCost())
                .totalCostBreakdown(filteredPresets.isEmpty() ?
                        EquipmentExpectationResponseV4.CostBreakdownDto.empty() :
                        filteredPresets.get(0).getCostBreakdown())
                .presets(filteredPresets)
                .build();
    }

    private String maskIgn(String ign) {
        if (ign == null || ign.length() < 2) return "***";
        return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
    }
}
