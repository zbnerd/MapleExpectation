package maple.expectation.service.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import maple.expectation.service.v2.cache.EquipmentCacheService; // 💡 캐시 서비스 임포트
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
    private final EquipmentCacheService equipmentCacheService; // 💡 추가
    private final ObjectMapper objectMapper; // DTO 변환용

    /**
     * 🚀 [V3 메인 로직] 기대값 계산
     * 캐시(L1/L2) -> DB(L3) -> API 순서로 데이터를 확보하여 RPS를 극대화합니다.
     */
    @TraceLog
    @Transactional
    public TotalExpectationResponse calculateTotalExpectation(String userIgn) {
        // 1. 캐릭터 정보 획득 (Facade에서 JOIN FETCH로 장비까지 가져옴)
        GameCharacter character = gameCharacterFacade.findCharacterByUserIgn(userIgn);
        String ocid = character.getOcid();
        byte[] targetData;

        // 🛡️ [STEP 1] 애플리케이션 캐시 확인 (Redis/Caffeine)
        Optional<EquipmentResponse> cachedResponse = equipmentCacheService.getValidCache(ocid);

        if (cachedResponse.isPresent()) {
            // 캐시된 DTO를 바이트로 변환하여 파서에 전달
            targetData = serializeToBytes(cachedResponse.get());
        }
        // 📦 [STEP 2] DB 데이터 확인 (캐시 미스 시)
        else if (character.getEquipment() != null) {
            String jsonContent = character.getEquipment().getJsonContent();
            targetData = jsonContent.getBytes(StandardCharsets.UTF_8);

            // DB에 있던 데이터를 다음 요청을 위해 캐시에도 저장
            equipmentCacheService.saveCache(ocid, deserializeToDto(jsonContent));
        }
        // 🌐 [STEP 3] API 호출 (최후의 수단)
        else {
            log.info("🌐 [DB/Cache Miss] 넥슨 API 신규 호출: {}", userIgn);
            EquipmentResponse response = equipmentProvider.getEquipmentResponse(ocid).join();

            // saveCache 내부에서 '캐시 저장 + 비동기 DB 저장'을 한꺼번에 수행함
            equipmentCacheService.saveCache(ocid, response);

            // 파싱용 Raw 데이터 확보 (GZIP 압축본)
            targetData = equipmentProvider.getRawEquipmentData(ocid).join();
        }

        // 2. 파싱 및 계산 수행
        List<CubeCalculationInput> inputs = streamingParser.parseCubeInputs(targetData);
        return processCalculation(userIgn, inputs);
    }

    // --- Helper Methods ---

    private byte[] serializeToBytes(EquipmentResponse response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (Exception e) {
            log.error("직렬화 실패", e);
            return new byte[0];
        }
    }

    private EquipmentResponse deserializeToDto(String json) {
        try {
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (Exception e) {
            log.error("역직렬화 실패", e);
            return null;
        }
    }

    // --- 기존 메서드 유지 (테스트 코드 파손 방지) ---

    public TotalExpectationResponse calculateTotalExpectationLegacy(String userIgn) {
        EquipmentResponse equipment = equipmentProvider.getEquipmentResponse(getOcid(userIgn)).join();
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

    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        return equipmentProvider.getEquipmentResponse(getOcid(userIgn)).join();
    }

    private String getOcid(String userIgn) {
        return gameCharacterFacade.findCharacterByUserIgn(userIgn).getOcid();
    }
}