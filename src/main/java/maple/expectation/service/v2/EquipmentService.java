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
import maple.expectation.service.v2.calculator.ExpectationCalculatorFactory;
import maple.expectation.service.v2.calculator.impl.BaseItem;
import maple.expectation.service.v2.calculator.impl.BlackCubeDecorator;
import maple.expectation.service.v2.mapper.EquipmentMapper;
import maple.expectation.service.v2.policy.CubeCostPolicy;
import maple.expectation.util.GzipUtils;
import maple.expectation.util.StatParser;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentService {

    private final GameCharacterService characterService;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    private final ExpectationCalculatorFactory calculatorFactory;
    private final EquipmentMapper equipmentMapper; // 💡 새로 만든 매퍼 주입

    public TotalExpectationResponse calculateTotalExpectation(String userIgn) {
        // 1. 데이터 획득
        byte[] rawData = equipmentProvider.getRawEquipmentData(getOcid(userIgn)).join();

        // 2. 파싱 및 계산 수행
        List<CubeCalculationInput> inputs = streamingParser.parseCubeInputs(rawData);
        return processCalculation(userIgn, inputs);
    }

    public TotalExpectationResponse calculateTotalExpectationLegacy(String userIgn) {
        // 1. DTO로 데이터 획득
        EquipmentResponse equipment = equipmentProvider.getEquipmentResponse(getOcid(userIgn)).join();

        // 2. 매퍼를 이용한 변환 및 계산 수행
        List<CubeCalculationInput> inputs = equipment.getItemEquipment().stream()
                .filter(item -> item.getPotentialOptionGrade() != null)
                .map(equipmentMapper::toCubeInput)
                .toList();

        return processCalculation(userIgn, inputs);
    }

    private TotalExpectationResponse processCalculation(String userIgn, List<CubeCalculationInput> inputs) {
        List<TotalExpectationResponse.ItemExpectation> details = inputs.stream()
                .map(input -> {
                    ExpectationCalculator calc = calculatorFactory.createBlackCubeCalculator(input);
                    return equipmentMapper.toItemExpectation(input, calc.calculateCost(), calc.getTrials().orElse(0L));
                })
                .toList();

        long totalCost = details.stream().mapToLong(TotalExpectationResponse.ItemExpectation::getExpectedCost).sum();

        return equipmentMapper.toTotalResponse(userIgn, totalCost, details);
    }

    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        equipmentProvider.streamAndDecompress(getOcid(userIgn), outputStream);
    }

    @Cacheable(value = "equipment", key = "#userIgn")
    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        log.info("💾 [Cache Miss] DB/API에서 장비 데이터를 가져옵니다: {}", userIgn);
        return equipmentProvider.getEquipmentResponse(getOcid(userIgn)).join();
    }

    private String getOcid(String userIgn) {
        return characterService.findCharacterByUserIgn(userIgn).getOcid();
    }
}