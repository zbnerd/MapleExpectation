package maple.expectation.service.v2;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.util.GzipUtils;
import maple.expectation.util.StatParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

    @Value("${app.optimization.use-compression}")
    private boolean USE_COMPRESSION;

    private final CharacterEquipmentRepository equipmentRepository;
    private final MaplestoryApiClient apiClient;
    private final GameCharacterService characterService; // OCID 조회용 (기존 서비스)
    private final ObjectMapper objectMapper; // JSON 변환용

    /**
     * 캐릭터 닉네임으로 장비 정보를 조회합니다. (15분 캐싱 적용)
     */
    @Transactional
    public EquipmentResponse getEquipmentByUserIgn(String userIgn) {
        GameCharacter character = characterService.findCharacterByUserIgn(userIgn);
        String ocid = character.getOcid();

        return equipmentRepository.findById(ocid)
                .map(entity -> {
                    // [만료됨] -> 갱신
                    if (isExpired(entity.getUpdatedAt())) {
                        log.info("🔄 [Cache Expired] 데이터 만료 -> 갱신 (압축모드: {})", USE_COMPRESSION);
                        return fetchAndSave(ocid, entity);
                    }

                    // [최신 데이터] -> DB 반환
                    // 🔓 스위치에 따라 압축 해제 방식 분기
                    String jsonString = USE_COMPRESSION
                            ? GzipUtils.decompress(entity.getRawData()) // 압축모드
                            : new String(entity.getRawData(), StandardCharsets.UTF_8); // 비압축 모드

                    log.info("✅ [Cache Hit] DB 반환 (압축모드: {})", USE_COMPRESSION);
                    return parseJson(jsonString);
                })
                .orElseGet(() -> {
                    log.info("🆕 [Cache Miss] 신규 조회 (압축모드: {})", USE_COMPRESSION);
                    return fetchAndSave(ocid, null);
                });
    }

    @Transactional(readOnly = true)
    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        // DB에서 데이터 로드 (캐싱 로직 포함)
        EquipmentResponse response = getEquipmentByUserIgn(userIgn); // 기존 로직 재활용 가능

        // Jackson ObjectMapper를 사용하여 스트리밍 직렬화 준비
        ObjectMapper mapper = new ObjectMapper();

        try {
            // 1. JsonGenerator 생성: 데이터를 outputStream에 직접 작성합니다.
            JsonGenerator jsonGenerator = mapper.getFactory().createGenerator(outputStream);

            // 2. 직렬화 실행: 메모리에 String을 만들지 않고 바로 스트림에 씁니다.
            mapper.writeValue(jsonGenerator, response);

            // 3. 플러시 (버퍼 비우기)
            jsonGenerator.flush();

        } catch (IOException e) {
            throw new RuntimeException("JSON 스트리밍 직렬화 실패", e);
        }
    }

    public List<CubeCalculationInput> getCubeCalculationInputs(String userIgn) {
        // 1. Raw Data 획득 (byte[] 또는 InputStream)
        byte[] rawJsonData = fetchRawDataFromApi(userIgn);

        // 2. 스트리밍 파싱 -> DTO 변환
        return parseAndCollectInputs(rawJsonData);
    }

    /**
     * Jackson JsonParser를 사용한 데이터 수집 (Data Collector)
     */
    private List<CubeCalculationInput> parseAndCollectInputs(byte[] rawJsonData) {
        if (rawJsonData == null || rawJsonData.length == 0) return new ArrayList<>();

        List<CubeCalculationInput> resultList = new ArrayList<>();
        JsonFactory factory = new JsonFactory();

        // GZIP 해제 로직 (기존 동일)
        InputStream inputStream = new ByteArrayInputStream(rawJsonData);
        try {
            if (rawJsonData.length > 2 && rawJsonData[0] == (byte) 0x1F && rawJsonData[1] == (byte) 0x8B) {
                inputStream = new GZIPInputStream(inputStream);
            }

            try (JsonParser parser = factory.createParser(inputStream)) {
                // 1. "item_equipment" 필드를 찾을 때까지 쭉 스킵
                while (parser.nextToken() != null) {
                    if ("item_equipment".equals(parser.currentName())) {
                        parser.nextToken(); // 필드명 -> START_ARRAY ([) 로 이동
                        break; // 찾았으니 루프 탈출
                    }
                }

                // 2. "item_equipment" 배열 내부만 순회
                if (parser.currentToken() == JsonToken.START_ARRAY) {
                    CubeCalculationInput currentItem = new CubeCalculationInput();

                    // 배열이 끝날 때(END_ARRAY)까지 반복
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        String fieldName = parser.currentName();

                        // (배열 끝 체크 안전장치)
                        if (fieldName == null) continue;

                        switch (fieldName) {
                            case "item_equipment_slot":
                                parser.nextToken();
                                // 새 아이템 시작 감지 (이전 아이템 저장)
                                if (currentItem.isReady()) {
                                    resultList.add(currentItem);
                                    currentItem = new CubeCalculationInput();
                                }
                                currentItem.setPart(parser.getText());
                                break;

                            case "potential_option_grade":
                                parser.nextToken();
                                currentItem.setGrade(parser.getText());
                                break;

                            case "base_equipment_level":
                                parser.nextToken();
                                if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                                    currentItem.setLevel(parser.getIntValue());
                                } else if (parser.currentToken() == JsonToken.VALUE_STRING) {
                                    currentItem.setLevel(StatParser.parseNum(parser.getText()));
                                }
                                break;

                            case "item_name":
                                parser.nextToken();
                                currentItem.setItemName(parser.getText());
                                break;

                            case "potential_option_1":
                            case "potential_option_2":
                            case "potential_option_3":
                                parser.nextToken();
                                String val = parser.getText();
                                if (val != null && !val.trim().isEmpty()) {
                                    currentItem.getOptions().add(val);
                                }
                                break;
                        }
                    }
                    // 마지막 아이템 저장
                    if (currentItem.isReady()) {
                        resultList.add(currentItem);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("JSON 스트리밍 파싱 실패", e);
        }

        return resultList;
    }

    /**
     * [신규] 350KB 데이터를 객체로 만들지 않고, Raw Byte 상태로 가져옵니다.
     * - Cache Hit: DB에서 byte[] 조회 (압축된 상태일 수 있음)
     * - Cache Miss: API 호출 -> DB 저장 -> JSON bytes 반환
     */
    private byte[] fetchRawDataFromApi(String userIgn) {
        GameCharacter character = characterService.findCharacterByUserIgn(userIgn);
        String ocid = character.getOcid();

        return equipmentRepository.findById(ocid)
                .map(entity -> {
                    // 1. 만료 체크
                    if (isExpired(entity.getUpdatedAt())) {
                        log.info("🔄 [Cache Expired] 데이터 만료 -> 갱신 (압축모드: {})", USE_COMPRESSION);
                        return fetchAndSaveRaw(ocid, entity); // 갱신 후 반환
                    }
                    // 2. Cache Hit: DB에 저장된 byte[] 그대로 반환
                    // (압축 여부는 파서가 처리하거나, 여기서 풀어서 줄 수 있음)
                    log.info("✅ [Cache Hit] DB 반환 (압축모드: {})", USE_COMPRESSION);
                    return entity.getRawData();
                })
                .orElseGet(() -> fetchAndSaveRaw(ocid, null)); // 신규 조회
    }

    // [내부 메서드] API 호출 -> DB 저장 -> Raw Data 반환
    private byte[] fetchAndSaveRaw(String ocid, CharacterEquipment existingEntity) {
        // 1. API 호출 (어쩔 수 없이 DTO 생성됨 - Cache Miss일 때만 발생)
        EquipmentResponse response = apiClient.getItemDataByOcid(ocid);

        try {
            // 2. DTO -> JSON Byte[] 변환
            String jsonString = objectMapper.writeValueAsString(response);
            byte[] rawData;

            // 3. 설정에 따라 압축
            if (USE_COMPRESSION) {
                rawData = GzipUtils.compress(jsonString);
            } else {
                rawData = jsonString.getBytes(StandardCharsets.UTF_8);
            }

            // 4. DB 저장
            if (existingEntity != null) {
                existingEntity.updateData(rawData);
            } else {
                equipmentRepository.save(new CharacterEquipment(ocid, rawData));
            }

            return rawData;

        } catch (JsonProcessingException e) {
            throw new RuntimeException("데이터 갱신 중 오류 발생", e);
        }
    }

    // 15분 만료 체크
    private boolean isExpired(LocalDateTime updatedAt) {
        return updatedAt.isBefore(LocalDateTime.now().minusMinutes(15));
    }

    // API 호출 -> DB 저장 -> DTO 반환
    private EquipmentResponse fetchAndSave(String ocid, CharacterEquipment existingEntity) {
        // 1. API 호출
        EquipmentResponse response = apiClient.getItemDataByOcid(ocid);

        // 2. 변환 (DTO -> JSON String)
        String jsonString = toJson(response);

        // 3. 🔒 설정에 따라 압축 여부 결정 (이 부분이 수정됨!)
        byte[] dataToSave;

        dataToSave = USE_COMPRESSION ? GzipUtils.compress(jsonString) : jsonString.getBytes(StandardCharsets.UTF_8);

        // 4. 저장 (Upsert)
        if (existingEntity != null) {
            existingEntity.updateData(dataToSave);
        } else {
            equipmentRepository.save(new CharacterEquipment(ocid, dataToSave));
        }

        return response;
    }

    // 유틸: DTO -> JSON String
    private String toJson(EquipmentResponse dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 변환 오류", e);
        }
    }

    // 유틸: JSON String -> DTO
    private EquipmentResponse parseJson(String json) {
        try {
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 파싱 오류", e);
        }
    }
}