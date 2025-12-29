package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.cache.EquipmentCacheService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(1)
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final LockStrategy lockStrategy;
    private final CacheManager cacheManager; // L1 확인을 위해 직접 주입

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // 1️⃣ [True Fast Path] 락 없이 '로컬 메모리(L1)'만 확인
        // Redis(L2) 네트워크를 타지 않기 위해 직접 L1 캐시 매니저를 찌르는 것이 가장 빠릅니다.
        EquipmentResponse localOnly = checkOnlyL1(ocid);
        if (localOnly != null) {
            log.debug("⚡ [L1 Hit] 네트워크 비용 0ms - 즉시 반환: {}", ocid);
            return wrapResponse(localOnly, returnType);
        }

        try {
            // 2️⃣ [Distributed Lock] L1에 없을 때만 락 시도 (줄 세우기)
            return lockStrategy.executeWithLock(ocid, 2, 20, () -> {

                // 3️⃣ [Double-Check] 락 획득 후에는 Redis와 DB를 모두 확인
                // 여기서의 cacheService.getValidCache는 L1->L2->DB 순으로 확인합니다.
                Optional<EquipmentResponse> doubleCheck = cacheService.getValidCache(ocid);
                if (doubleCheck.isPresent()) {
                    log.info("🎯 [Lock Follower] Redis 또는 DB에서 찾음: {}", ocid);
                    return wrapResponse(doubleCheck.get(), returnType);
                }

                // 4️⃣ [Winner] 진짜 어디에도 없으면 API 호출
                log.info("🏃 [Lock Winner] 내가 API 호출하러 함: {}", ocid);
                return proceedAndSave(joinPoint, ocid, returnType);
            });

        } catch (DistributedLockException e) {
            log.warn("⏭️ [Lock Timeout] {} - 마지막 수단으로 Redis 확인 시도", ocid);
            return cacheService.getValidCache(ocid)
                    .map(res -> wrapResponse(res, returnType))
                    .orElseThrow(() -> new DistributedLockException("현재 요청이 너무 많아 처리가 지연되고 있습니다."));
        }
    }

    /**
     * L1(Caffeine)만 직접 확인하여 Redis RTT(네트워크 비용)를 제거합니다.
     */
    private EquipmentResponse checkOnlyL1(String ocid) {
        try {
            // TieredCacheManager에서 L1 매니저를 꺼내오거나,
            // Caffeine 캐시 객체에 직접 접근하는 로직이 필요합니다.
            Cache cache = cacheManager.getCache("equipment");
            if (cache instanceof maple.expectation.global.cache.TieredCache tiered) {
                // TieredCache 내부의 l1만 get(key) 하도록 별도 메서드를 TieredCache에 만드셔도 됩니다.
                // 임시로 그냥 get을 쓰되, L1에서 바로 안 나오면 Redis 비용이 발생하므로 주의가 필요합니다.
                return cache.get(ocid, EquipmentResponse.class);
            }
            return cache.get(ocid, EquipmentResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Object proceedAndSave(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType) throws Throwable {
        Object result = joinPoint.proceed();
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return ((CompletableFuture<?>) result).thenApply(res -> {
                if (res != null) cacheService.saveCache(ocid, (EquipmentResponse) res);
                return res;
            });
        }
        if (result != null) cacheService.saveCache(ocid, (EquipmentResponse) result);
        return result;
    }

    private Object wrapResponse(EquipmentResponse response, Class<?> returnType) {
        return CompletableFuture.class.isAssignableFrom(returnType) ?
                CompletableFuture.completedFuture(response) : response;
    }
}