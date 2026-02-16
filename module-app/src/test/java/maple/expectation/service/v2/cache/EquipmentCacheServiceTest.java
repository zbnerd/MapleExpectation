package maple.expectation.service.v2.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.worker.EquipmentDbWorker;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * EquipmentCacheService 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 장비 캐시 서비스를 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>L1+L2 Tiered 캐시 조회
 *   <li>L1-only 캐시 조회/저장
 *   <li>Negative 캐시 마커 처리
 *   <li>비동기 DB 저장 트리거
 * </ul>
 */
@Tag("unit")
class EquipmentCacheServiceTest {

  private static final String OCID = "test-ocid-123";
  private static final String CACHE_NAME = "equipment";

  private CacheManager cacheManager;
  private CacheManager l1CacheManager;
  private EquipmentDbWorker dbWorker;
  private LogicExecutor executor;
  private Cache tieredCache;
  private Cache l1Cache;

  private EquipmentCacheService cacheService;

  @BeforeEach
  void setUp() {
    cacheManager = mock(CacheManager.class);
    l1CacheManager = mock(CacheManager.class);
    dbWorker = mock(EquipmentDbWorker.class);
    executor = TestLogicExecutors.passThrough();
    tieredCache = mock(Cache.class);
    l1Cache = mock(Cache.class);

    // P1-4: 생성자에서 Cache 필드 캐싱하므로 getCache() stub 선행 필수
    given(cacheManager.getCache(CACHE_NAME)).willReturn(tieredCache);
    given(l1CacheManager.getCache(CACHE_NAME)).willReturn(l1Cache);

    cacheService = new EquipmentCacheService(cacheManager, l1CacheManager, dbWorker, executor);
  }

  @Nested
  @DisplayName("getValidCache (Tiered)")
  class GetValidCacheTest {

    @Test
    @DisplayName("캐시 히트 시 응답 반환")
    void shouldReturnCachedResponse() {
      // given
      EquipmentResponse response = createEquipmentResponse("Bishop");
      given(tieredCache.get(OCID, EquipmentResponse.class)).willReturn(response);

      // when
      Optional<EquipmentResponse> result = cacheService.getValidCache(OCID);

      // then
      assertThat(result).isPresent();
      assertThat(result.get().getCharacterClass()).isEqualTo("Bishop");
    }

