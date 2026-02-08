package maple.expectation.service.v2.cache;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.CachePersistenceException;
import maple.expectation.global.error.exception.base.BaseException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.worker.EquipmentDbWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Equipment 캐시 서비스 (Issue #24: AbstractTieredCacheService 리팩토링)
 *
 * <h4>P1-4: Cache 필드 캐싱</h4>
 *
 * <p>AbstractTieredCacheService를 상속받아 L1→L2→Warm-up 패턴의 중복을 제거했습니다.
 *
 * <h4>추가 기능</h4>
 *
 * <ul>
 *   <li>비동기 DB 저장 (EquipmentDbWorker)
 *   <li>Negative 캐시 체크
 * </ul>
 */
@Slf4j
@Service
public class EquipmentCacheService extends AbstractTieredCacheService<EquipmentResponse> {

  private static final String CACHE_NAME = "equipment";
  private static final EquipmentResponse NULL_MARKER = new EquipmentResponse();

  static {
    NULL_MARKER.setCharacterClass("NEGATIVE_MARKER");
  }

  private final EquipmentDbWorker dbWorker;

  public EquipmentCacheService(
      CacheManager cacheManager,
      @Qualifier("expectationL1CacheManager") CacheManager l1CacheManager,
      EquipmentDbWorker dbWorker,
      LogicExecutor executor) {
    super(CACHE_NAME, cacheManager, l1CacheManager, executor);
    this.dbWorker = dbWorker;
  }

  // ==================== AbstractTieredCacheService Implementation ====================

  @Override
  protected boolean isValidNullMarker(EquipmentResponse value) {
    return value != null && "NEGATIVE_MARKER".equals(value.getCharacterClass());
  }

  // ==================== Public API ====================

  /** 캐시 조회 로직 (L1 → L2 → Warm-up) */
  public Optional<EquipmentResponse> getValidCache(String ocid) {
    return getFromTieredCache(ocid, EquipmentResponse.class);
  }

  /** Negative 캐시 존재 여부 확인 */
  public boolean hasNegativeCache(String ocid) {
    return executor.executeOrDefault(
        () -> {
          EquipmentResponse cached = tieredCache.get(ocid, EquipmentResponse.class);
          return cached != null && isValidNullMarker(cached);
        },
        false,
        TaskContext.of("EquipmentCache", "CheckNegative", ocid));
  }

  /** 캐시 저장 및 비동기 DB persist */
  public void saveCache(String ocid, EquipmentResponse response) {
    TaskContext context = TaskContext.of("EquipmentCache", "Save", ocid);

    executor.executeOrCatch(
        () -> {
          // 1. Tiered 캐시 저장 (L2 → L1)
          saveToTieredCache(ocid, response, NULL_MARKER);
          // 2. 비동기 DB 저장 트리거
          triggerAsyncPersist(ocid, response);
          return null;
        },
        e -> handleSaveFailure(ocid, e),
        context);
  }

  /** L1-only 캐시 조회 (Expectation 경로 전용 - L2 우회) */
  public Optional<EquipmentResponse> getValidCacheL1Only(String ocid) {
    return getFromL1Only(ocid, EquipmentResponse.class);
  }

  /** L1-only 캐시 저장 (Expectation 경로 전용 - L2 우회, DB 저장도 스킵) */
  public void saveCacheL1Only(String ocid, EquipmentResponse response) {
    saveToL1Only(ocid, response, NULL_MARKER);
  }

  // ==================== Equipment-Specific Logic (DB Persistence) ====================

  /** 비동기 DB 저장 트리거 및 내부 예외 관측 */
  private void triggerAsyncPersist(String ocid, EquipmentResponse response) {
    if (response == null) return;

    dbWorker.persist(ocid, response).exceptionally(ex -> observeAsyncError(ocid, ex));
  }

  /** 비동기 에러 관측 (executor 내부 중첩 방지용) */
  private Void observeAsyncError(String ocid, Throwable ex) {
    executor.executeVoid(
        () -> {
          throw new CachePersistenceException(ocid, ex);
        },
        TaskContext.of("EquipmentDbWorker", "AsyncPersistFailed", ocid));
    return null;
  }

  /** 셧다운 및 공통 예외 핸들러 */
  private EquipmentResponse handleSaveFailure(String ocid, Throwable e) {
    if (e instanceof IllegalStateException) {
      log.warn("[Equipment Cache] Shutdown 진행 중 - DB 저장 스킵(캐시만 유지): {}", ocid);
      return null;
    }
    if (e instanceof BaseException be) {
      throw be;
    }
    if (e instanceof RuntimeException re) {
      throw re;
    }
    throw new CachePersistenceException(ocid, e);
  }
}
