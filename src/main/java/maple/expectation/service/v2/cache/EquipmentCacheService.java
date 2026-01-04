package maple.expectation.service.v2.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
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
    private final LogicExecutor executor; // ✅ 지능형 실행기 추가

    private static final EquipmentResponse NULL_MARKER = new EquipmentResponse();
    static { NULL_MARKER.setCharacterClass("NEGATIVE_MARKER"); }

    /**
     * ✅ [관측성 확보] 캐시 조회 로직 평탄화
     */
    public Optional<EquipmentResponse> getValidCache(String ocid) {
        return executor.execute(() -> { //
            Cache cache = cacheManager.getCache("equipment");
            if (cache == null) return Optional.<EquipmentResponse>empty();

            EquipmentResponse cached = cache.get(ocid, EquipmentResponse.class);
            if (cached != null && !"NEGATIVE_MARKER".equals(cached.getCharacterClass())) {
                return Optional.of(cached);
            }
            return Optional.empty();
        }, TaskContext.of("EquipmentCache", "GetValid", ocid)); //
    }

    public boolean hasNegativeCache(String ocid) {
        return executor.executeOrDefault(() -> { //
            Cache cache = cacheManager.getCache("equipment");
            EquipmentResponse cached = (cache != null) ? cache.get(ocid, EquipmentResponse.class) : null;
            return cached != null && "NEGATIVE_MARKER".equals(cached.getCharacterClass());
        }, false, TaskContext.of("EquipmentCache", "CheckNegative", ocid));
    }

    /**
     * ✅  try-catch 및 비동기 예외 처리 평탄화
     */
    public void saveCache(String ocid, EquipmentResponse response) {
        TaskContext context = TaskContext.of("EquipmentCache", "Save", ocid); //

        // [패턴 5] executeWithRecovery: 정상 흐름과 셧다운 복구 시나리오를 분리
        executor.executeWithRecovery(
                () -> {
                    performCaching(ocid, response);  // 1. 캐시 저장 로직
                    triggerAsyncPersist(ocid, response); // 2. 비동기 저장 트리거
                    return null;
                },
                e -> handleSaveFailure(ocid, e), // 3. 장애 및 셧다운 대응 로직 격리
                context
        );
    }

    /**
     * 헬퍼 1: 캐시 저장 로직 (Null Marker 처리 포함)
     */
    private void performCaching(String ocid, EquipmentResponse response) {
        Cache cache = cacheManager.getCache("equipment");
        if (cache != null) {
            cache.put(ocid, (response == null) ? NULL_MARKER : response);
        }
    }

    /**
     * 헬퍼 2: 비동기 DB 저장 트리거 및 내부 예외 관측
     */
    private void triggerAsyncPersist(String ocid, EquipmentResponse response) {
        if (response == null) return;

        dbWorker.persist(ocid, response)
                .exceptionally(ex -> observeAsyncError(ocid, ex)); //
    }

    /**
     * 헬퍼 3: 비동기 에러 관측 (executor 내부 중첩 방지용)
     */
    private Void observeAsyncError(String ocid, Throwable ex) {
        executor.executeVoid(
                () -> { throw new RuntimeException(ex); },
                TaskContext.of("EquipmentDbWorker", "AsyncPersistFailed", ocid)
        );
        return null;
    }

    /**
     * 헬퍼 4: 셧다운 및 공통 예외 핸들러
     */
    private EquipmentResponse handleSaveFailure(String ocid, Throwable e) {
        // Shutdown 진행 중인 경우(IllegalStateException)는 정상적인 흐름으로 간주하여 복구
        if (e instanceof IllegalStateException) {
            log.warn("⚠️ [Equipment Cache] Shutdown 진행 중 - DB 저장 스킵(캐시만 유지): {}", ocid);
            return null;
        }
        // 그 외의 런타임 예외는 그대로 전파
        throw (RuntimeException) e;
    }
}