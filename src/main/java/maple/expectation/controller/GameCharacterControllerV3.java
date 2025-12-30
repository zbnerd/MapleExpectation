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

    @GetMapping("/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> getEquipmentExpectation(@PathVariable String userIgn) {

        TotalExpectationResponse response = equipmentService.calculateTotalExpectation(userIgn);
        return ResponseEntity.ok(response);
    }
}