package maple.expectation.service.v4.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.error.exception.CacheDataNotFoundException;
import maple.expectation.error.exception.EquipmentDataProcessingException;
import maple.expectation.infrastructure.cache.TieredCacheManager;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.util.GzipUtils;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

/**
 * 기대값 캐시 코디네이터 (P1-5: God Class 분해)
 *
 * <h3>책임</h3>
 *
 * <ul>
 *   <li>Singleflight 패턴: TieredCache.get(key, Callable) 캐시 조회/저장
 *   <li>GZIP+Base64 압축/해제
 *   <li>L1 Fast Path 직접 조회
 *   <li>fromCache 플래그 관리
 * </ul>
 */
@Slf4j
@Component
public class ExpectationCacheCoordinator {

  private static final String CACHE_NAME = "expectationV4";

  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;
  private final Cache expectationCache;
  private final TieredCacheManager tieredCacheManager;
  private final MeterRegistry meterRegistry;

  public ExpectationCacheCoordinator(
      LogicExecutor executor, ObjectMapper objectMapper, TieredCacheManager tieredCacheManager) {
    this.executor = executor;
    this.objectMapper = objectMapper;
    this.tieredCacheManager = tieredCacheManager;
    this.expectationCache = tieredCacheManager.getCache(CACHE_NAME);
    this.meterRegistry = tieredCacheManager.getMeterRegistry();
  }

  /**
   * Singleflight 패턴으로 기대값 조회 또는 계산 (#262)
   *
   * <h3>핵심 원칙 (#262 Fix)</h3>
   *
   * <ul>
   *   <li>캐시 히트: 압축 해제 후 반환 (계산 절대 금지)
   *   <li>캐시 미스: Callable 내에서만 계산
   *   <li>압축 해제 실패: 예외 발생 (재계산 X)
   * </ul>
   *
   * @param userIgn 캐릭터 IGN
   * @param force true: 캐시 무시, false: Singleflight 캐시 사용
   * @param calculator 캐시 미스 시 실행될 계산 로직
   * @return 기대값 응답
   */
  public EquipmentExpectationResponseV4 getOrCalculate(
      String userIgn, boolean force, Callable<EquipmentExpectationResponseV4> calculator) {
    if (force) {
      log.info("[V4] Force refresh - 캐시 무시 및 갱신: {}", userIgn);
      EquipmentExpectationResponseV4 response = executeCalculator(calculator);
      String compressedBase64 =
          executor.executeWithTranslation(
              () -> compressAndSerialize(response, userIgn),
              (e, ctx) ->
                  new EquipmentDataProcessingException(
                      String.format(
                          "Cache serialization failed [%s]: %s", ctx.toTaskName(), userIgn),
                      e),
              TaskContext.of("CacheCoordinator", "SerializeForce", userIgn));
      expectationCache.put(userIgn, compressedBase64);
      return response;
    }

    Object cachedValue = expectationCache.get(userIgn);
    if (cachedValue != null) {
      String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
      return decompressCachedResponse(compressedBase64, userIgn);
    }

    // Cache miss - calculate and store
    log.info("[V4] Cache MISS - 계산 시작: {}", userIgn);
    EquipmentExpectationResponseV4 response = executeCalculator(calculator);
    String compressedBase64 =
        executor.executeWithTranslation(
            () -> compressAndSerialize(response, userIgn),
            (e, ctx) ->
                new EquipmentDataProcessingException(
                    String.format("Cache serialization failed [%s]: %s", ctx.toTaskName(), userIgn),
                    e),
            TaskContext.of("CacheCoordinator", "Serialize", userIgn));
    expectationCache.put(userIgn, compressedBase64);

    return response;
  }

