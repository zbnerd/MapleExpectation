package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse.ItemExpectation;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@TraceLog
@Service
@RequiredArgsConstructor
public class EquipmentService {

    private final GameCharacterService characterService;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    private final CubeService cubeService;

    @Transactional(readOnly = true)
    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        String ocid = getOcid(userIgn);
        return equipmentProvider.getEquipmentResponse(ocid);
    }

    /**
     * [V3 API] 기대 비용 총계 계산
     * ✅ try-catch 완전 제거 완료!
     */
    @Transactional(readOnly = true)
    public TotalExpectationResponse calculateTotalExpectation(String userIgn) {
        // 1. 파싱 (예외 발생 시 Parser 내부에서 RuntimeException으로 던짐 -> ControllerAdvice가 처리)
        List<CubeCalculationInput> inputs = getCubeCalculationInputs(userIgn);

        long totalCost = 0;
        List<ItemExpectation> itemDetails = new ArrayList<>();

        // 2. 계산 로직 (순수 비즈니스 로직)
        for (CubeCalculationInput input : inputs) {
            long cost = cubeService.calculateExpectedCost(input);

            if (cost > 0) {
                totalCost += cost;
                itemDetails.add(mapToItemExpectation(input, cost));
            }
        }

        return TotalExpectationResponse.builder()
                .userIgn(userIgn)
                .totalCost(totalCost)
                .totalCostText(String.format("%,d 메소", totalCost))
                .items(itemDetails)
                .build();
    }

    /**
     * [Stream API]
     * ✅ try-catch 완전 제거 완료!
     */
    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        EquipmentResponse response = getEquipmentByUserIgn(userIgn);
        // 쓰기 작업도 Parser에게 위임 (Service는 흐름만 제어)
        streamingParser.writeToStream(response, outputStream);
    }

    // --- Private Helper Methods ---

    private List<CubeCalculationInput> getCubeCalculationInputs(String userIgn) {
        String ocid = getOcid(userIgn);
        // Provider는 이미 RuntimeException을 던지도록 되어있으므로 안심하고 호출
        byte[] rawData = equipmentProvider.getRawEquipmentData(ocid);
        // Parser도 이제 RuntimeException을 던짐
        return streamingParser.parseCubeInputs(rawData);
    }

    private String getOcid(String userIgn) {
        GameCharacter character = characterService.findCharacterByUserIgn(userIgn);
        return character.getOcid();
    }

    private ItemExpectation mapToItemExpectation(CubeCalculationInput input, long cost) {
        return ItemExpectation.builder()
                .part(input.getPart())
                .itemName(input.getItemName())
                .potential(formatPotential(input.getOptions()))
                .expectedCost(cost)
                .expectedCostText(String.format("%,d 메소", cost))
                .build();
    }

    private String formatPotential(List<String> options) {
        return String.join(" | ", options);
    }
}