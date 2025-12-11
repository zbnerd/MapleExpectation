package maple.expectation.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.exception.EquipmentDataProcessingException; // 커스텀 예외
import maple.expectation.external.MaplestoryApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Slf4j
@Component
@TraceLog
@RequiredArgsConstructor
public class EquipmentDataProvider {

    @Value("${app.optimization.use-compression}")
    private boolean USE_COMPRESSION;

    private final CharacterEquipmentRepository equipmentRepository;
    private final MaplestoryApiClient apiClient;
    private final ObjectMapper objectMapper;

    /**
     * OCID에 해당하는 최신 Raw Data(byte[])를 보장하여 반환합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public byte[] getRawEquipmentData(String ocid) {
        return equipmentRepository.findById(ocid)
                .map(entity -> {
                    if (isExpired(entity.getUpdatedAt())) {
                        log.info("🔄 [Provider] 캐시 만료 -> API 갱신 (압축: {})", USE_COMPRESSION);
                        return fetchFromApiAndSave(ocid, entity);
                    }
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
        byte[] rawData = getRawEquipmentData(ocid);

        String jsonString = USE_COMPRESSION
                ? GzipUtils.decompress(rawData)
                : new String(rawData, StandardCharsets.UTF_8);

        return parseJson(jsonString);
    }

    // --- 내부 로직 ---

    private byte[] fetchFromApiAndSave(String ocid, CharacterEquipment existingEntity) {
        // 1. API 호출 (실패 시 RestClientException 등 발생 -> GlobalExceptionHandler 처리)
        EquipmentResponse response = apiClient.getItemDataByOcid(ocid);

        try {
            // 2. 변환 및 압축
            String jsonString = objectMapper.writeValueAsString(response);
            byte[] rawData = USE_COMPRESSION
                    ? GzipUtils.compress(jsonString)
                    : jsonString.getBytes(StandardCharsets.UTF_8);

            // 3. DB 저장 (Upsert)
            if (existingEntity != null) {
                log.info("💾 [DB Update] 기존 데이터 갱신: {}", ocid);
                existingEntity.updateData(rawData);
                // ★ [중요] 캐시 만료 해결을 위해 saveAndFlush 사용
                equipmentRepository.saveAndFlush(existingEntity);
            } else {
                log.info("💾 [DB Insert] 신규 데이터 저장: {}", ocid);
                CharacterEquipment newEntity = new CharacterEquipment(ocid, rawData);
                equipmentRepository.saveAndFlush(newEntity);
            }

            return rawData;

        } catch (JsonProcessingException e) {
            // ★ [수정] 명확한 커스텀 예외로 감싸서 던짐
            log.error("장비 데이터 JSON 직렬화 중 오류 발생: OCID={}", ocid, e);
            throw new EquipmentDataProcessingException("장비 데이터 직렬화/압축 실패", e);
        } catch (Exception e) {
            log.error("장비 데이터 저장 중 알 수 없는 오류 발생: OCID={}", ocid, e);
            throw new EquipmentDataProcessingException("장비 데이터 갱신 중 예기치 못한 오류 발생", e);
        }
    }

    private boolean isExpired(LocalDateTime updatedAt) {
        return updatedAt.isBefore(LocalDateTime.now().minusMinutes(15));
    }

    private EquipmentResponse parseJson(String json) {
        try {
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (JsonProcessingException e) {
            // ★ [수정] 명확한 커스텀 예외로 감싸서 던짐
            log.error("JSON 문자열 파싱 실패 (길이: {})", json.length(), e);
            throw new EquipmentDataProcessingException("장비 데이터(JSON) 파싱 오류", e);
        }
    }
}