package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import maple.expectation.service.v4.warmup.PopularCharacterTracker;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private final PopularCharacterTracker popularCharacterTracker;

    /**
     * 전체 기대값 조회 (GZIP 응답 지원) (#262 성능 최적화, #264 Fast Path)
     *
     * <h4>#264 Fast Path 최적화</h4>
     * <ul>
     *   <li>L1 캐시 히트 → 스레드풀 우회, 동기 반환</li>
     *   <li>L1 캐시 미스 → 기존 비동기 경로 사용</li>
     *   <li>force=true → Fast Path 스킵, 강제 재계산</li>
     * </ul>
     *
     * <h4>응답 형식</h4>
     * <ul>
     *   <li>Accept-Encoding: gzip → GZIP 바이트 직접 반환 (서버 CPU 절감)</li>
     *   <li>그 외 → JSON 응답 (기존 방식)</li>
     * </ul>
     *
     * <h4>쿼리 파라미터</h4>
     * <ul>
     *   <li>force=true: 캐시 무시하고 강제 재계산</li>
     *   <li>force=false (기본): 캐시 응답 사용</li>
     * </ul>
     *
     * <h4>성능 이점 (#264)</h4>
     * <ul>
     *   <li>L1 히트: 0.1ms (스레드풀 경합 없음, RPS 3-5x 향상)</li>
     *   <li>네트워크: 200KB → 15KB (93% 감소)</li>
     * </ul>
     *
     * @param userIgn 캐릭터 IGN
     * @param force 강제 재계산 여부 (기본값: false)
     * @param acceptEncoding Accept-Encoding 헤더
     * @return V4 기대값 응답 (GZIP 또는 JSON)
     */
    @GetMapping("/{userIgn}/expectation")
    public CompletableFuture<ResponseEntity<?>> getExpectation(
            @PathVariable String userIgn,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {

        log.debug("[V4] Calculating expectation for: {} (force={}, gzip={})",
                maskIgn(userIgn), force, acceptsGzip(acceptEncoding));

        // #275 Auto Warmup: 호출 횟수 기록 (Fire-and-Forget)
        popularCharacterTracker.recordAccess(userIgn);

        // #264 Fast Path: GZIP 요청 + force=false + L1 캐시 히트 시 스레드풀 우회
        if (acceptsGzip(acceptEncoding) && !force) {
            var fastPathResult = expectationService.getGzipFromL1CacheDirect(userIgn);
            if (fastPathResult.isPresent()) {
                log.debug("[V4] L1 Fast Path HIT: {}", maskIgn(userIgn));
                return CompletableFuture.completedFuture(buildGzipResponse(fastPathResult.get()));
            }
            // L1 미스 → 기존 비동기 경로로 Fallback
            log.debug("[V4] L1 Fast Path MISS, falling back to async: {}", maskIgn(userIgn));
        }

        // Accept-Encoding: gzip 지원 시 GZIP 바이트 직접 반환
        if (acceptsGzip(acceptEncoding)) {
            return expectationService.getGzipExpectationAsync(userIgn, force)
                    .thenApply(this::buildGzipResponse);
        }

        // 기존 방식: JSON 응답
        return expectationService.calculateExpectationAsync(userIgn, force)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * GZIP 바이트를 ResponseEntity로 변환
     */
    private ResponseEntity<byte[]> buildGzipResponse(byte[] gzipBytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(gzipBytes.length)
                .body(gzipBytes);
    }

    /**
     * Accept-Encoding 헤더에서 gzip 지원 여부 확인
     */
    private boolean acceptsGzip(String acceptEncoding) {
        return acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");
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
