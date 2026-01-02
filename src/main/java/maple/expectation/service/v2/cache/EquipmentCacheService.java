package maple.expectation.service.v2.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.worker.EquipmentDbWorker;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentCacheService {
    private final CacheManager cacheManager;
    private final EquipmentDbWorker dbWorker;

    // 식별용 빈 객체
    private static final EquipmentResponse NULL_MARKER = new EquipmentResponse();
    static { NULL_MARKER.setCharacterClass("NEGATIVE_MARKER"); }

    public Optional<EquipmentResponse> getValidCache(String ocid) {
        Cache cache = cacheManager.getCache("equipment");
        EquipmentResponse cached = cache.get(ocid, EquipmentResponse.class);

        if (cached != null && !"NEGATIVE_MARKER".equals(cached.getCharacterClass())) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    public boolean hasNegativeCache(String ocid) {
        EquipmentResponse cached = cacheManager.getCache("equipment").get(ocid, EquipmentResponse.class);
        return cached != null && "NEGATIVE_MARKER".equals(cached.getCharacterClass());
    }

    public void saveCache(String ocid, EquipmentResponse response) {
        Cache cache = cacheManager.getCache("equipment");
        if (response == null) {
            cache.put(ocid, NULL_MARKER); // 10분간 "없는 놈"으로 기억 (TTL은 캐시 설정 따름)
            return;
        }
        cache.put(ocid, response);

        // 비동기 DB 저장 (Graceful Shutdown 추적 포함)
        try {
            dbWorker.persist(ocid, response)
                    .exceptionally(throwable -> {
                        log.error("❌ [Equipment Cache] 비동기 저장 실패: {}", ocid, throwable);
                        return null;
                    });
        } catch (IllegalStateException e) {
            // Shutdown 진행 중인 경우 - 캐시만 저장하고 DB 저장은 스킵
            log.warn("⚠️ [Equipment Cache] Shutdown 진행 중 - DB 저장 스킵: {}", ocid);
        }
    }
}