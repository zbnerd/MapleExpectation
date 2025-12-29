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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(1) // 💡 중요: 트랜잭션보다 먼저 실행되어야 DB 커넥션을 미리 잡지 않습니다.
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final LockStrategy lockStrategy;

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // 1️⃣ [Fast Path] 락 없이 1차 확인
        // 이미 누군가 채워둔 캐시가 있다면 락 경합 없이 바로 반환합니다.
        Optional<EquipmentResponse> fastCache = cacheService.getValidCache(ocid);
        if (fastCache.isPresent()) {
            log.debug("🚀 [Cache Hit] No Lock - 즉시 반환: {}", ocid);
            return wrapResponse(fastCache.get(), returnType);
        }

        try {
            // 2️⃣ [Distributed Lock] 줄 세우기 (10초 대기)
            // 1등이 API를 다녀오기에 충분한 시간을 줍니다.
            return lockStrategy.executeWithLock(ocid, 10, 20, () -> {

                // 3️⃣ [Double-Check] 락 획득 후 2차 확인
                // 내가 99명 중 하나라면, 앞서 나갔던 1등이 채워둔 캐시를 여기서 발견합니다.
                Optional<EquipmentResponse> doubleCheck = cacheService.getValidCache(ocid);
                if (doubleCheck.isPresent()) {
                    log.info("🎯 [Lock Follower] 1등이 채운 캐시 사용: {}", ocid);
                    return wrapResponse(doubleCheck.get(), returnType);
                }

                // 4️⃣ [Winner] 진짜 없으면 내가 대표로 API 호출
                log.info("🏃 [Lock Winner] 내가 API 호출하러 감: {}", ocid);
                return proceedAndSave(joinPoint, ocid, returnType);
            });

        } catch (DistributedLockException e) {
            // 5️⃣ [Fail-Safe] 10초 대기 후에도 락을 못 잡은 경우
            log.warn("⏭️ [Lock Timeout] {} - 마지막 캐시 확인 시도", ocid);

            // 마지막으로 캐시만 한 번 더 확인하고 없으면 과부하 에러 반환
            return cacheService.getValidCache(ocid)
                    .map(res -> wrapResponse(res, returnType))
                    .orElseThrow(() -> new DistributedLockException("요청이 너무많습니다."));
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

        if (result != null) {
            cacheService.saveCache(ocid, (EquipmentResponse) result);
        }
        return result;
    }

    private Object wrapResponse(EquipmentResponse response, Class<?> returnType) {
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return CompletableFuture.completedFuture(response);
        }
        return response;
    }
}