package maple.expectation.service.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성 확보
import maple.expectation.global.executor.strategy.ExceptionTranslator; // ✅ JSON 전용 번역기
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.service.v2.cache.EquipmentCacheService;
import maple.expectation.service.v2.calculator.ExpectationCalculator;
import maple.expectation.service.v2.calculator.ExpectationCalculatorFactory;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import maple.expectation.service.v2.mapper.EquipmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

    private final GameCharacterFacade gameCharacterFacade;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    private final ExpectationCalculatorFactory calculatorFactory;
    private final EquipmentMapper equipmentMapper;
    private final EquipmentCacheService equipmentCacheService;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor; // ✅ 전역 로직 실행기 주입

    /**
     * 🚀 [V3 메인 로직] 기대값 계산
     */
    @TraceLog
    @Transactional
    public TotalExpectationResponse calculateTotalExpectation(String userIgn) {
        TaskContext context = TaskContext.of("EquipmentService", "CalculateTotal", userIgn);

        return executor.execute(() -> {
            GameCharacter character = gameCharacterFacade.findCharacterByUserIgn(userIgn);
            String ocid = character.getOcid();
            byte[] targetData;

            Optional<EquipmentResponse> cachedResponse = equipmentCacheService.getValidCache(ocid);

            if (cachedResponse.isPresent()) {
                targetData = serializeToBytes(cachedResponse.get()); // ✅ try-catch 제거됨
            } else if (character.getEquipment() != null) {
                String jsonContent = character.getEquipment().getJsonContent();
                targetData = jsonContent.getBytes(StandardCharsets.UTF_8);
                equipmentCacheService.saveCache(ocid, deserializeToDto(jsonContent)); // ✅ try-catch 제거됨
            } else {
                log.info("🌐 [DB/Cache Miss] 넥슨 API 신규 호출: {}", userIgn);
                EquipmentResponse response = equipmentProvider.getEquipmentResponse(ocid).join();
                equipmentCacheService.saveCache(ocid, response);
                targetData = equipmentProvider.getRawEquipmentData(ocid).join();
            }

            List<CubeCalculationInput> inputs = streamingParser.parseCubeInputs(targetData);
            return processCalculation(userIgn, inputs);
        }, context);
    }

    /**
     * ✅  try-catch 제거 및 ExceptionTranslator 적용
     */
    private byte[] serializeToBytes(EquipmentResponse response) {
        return executor.executeWithTranslation(
                () -> objectMapper.writeValueAsBytes(response),
                ExceptionTranslator.forJson(), // JSON 처리용 세탁기 가동
                TaskContext.of("EquipmentService", "Serialize")
        );
    }

    /**
     * ✅  try-catch 제거 및 ExceptionTranslator 적용
     */
    private EquipmentResponse deserializeToDto(String json) {
        return executor.executeWithTranslation(
                () -> objectMapper.readValue(json, EquipmentResponse.class),
                ExceptionTranslator.forJson(),
                TaskContext.of("EquipmentService", "Deserialize")
        );
    }

    // --- 기존 메서드 보존 (이름 유지 / 삭제 없음) ---

    public TotalExpectationResponse calculateTotalExpectationLegacy(String userIgn) {
        return executor.execute(() -> {
            EquipmentResponse equipment = equipmentProvider.getEquipmentResponse(getOcid(userIgn)).join();
            List<CubeCalculationInput> inputs = equipment.getItemEquipment().stream()
                    .filter(item -> item.getPotentialOptionGrade() != null)
                    .map(equipmentMapper::toCubeInput)
                    .toList();
            return processCalculation(userIgn, inputs);
        }, TaskContext.of("EquipmentService", "CalculateLegacy", userIgn));
    }

    private TotalExpectationResponse processCalculation(String userIgn, List<CubeCalculationInput> inputs) {
        TaskContext context = TaskContext.of("EquipmentService", "ProcessCalculation", userIgn); //

        return executor.execute(() -> { //
            // 1. 개별 아이템 기대값 계산 (Stream 로직 평탄화)
            List<TotalExpectationResponse.ItemExpectation> details = inputs.stream()
                    .map(this::mapToItemExpectation) // 로직 분리 (메서드 추출)
                    .toList();

            // 2. 전체 비용 합산
            long totalCost = details.stream()
                    .mapToLong(TotalExpectationResponse.ItemExpectation::getExpectedCost)
                    .sum();

            // 3. 결과 Response 매핑 및 반환
            return equipmentMapper.toTotalResponse(userIgn, totalCost, details);
        }, context); //
    }

    /**
     * 헬퍼 메서드: 단일 아이템에 대한 기대값 계산 및 DTO 매핑
     */
    private TotalExpectationResponse.ItemExpectation mapToItemExpectation(CubeCalculationInput input) {
        // Calculator 생성 및 비용 산출 로직을 격리하여 가독성 확보
        ExpectationCalculator calc = calculatorFactory.createBlackCubeCalculator(input);

        return equipmentMapper.toItemExpectation(
                input,
                calc.calculateCost(),
                calc.getTrials().orElse(0L)
        );
    }

    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        executor.executeVoid(
                () -> equipmentProvider.streamAndDecompress(getOcid(userIgn), outputStream),
                TaskContext.of("EquipmentService", "StreamData", userIgn)
        );
    }

    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        return executor.execute(
                () -> equipmentProvider.getEquipmentResponse(getOcid(userIgn)).join(),
                TaskContext.of("EquipmentService", "GetEquipment", userIgn)
        );
    }

    private String getOcid(String userIgn) {
        return gameCharacterFacade.findCharacterByUserIgn(userIgn).getOcid();
    }
}