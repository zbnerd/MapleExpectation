package maple.expectation.external.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.EquipmentDataProcessingException;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component("nexonApiCachingProxy")
public class NexonApiCachingProxy implements NexonApiClient {

    private final NexonApiClient realClient;
    private final CharacterEquipmentRepository equipmentRepository;
    private final ObjectMapper objectMapper;
    private final LockStrategy lockStrategy;

    @Value("${app.optimization.use-compression:true}")
    private boolean USE_COMPRESSION;

    public NexonApiCachingProxy(@Qualifier("realNexonApiClient") NexonApiClient realClient,
                                CharacterEquipmentRepository equipmentRepository,
                                ObjectMapper objectMapper,
                                LockStrategy lockStrategy) {
        this.realClient = realClient;
        this.equipmentRepository = equipmentRepository;
        this.objectMapper = objectMapper;
        this.lockStrategy = lockStrategy;
    }

    @Override
    public CharacterOcidResponse getOcidByCharacterName(String name) {
        return realClient.getOcidByCharacterName(name);
    }

    @Override
    @Transactional
    public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. 유효 캐시 확인 (Fast Path)
            return equipmentRepository.findById(ocid)
                    .filter(this::isValidCache)
                    .map(this::convertToResponse)
                    // 2. 캐시 없으면 락을 잡고 데이터 동기화 시도 (Slow Path)
                    .orElseGet(() -> lockStrategy.executeWithLock(ocid, () -> fetchAndCache(ocid)));
        });
    }

    /**
     * 🔹 핵심 로직 분리: 락 내부에서 실행될 "진짜" 데이터 획득 및 저장 로직
     */
    private EquipmentResponse fetchAndCache(String ocid) {
        // Double Check: 락을 대기하는 동안 다른 스레드가 이미 캐시를 만들었을 수 있음
        return equipmentRepository.findById(ocid)
                .filter(this::isValidCache)
                .map(this::convertToResponse)
                .orElseGet(() -> {
                    try {
                        log.info("🔄 [Proxy] 캐시 만료 혹은 없음. API 호출 진행: {}", ocid);
                        // 비동기 결과를 동기적으로 기다려 저장 (Lock 내부이므로 안전)
                        EquipmentResponse res = realClient.getItemDataByOcid(ocid).get();
                        saveToDb(ocid, res);
                        return res;
                    } catch (Exception e) {
                        log.error("❌ [Proxy] 외부 API 데이터 호출 실패: {}", ocid, e);
                        throw new RuntimeException("데이터 동기화 중 오류 발생", e);
                    }
                });
    }

    public EquipmentResponse getExpiredCache(String ocid) {
        return equipmentRepository.findById(ocid)
                .map(this::convertToResponse)
                .orElse(null);
    }

    private boolean isValidCache(CharacterEquipment e) {
        return e != null && e.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(15));
    }

    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            byte[] data = entity.getRawData();
            String json = (data.length > 2 && data[0] == (byte) 0x1F)
                    ? GzipUtils.decompress(data)
                    : new String(data, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (Exception e) {
            throw new EquipmentDataProcessingException("캐시 파싱 실패");
        }
    }

    private void saveToDb(String ocid, EquipmentResponse res) {
        try {
            String json = objectMapper.writeValueAsString(res);
            byte[] data = USE_COMPRESSION ? GzipUtils.compress(json) : json.getBytes(StandardCharsets.UTF_8);

            // 기존 데이터가 있으면 업데이트, 없으면 신규 생성
            CharacterEquipment entity = equipmentRepository.findById(ocid)
                    .orElse(new CharacterEquipment(ocid, data));
            entity.updateData(data);

            equipmentRepository.saveAndFlush(entity);
        } catch (JsonProcessingException e) {
            throw new EquipmentDataProcessingException("직렬화 실패");
        }
    }
}