    @Test
    @DisplayName("캐시 미스 시 empty 반환")
    void shouldReturnEmptyOnCacheMiss() {
      // given
      given(tieredCache.get(OCID, EquipmentResponse.class)).willReturn(null);

      // when
      Optional<EquipmentResponse> result = cacheService.getValidCache(OCID);

      // then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Negative 마커는 empty 반환")
    void shouldReturnEmptyForNegativeMarker() {
      // given
      EquipmentResponse negativeMarker = createEquipmentResponse("NEGATIVE_MARKER");
      given(tieredCache.get(OCID, EquipmentResponse.class)).willReturn(negativeMarker);

      // when
      Optional<EquipmentResponse> result = cacheService.getValidCache(OCID);

      // then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("P1-4: 캐시 없음 시 생성자에서 NPE (fail-fast)")
    void shouldThrowNPEWhenCacheNull() {
      // given
      given(cacheManager.getCache(CACHE_NAME)).willReturn(null);

      // when & then: P1-4 Objects.requireNonNull fail-fast
      assertThatThrownBy(
              () -> new EquipmentCacheService(cacheManager, l1CacheManager, dbWorker, executor))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("Tiered cache 'equipment' must not be null");
    }
  }

  @Nested
  @DisplayName("hasNegativeCache")
  class HasNegativeCacheTest {

    @Test
    @DisplayName("Negative 마커 존재 시 true")
    void shouldReturnTrueForNegativeMarker() {
      // given
      EquipmentResponse negativeMarker = createEquipmentResponse("NEGATIVE_MARKER");
      given(tieredCache.get(OCID, EquipmentResponse.class)).willReturn(negativeMarker);

      // when
      boolean result = cacheService.hasNegativeCache(OCID);

      // then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("일반 캐시 존재 시 false")
    void shouldReturnFalseForNormalCache() {
      // given
      EquipmentResponse response = createEquipmentResponse("Bishop");
      given(tieredCache.get(OCID, EquipmentResponse.class)).willReturn(response);

      // when
      boolean result = cacheService.hasNegativeCache(OCID);

      // then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("캐시 없음 시 false")
    void shouldReturnFalseWhenNoCache() {
      // given
      given(tieredCache.get(OCID, EquipmentResponse.class)).willReturn(null);

      // when
      boolean result = cacheService.hasNegativeCache(OCID);

      // then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("saveCache")
  class SaveCacheTest {

    @Test
    @DisplayName("정상 응답 저장 및 비동기 DB 저장 트리거")
    void shouldSaveCacheAndTriggerDbPersist() {
      // given
      EquipmentResponse response = createEquipmentResponse("Bishop");
      given(dbWorker.persist(eq(OCID), eq(response)))
          .willReturn(CompletableFuture.completedFuture(null));

      // when
      cacheService.saveCache(OCID, response);

      // then
      verify(tieredCache).put(OCID, response);
      verify(dbWorker).persist(OCID, response);
    }

    @Test
    @DisplayName("null 응답 시 Negative 마커 저장")
    void shouldSaveNegativeMarkerForNullResponse() {
      // when
      cacheService.saveCache(OCID, null);

      // then
      verify(tieredCache)
          .put(
              eq(OCID),
              argThat(
                  obj ->
                      obj instanceof EquipmentResponse
                          && "NEGATIVE_MARKER"
                              .equals(((EquipmentResponse) obj).getCharacterClass())));
      verify(dbWorker, never()).persist(anyString(), any());
    }
  }

  @Nested
  @DisplayName("getValidCacheL1Only")
  class GetValidCacheL1OnlyTest {

    @Test
    @DisplayName("L1 캐시 히트 시 응답 반환")
    void shouldReturnL1CachedResponse() {
      // given
      EquipmentResponse response = createEquipmentResponse("Aran");
      given(l1Cache.get(OCID, EquipmentResponse.class)).willReturn(response);

      // when
      Optional<EquipmentResponse> result = cacheService.getValidCacheL1Only(OCID);

      // then
      assertThat(result).isPresent();
      assertThat(result.get().getCharacterClass()).isEqualTo("Aran");
    }

    @Test
    @DisplayName("L1 캐시 미스 시 empty 반환")
    void shouldReturnEmptyOnL1Miss() {
      // given
      given(l1Cache.get(OCID, EquipmentResponse.class)).willReturn(null);

      // when
      Optional<EquipmentResponse> result = cacheService.getValidCacheL1Only(OCID);

      // then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("L1에 Negative 마커 시 empty 반환")
    void shouldReturnEmptyForL1NegativeMarker() {
      // given
      EquipmentResponse negativeMarker = createEquipmentResponse("NEGATIVE_MARKER");
      given(l1Cache.get(OCID, EquipmentResponse.class)).willReturn(negativeMarker);

      // when
      Optional<EquipmentResponse> result = cacheService.getValidCacheL1Only(OCID);

      // then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("P1-4: L1 캐시 없음 시 생성자에서 NPE (fail-fast)")
    void shouldThrowNPEWhenL1CacheNull() {
      // given
      given(l1CacheManager.getCache(CACHE_NAME)).willReturn(null);

      // when & then: P1-4 Objects.requireNonNull fail-fast
      assertThatThrownBy(
              () -> new EquipmentCacheService(cacheManager, l1CacheManager, dbWorker, executor))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("L1-only cache 'equipment' must not be null");
    }
  }

  @Nested
  @DisplayName("saveCacheL1Only")
  class SaveCacheL1OnlyTest {

    @Test
    @DisplayName("L1 캐시에만 저장 (DB 저장 스킵)")
    void shouldSaveToL1OnlyWithoutDbPersist() {
      // given
      EquipmentResponse response = createEquipmentResponse("Phantom");

      // when
      cacheService.saveCacheL1Only(OCID, response);

      // then
      verify(l1Cache).put(OCID, response);
      verify(tieredCache, never()).put(anyString(), any());
      verify(dbWorker, never()).persist(anyString(), any());
    }

    @Test
    @DisplayName("null 응답 시 L1에 Negative 마커 저장")
    void shouldSaveNegativeMarkerToL1ForNullResponse() {
      // when
      cacheService.saveCacheL1Only(OCID, null);

      // then
      verify(l1Cache)
          .put(
              eq(OCID),
              argThat(
                  obj ->
                      obj instanceof EquipmentResponse
                          && "NEGATIVE_MARKER"
                              .equals(((EquipmentResponse) obj).getCharacterClass())));
    }
  }

  // ==================== Helper Methods ====================

  private EquipmentResponse createEquipmentResponse(String characterClass) {
    EquipmentResponse response = new EquipmentResponse();
    response.setCharacterClass(characterClass);
    return response;
  }
}
