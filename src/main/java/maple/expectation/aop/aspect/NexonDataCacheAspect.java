package maple.expectation.aop.aspect;

import io.github.resilience4j.retry.annotation.Retry;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE) // 캐시(@Cacheable)가 먼저 실행되도록 우선순위를 낮춤
public class NexonDataCacheAspect {

    private final EquipmentCacheService cacheService;
    private final LockStrategy lockStrategy;

    @Around("@annotation(maple.expectation.aop.annotation.NexonDataCache) && args(ocid, ..)")
    @Retry(name = "nexonLockRetry")
    public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnType = signature.getReturnType();

        try {
            // 1. [Lock Path] 분산 락 획득 시도 (실패 시 Retry 설정에 따라 재시도)
            return lockStrategy.executeWithLock(ocid, () -> {

                // 2. [Double Check] 락 획득 성공 후, 다른 스레드가 이미 채워둔 캐시가 있는지 확인
                Optional<EquipmentResponse> latest = cacheService.getValidCache(ocid);
                if (latest.isPresent()) {
                    log.info("🎯 [Lock Winner - Cache Hit] ocid: {}", ocid);
                    return wrapResponse(latest.get(), returnType);
                }

                // 3. [Miss Path] 락 승리자가 직접 API 호출 및 캐시 갱신
                log.info("🔄 [Lock Winner - Cache Miss] API 직접 호출 시작: {}", ocid);
                return proceedAndSave(joinPoint, ocid, returnType);
            });

        } catch (DistributedLockException e) {
            // 🚀 [Fallback Path] 5번의 리트라이 후에도 락을 못 잡은 경우 (S002 방지)
            // 에러를 던져서 사용자를 튕기게 하는 대신, 그냥 원본 데이터를 직접 호출하게 우회합니다.
            log.warn("⚠️ [Lock Timeout Fallback] 락 경합 과다로 직접 호출을 선택합니다: {}", ocid);
            return proceedAndSave(joinPoint, ocid, returnType);
        }
    }

    /**
     * 실제 타겟 메서드(API 호출)를 실행하고 결과를 캐시에 저장하는 공통 로직
     */
    private Object proceedAndSave(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType) throws Throwable {
        Object result = joinPoint.proceed();

        // 비동기 처리(CompletableFuture) 대응
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return ((CompletableFuture<?>) result).thenApply(res -> {
                if (res instanceof EquipmentResponse) {
                    cacheService.saveCache(ocid, (EquipmentResponse) res);
                }
                return res;
            });
        }

        // 동기 처리 대응
        if (result instanceof EquipmentResponse) {
            cacheService.saveCache(ocid, (EquipmentResponse) result);
        }
        return result;
    }

    /**
     * 캐시된 데이터를 메서드의 반환 타입(동기/비동기)에 맞게 래핑
     */
    private Object wrapResponse(EquipmentResponse response, Class<?> returnType) {
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return CompletableFuture.completedFuture(response);
        }
        return response;
    }
}