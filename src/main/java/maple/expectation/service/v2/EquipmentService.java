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
import maple.expectation.service.v2.calculator.ExpectationCalculator;
import maple.expectation.service.v2.calculator.impl.BaseItem;
import maple.expectation.service.v2.calculator.impl.BlackCubeDecorator;
import maple.expectation.service.v2.policy.CubeCostPolicy;
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
@Transactional
public class EquipmentService {

    private final GameCharacterService characterService;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    private final CubeService cubeService;
    private final CubeCostPolicy costPolicy;

    /**
     * [V3 API] Streaming Parser 기반
     */
    public TotalExpectationResponse calculateTotalExpectation(String userIgn) {
        String ocid = getOcid(userIgn);
        byte[] rawData = equipmentProvider.getRawEquipmentData(ocid);
        List<CubeCalculationInput> inputs = streamingParser.parseCubeInputs(rawData);

        return calculateCostFromInputs(userIgn, inputs);
    }

    /**
     * [Legacy API] ObjectMapper 기반
     */
    public TotalExpectationResponse calculateTotalExpectationLegacy(String userIgn) {
        EquipmentResponse equipment = getEquipmentByUserIgn(userIgn);
        List<CubeCalculationInput> inputs = new ArrayList<>();

        if (equipment.getItemEquipment() != null) {
            for (EquipmentResponse.ItemEquipment item : equipment.getItemEquipment()) {
                if (item.getPotentialOptionGrade() == null) continue;
                inputs.add(mapToCubeInput(item));
            }
        }
        return calculateCostFromInputs(userIgn, inputs);
    }

    /**
     * [Core Logic] Decorator 패턴 조립 및 계산
     */
    private TotalExpectationResponse calculateCostFromInputs(String userIgn, List<CubeCalculationInput> inputs) {
        long totalCost = 0;
        List<ItemExpectation> itemDetails = new ArrayList<>();

        for (CubeCalculationInput input : inputs) {
            // 1. 시작점: 기본 아이템
            ExpectationCalculator calculator = new BaseItem(input.getItemName());

            // 2. 블랙큐브(윗잠재) 데코레이터 장착
            // 나중에 레드큐브나 에디셔널이 추가되면 유저 선택에 따라 여기에 if문으로 감싸기만 하면 됩니다.
            calculator = new BlackCubeDecorator(calculator, cubeService, costPolicy, input);

            // 3. 최종 비용 합산
            long cost = calculator.calculateCost();
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
     * [Stream API] Zero-Copy 스트리밍
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

    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        String ocid = getOcid(userIgn);
        return equipmentProvider.getEquipmentResponse(ocid);
    }

    // --- Private Helper Methods ---

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