  /**
   * GZIP 압축된 기대값 응답 반환 (#262 성능 최적화)
   *
   * @param userIgn 캐릭터 IGN
   * @param force true: 캐시 무시, false: 캐시 사용
   * @param calculator 캐시 미스 시 실행될 계산 로직
   * @return GZIP 압축된 바이트 배열
   */
  public byte[] getGzipOrCalculate(
      String userIgn, boolean force, Callable<EquipmentExpectationResponseV4> calculator) {
    if (force) {
      log.info("[V4] Force refresh (GZIP) - 캐시 무시 및 갱신: {}", userIgn);
      EquipmentExpectationResponseV4 response = executeCalculator(calculator);
      String compressedBase64 =
          executor.executeWithTranslation(
              () -> compressAndSerialize(response, userIgn),
              (e, ctx) ->
                  new EquipmentDataProcessingException(
                      String.format(
                          "Cache serialization failed [%s]: %s", ctx.toTaskName(), userIgn),
                      e),
              TaskContext.of("CacheCoordinator", "SerializeGzipForce", userIgn));
      expectationCache.put(userIgn, compressedBase64);
      return java.util.Base64.getDecoder().decode(compressedBase64);
    }

    Object cachedValue = expectationCache.get(userIgn);
    if (cachedValue != null) {
      String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
      if (compressedBase64 == null || compressedBase64.isEmpty()) {
        throw new CacheDataNotFoundException(userIgn);
      }
      log.debug("[V4] GZIP Cache HIT: {} ({}KB)", userIgn, compressedBase64.length() / 1024);
      return java.util.Base64.getDecoder().decode(compressedBase64);
    }

    // Cache miss - calculate and store
    log.info("[V4] Cache MISS (GZIP) - 계산 시작: {}", userIgn);
    EquipmentExpectationResponseV4 response = executeCalculator(calculator);
    String compressedBase64 =
        executor.executeWithTranslation(
            () -> compressAndSerialize(response, userIgn),
            (e, ctx) ->
                new EquipmentDataProcessingException(
                    String.format("Cache serialization failed [%s]: %s", ctx.toTaskName(), userIgn),
                    e),
            TaskContext.of("CacheCoordinator", "SerializeGzip", userIgn));
    expectationCache.put(userIgn, compressedBase64);

    return java.util.Base64.getDecoder().decode(compressedBase64);
  }

  /**
   * L1 캐시 직접 조회 - Fast Path (#264 성능 최적화)
   *
   * @param userIgn 캐릭터 IGN
   * @return GZIP 바이트 (L1 히트 시), Empty (L1 미스 시)
   */
  public Optional<byte[]> getGzipFromL1CacheDirect(String userIgn) {
    Cache l1Cache = tieredCacheManager.getL1CacheDirect(CACHE_NAME);
    if (l1Cache == null) {
      recordFastPathMiss();
      return Optional.empty();
    }

    Cache.ValueWrapper wrapper = l1Cache.get(userIgn);
    if (wrapper == null || wrapper.get() == null) {
      recordFastPathMiss();
      return Optional.empty();
    }

    Object cachedValue = wrapper.get();
    byte[] gzipBytes = convertCachedValueToGzipBytes(cachedValue, userIgn);

    if (gzipBytes == null) {
      recordFastPathMiss();
      return Optional.empty();
    }

    recordFastPathHit();
    log.debug("[V4] L1 Fast Path HIT: {} ({}KB)", userIgn, gzipBytes.length / 1024);
    return Optional.of(gzipBytes);
  }

  /**
   * Legacy byte[] → GZIP bytes 변환 (L1 Fast Path용)
   *
   * @param cachedValue 캐시에서 조회된 값 (byte[] 또는 String)
   * @param userIgn 캐릭터 IGN
   * @return GZIP 압축 바이트 배열
   */
  private byte[] convertCachedValueToGzipBytes(Object cachedValue, String userIgn) {
    if (cachedValue instanceof String base64) {
      return java.util.Base64.getDecoder().decode(base64);
    }

    if (cachedValue instanceof byte[] gzipBytes) {
      log.warn(
          "[V4] L1 Legacy byte[] format detected: {} ({}KB)", userIgn, gzipBytes.length / 1024);
      return gzipBytes;
    }

    log.error(
        "[V4] L1 Unknown cache value type: {} for userIgn={}", cachedValue.getClass(), userIgn);
    return null;
  }

  // ==================== Internal Methods ====================

  /**
   * Legacy byte[] → Base64 String 마이그레이션
   *
   * <p>캐시에 저장된 값이 old format(byte[])인지 new format(Base64 String)인지 확인하고 변환. old format을 만나면 new
   * format으로 변환하여 캐시에 갱신(migration).
   *
   * @param cachedValue 캐시에서 조회된 값 (byte[] 또는 String)
   * @param userIgn 캐릭터 IGN (로그용)
   * @return Base64 String (압축된 데이터)
   */
  private String convertCachedValueToBase64(Object cachedValue, String userIgn) {
    // Unwrap SimpleValueWrapper (Spring Cache wrapper)
    Object unwrappedValue = cachedValue;
    if (cachedValue instanceof org.springframework.cache.support.SimpleValueWrapper wrapper) {
      unwrappedValue = wrapper.get();
      log.debug("[V4] Unwrapped SimpleValueWrapper for: {}", userIgn);
    }

    if (unwrappedValue instanceof String base64) {
      log.debug("[V4] Cache HIT (New Base64 format): {}", userIgn);
      return base64;
    }

    if (unwrappedValue instanceof byte[] oldGzipBytes) {
      log.warn(
          "[V4] Legacy byte[] format detected - migrating to Base64: {} ({}KB)",
          userIgn,
          oldGzipBytes.length / 1024);
      String migratedBase64 = java.util.Base64.getEncoder().encodeToString(oldGzipBytes);
      // Migrate to new format
      expectationCache.put(userIgn, migratedBase64);
      log.info("[V4] Migration complete: {}", userIgn);
      return migratedBase64;
    }

    log.error(
        "[V4] Unknown cache value type: {} (unwrapped: {}) for userIgn={}",
        cachedValue.getClass(),
        unwrappedValue != null ? unwrappedValue.getClass() : "null",
        userIgn);
    throw new EquipmentDataProcessingException(
        String.format("Invalid cache value type: %s", cachedValue.getClass()));
  }

