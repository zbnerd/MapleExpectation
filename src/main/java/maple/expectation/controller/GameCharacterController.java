package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.service.v2.CubeService;
import maple.expectation.service.v2.EquipmentService;
import maple.expectation.service.v2.GameCharacterService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

@Slf4j
@RestController
@RequiredArgsConstructor
public class GameCharacterController {

    private final GameCharacterService gameCharacterService;
    private final EquipmentService equipmentService;
    private final CubeService cubeService;

    @GetMapping("/api/v1/characters/{userIgn}")
    public ResponseEntity<GameCharacter> findCharacterByUserIgn(@PathVariable String userIgn) {
        GameCharacter character = gameCharacterService.findCharacterByUserIgn(userIgn);

        return ResponseEntity.ok(character); // 200 + JSON
    }

    @GetMapping("/api/v2/characters/{userIgn}/equipment")
    public ResponseEntity<EquipmentResponse> getCharacterEquipment(@PathVariable String userIgn) {
        // 서비스가 알아서 캐싱/API호출 판단 후 리턴함
        EquipmentResponse response = equipmentService.getEquipmentByUserIgn(userIgn);
        return ResponseEntity.ok(response);
    }

    /**
     * 🏆 캐릭터 장비 총 기대 비용 시뮬레이션 API
     * GET /api/v2/characters/{userIgn}/expectation
     */
    @GetMapping("/api/v2/characters/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> calculateTotalCost(@PathVariable String userIgn) {

        // 1. 장비 데이터 가져오기 (15분 캐싱 자동 적용됨)
        EquipmentResponse equipment = equipmentService.getEquipmentByUserIgn(userIgn);

        long totalCost = 0;
        List<TotalExpectationResponse.ItemExpectation> itemDetails = new ArrayList<>();

        // 2. 각 아이템별 비용 계산 (CubeService 활용)
        // null 체크: 장비가 하나도 없는 경우 대비
        if (equipment.getItemEquipment() != null) {
            for (EquipmentResponse.ItemEquipment item : equipment.getItemEquipment()) {
                // 잠재능력이 없는 아이템은 계산 스킵
                if (item.getPotentialOptionGrade() == null) continue;

                // 🎲 핵심: 큐브 기대 비용 계산
                long cost = cubeService.calculateExpectedCost(item);

                if (cost > 0) {
                    totalCost += cost;

                    // 상세 영수증 추가
                    itemDetails.add(TotalExpectationResponse.ItemExpectation.builder()
                            .part(item.getItemEquipmentPart())
                            .itemName(item.getItemName())
                            .potential(formatPotential(item)) // 예쁘게 포맷팅
                            .expectedCost(cost)
                            .expectedCostText(String.format("%,d 메소", cost))
                            .build());
                }
            }
        }

        // 3. 최종 결과 반환
        return ResponseEntity.ok(TotalExpectationResponse.builder()
                .userIgn(userIgn)
                .totalCost(totalCost)
                .totalCostText(String.format("%,d 메소", totalCost))
                .items(itemDetails)
                .build());
    }

    // 헬퍼 메서드: 잠재능력 3줄을 한 줄로 합치기
    private String formatPotential(EquipmentResponse.ItemEquipment item) {
        return String.format("%s | %s | %s",
                item.getPotentialOption1(),
                item.getPotentialOption2(),
                item.getPotentialOption3());
    }

    @GetMapping("/api/v3/characters/{userIgn}/equipment")
    public ResponseEntity<StreamingResponseBody> getEquipmentStream(@PathVariable String userIgn) {
        StreamingResponseBody responseBody = outputStream -> {
            // 1. HTTP 응답 스트림에 바로 GZIP 압축 레이어를 씌웁니다.
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream);
                 OutputStream bufferedOutput = new BufferedOutputStream(gzipOutputStream)) {

                // 2. 서비스 로직 호출 및 데이터 직렬화
                equipmentService.streamEquipmentData(userIgn, bufferedOutput);

            } catch (Exception e) {
                // 스트림 닫기 전 에러 처리
                throw new RuntimeException("스트리밍 처리 중 오류 발생", e);
            }
        };

        // 클라이언트에게 GZIP으로 압축된 응답임을 알림
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody);
    }

    @GetMapping("/api/v3/characters/{userIgn}/expectation")
    public ResponseEntity<TotalExpectationResponse> getEquipmentExpectation(@PathVariable String userIgn) {

        // 1. [EquipmentService] 데이터 가져오기 (List 반환)
        List<CubeCalculationInput> inputs = equipmentService.getCubeCalculationInputs(userIgn);


        // 2. [CubeService] 전체 비용 계산 (List 처리)
//        long totalExpectedCost = cubeService.calculateTotalExpectedCost(inputs);
        for (CubeCalculationInput input : inputs) {
            long expectedCost = cubeService.calculateExpectedCost(input);
            input.setExpectedCost(expectedCost);
        }

        long totalExpectedCost = inputs.stream()
                .mapToLong(CubeCalculationInput::getExpectedCost)
                .sum();

        List<TotalExpectationResponse.ItemExpectation> itemDetails = new ArrayList<>();

        for (CubeCalculationInput input : inputs) {
            itemDetails.add(TotalExpectationResponse.ItemExpectation.builder()
                    .part(input.getPart())
                    .itemName(input.getItemName())
                    .potential(formatPotential(input)) // 예쁘게 포맷팅
                    .expectedCost(input.getExpectedCost())
                    .expectedCostText(String.format("%,d 메소", input.getExpectedCost()))
                    .build());
        }

        // 3. 결과 반환
        return ResponseEntity.ok(TotalExpectationResponse.builder()
                .userIgn(userIgn)
                .totalCost(totalExpectedCost)
                .totalCostText(String.format("%,d 메소", totalExpectedCost))
                .items(itemDetails)
                .build());
    }

    private String formatPotential(CubeCalculationInput input) {
        return input.getOptions().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(" | "));
    }
}
