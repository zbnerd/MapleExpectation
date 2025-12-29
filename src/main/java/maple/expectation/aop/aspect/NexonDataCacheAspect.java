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
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        // 1. [Pre-check] 락 근처도 안 가고 캐시부터 확인 (병목 제거의 핵심)
        Optional<EquipmentResponse> cached = cacheService.getValidCache(ocid);
        if (cached.isPresent()) {
            log.info("🎯 [Pre-Check Hit] 캐시 발견, 락 없이 즉시 응답: {}", ocid);
            return wrapResponse(cached.get(), returnType);
        }

        // 2. [Winner's Race] 1등이 되기 위해 락 획득 시도 (WaitTime=0)
        // 줄 서지 않고 즉시 성공/실패만 확인하여 톰캣 스레드를 보호합니다.
        boolean isLocked = lockStrategy.tryLockImmediately(ocid, 15);

        if (isLocked) {
            try {
                // 3. [Winner's Path] 1등은 API를 호출하고 캐시를 채웁니다.
                log.info("👑 [Lock Winner] 내가 1등이다! API 호출 시작: {}", ocid);
                return proceedAndSave(joinPoint, ocid, returnType);
            } finally {
                lockStrategy.unlock(ocid);
            }
        }

        // 4. [Waiters' Path] 락을 못 잡았다면(1등이 이미 있음)
        // 승준님의 아이디어: 락 대기열에 서지 않고 잠시 기다렸다가 캐시만 다시 확인!
        log.info("⏳ [Lock Waiter] 1등이 작업 중입니다. 500ms 대기 후 캐시 재확인: {}", ocid);
        Thread.sleep(500);

        Optional<EquipmentResponse> finalCheck = cacheService.getValidCache(ocid);
        if (finalCheck.isPresent()) {
            log.info("🎯 [Waiter Success] 1등이 채워준 캐시 발견! : {}", ocid);
            return wrapResponse(finalCheck.get(), returnType);
        }

        // 5. [Final Fallback] 끝까지 안 나오면 사용자 경험을 위해 직접 호출
        log.warn("⚠️ [Final Fallback] 캐시가 생성되지 않아 직접 호출합니다: {}", ocid);
        return proceedAndSave(joinPoint, ocid, returnType);
    }

    private Object proceedAndSave(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType) throws Throwable {
        Object result = joinPoint.proceed();
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return ((CompletableFuture<?>) result).thenApply(res -> {
                cacheService.saveCache(ocid, (EquipmentResponse) res);
                return res;
            });
        }
        cacheService.saveCache(ocid, (EquipmentResponse) result);
        return result;
    }

    private Object wrapResponse(EquipmentResponse response, Class<?> returnType) {
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return CompletableFuture.completedFuture(response);
        }
        return response;
    }
}