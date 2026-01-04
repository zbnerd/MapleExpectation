package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
@Order(1)
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final RedissonClient redissonClient;
    private final LogicExecutor executor;

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
            redissonClient.getKeys().expire(latchKey, 60, TimeUnit.SECONDS);
            return executeAsLeader(joinPoint, ocid, returnType, latch);
        }
        return executeAsFollower(ocid, returnType, latch);
    }

    private Object executeAsLeader(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch) {
        // ✅ TaskContext 적용: String 오류 해결
        return executor.execute(
                () -> this.fetchAndCacheData(joinPoint, ocid, returnType, latch),
                TaskContext.of("NexonCache", "Leader", ocid)
        );
    }

    private Object fetchAndCacheData(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch) throws Throwable {
        Object result = joinPoint.proceed();

        if (result instanceof CompletableFuture<?> future) {
            // ✅ 비동기 평탄화: handle 내부의 try-finally를 processAsyncResult로 격리
            return future.handle((res, ex) -> this.processAsyncResult(res, ex, ocid, latch));
        }

        // ✅ 동기 평탄화: [패턴 1] executeWithFinally 사용으로 try-finally 키워드 박멸
        return executor.executeWithFinally(
                () -> this.saveAndWrap(result, ocid, returnType),
                () -> finalizeLatch(latch),
                TaskContext.of("NexonCache", "SyncCache", ocid)
        );
    }

    /**
     * 비동기 결과 처리 (평탄화: try 키워드 삭제)
     */
    private Object processAsyncResult(Object res, Throwable ex, String ocid, RCountDownLatch latch) {
        // executor.executeVoid로 비동기 블록 내부의 로깅과 예외 처리 일원화
        executor.executeVoid(() -> {
            if (ex == null) cacheService.saveCache(ocid, (EquipmentResponse) res);
        }, TaskContext.of("NexonCache", "AsyncSave", ocid));

        finalizeLatch(latch); // 반드시 실행되어야 하는 정리 로직
        return res;
    }

    private Object saveAndWrap(Object result, String ocid, Class<?> returnType) {
        EquipmentResponse response = (EquipmentResponse) result;
        cacheService.saveCache(ocid, response);
        return wrap(response, returnType);
    }

    private Object executeAsFollower(String ocid, Class<?> returnType, RCountDownLatch latch) {
        return executor.execute(() -> {
            log.info("😴 [Follower] 대장 완료 대기 중...: {}", ocid);
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new InternalSystemException("NexonCache Follower Timeout: " + ocid);
            }

            return getCachedResult(ocid, returnType)
                    .orElseThrow(() -> new InternalSystemException("NexonCache Leader Failed: " + ocid));
        }, TaskContext.of("NexonCache", "Follower", ocid));
    }

    private void finalizeLatch(RCountDownLatch latch) {
        latch.countDown();
        redissonClient.getKeys().expire(latch.getName(), 10, TimeUnit.SECONDS);
        log.debug("🚀 [Leader] 래치 정리 완료 (10초 뒤 만료)");
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