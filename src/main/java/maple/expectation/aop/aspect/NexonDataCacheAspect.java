package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.service.v2.cache.EquipmentCacheService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RCountDownLatch;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(1) // 최우선 처리
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final RedissonClient redissonClient;

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // 1. 캐시/Negative 마커 확인 (이미 있으면 즉시 반환)
        Optional<EquipmentResponse> cached = cacheService.getValidCache(ocid);
        if (cached.isPresent()) return wrap(cached.get(), returnType);
        if (cacheService.hasNegativeCache(ocid)) return wrap(null, returnType);

        // 2. 분산 래치(Latch) 생성
        RCountDownLatch latch = redissonClient.getCountDownLatch("latch:eq:" + ocid);

        // 3. 대장 선출 (Leader Election)
        boolean isLeader = latch.trySetCount(1);

        if (isLeader) {
            try {
                log.info("👑 [Leader] 내가 대표로 넥슨 API 호출: {}", ocid);
                Object result = joinPoint.proceed();

                EquipmentResponse response = (result instanceof CompletableFuture<?> future)
                        ? (EquipmentResponse) future.join() : (EquipmentResponse) result;

                cacheService.saveCache(ocid, response);
                return wrap(response, returnType);
            } finally {
                // 🚀 [Publish] 대기 중인 모든 스레드(Followers)를 한꺼번에 깨움
                latch.countDown();
                latch.delete();
            }
        } else {
            // 4. [Subscribe] 대장을 믿고 잠들기
            log.info("😴 [Follower] 대장 완료 대기 중...: {}", ocid);
            boolean completed = latch.await(5, TimeUnit.SECONDS);

            if (completed) {
                log.info("⏰ [Follower] 대장 완료 확인! 캐시에서 읽음: {}", ocid);
                return wrap(cacheService.getValidCache(ocid).orElse(null), returnType);
            } else {
                log.warn("🚨 [Follower Timeout] 대장이 너무 느려 직접 확인: {}", ocid);
                return wrap(cacheService.getValidCache(ocid).orElse(null), returnType);
            }
        }
    }

    private Object wrap(EquipmentResponse res, Class<?> type) {
        return CompletableFuture.class.isAssignableFrom(type) ? CompletableFuture.completedFuture(res) : res;
    }
}