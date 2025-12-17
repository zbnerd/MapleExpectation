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
import maple.expectation.util.GzipUtils;
import maple.expectation.util.StatParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@TraceLog
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 성능 최적화: 기본 읽기 전용
public class EquipmentService {

    private final GameCharacterService characterService;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    private final CubeService cubeService;

    /**
     * [V3 API] Streaming Parser 기반 (고성능 / 메모리 절약)
     */
    public TotalExpectationResponse calculateTotalExpectation(String userIgn) {
        String ocid = getOcid(userIgn);
        // Provider에서 Raw Data(byte[])를 받아 바로 파싱 -> 객체 변환 오버헤드 제거
        byte[] rawData = equipmentProvider.getRawEquipmentData(ocid);
        List<CubeCalculationInput> inputs = streamingParser.parseCubeInputs(rawData);

        return calculateCostFromInputs(userIgn, inputs);
    }

    /**
     * [Legacy API] ObjectMapper 기반 (기존 클라이언트 호환용)
     */
    public TotalExpectationResponse calculateTotalExpectationLegacy(String userIgn) {
        EquipmentResponse equipment = getEquipmentByUserIgn(userIgn);
        List<CubeCalculationInput> inputs = new ArrayList<>();

        if (equipment.getItemEquipment() != null) {
            for (EquipmentResponse.ItemEquipment item : equipment.getItemEquipment()) {
                if (item.getPotentialOptionGrade() == null) continue;
                inputs.add(mapToCubeInput(item)); // 기존 매핑 로직
            }
        }
        return calculateCostFromInputs(userIgn, inputs);
    }

    /**
     * [Stream API] Zero-Copy 스트리밍 (API 호출 -> 압축 해제 -> 전송)
     */
    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        String ocid = getOcid(userIgn);
        byte[] rawData = equipmentProvider.getRawEquipmentData(ocid);

        try {
            if (isGzip(rawData)) {
                outputStream.write(GzipUtils.decompress(rawData).getBytes());
            } else {
                outputStream.write(rawData);
            }
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException("데이터 스트리밍 실패", e);
        }
    }

    // [단순 조회]
    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        String ocid = getOcid(userIgn);
        return equipmentProvider.getEquipmentResponse(ocid);
    }

    // --- Private Helper Methods ---

    // 공통 계산 로직 추출
    private TotalExpectationResponse calculateCostFromInputs(String userIgn, List<CubeCalculationInput> inputs) {
        long totalCost = 0;
        List<ItemExpectation> itemDetails = new ArrayList<>();

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

    private String getOcid(String userIgn) {
        GameCharacter character = characterService.findCharacterByUserIgn(userIgn);
        return character.getOcid();
    }

    private CubeCalculationInput mapToCubeInput(EquipmentResponse.ItemEquipment item) {
        List<String> optionList = new ArrayList<>();
        if (item.getPotentialOption1() != null) optionList.add(item.getPotentialOption1());
        if (item.getPotentialOption2() != null) optionList.add(item.getPotentialOption2());
        if (item.getPotentialOption3() != null) optionList.add(item.getPotentialOption3());

        int level = (item.getBaseOption() != null)
                ? StatParser.parseNum(item.getBaseOption().getBaseEquipmentLevel())
                : 0;

        return CubeCalculationInput.builder()
                .itemName(item.getItemName())
                .level(level)
                .part(item.getItemEquipmentSlot())
                .grade(item.getPotentialOptionGrade())
                .options(optionList)
                .build();
    }

    private ItemExpectation mapToItemExpectation(CubeCalculationInput input, long cost) {
        return ItemExpectation.builder()
                .part(input.getPart())
                .itemName(input.getItemName())
                .potential(String.join(" | ", input.getOptions()))
                .expectedCost(cost)
                .expectedCostText(String.format("%,d 메소", cost))
                .build();
    }

    private boolean isGzip(byte[] data) {
        return data != null && data.length > 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B;
    }
}