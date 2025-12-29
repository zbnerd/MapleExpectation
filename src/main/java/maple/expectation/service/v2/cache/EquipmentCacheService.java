package maple.expectation.service.v2.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
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
    private final CharacterEquipmentRepository repository;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    /**
     * 캐시 계층을 우선 확인 (변경 없음)
     */
    @Transactional(readOnly = true)
    public Optional<EquipmentResponse> getValidCache(String ocid) {
        Cache tieredCache = cacheManager.getCache("equipment");
        EquipmentResponse cached = tieredCache.get(ocid, EquipmentResponse.class);

        if (cached != null) {
            log.debug("⚡ [Tiered Cache Hit] : {}", ocid);
            return Optional.of(cached);
        }

        return repository.findById(ocid)
                .filter(e -> e.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(15)))
                .map(this::convertToResponse)
                .map(res -> {
                    tieredCache.put(ocid, res);
                    return res;
                });
    }

    public Optional<EquipmentResponse> getLocalCacheOnly(String ocid) {
        Cache cache = cacheManager.getCache("equipment");
        EquipmentResponse response = cache.get(ocid, EquipmentResponse.class);
        return Optional.ofNullable(response);
    }

    /**
     * [최종 개선] 캐시는 즉시 갱신하고 DB 저장은 비동기로 처리합니다.
     */
    public void saveCache(String ocid, EquipmentResponse response) {
        try {
            // 1️⃣ [동기] Tiered Cache(L1, L2)를 즉시 업데이트
            // 이 시점 이후부터 "진성" 캐릭터는 DB에 없어도 캐시에서 조회 가능해집니다.
            cacheManager.getCache("equipment").put(ocid, response);
            log.debug("🚀 [Cache Warm-up Success] 캐시 우선 갱신 완료: {}", ocid);

            // 2️⃣ [비동기] DB 저장은 백그라운드 스레드에 위임
            // 호출 스레드(Request Thread)는 여기서 즉시 리턴되어 다음 요청을 받으러 갑니다.
            CompletableFuture.runAsync(() -> {
                try {
                    persistToDatabase(ocid, response);
                } catch (Exception e) {
                    log.error("❌ [Async DB Error] 비동기 DB 저장 실패: ocid={}", ocid, e);
                }
            });

        } catch (Exception e) {
            log.error("❌ 캐시 저장 로직 오류: ocid={}", ocid, e);
        }
    }

    /**
     * 실제 DB 저장 로직 (비동기 스레드에서 실행됨)
     */
    private void persistToDatabase(String ocid, EquipmentResponse response) throws Exception {
        String json = objectMapper.writeValueAsString(response);

        // 신규 캐릭터면 Insert, 기존 캐릭터면 Update를 자동으로 수행
        CharacterEquipment entity = repository.findById(ocid)
                .orElseGet(() -> CharacterEquipment.builder().ocid(ocid).build());

        entity.updateData(json);
        repository.saveAndFlush(entity); // 비동기이므로 즉시 반영(Flush) 권장

        log.info("💾 [Async DB Save] DB 영속화 완료: {}", ocid);
    }

    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            return objectMapper.readValue(entity.getJsonContent(), EquipmentResponse.class);
        } catch (Exception e) {
            log.error("❌ JSON 파싱 에러: ocid={}", entity.getOcid());
            return null;
        }
    }
}