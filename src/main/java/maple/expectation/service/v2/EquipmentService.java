package maple.expectation.service.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.LogExecutionTime;
import maple.expectation.aop.SimpleLogTime;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

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
    @SimpleLogTime
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
                    String jsonString;
                    if (USE_COMPRESSION) {
                        jsonString = GzipUtils.decompress(entity.getRawData());
                    } else {
                        // 압축 안 함: byte[]를 그대로 String으로
                        jsonString = new String(entity.getRawData(), StandardCharsets.UTF_8);
                    }

                    log.info("✅ [Cache Hit] DB 반환 (압축모드: {})", USE_COMPRESSION);
                    return parseJson(jsonString);
                })
                .orElseGet(() -> {
                    log.info("🆕 [Cache Miss] 신규 조회 (압축모드: {})", USE_COMPRESSION);
                    return fetchAndSave(ocid, null);
                });
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
        if (USE_COMPRESSION) {
            // 압축 모드: GZIP 압축 수행
            dataToSave = GzipUtils.compress(jsonString);
        } else {
            // 비압축 모드: 문자열을 그대로 바이트로 변환
            dataToSave = jsonString.getBytes(StandardCharsets.UTF_8);
        }

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