  private EquipmentExpectationResponseV4 executeCalculator(
      Callable<EquipmentExpectationResponseV4> calculator) {
    return executor.execute(calculator::call, TaskContext.of("CacheCoordinator", "Calculate"));
  }

  /** Response → JSON → GZIP → Base64 String 변환 (#262) */
  private String compressAndSerialize(EquipmentExpectationResponseV4 response, String userIgn)
      throws Exception {
    String json = objectMapper.writeValueAsString(response);
    byte[] compressed = GzipUtils.compress(json);
    String base64 = java.util.Base64.getEncoder().encodeToString(compressed);
    log.debug(
        "[V4] GZIP+Base64 압축 완료: {} (원본: {}KB → 압축: {}KB → Base64: {}KB)",
        userIgn,
        json.length() / 1024,
        compressed.length / 1024,
        base64.length() / 1024);
    return base64;
  }

  /** Response → JSON → GZIP byte[] 직접 변환 (force=true 용) */
  private byte[] compressToGzipBytes(EquipmentExpectationResponseV4 response, String userIgn) {
    TaskContext context = TaskContext.of("CacheCoordinator", "CompressForce", userIgn);
    return executor.executeWithTranslation(
        () -> {
          String json = objectMapper.writeValueAsString(response);
          return GzipUtils.compress(json);
        },
        (e, ctx) ->
            new EquipmentDataProcessingException(
                String.format("GZIP 생성 실패 [%s]: %s", ctx.toTaskName(), userIgn), e),
        context);
  }

  /** Base64 → GZIP byte[] → JSON → Response 압축 해제 (#262 Fix) */
  private EquipmentExpectationResponseV4 decompressCachedResponse(
      String compressedBase64, String userIgn) {
    return executor.executeWithTranslation(
        () -> decompressInternal(compressedBase64, userIgn),
        (e, context) ->
            new EquipmentDataProcessingException(
                String.format("GZIP 압축 해제 실패 [%s]: %s", context.toTaskName(), userIgn), e),
        TaskContext.of("CacheCoordinator", "Decompress", userIgn));
  }

  private EquipmentExpectationResponseV4 decompressInternal(String compressedBase64, String userIgn)
      throws Exception {
    if (compressedBase64 == null || compressedBase64.isEmpty()) {
      throw new CacheDataNotFoundException(userIgn);
    }

    byte[] compressed = java.util.Base64.getDecoder().decode(compressedBase64);
    String json = GzipUtils.decompress(compressed);
    EquipmentExpectationResponseV4 response =
        objectMapper.readValue(json, EquipmentExpectationResponseV4.class);

    log.debug(
        "[V4] Cache HIT (Base64+GZIP): {} (Base64: {}KB → 압축: {}KB → 원본: {}KB)",
        userIgn,
        compressedBase64.length() / 1024,
        compressed.length / 1024,
        json.length() / 1024);

    return rebuildWithCacheFlag(response);
  }

  private EquipmentExpectationResponseV4 rebuildWithCacheFlag(
      EquipmentExpectationResponseV4 original) {
    return EquipmentExpectationResponseV4.builder()
        .userIgn(original.getUserIgn())
        .calculatedAt(original.getCalculatedAt())
        .fromCache(true)
        .totalExpectedCost(original.getTotalExpectedCost())
        .totalCostText(original.getTotalCostText())
        .totalCostBreakdown(original.getTotalCostBreakdown())
        .maxPresetNo(original.getMaxPresetNo())
        .presets(original.getPresets())
        .build();
  }

  // ==================== Metrics ====================

  private void recordFastPathHit() {
    meterRegistry.counter("cache.l1.fast_path", "result", "hit").increment();
  }

  private void recordFastPathMiss() {
    meterRegistry.counter("cache.l1.fast_path", "result", "miss").increment();
  }
}
