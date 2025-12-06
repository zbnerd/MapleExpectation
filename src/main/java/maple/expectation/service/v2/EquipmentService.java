package maple.expectation.service.v2;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.provider.EquipmentDataProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

    private final GameCharacterService characterService;
    private final EquipmentDataProvider equipmentProvider;   // 데이터 공급 담당
    private final EquipmentStreamingParser streamingParser;  // 파싱 담당

    /**
     * [V2 API용] 전체 장비 정보 조회 (DTO 반환)
     */
    @Transactional
    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        String ocid = getOcid(userIgn);
        // Provider에게 DTO 달라고 요청
        return equipmentProvider.getEquipmentResponse(ocid);
    }

    /**
     * [V3 API용] 큐브 계산에 필요한 핵심 정보만 추출 (List 반환)
     */
    public List<CubeCalculationInput> getCubeCalculationInputs(String userIgn) throws IOException {
        String ocid = getOcid(userIgn);

        // 1. Raw Data 확보 (Provider)
        byte[] rawData = equipmentProvider.getRawEquipmentData(ocid);

        // 2. 스트리밍 파싱 (Parser)
        return streamingParser.parseCubeInputs(rawData);
    }

    /**
     * [스트리밍 API용] DB 데이터를 바로 OutputStream으로 전송
     */
    @Transactional(readOnly = true)
    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        // 기존 DTO 로직 재활용 (Provider가 캐싱 처리 다 해줌)
        EquipmentResponse response = getEquipmentByUserIgn(userIgn);

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonGenerator jsonGenerator = mapper.getFactory().createGenerator(outputStream);
            mapper.writeValue(jsonGenerator, response);
            jsonGenerator.flush();
        } catch (IOException e) {
            throw new RuntimeException("JSON 스트리밍 직렬화 실패", e);
        }
    }

    private String getOcid(String userIgn) {
        GameCharacter character = characterService.findCharacterByUserIgn(userIgn);
        return character.getOcid();
    }
}