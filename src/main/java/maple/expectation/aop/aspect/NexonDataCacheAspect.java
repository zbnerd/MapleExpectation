package maple.expectation.aop.aspect;

import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.cache.EquipmentCacheService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final LockStrategy lockStrategy;

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    @Retry(name = "nexonLockRetry")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // ❌ [삭제] 락 획득 전 DB 조회(getValidCache)는 커넥션 풀 고갈의 주범입니다.

        // 1. 바로 분산 락부터 획득 시도
        // 락을 기다리는 동안은 DB 커넥션을 잡지 않으므로 500명이 대기해도 안전합니다.
        return lockStrategy.executeWithLock(ocid, () -> {

            // 2. 락 획득 후 딱 한 명만 DB(L3) 확인 (Double Check)
            Optional<EquipmentResponse> latest = cacheService.getValidCache(ocid);
            if (latest.isPresent()) {
                log.info("🎯 [Lock Winner - Cache Hit] ocid: {}", ocid);
                return wrapResponse(latest.get(), returnType);
            }

            log.info("🔄 [Lock Winner - Cache Miss] API 호출 시작: {}", ocid);
            Object result = joinPoint.proceed();

            // 3. 비동기/동기 결과 저장 로직 (기존 유지)
            if (CompletableFuture.class.isAssignableFrom(returnType)) {
                return ((CompletableFuture<?>) result).thenApply(res -> {
                    cacheService.saveCache(ocid, (EquipmentResponse) res);
                    return res;
                });
            }

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