package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.cache.EquipmentCacheService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final LockStrategy lockStrategy;

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // 1. [Fast Path] L2 캐시(DB) 확인
        Optional<EquipmentResponse> cached = cacheService.getValidCache(ocid);
        if (cached.isPresent()) {
            log.info("🎯 [AOP Cache Hit] ocid: {}", ocid);
            return wrapResponse(cached.get(), returnType);
        }

        // 2. [Slow Path] 분산 락을 통한 캐시 스탬피드 방지 및 API 호출
        // ✅ Best Practice 2: ThrowingSupplier 도입으로 억지 RuntimeException 래핑 제거
        return lockStrategy.executeWithLock(ocid, () -> {

            // 3. Double Check (락 획득 대기 중 다른 스레드가 먼저 갱신했을 수 있음)
            Optional<EquipmentResponse> latest = cacheService.getValidCache(ocid);
            if (latest.isPresent()) {
                return wrapResponse(latest.get(), returnType);
            }

            log.info("🔄 [AOP Cache Miss] API 호출 및 캐시 갱신 시작: {}", ocid);
            Object result = joinPoint.proceed();

            // 4. [Non-blocking Pipeline] 비동기 처리 여부에 따른 후속 작업
            if (CompletableFuture.class.isAssignableFrom(returnType)) {
                // ✅ Best Practice 3: join()을 쓰지 않고 thenApply 체인으로 연결 (진짜 비동기)
                return ((CompletableFuture<?>) result).thenApply(res -> {
                    // 캐시 저장은 별도 서비스(REQUIRES_NEW)에서 수행하여 트랜잭션 격리
                    cacheService.saveCache(ocid, (EquipmentResponse) res);
                    return res;
                });
            }

            // 동기 방식인 경우 즉시 저장 후 반환
            cacheService.saveCache(ocid, (EquipmentResponse) result);
            return result;
        });
    }

    /**
     * 캐시된 데이터를 메서드의 반환 타입에 맞게 래핑합니다.
     */
    private Object wrapResponse(EquipmentResponse response, Class<?> returnType) {
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return CompletableFuture.completedFuture(response);
        }
        return response;
    }
}