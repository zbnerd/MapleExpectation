package maple.expectation.infrastructure.aop.aspect;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.ExternalServiceException;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.infrastructure.aop.context.SkipEquipmentL2CacheContext;
import maple.expectation.infrastructure.cache.port.EquipmentCache;
import maple.expectation.infrastructure.config.NexonApiProperties;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RCountDownLatch;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@Order(1)
public class NexonDataCacheAspect {

  private final EquipmentCache cacheService;
  private final RedissonClient redissonClient;
  private final LogicExecutor executor;
  private final NexonApiProperties nexonApiProperties;

  public NexonDataCacheAspect(
      EquipmentCache cacheService,
      RedissonClient redissonClient,
      LogicExecutor executor,
      NexonApiProperties nexonApiProperties) {
    this.cacheService = cacheService;
    this.redissonClient = redissonClient;
    this.executor = executor;
    this.nexonApiProperties = nexonApiProperties;
  }

  @Around(
      "@annotation(maple.expectation.infrastructure.aop.annotation.NexonDataCache) && args(ocid, ..)")
  public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Class<?> returnType = signature.getReturnType();

    return getCachedResult(ocid, returnType)
        .orElseGet(() -> this.executeDistributedStrategy(joinPoint, ocid, returnType));
  }

  private Object executeDistributedStrategy(
      ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType) {
    String latchKey = "latch:eq:" + ocid;
    RCountDownLatch latch = redissonClient.getCountDownLatch(latchKey);

    if (latch.trySetCount(1)) {
      int initialTtl = nexonApiProperties.getLatchInitialTtlSeconds();
      redissonClient.getKeys().expire(latchKey, initialTtl, TimeUnit.SECONDS);
      return executeAsLeader(joinPoint, ocid, returnType, latch);
    }
    return executeAsFollower(ocid, returnType, latch);
  }

  private Object executeAsLeader(
      ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch) {
    return executor.execute(
        () -> this.fetchAndCacheData(joinPoint, ocid, returnType, latch),
        TaskContext.of("NexonCache", "Leader", ocid));
  }

  private Object fetchAndCacheData(
      ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch)
      throws Throwable {
    Object result = joinPoint.proceed();

    if (result instanceof CompletableFuture<?> future) {
      return handleAsyncResult(future, ocid, latch);
    }

    // 동기 경로
    return executor.executeWithFinally(
        () -> saveAndWrap(result, ocid, returnType),
        () -> finalizeLatch(latch),
        TaskContext.of("NexonCache", "SyncCache", ocid));
  }

  /** 비동기 결과 처리 (평탄화) */
  private Object handleAsyncResult(
      CompletableFuture<?> future, String ocid, RCountDownLatch latch) {
    String skipContextSnap = SkipEquipmentL2CacheContext.snapshot(); // V5: MDC 기반

    return future.handle(
        (res, ex) ->
            executor.executeWithFinally(
                () -> processAsyncCallback(res, ex, ocid, skipContextSnap),
                () -> finalizeLatch(latch),
                TaskContext.of("NexonCache", "AsyncCache", ocid)));
  }

  /** 비동기 콜백 처리 로직 (평탄화) */
  private Object processAsyncCallback(
      Object res, Throwable ex, String ocid, String skipContextSnap) {
    String before = SkipEquipmentL2CacheContext.snapshot(); // V5: MDC 기반
    SkipEquipmentL2CacheContext.restore(skipContextSnap);

    return executor.executeWithFinally(
        () -> doProcessAsyncCallback(res, ex, ocid),
        () -> SkipEquipmentL2CacheContext.restore(before),
        TaskContext.of("NexonCache", "AsyncCallback", ocid));
  }

  /** 비동기 콜백 핵심 로직 */
  private Object doProcessAsyncCallback(Object res, Throwable ex, String ocid) {
    if (ex != null) {
      throw toRuntimeException(ex, ocid);
    }

    if (res instanceof EquipmentResponse er) {
      saveEquipmentIfAllowed(ocid, er);
    }

    return res;
  }

  /** Equipment 저장 (Expectation 경로 분기) */
  private void saveEquipmentIfAllowed(String ocid, EquipmentResponse response) {
    if (SkipEquipmentL2CacheContext.enabled()) {
      log.debug("[NexonCache] L2 save skipped (Expectation path): {}", ocid);
      return;
    }
    cacheService.saveCache(ocid, response);
  }

  /**
   * Checked 예외를 RuntimeException으로 변환
   *
   * <h4>Issue #166: 5-Agent Council Decision</h4>
   *
   * <p>CompletionException 대신 프로젝트 예외 계층 사용으로 원본 타입 보존
   *
   * <h4>변환 규칙 (CLAUDE.md 섹션 11, 12)</h4>
   *
   * <ol>
   *   <li>Error → 즉시 throw (복구 불가)
   *   <li>RuntimeException (BaseException 포함) → 그대로 반환
   *   <li>TimeoutException → ExternalServiceException (🚨 Red Agent: HTTP 503 보존)
   *   <li>InterruptedException → 인터럽트 플래그 복원 후 InternalSystemException
   *   <li>기타 Checked Exception → InternalSystemException
   * </ol>
   *
   * <h4>메시지 포맷 (Purple Agent)</h4>
   *
   * <p>{@code NexonCache:AsyncCallback:{type}:{ocid}}
   *
   * @param ex 원본 예외
   * @param ocid 캐릭터 OCID (디버깅용)
   * @return RuntimeException (원본 또는 변환된 예외)
   */
  private RuntimeException toRuntimeException(Throwable ex, String ocid) {
    // P0: Error는 즉시 전파 (OOM, StackOverflow 등)
    if (ex instanceof Error err) {
      throw err;
    }

    // P1: RuntimeException (BaseException 포함)은 타입 보존
    if (ex instanceof RuntimeException re) {
      return re;
    }

    // P2: TimeoutException → ExternalServiceException (🚨 CRITICAL: HTTP 503 보존)
    if (ex instanceof TimeoutException) {
      return new ExternalServiceException("NexonCache:AsyncCallback:timeout:" + ocid, ex);
    }

    // P3: InterruptedException 특수 처리 - 인터럽트 플래그 복원
    if (ex instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return new InternalSystemException("NexonCache:AsyncCallback:interrupted:" + ocid, ex);
    }

    // P4: 기타 Checked Exception → InternalSystemException
    return new InternalSystemException("NexonCache:AsyncCallback:" + ocid, ex);
  }

  private Object saveAndWrap(Object result, String ocid, Class<?> returnType) {
    EquipmentResponse response = (EquipmentResponse) result;
    // Issue #158: Expectation 경로에서는 L2 저장 스킵
    if (!SkipEquipmentL2CacheContext.enabled()) {
      cacheService.saveCache(ocid, response);
    } else {
      log.debug("[NexonCache] L2 save skipped (Expectation path): {}", ocid);
    }
    return wrap(response, returnType);
  }

  private Object executeAsFollower(String ocid, Class<?> returnType, RCountDownLatch latch) {
    return executor.execute(
        () -> {
          log.info("[Follower] 대장 완료 대기 중...: {}", ocid);
          int timeoutSeconds = nexonApiProperties.getCacheFollowerTimeoutSeconds();
          if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new InternalSystemException("NexonCache Follower Timeout: " + ocid);
          }

          return getCachedResult(ocid, returnType)
              .orElseThrow(() -> new InternalSystemException("NexonCache Leader Failed: " + ocid));
        },
        TaskContext.of("NexonCache", "Follower", ocid));
  }

  private void finalizeLatch(RCountDownLatch latch) {
    latch.countDown();
    int finalizeTtl = nexonApiProperties.getLatchFinalizeTtlSeconds();
    redissonClient.getKeys().expire(latch.getName(), finalizeTtl, TimeUnit.SECONDS);
    log.debug("[Leader] 래치 정리 완료 ({}초 뒤 만료)", finalizeTtl);
  }

  private Optional<Object> getCachedResult(String ocid, Class<?> returnType) {
    return cacheService
        .getValidCache(ocid)
        .map(res -> wrap(res, returnType))
        .or(
            () ->
                cacheService.hasNegativeCache(ocid)
                    ? Optional.of(wrap(null, returnType))
                    : Optional.empty());
  }

  private Object wrap(EquipmentResponse res, Class<?> type) {
    return CompletableFuture.class.isAssignableFrom(type)
        ? CompletableFuture.completedFuture(res)
        : res;
  }
}
