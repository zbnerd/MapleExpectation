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
    private final CubeTrialsProvider trialsProvider;
    private final CubeCostPolicy costPolicy;

    /**
     * [V3 API] Streaming Parser 기반
     * 💡 변경: 비동기 데이터 획득 후 join()으로 결과를 기다려 계산 수행
     */
    public TotalExpectationResponse calculateTotalExpectation(String userIgn) {
        String ocid = getOcid(userIgn);
        // 💡 Scenario C 대응: 3초 타임아웃이 걸린 비동기 호출
        byte[] rawData = equipmentProvider.getRawEquipmentData(ocid).join();
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
            ExpectationCalculator calculator = new BaseItem(input.getItemName());
            calculator = new BlackCubeDecorator(calculator, trialsProvider, costPolicy, input);

            long cost = calculator.calculateCost();
            if (cost > 0) {
                totalCost += cost;
                itemDetails.add(mapToItemExpectation(input, cost, calculator.getTrials().orElse(0L)));
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
        // 💡 비동기로 데이터를 가져와서 스트림에 기록
        byte[] rawData = equipmentProvider.getRawEquipmentData(ocid).join();

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

    /**
     * 💡 중요 수정: 반환 타입 대응
     * NexonApiClient가 CompletableFuture를 반환하므로 join()으로 동기화 처리
     */
    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        String ocid = getOcid(userIgn);
        // 💡 CompletableFuture의 join()을 사용하여 결과를 가져옴 (장애 시 Fallback 작동)
        return equipmentProvider.getEquipmentResponse(ocid).join();
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

    private ItemExpectation mapToItemExpectation(CubeCalculationInput input, long cost, long count) {
        return ItemExpectation.builder()
                .part(input.getPart())
                .itemName(input.getItemName())
                .potential(String.join(" | ", input.getOptions()))
                .expectedCost(cost)
                .expectedCostText(String.format("%,d 메소", cost))
                .expectedCount(count)
                .build();
    }

    private boolean isGzip(byte[] data) {
        return data != null && data.length > 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B;
    }
}