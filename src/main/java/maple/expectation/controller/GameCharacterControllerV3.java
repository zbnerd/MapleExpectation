package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.EquipmentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

/**
 * 🚀 [V3 Controller] Extreme Optimization & Resource Efficiency
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v3/characters")
public class GameCharacterControllerV3 {

    private final EquipmentService equipmentService;

    /**
     * 🌊 장비 데이터 스트리밍 조회 (Streaming + GZIP)
     * Heap Memory 사용량을 O(1)로 유지하며 데이터를 압축 전송합니다.
     */
    @GetMapping("/{userIgn}/equipment")
    public ResponseEntity<StreamingResponseBody> getEquipmentStream(@PathVariable String userIgn) {
        StreamingResponseBody responseBody = outputStream -> {
            // Try-with-resources: GZIP -> Buffer -> Output 순서로 스트림 체이닝
            try (GZIPOutputStream gzipos = new GZIPOutputStream(outputStream);
                 OutputStream bufferedOs = new BufferedOutputStream(gzipos)) {

                // Service에게 "이 스트림에다가 데이터 써줘"라고 위임
                equipmentService.streamEquipmentData(userIgn, bufferedOs);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody);
    }

    /**
     * 🚀 장비 기대값 조회 (비동기 - Issue #118 준수)
     *
     * <p>Spring MVC의 CompletableFuture 반환 지원을 활용하여
     * 톰캣 스레드를 즉시 반환하고, Future 완료 시 응답을 전송합니다.</p>
     *
     * <h4>비동기 흐름</h4>
     * <ol>
     *   <li>톰캣 스레드: 요청 수신 → CompletableFuture 반환 → 즉시 풀 반환</li>
     *   <li>expectation-* 스레드: 실제 계산 수행</li>
     *   <li>Future 완료 시: Spring이 자동으로 응답 전송</li>
     * </ol>
     */
    @GetMapping("/{userIgn}/expectation")
    public CompletableFuture<ResponseEntity<TotalExpectationResponse>> getEquipmentExpectation(
            @PathVariable String userIgn) {

        return equipmentService.calculateTotalExpectationAsync(userIgn)
                .thenApply(ResponseEntity::ok);
    }
}