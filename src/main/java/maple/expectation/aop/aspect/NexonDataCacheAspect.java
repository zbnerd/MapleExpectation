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
    private final CacheManager cacheManager;

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // 1️⃣ L1 확인
        EquipmentResponse localOnly = checkOnlyL1(ocid);
        if (localOnly != null) {
            // 마커면 null로 래핑해서 반환
            return wrapResponse(cacheService.isNullMarker(localOnly) ? null : localOnly, returnType);
        }

        try {
            return lockStrategy.executeWithLock(ocid, 2, 20, () -> {
                // 2️⃣ Double-Check
                Optional<EquipmentResponse> doubleCheck = cacheService.getValidCache(ocid);

                // 값이 있거나 마커(Negative Cache)가 있는 경우
                if (doubleCheck.isPresent() || cacheService.hasNegativeCache(ocid)) {
                    log.info("🎯 [Lock Follower] 캐시 발견: {}", ocid);
                    return wrapResponse(doubleCheck.orElse(null), returnType);
                }

                // 3️⃣ API 호출
                log.info("🏃 [Lock Winner] API 호출 시작: {}", ocid);
                return proceedAndSaveSync(joinPoint, ocid, returnType);
            });
        } catch (DistributedLockException e) {
            log.warn("⏭️ [Lock Timeout] {} - 마지막 수단으로 캐시 확인", ocid);
            Optional<EquipmentResponse> res = cacheService.getValidCache(ocid);
            if (res.isPresent()) return wrapResponse(res.get(), returnType);
            throw new DistributedLockException("현재 요청이 너무 많습니다.");
        }
    }

    private Object proceedAndSaveSync(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType) throws Throwable {
        Object result = joinPoint.proceed();
        EquipmentResponse responseObj = null;

        if (result instanceof CompletableFuture<?> future) {
            responseObj = (EquipmentResponse) future.join();
        } else {
            responseObj = (EquipmentResponse) result;
        }

        // null이어도 마커 저장을 위해 호출
        cacheService.saveCache(ocid, responseObj);

        return wrapResponse(responseObj, returnType);
    }

    private EquipmentResponse checkOnlyL1(String ocid) {
        try {
            Cache cache = cacheManager.getCache("equipment");
            return (cache != null) ? cache.get(ocid, EquipmentResponse.class) : null;
        } catch (Exception e) { return null; }
    }

    private Object wrapResponse(EquipmentResponse response, Class<?> returnType) {
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return CompletableFuture.completedFuture(response);
        }
        return response;
    }
}