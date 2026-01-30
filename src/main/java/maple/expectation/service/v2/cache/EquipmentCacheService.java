package maple.expectation.service.v2.cache;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.worker.EquipmentDbWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Equipment 캐시 서비스
 *
 * <h4>P1-4: Cache 필드 캐싱</h4>
 * <p>매 호출마다 cacheManager.getCache() 반복 호출 대신
 * 생성자에서 1회 조회하여 필드에 캐싱</p>
 */
@Slf4j
@Service
public class EquipmentCacheService {
    private final Cache tieredEquipmentCache;     // P1-4: Tiered (L1+L2) 필드 캐싱
    private final Cache l1OnlyEquipmentCache;     // P1-4: L1 Only 필드 캐싱
    private final EquipmentDbWorker dbWorker;
    private final LogicExecutor executor;

    private static final String CACHE_NAME = "equipment";
    private static final EquipmentResponse NULL_MARKER = new EquipmentResponse();
    static { NULL_MARKER.setCharacterClass("NEGATIVE_MARKER"); }

    public EquipmentCacheService(
            CacheManager cacheManager,
            @Qualifier("expectationL1CacheManager") CacheManager l1CacheManager,
            EquipmentDbWorker dbWorker,
            LogicExecutor executor) {
        this.tieredEquipmentCache = Objects.requireNonNull(
                cacheManager.getCache(CACHE_NAME),
                "Tiered equipment cache must not be null");
        this.l1OnlyEquipmentCache = Objects.requireNonNull(
                l1CacheManager.getCache(CACHE_NAME),
                "L1-only equipment cache must not be null");
        this.dbWorker = dbWorker;
        this.executor = executor;
    }

    /**
     * 캐시 조회 로직 (P1-4: 필드 캐싱 적용)
     */
    public Optional<EquipmentResponse> getValidCache(String ocid) {
        return executor.execute(() -> {
            EquipmentResponse cached = tieredEquipmentCache.get(ocid, EquipmentResponse.class);
            if (cached != null && !"NEGATIVE_MARKER".equals(cached.getCharacterClass())) {
                return Optional.of(cached);
            }
            return Optional.empty();
        }, TaskContext.of("EquipmentCache", "GetValid", ocid));
    }

    public boolean hasNegativeCache(String ocid) {
        return executor.executeOrDefault(() -> {
            EquipmentResponse cached = tieredEquipmentCache.get(ocid, EquipmentResponse.class);
            return cached != null && "NEGATIVE_MARKER".equals(cached.getCharacterClass());
        }, false, TaskContext.of("EquipmentCache", "CheckNegative", ocid));
    }

    /**
     * 캐시 저장 및 비동기 DB persist (P1-4: 필드 캐싱 적용)
     */
    public void saveCache(String ocid, EquipmentResponse response) {
        TaskContext context = TaskContext.of("EquipmentCache", "Save", ocid);

        executor.executeOrCatch(
                () -> {
                    performCaching(ocid, response);
                    triggerAsyncPersist(ocid, response);
                    return null;
                },
                e -> handleSaveFailure(ocid, e),
                context
        );
    }

    /**
     * 헬퍼 1: 캐시 저장 로직 (Null Marker 처리 포함, P1-4: 필드 캐싱)
     */
    private void performCaching(String ocid, EquipmentResponse response) {
        tieredEquipmentCache.put(ocid, (response == null) ? NULL_MARKER : response);
    }

    /**
     * 헬퍼 2: 비동기 DB 저장 트리거 및 내부 예외 관측
     */
    private void triggerAsyncPersist(String ocid, EquipmentResponse response) {
        if (response == null) return;

        dbWorker.persist(ocid, response)
                .exceptionally(ex -> observeAsyncError(ocid, ex));
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
        if (e instanceof IllegalStateException) {
            log.warn("[Equipment Cache] Shutdown 진행 중 - DB 저장 스킵(캐시만 유지): {}", ocid);
            return null;
        }
        throw (RuntimeException) e;
    }

    // ==================== L1-only API (Expectation 경로 전용) ====================

    /**
     * L1 캐시에서만 조회 (Expectation 경로 전용 - L2 우회, P1-4: 필드 캐싱)
     */
    public Optional<EquipmentResponse> getValidCacheL1Only(String ocid) {
        return executor.execute(() -> {
            EquipmentResponse cached = l1OnlyEquipmentCache.get(ocid, EquipmentResponse.class);
            if (cached != null && !"NEGATIVE_MARKER".equals(cached.getCharacterClass())) {
                return Optional.of(cached);
            }
            return Optional.empty();
        }, TaskContext.of("EquipmentCache", "GetValidL1Only", ocid));
    }

    /**
     * L1 캐시에만 저장 (Expectation 경로 전용 - L2 우회, DB 저장도 스킵, P1-4: 필드 캐싱)
     */
    public void saveCacheL1Only(String ocid, EquipmentResponse response) {
        executor.executeVoid(() -> {
            l1OnlyEquipmentCache.put(ocid, (response == null) ? NULL_MARKER : response);
            log.debug("[EquipmentCache] L1-only save: {}", ocid);
        }, TaskContext.of("EquipmentCache", "SaveL1Only", ocid));
    }
}
