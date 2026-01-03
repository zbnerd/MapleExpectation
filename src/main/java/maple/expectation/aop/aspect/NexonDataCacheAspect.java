package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.executor.LogicExecutor;
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

/**
 * Nexon 데이터 캐시 Aspect (코드 평탄화 적용)
 *
 * <p>Leader/Follower 패턴을 사용한 넥슨 API 호출 중복 방지 Aspect
 *
 * <h3>Before (try-finally 보일러플레이트)</h3>
 * <pre>{@code
 * if (isLeader) {
 *     try {
 *         Object result = joinPoint.proceed();
 *         cacheService.saveCache(ocid, response);
 *         return wrap(response, returnType);
 *     } finally {
 *         latch.countDown();
 *         latch.delete();
 *     }
 * }
 * }</pre>
 *
 * <h3>After (LogicExecutor 사용)</h3>
 * <pre>{@code
 * return executor.executeWithFinally(
 *     () -> this.fetchAndCacheData(joinPoint, ocid, returnType),
 *     () -> this.releaseLatch(latch),
 *     "nexonCache:leader:" + ocid
 * );
 * }</pre>
 *
 * @see LogicExecutor
 * @since 1.0.0
 */

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(1) // 최우선 처리
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final RedissonClient redissonClient;
    private final LogicExecutor executor;

    /**
     * Nexon 데이터 캐시 핸들링 (코드 평탄화 적용)
     *
     * <p>throws Throwable 제거, try-finally 블록 제거, 비즈니스 로직 분리
     */
    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // 1. 캐시 조회 (이미 있으면 즉시 반환)
        Optional<Object> cachedResult = getCachedResult(ocid, returnType);
        if (cachedResult.isPresent()) {
            return cachedResult.get();
        }

        // 2. 분산 래치로 Leader Election
        RCountDownLatch latch = redissonClient.getCountDownLatch("latch:eq:" + ocid);
        boolean isLeader = latch.trySetCount(1);

        return isLeader
                ? executeAsLeader(joinPoint, ocid, returnType, latch)
                : executeAsFollower(ocid, returnType, latch);
    }

    /**
     * 캐시 조회 (평탄화: 별도 메서드로 분리)
     *
     * @param ocid OCID
     * @param returnType 반환 타입
     * @return 캐시된 결과 (없으면 Empty)
     */
    private Optional<Object> getCachedResult(String ocid, Class<?> returnType) {
        Optional<EquipmentResponse> cached = cacheService.getValidCache(ocid);
        if (cached.isPresent()) {
            return Optional.of(wrap(cached.get(), returnType));
        }
        if (cacheService.hasNegativeCache(ocid)) {
            return Optional.of(wrap(null, returnType));
        }
        return Optional.empty();
    }

    /**
     * Leader로 실행 (평탄화: 별도 메서드로 분리)
     *
     * <p>LogicExecutor.executeWithFinally를 사용하여 try-finally 제거
     */
    private Object executeAsLeader(
            ProceedingJoinPoint joinPoint,
            String ocid,
            Class<?> returnType,
            RCountDownLatch latch
    ) {
        log.info("👑 [Leader] 내가 대표로 넥슨 API 호출: {}", ocid);

        return executor.executeWithFinally(
                () -> this.fetchAndCacheData(joinPoint, ocid, returnType),
                () -> this.releaseLatch(latch),
                "nexonCache:leader:" + ocid
        );
    }

    /**
     * Follower로 실행 (평탄화: 별도 메서드로 분리)
     */
    private Object executeAsFollower(String ocid, Class<?> returnType, RCountDownLatch latch) {
        log.info("😴 [Follower] 대장 완료 대기 중...: {}", ocid);

        return executor.executeOrDefault(
                () -> this.awaitLeaderAndGetCache(ocid, returnType, latch),
                wrap(null, returnType),
                "nexonCache:follower:" + ocid
        );
    }

    /**
     * 데이터 가져오기 및 캐싱 (평탄화: 핵심 로직 분리)
     */
    private Object fetchAndCacheData(
            ProceedingJoinPoint joinPoint,
            String ocid,
            Class<?> returnType
    ) throws Throwable {
        Object result = joinPoint.proceed();

        EquipmentResponse response = (result instanceof CompletableFuture<?> future)
                ? (EquipmentResponse) future.join()
                : (EquipmentResponse) result;

        cacheService.saveCache(ocid, response);
        return wrap(response, returnType);
    }

    /**
     * Leader 대기 및 캐시 조회 (평탄화: Follower 로직 분리)
     */
    private Object awaitLeaderAndGetCache(
            String ocid,
            Class<?> returnType,
            RCountDownLatch latch
    ) throws Exception {
        boolean completed = latch.await(5, TimeUnit.SECONDS);

        if (completed) {
            log.info("⏰ [Follower] 대장 완료 확인! 캐시에서 읽음: {}", ocid);
        } else {
            log.warn("🚨 [Follower Timeout] 대장이 너무 느려 직접 확인: {}", ocid);
        }

        return wrap(cacheService.getValidCache(ocid).orElse(null), returnType);
    }

    /**
     * 래치 해제 (평탄화: finally 로직 분리)
     */
    private void releaseLatch(RCountDownLatch latch) {
        latch.countDown();
        latch.delete();
        log.debug("🚀 [Leader] 모든 Follower에게 완료 신호 전송 완료");
    }

    /**
     * 응답 래핑 (CompletableFuture 또는 일반 객체)
     */
    private Object wrap(EquipmentResponse res, Class<?> type) {
        return CompletableFuture.class.isAssignableFrom(type)
                ? CompletableFuture.completedFuture(res)
                : res;
    }
}