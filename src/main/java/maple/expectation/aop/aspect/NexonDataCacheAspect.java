package maple.expectation.aop.aspect;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.NexonApiProperties;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.cache.EquipmentCacheService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RCountDownLatch;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@Component
@Order(1)
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final NexonApiProperties nexonApiProperties;

    public NexonDataCacheAspect(
            EquipmentCacheService cacheService,
            RedissonClient redissonClient,
            LogicExecutor executor,
            NexonApiProperties nexonApiProperties) {
        this.cacheService = cacheService;
        this.redissonClient = redissonClient;
        this.executor = executor;
        this.nexonApiProperties = nexonApiProperties;
    }

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        return getCachedResult(ocid, returnType)
                .orElseGet(() -> this.executeDistributedStrategy(joinPoint, ocid, returnType));
    }

    private Object executeDistributedStrategy(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType) {
        String latchKey = "latch:eq:" + ocid;
        RCountDownLatch latch = redissonClient.getCountDownLatch(latchKey);

        if (latch.trySetCount(1)) {
            int initialTtl = nexonApiProperties.getLatchInitialTtlSeconds();
            redissonClient.getKeys().expire(latchKey, initialTtl, TimeUnit.SECONDS);
            return executeAsLeader(joinPoint, ocid, returnType, latch);
        }
        return executeAsFollower(ocid, returnType, latch);
    }

    private Object executeAsLeader(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch) {
        return executor.execute(
                () -> this.fetchAndCacheData(joinPoint, ocid, returnType, latch),
                TaskContext.of("NexonCache", "Leader", ocid)
        );
    }

    private Object fetchAndCacheData(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch) throws Throwable {
        Object result = joinPoint.proceed();

        if (result instanceof CompletableFuture<?> future) {
            // P0-1 수정: 예외 전파 보존 + latch 정리 보장
            return future.handle((res, ex) -> executor.executeWithFinally(
                    () -> {
                        if (ex != null) throw toRuntimeException(ex);  // 예외 전파 보존
                        if (res instanceof EquipmentResponse er) {     // null/타입 안전
                            cacheService.saveCache(ocid, er);
                        }
                        return res;  // CF<EquipmentResponse> 유지 (중첩 방지)
                    },
                    () -> finalizeLatch(latch),
                    TaskContext.of("NexonCache", "AsyncCache", ocid)
            ));
        }

        // 동기 경로: executeWithFinally로 latch 정리 보장
        return executor.executeWithFinally(
                () -> this.saveAndWrap(result, ocid, returnType),
                () -> finalizeLatch(latch),
                TaskContext.of("NexonCache", "SyncCache", ocid)
        );
    }

    /**
     * Checked 예외를 RuntimeException으로 변환 (예외 전파 보존용)
     */
    private RuntimeException toRuntimeException(Throwable ex) {
        if (ex instanceof RuntimeException re) return re;
        if (ex instanceof Error err) throw err;
        return new java.util.concurrent.CompletionException(ex);
    }

    private Object saveAndWrap(Object result, String ocid, Class<?> returnType) {
        EquipmentResponse response = (EquipmentResponse) result;
        cacheService.saveCache(ocid, response);
        return wrap(response, returnType);
    }

    private Object executeAsFollower(String ocid, Class<?> returnType, RCountDownLatch latch) {
        return executor.execute(() -> {
            log.info("[Follower] 대장 완료 대기 중...: {}", ocid);
            int timeoutSeconds = nexonApiProperties.getCacheFollowerTimeoutSeconds();
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new InternalSystemException("NexonCache Follower Timeout: " + ocid);
            }

            return getCachedResult(ocid, returnType)
                    .orElseThrow(() -> new InternalSystemException("NexonCache Leader Failed: " + ocid));
        }, TaskContext.of("NexonCache", "Follower", ocid));
    }

    private void finalizeLatch(RCountDownLatch latch) {
        latch.countDown();
        int finalizeTtl = nexonApiProperties.getLatchFinalizeTtlSeconds();
        redissonClient.getKeys().expire(latch.getName(), finalizeTtl, TimeUnit.SECONDS);
        log.debug("[Leader] 래치 정리 완료 ({}초 뒤 만료)", finalizeTtl);
    }

    private Optional<Object> getCachedResult(String ocid, Class<?> returnType) {
        return cacheService.getValidCache(ocid)
                .map(res -> wrap(res, returnType))
                .or(() -> cacheService.hasNegativeCache(ocid) ? Optional.of(wrap(null, returnType)) : Optional.empty());
    }

    private Object wrap(EquipmentResponse res, Class<?> type) {
        return CompletableFuture.class.isAssignableFrom(type)
                ? CompletableFuture.completedFuture(res)
                : res;
    }
}
