package maple.expectation.service.v4.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.MySQLFallbackException;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.resilience.CompensationLogService;
import maple.expectation.infrastructure.resilience.MySQLFallbackProperties;
import maple.expectation.infrastructure.resilience.MySQLHealthEventPublisher;
import maple.expectation.infrastructure.resilience.MySQLHealthState;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Nexon API Fallback Service (Issue #218)
 *
 * <p>MySQL 장애 시 Nexon API를 직접 호출하여 데이터를 제공합니다.
 *
 * <h4>동작 흐름</h4>
 *
 * <ol>
 *   <li>MySQL 상태 확인 → DEGRADED 상태인지 검사
 *   <li>Nexon API 직접 호출
 *   <li>결과를 Redis 캐시에 저장 (TTL 무한대)
 *   <li>Compensation Log에 기록
 * </ol>
 *
 * <h4>예외 처리 (P0-3)</h4>
 *
 * <ul>
 *   <li>Fallback 성공 → 정상 응답 반환
 *   <li>Fallback 실패 → MySQLFallbackException (CircuitBreakerRecordMarker)
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NexonApiFallbackService {

  private static final String COMPENSATION_TYPE_EQUIPMENT = "equipment";
  private static final String CACHE_KEY_PREFIX = "equipment:";

  private final MySQLHealthEventPublisher healthEventPublisher;
  private final CompensationLogService compensationLogService;
  private final RedissonClient redissonClient;
  private final ObjectMapper objectMapper;
  private final MySQLFallbackProperties properties;

  @Qualifier("checkedLogicExecutor") private final CheckedLogicExecutor checkedExecutor;

  @Qualifier("realNexonApiClient") private final NexonApiClient realNexonApiClient;

  /**
   * MySQL 장애 시 Nexon API로 장비 데이터 조회
   *
   * <p>MySQL이 DEGRADED 상태일 때만 호출됩니다.
   *
   * @param ocid 캐릭터 OCID
   * @return 장비 응답 데이터
   * @throws MySQLFallbackException Fallback 실패 시
   */
  public EquipmentResponse getEquipmentDataWithFallback(String ocid) {
    return checkedExecutor.executeUnchecked(
        () -> {
          MySQLHealthState state = healthEventPublisher.getCurrentState();

          if (!state.isDegraded()) {
            log.debug("[Fallback] MySQL 정상 상태 - Fallback 스킵");
            return null;
          }

          log.warn("[Fallback] MySQL DEGRADED - Nexon API 직접 호출: ocid={}", ocid);

          // 1. Nexon API 직접 호출
          EquipmentResponse response = callNexonApiDirect(ocid);

          // 2. Redis 캐시에 저장 (TTL 무한대 - DEGRADED 상태에서는 PERSIST 적용됨)
          saveToRedisCache(ocid, response);

          // 3. Compensation Log 기록
          writeCompensationLog(ocid, response);

          log.info("[Fallback] Nexon API Fallback 성공: ocid={}", ocid);
          return response;
        },
        TaskContext.of("Fallback", "GetEquipmentData", ocid),
        e -> new MySQLFallbackException(ocid, e));
  }

  /** Nexon API 직접 호출 */
  private EquipmentResponse callNexonApiDirect(String ocid) throws Exception {
    CompletableFuture<EquipmentResponse> future = realNexonApiClient.getItemDataByOcid(ocid);

    // 타임아웃 적용 (기존 TimeLimiter 설정과 동일: 28초)
    return future.get(28, TimeUnit.SECONDS);
  }

  /**
   * Redis 캐시에 저장
   *
   * <p>DEGRADED 상태에서는 DynamicTTLManager가 TTL을 제거(PERSIST)하므로, 여기서는 기본 TTL로 저장합니다.
   */
  private void saveToRedisCache(String ocid, EquipmentResponse response) throws Exception {
    String cacheKey = CACHE_KEY_PREFIX + ocid;
    String json = objectMapper.writeValueAsString(response);

    RBucket<String> bucket = redissonClient.getBucket(cacheKey);
    // 기본 TTL 10분 설정 (DEGRADED 상태에서는 PERSIST됨)
    bucket.set(json, Duration.ofMinutes(10));

    log.debug("[Fallback] Redis 캐시 저장: key={}", cacheKey);
  }

  /** Compensation Log 기록 */
  private void writeCompensationLog(String ocid, EquipmentResponse response) {
    compensationLogService.writeLog(COMPENSATION_TYPE_EQUIPMENT, ocid, response);
  }

  /**
   * MySQL 장애 상태 확인
   *
   * @return DEGRADED 또는 RECOVERING 상태이면 true
   */
  public boolean isMySQLDegraded() {
    return healthEventPublisher.getCurrentState().isDegraded();
  }
}
