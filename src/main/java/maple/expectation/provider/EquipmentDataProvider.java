package maple.expectation.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDataProvider {

    @Value("${app.optimization.use-compression}")
    private boolean USE_COMPRESSION;

    private final CharacterEquipmentRepository equipmentRepository;
    private final MaplestoryApiClient apiClient;
    private final ObjectMapper objectMapper;

    /**
     * OCID에 해당하는 최신 Raw Data(byte[])를 보장하여 반환합니다.
     * (DB 조회 -> 만료 체크 -> API 갱신 -> 압축 저장 로직 포함)
     */
    @Transactional
    public byte[] getRawEquipmentData(String ocid) {
        return equipmentRepository.findById(ocid)
                .map(entity -> {
                    // 1. 만료 체크
                    if (isExpired(entity.getUpdatedAt())) {
                        log.info("🔄 [Provider] 캐시 만료 -> API 갱신 (압축: {})", USE_COMPRESSION);
                        return fetchFromApiAndSave(ocid, entity);
                    }
                    // 2. 최신 데이터 (DB)
                    log.info("✅ [Provider] DB 캐시 반환 (압축: {})", USE_COMPRESSION);
                    return entity.getRawData();
                })
                .orElseGet(() -> {
                    log.info("🆕 [Provider] 신규 데이터 조회 (압축: {})", USE_COMPRESSION);
                    return fetchFromApiAndSave(ocid, null);
                });
    }

    /**
     * (V2 호환용) 최신 EquipmentResponse 객체를 반환합니다.
     */
    @Transactional
    public EquipmentResponse getEquipmentResponse(String ocid) {
        // 1. 최신 Raw Data 확보
        byte[] rawData = getRawEquipmentData(ocid);

        // 2. 압축 해제 및 DTO 변환
        String jsonString = USE_COMPRESSION
                ? GzipUtils.decompress(rawData)
                : new String(rawData, StandardCharsets.UTF_8);

        return parseJson(jsonString);
    }

    // --- 내부 로직 ---

    private byte[] fetchFromApiAndSave(String ocid, CharacterEquipment existingEntity) {
        // 1. API 호출
        EquipmentResponse response = apiClient.getItemDataByOcid(ocid);

        try {
            // 2. 변환 및 압축
            String jsonString = objectMapper.writeValueAsString(response);
            byte[] rawData = USE_COMPRESSION
                    ? GzipUtils.compress(jsonString)
                    : jsonString.getBytes(StandardCharsets.UTF_8);

            // 3. DB 저장 (Upsert)
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

    private boolean isExpired(LocalDateTime updatedAt) {
        return updatedAt.isBefore(LocalDateTime.now().minusMinutes(15));
    }

    private EquipmentResponse parseJson(String json) {
        try {
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 파싱 오류", e);
        }
    }
}