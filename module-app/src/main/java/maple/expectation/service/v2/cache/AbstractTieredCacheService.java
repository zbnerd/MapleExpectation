package maple.expectation.service.v2.cache;

import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Abstract Tiered Cache Service Template (Issue #24)
 *
 * <h3>Purpose</h3>
 *
 * <p>L1 → L2 → Warm-up 패턴의 중복을 제거하기 위한 추상 템플릿입니다.
 *
 * <h3>Common Patterns Extracted</h3>
 *
 * <ul>
 *   <li>L1 → L2 계층적 캐시 조회
 *   <li>L2 hit 시 L1 warm-up *
 *   <li>L2 → L1 저장 순서 (불변식 준수)
 *   <li>LogicExecutor 래핑
 *   <li>캐시 필드 캐싱 (P1-4)
 * </ul>
 *
 * <h3>Usage Example</h3>
 *
 * <pre>{@code
 * public class MyCacheService extends AbstractTieredCacheService<MyResponse> {
 *
 *   private static final String CACHE_NAME = "myCache";
 *   private static final MyResponse NULL_MARKER = new MyResponse();
 *
 *   public MyCacheService(
 *       CacheManager tieredCacheManager,
 *       @Qualifier("expectationL1CacheManager") CacheManager l1CacheManager,
 *       LogicExecutor executor) {
 *     super(CACHE_NAME, tieredCacheManager, l1CacheManager, executor);
 *   }
 *
 *   public Optional<MyResponse> getValidCache(String key) {
 *     return getFromTieredCache(key, MyResponse.class);
 *   }
 *
 *   public void saveCache(String key, MyResponse response) {
 *     saveToTieredCache(key, response, NULL_MARKER);
 *   }
 *
 *   public Optional<MyResponse> getValidCacheL1Only(String key) {
 *     return getFromL1Only(key, MyResponse.class);
 *   }
 *
 *   public void saveCacheL1Only(String key, MyResponse response) {
 *     saveToL1Only(key, response, NULL_MARKER);
 *   }
 *
 *   // Cache-specific implementations
 *   @Override
 *   protected boolean isValidNullMarker(MyResponse value) {
 *     return value != null && "NEGATIVE_MARKER".equals(value.getStatus());
 *   }
 * }
 * }</pre>
 *
 * @param <T> 캐시할 데이터 타입
 */
@Slf4j
public abstract class AbstractTieredCacheService<T> {

  protected final Cache tieredCache;
  protected final Cache l1OnlyCache;
  protected final LogicExecutor executor;
  protected final String cacheName;

  /**
   * 생성자: 캐시 매니저에서 캐시 인스턴스를 조회하여 필드에 캐싱 (P1-4)
   *
   * @param cacheName 캐시 이름
   * @param tieredCacheManager L1+L2 Tiered 캐시 매니저
   * @param l1CacheManager L1-only 캐시 매니저
   * @param executor LogicExecutor
   */
  protected AbstractTieredCacheService(
      String cacheName,
      CacheManager tieredCacheManager,
      CacheManager l1CacheManager,
      LogicExecutor executor) {
    this.cacheName = cacheName;
    this.tieredCache =
        Objects.requireNonNull(
            tieredCacheManager.getCache(cacheName),
            String.format("Tiered cache '%s' must not be null", cacheName));
    this.l1OnlyCache =
        Objects.requireNonNull(
            l1CacheManager.getCache(cacheName),
            String.format("L1-only cache '%s' must not be null", cacheName));
    this.executor = executor;
  }

  // ==================== Template Methods (Subclasses MUST Implement) ====================

  /**
   * Null Marker 여부를 판단하는 메서드 (서브클래스 구현)
   *
   * @param value 캐시된 값
   * @return true: Null Marker (캐시 미스로 간주), false: 정상 값
   */
  protected abstract boolean isValidNullMarker(T value);

  /**
   * 캐시 키를 로그용으로 마스킹하는 메서드 (선택 구현)
   *
   * @param key 원본 캐시 키
   * @return 마스킹된 키 (기본: 원본 반환)
   */
  protected String maskKey(String key) {
    return key;
  }

  /**
   * L2 저장 실패 시 로그를 기록하는 메서드 (선택 구현)
   *
   * @param key 캐시 키
   * @param value 저장하려던 값
   * @param error 발생한 예외
   */
  protected void logL2SaveFailure(String key, T value, Throwable error) {
    log.warn(
        "[Cache] L2 SAVE FAIL | cache={} | key={} | err={}",
        cacheName,
        maskKey(key),
        error.toString());
  }

  /**
   * L1 warm-up 완료 로그를 기록하는 메서드 (선택 구현)
   *
   * @param key 캐시 키
   */
  protected void logL1WarmupComplete(String key) {
    log.debug("[Cache] L1 warm-up completed | cache={} | key={}", cacheName, maskKey(key));
  }

  // ==================== Core Operations (L1 → L2 → Warm-up) ====================

  /**
   * Tiered 캐시에서 유효한 값 조회 (L1 → L2 → Warm-up)
   *
   * <h4>Lookup Flow</h4>
   *
   * <ol>
   *   <li>L1 조회 (tieredCache의 L1 계층)
   *   <li>L1 미스 시 L2 조회
   *   <li>L2 hit 시 L1 warm-up 수행
   *   <li>Null Marker 필터링
   * </ol>
   *
   * @param key 캐시 키
   * @param type 캐시된 값의 타입
   * @return 캐시된 값 (없으면 empty)
   */
  protected Optional<T> getFromTieredCache(String key, Class<T> type) {
    return executor.execute(
        () -> {
          // 1. L1 조회 (TieredCache의 L1 계층)
          T cached = tieredCache.get(key, type);
          if (cached != null && !isValidNullMarker(cached)) {
            logCacheHit("L1", key, cached);
            return Optional.of(cached);
          }

          // 2. L2 조회 (TieredCache의 L2 계층은 자동으로 수행됨)
          // Note: Spring TieredCache가 L1→L2를 자동으로 처리하므로 별도 조회 불필요
          // L2 hit 시 L1 warm-up은 TieredCache가 자동으로 수행
          logCacheMiss(key);
          return Optional.empty();
        },
        TaskContext.of(cacheName, "GetFromTiered", maskKey(key)));
  }

  /**
   * L1-only 캐시에서 유효한 값 조회 (L2 우회)
   *
   * @param key 캐시 키
   * @param type 캐시된 값의 타입
   * @return 캐시된 값 (없으면 empty)
   */
  protected Optional<T> getFromL1Only(String key, Class<T> type) {
    return executor.execute(
        () -> {
          T cached = l1OnlyCache.get(key, type);
          if (cached != null && !isValidNullMarker(cached)) {
            logCacheHit("L1-Only", key, cached);
            return Optional.of(cached);
          }
          logCacheMiss(key);
          return Optional.empty();
        },
        TaskContext.of(cacheName, "GetFromL1Only", maskKey(key)));
  }

  /**
   * Tiered 캐시에 값 저장 (L2 → L1 순서)
   *
   * <h4>저장 순서 (불변식)</h4>
   *
   * <ol>
   *   <li>L2에 먼저 저장 (Redis)
   *   <li>L1에 저장 (로컬 캐시)
   * </ol>
   *
   * <p>이 순서를 지켜야 L1만 stale data가 존재하는 상황을 방지할 수 있습니다.
   *
   * @param key 캐시 키
   * @param value 저장할 값
   * @param nullMarker null 대신 사용할 마커 객체
   */
  protected void saveToTieredCache(String key, T value, T nullMarker) {
    executor.executeVoid(
        () -> {
          T valueToStore = (value == null) ? nullMarker : value;
          tieredCache.put(key, valueToStore);
          logCacheSave("Tiered", key, valueToStore);
        },
        TaskContext.of(cacheName, "SaveToTiered", maskKey(key)));
  }

  /**
   * L1-only 캐시에 값 저장 (L2 우회, DB 저장도 스킵)
   *
   * @param key 캐시 키
   * @param value 저장할 값
   * @param nullMarker null 대신 사용할 마커 객체
   */
  protected void saveToL1Only(String key, T value, T nullMarker) {
    executor.executeVoid(
        () -> {
          T valueToStore = (value == null) ? nullMarker : value;
          l1OnlyCache.put(key, valueToStore);
          logCacheSave("L1-Only", key, valueToStore);
        },
        TaskContext.of(cacheName, "SaveToL1Only", maskKey(key)));
  }

  /**
   * 캐시 키 생성 (형식: {cacheName}:v1:{keyParts})
   *
   * @param keyParts 키 구성 요소들
   * @return 포맷된 캐시 키
   */
  protected String buildCacheKey(String... keyParts) {
    return String.format("%s:v1:%s", cacheName, String.join(":", keyParts));
  }

  /**
   * 캐시 키 생성 (버전 지정)
   *
   * @param version 캐시 버전
   * @param keyParts 키 구성 요소들
   * @return 포맷된 캐시 키
   */
  protected String buildCacheKey(String version, String... keyParts) {
    return String.format("%s:%s:%s", cacheName, version, String.join(":", keyParts));
  }

  // ==================== Logging Helpers ====================

  /** 캐시 히트 로그 (서브클래스에서 오버라이드하여 커스터마이징 가능) */
  protected void logCacheHit(String layer, String key, T value) {
    log.info("[Cache] {} HIT | cache={} | key={}", layer, cacheName, maskKey(key));
  }

  /** 캐시 미스 로그 */
  protected void logCacheMiss(String key) {
    log.info("[Cache] MISS | cache={} | key={}", cacheName, maskKey(key));
  }

  /** 캐시 저장 로그 */
  protected void logCacheSave(String layer, String key, T value) {
    log.debug("[Cache] {} SAVE | cache={} | key={}", layer, cacheName, maskKey(key));
  }

  /** 캐시 무효화 로그 */
  protected void logCacheEvict(String key) {
    log.debug("[Cache] EVICT | cache={} | key={}", cacheName, maskKey(key));
  }

  // ==================== Cache Invalidation ====================

  /**
   * Tiered 캐시에서 키 무효화
   *
   * @param key 캐시 키
   */
  public void evictFromTieredCache(String key) {
    executor.executeVoid(
        () -> {
          tieredCache.evict(key);
          logCacheEvict(key);
        },
        TaskContext.of(cacheName, "EvictTiered", maskKey(key)));
  }

  /**
   * L1-only 캐시에서 키 무효화
   *
   * @param key 캐시 키
   */
  public void evictFromL1OnlyCache(String key) {
    executor.executeVoid(
        () -> {
          l1OnlyCache.evict(key);
          logCacheEvict(key);
        },
        TaskContext.of(cacheName, "EvictL1Only", maskKey(key)));
  }

  /** Tiered 캐시 전체 무효화 */
  public void clearTieredCache() {
    executor.executeVoid(
        () -> {
          tieredCache.clear();
          log.info("[Cache] CLEAR | cache={} | layer=Tiered", cacheName);
        },
        TaskContext.of(cacheName, "ClearTiered"));
  }

  /** L1-only 캐시 전체 무효화 */
  public void clearL1OnlyCache() {
    executor.executeVoid(
        () -> {
          l1OnlyCache.clear();
          log.info("[Cache] CLEAR | cache={} | layer=L1-Only", cacheName);
        },
        TaskContext.of(cacheName, "ClearL1Only"));
  }
}
