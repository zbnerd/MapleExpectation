package maple.expectation.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.exception.EquipmentDataProcessingException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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

    // 🔑 동시성 제어를 위한 Key-based Lock (OCID -> Lock)
    private final Map<String, ReentrantLock> mutexMap = new ConcurrentHashMap<>();

    /**
     * [핵심] Raw Data 조회 (동시성 제어 적용)
     */

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public byte[] getRawEquipmentData(String ocid) {
        // 1. [Fast-Path] 락 없이 먼저 캐시 조회 (대부분의 트래픽)
        CharacterEquipment entity = equipmentRepository.findById(ocid).orElse(null);
        if (isValidCache(entity)) {
            return entity.getRawData();
        }

        // 2. 락 가져오기 (없으면 생성)
        ReentrantLock lock = mutexMap.computeIfAbsent(ocid, k -> new ReentrantLock());

        lock.lock(); // 🔒 Lock 획득
        try {
            // 3. [Double-Check] 락 획득 후 다시 한번 DB 조회
            entity = equipmentRepository.findById(ocid).orElse(null);
            if (isValidCache(entity)) {
                log.debug("✅ [Provider-Sync] 동기화 후 DB 캐시 반환: {}", ocid);
                return entity.getRawData();
            }

            // 4. [Critical Section] API 호출 및 저장
            log.info("🔄 [Provider-API] 외부 API 호출 진행: {}", ocid);
            return fetchFromApiAndSave(ocid, entity);

        } finally {
            lock.unlock(); // 🔓 Lock 해제
        }
    }

    /**
     * (V2 호환용) 객체 반환
     */
    public EquipmentResponse getEquipmentResponse(String ocid) {
        byte[] rawData = getRawEquipmentData(ocid);
        String jsonString = USE_COMPRESSION
                ? GzipUtils.decompress(rawData)
                : new String(rawData, StandardCharsets.UTF_8);
        return parseJson(jsonString);
    }

    // --- 내부 로직 ---

    @Transactional // 저장이 일어나는 구간만 트랜잭션 처리
    protected byte[] fetchFromApiAndSave(String ocid, CharacterEquipment existingEntity) {
        EquipmentResponse response = apiClient.getItemDataByOcid(ocid);

        try {
            String jsonString = objectMapper.writeValueAsString(response);
            byte[] rawData = USE_COMPRESSION
                    ? GzipUtils.compress(jsonString)
                    : jsonString.getBytes(StandardCharsets.UTF_8);

            if (existingEntity != null) {
                existingEntity.updateData(rawData);
                equipmentRepository.saveAndFlush(existingEntity); // 기존 데이터 갱신
            } else {
                CharacterEquipment newEntity = new CharacterEquipment(ocid, rawData);
                equipmentRepository.saveAndFlush(newEntity); // 신규 저장
            }
            return rawData;

        } catch (JsonProcessingException e) {
            log.error("JSON 직렬화 오류: {}", ocid, e);
            throw new EquipmentDataProcessingException("장비 데이터 직렬화 실패", e);
        } catch (Exception e) {
            log.error("데이터 저장 중 오류: {}", ocid, e);
            throw new EquipmentDataProcessingException("장비 데이터 저장 실패", e);
        }
    }

    private boolean isValidCache(CharacterEquipment entity) {
        return entity != null && !isExpired(entity.getUpdatedAt());
    }

    private boolean isExpired(LocalDateTime updatedAt) {
        return updatedAt.isBefore(LocalDateTime.now().minusMinutes(15));
    }

    private EquipmentResponse parseJson(String json) {
        try {
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (JsonProcessingException e) {
            throw new EquipmentDataProcessingException("JSON 파싱 실패", e);
        }
    }
}