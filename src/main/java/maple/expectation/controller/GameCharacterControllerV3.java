package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.CubeService;
import maple.expectation.service.v2.EquipmentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * 🚀 [V3 Controller] Extreme Optimization & Resource Efficiency
 * <p>
 * 대규모 트래픽과 대용량 데이터 전송 시 발생하는 <b>OOM(Out Of Memory)</b> 및 <b>Network 병목</b>을 해결하기 위한 최종 최적화 버전입니다.<br>
 * 데이터를 메모리에 적재하지 않고 클라이언트에게 실시간으로 흘려보내는 <b>Streaming</b> 기술과,
 * 전송 크기를 최소화하는 <b>Compression(GZIP)</b> 기술이 적용되었습니다.
 * </p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v3/characters")
public class GameCharacterControllerV3 {

    private final EquipmentService equipmentService;
    private final CubeService cubeService;

    /**
     * 🌊 장비 데이터 스트리밍 조회 (Streaming + GZIP)
     * <p>
     * <b>문제 해결(Problem Solving):</b><br>
     * 기존(V1, V2) 방식은 모든 장비 데이터를 List에 담아 리턴하므로, 동시 요청이 몰릴 때 Heap Memory가 급증하여 GC 부하 및 OOM이 발생합니다.<br>
     * <br>
     * <b>전략(Strategy):</b><br>
     * 1. <b>StreamingResponseBody:</b> Spring MVC의 비동기 처리를 이용해 OutputStream에 직접 데이터를 씁니다.<br>
     * 2. <b>Chunked Transfer Encoding:</b> 전체 데이터 크기를 몰라도 전송을 시작하여 <b>TTFB(Time To First Byte)</b>를 획기적으로 단축합니다.<br>
     * 3. <b>GZIP Compression:</b> JSON 텍스트 데이터를 실시간으로 압축하여 네트워크 트래픽(Egress 비용)을 약 1/10로 절감합니다.<br>
     * <br>
     * <b>결과:</b> 데이터 크기와 상관없이 메모리 사용량을 <b>O(1)</b>에 가깝게 유지합니다.
     * </p>
     *
     * @param userIgn 캐릭터 닉네임
     * @return 압축된 스트리밍 응답 (Header: Content-Encoding: gzip)
     */
    @GetMapping("/{userIgn}/equipment")
    public ResponseEntity<StreamingResponseBody> getEquipmentStream(@PathVariable String userIgn) {
        StreamingResponseBody responseBody = outputStream -> {
            // GZIP 스트림과 버퍼링을 결합하여 I/O 효율 극대화
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream);
                 OutputStream bufferedOutput = new BufferedOutputStream(gzipOutputStream)) {

                // Service 내부에서 DB Cursor -> JSON 변환 -> Stream Write가 파이프라인처럼 연결됨
                equipmentService.streamEquipmentData(userIgn, bufferedOutput);

            } catch (Exception e) {
                throw new RuntimeException("Streaming Error during data transmission", e);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody);
    }

    /**
     * 기대 비용 계산 (Refactored Structure)
     * <p>
     * <b>개선점:</b> 복잡한 엔티티 의존성을 제거하고 계산에 필요한 최소한의 데이터만 {@link CubeCalculationInput} DTO로 추출하여 처리합니다.<br>
     * V3의 스트리밍 아키텍처와 결합 시, 대량의 시뮬레이션 요청도 안정적으로 처리할 수 있는 구조적 기반을 마련했습니다.
     * </p>
     */
    @GetMapping("/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> getEquipmentExpectation(@PathVariable String userIgn) throws IOException {
        List<CubeCalculationInput> inputs = equipmentService.getCubeCalculationInputs(userIgn);

        // ... (기존 V3 로직 그대로 이동) ...

        for (CubeCalculationInput input : inputs) {
            long expectedCost = cubeService.calculateExpectedCost(input);
            input.setExpectedCost(expectedCost);
        }

        long totalExpectedCost = inputs.stream().mapToLong(CubeCalculationInput::getExpectedCost).sum();

        List<TotalExpectationResponse.ItemExpectation> itemDetails = new ArrayList<>();
        for (CubeCalculationInput input : inputs) {
            itemDetails.add(TotalExpectationResponse.ItemExpectation.builder()
                    .part(input.getPart())
                    .itemName(input.getItemName())
                    .potential(formatPotential(input))
                    .expectedCost(input.getExpectedCost())
                    .expectedCostText(String.format("%,d 메소", input.getExpectedCost()))
                    .build());
        }

        return ResponseEntity.ok(TotalExpectationResponse.builder()
                .userIgn(userIgn)
                .totalCost(totalExpectedCost)
                .totalCostText(String.format("%,d 메소", totalExpectedCost))
                .items(itemDetails)
                .build());
    }

    private String formatPotential(CubeCalculationInput input) {
        return input.getOptions().stream().map(String::valueOf).collect(Collectors.joining(" | "));
    }
}