package maple.expectation.external.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.exception.EquipmentDataProcessingException;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.util.GzipUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class NexonApiCachingProxy implements NexonApiClient {

    private final RealNexonApiClient realClient;
    private final CharacterEquipmentRepository equipmentRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.optimization.use-compression:true}")
    private boolean USE_COMPRESSION;

    // L1 Cache: OCID (1일 유지)
    private final Cache<String, String> ocidCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    // 동시성 제어용 Lock Map
    private final Map<String, ReentrantLock> mutexMap = new ConcurrentHashMap<>();

    @Override
    public CharacterOcidResponse getOcidByCharacterName(String characterName) {
        String cachedOcid = ocidCache.getIfPresent(characterName);
        if (cachedOcid != null) return new CharacterOcidResponse(cachedOcid);

        CharacterOcidResponse response = realClient.getOcidByCharacterName(characterName);
        if (response != null) ocidCache.put(characterName, response.getOcid());
        return response;
    }

    @Override
    @Transactional // DB 저장이 포함되므로 트랜잭션 처리
    public EquipmentResponse getItemDataByOcid(String ocid) {
        // 1. [L2 Cache 조회] DB 확인
        return equipmentRepository.findById(ocid)
                .filter(this::isValidCache)
                .map(this::convertToResponse)
                .orElseGet(() -> synchronizedFetch(ocid));
    }

    private EquipmentResponse synchronizedFetch(String ocid) {
        ReentrantLock lock = mutexMap.computeIfAbsent(ocid, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-Check (락 획득 후 재조회)
            return equipmentRepository.findById(ocid)
                    .filter(this::isValidCache)
                    .map(this::convertToResponse)
                    .orElseGet(() -> {
                        log.info("🔄 [Proxy] 캐시 만료 혹은 없음. API 호출 진행: {}", ocid);
                        EquipmentResponse response = realClient.getItemDataByOcid(ocid);
                        saveToDb(ocid, response);
                        return response;
                    });
        } finally {
            lock.unlock();
            // 메모리 누수 방지: 락 대기열이 없으면 맵에서 삭제 고려 가능
        }
    }

    // --- 내부 헬퍼 메서드 (Provider에서 이관된 로직) ---

    private boolean isValidCache(CharacterEquipment entity) {
        return entity != null && entity.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(15));
    }

    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            byte[] rawData = entity.getRawData();
            String json = isGzip(rawData) ? GzipUtils.decompress(rawData) : new String(rawData, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (JsonProcessingException e) {
            throw new EquipmentDataProcessingException("캐시 데이터 파싱 실패", e);
        }
    }

    private void saveToDb(String ocid, EquipmentResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            byte[] rawData = USE_COMPRESSION ? GzipUtils.compress(json) : json.getBytes(StandardCharsets.UTF_8);

            CharacterEquipment entity = equipmentRepository.findById(ocid)
                    .orElse(new CharacterEquipment(ocid, rawData));

            entity.updateData(rawData);
            equipmentRepository.saveAndFlush(entity);
        } catch (JsonProcessingException e) {
            throw new EquipmentDataProcessingException("데이터 직렬화 실패", e);
        }
    }

    private boolean isGzip(byte[] data) {
        return data != null && data.length > 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B;
    }
}