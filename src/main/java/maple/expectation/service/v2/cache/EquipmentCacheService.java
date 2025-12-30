package maple.expectation.service.v2.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.worker.EquipmentDbWorker;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentCacheService {
    private final maple.expectation.repository.v2.CharacterEquipmentRepository repository;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final EquipmentDbWorker dbWorker;

    // 🚀 [429 방어] 데이터 없음(404) 상태를 나타내는 내부 마커
    private static final EquipmentResponse NULL_MARKER = new EquipmentResponse();

    @Transactional(readOnly = true)
    public Optional<EquipmentResponse> getValidCache(String ocid) {
        Cache tieredCache = cacheManager.getCache("equipment");
        EquipmentResponse cached = tieredCache.get(ocid, EquipmentResponse.class);

        if (cached != null) {
            if (isNullMarker(cached)) return Optional.empty();
            return Optional.of(cached);
        }

        return repository.findById(ocid)
                .filter(e -> e.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(15)))
                .map(entity -> {
                    EquipmentResponse res = convertToResponse(entity);
                    if (res != null) tieredCache.put(ocid, res);
                    return res;
                });
    }

    public void saveCache(String ocid, EquipmentResponse response) {
        if (response == null) {
            cacheManager.getCache("equipment").put(ocid, NULL_MARKER);
            log.warn("🚫 [Negative Cache Saved] 존재하지 않는 유저: {}", ocid);
            return;
        }

        try {
            cacheManager.getCache("equipment").put(ocid, response);
            CompletableFuture.runAsync(() -> dbWorker.persist(ocid, response));
        } catch (Exception e) {
            log.error("❌ 캐시 저장 오류 : {}", ocid, e);
        }
    }

    // 🚀 Aspect에서 컴파일 에러가 나지 않도록 public으로 선언
    public boolean isNullMarker(EquipmentResponse res) {
        return res == NULL_MARKER || (res != null && res.getCharacterClass() == null);
    }

    // 🚀 Aspect에서 컴파일 에러가 나지 않도록 public으로 선언
    public boolean hasNegativeCache(String ocid) {
        Cache cache = cacheManager.getCache("equipment");
        if (cache == null) return false;
        EquipmentResponse res = cache.get(ocid, EquipmentResponse.class);
        return isNullMarker(res);
    }

    private EquipmentResponse convertToResponse(maple.expectation.domain.v2.CharacterEquipment entity) {
        try {
            return objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class);
        } catch (Exception e) { return null; }
    }
}