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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentService {

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
        // 1. OCID 조회 (User Identity는 변하지 않으므로 별도 캐싱 불필요)
        GameCharacter character = characterService.findCharacterByUserIgn(userIgn);
        String ocid = character.getOcid();

        // 2. DB 조회 및 캐싱 로직
        return equipmentRepository.findById(ocid)
                .map(entity -> {
                    // [Case A] 데이터가 존재함 -> 시간 체크
                    if (isExpired(entity.getUpdatedAt())) {
                        log.info("[Cache Expired] 15분 경과 -> API 재호출 및 갱신: {}", userIgn);
                        return fetchAndSave(ocid, entity);
                    }
                    // [Case B] 최신 데이터 -> DB에서 반환
                    log.info("[Cache Hit] DB 데이터 반환 (API 호출 X): {}", userIgn);
                    return parseJson(entity.getRawData());
                })
                .orElseGet(() -> {
                    // [Case C] 데이터 없음 -> API 호출 및 신규 저장
                    log.info("[Cache Miss] 신규 데이터 -> API 호출 및 저장: {}", userIgn);
                    return fetchAndSave(ocid, null);
                });
    }

    // 15분 만료 체크
    private boolean isExpired(LocalDateTime updatedAt) {
        return updatedAt.isBefore(LocalDateTime.now().minusMinutes(15));
    }

    // API 호출 -> DB 저장 -> DTO 반환
    private EquipmentResponse fetchAndSave(String ocid, CharacterEquipment existingEntity) {
        // 1. 넥슨 API 호출
        EquipmentResponse response = apiClient.getItemDataByOcid(ocid);

        // 2. DTO -> JSON String 변환
        String jsonString = toJson(response);

        // 3. 저장 (Upsert)
        if (existingEntity != null) {
            existingEntity.updateData(jsonString); // Update (Dirty Checking)
        } else {
            equipmentRepository.save(new CharacterEquipment(ocid, jsonString)); // Insert
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