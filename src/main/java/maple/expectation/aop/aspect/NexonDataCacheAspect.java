package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.InternalSystemException;
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
 * Nexon 데이터 캐시 Aspect - 비동기 논블로킹 + 래치 TTL 정책
 *
 * <h3>🚨 P0: .join() 완전 박멸</h3>
 * <p>비동기 결과에 대해 {@code handle()} 체이닝을 사용하여 톰캣 스레드를 즉시 풀로 반환
 *
 * <h3>🚨 P0: 래치 TTL 생명줄</h3>
 * <p>{@code trySetCount(1)} 성공 직후 {@code expire(60초)} 설정하여 리더 크래시 시 팔로워 영구 대기 방지
 *
 * <h3>🚨 P0: finalizeLatch 전략</h3>
 * <p>{@code delete()} 대신 짧은 {@code expire(10초)}로 레이스 컨디션 방지
 *
 * @see LogicExecutor
 * @since 1.0.0
 */
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
        boolean isLeader = latch.trySetCount(1);

        if (isLeader) {
            // ✅ P0: 리더 선출 즉시 TTL 설정 (리더 크래시 대비 생명줄)
            redissonClient.getKeys().expire(latchKey, 60, TimeUnit.SECONDS);
            log.debug("🕐 [Leader] 래치 TTL 60초 설정 완료: {}", ocid);
            return executeAsLeader(joinPoint, ocid, returnType, latch);
        }
        return executeAsFollower(ocid, returnType, latch);
    }

    private Object executeAsLeader(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch) {
        log.info("👑 [Leader] 내가 대표로 넥슨 API 호출: {}", ocid);

        // ✅ 주의: 비동기일 경우 LogicExecutor의 finallyBlock은 Future 반환 시점에 실행됨.
        // 따라서 실제 래치 해제는 Future의 파이프라인 안에서 처리해야 함.
        return executor.execute(
                () -> this.fetchAndCacheData(joinPoint, ocid, returnType, latch),
                "NexonCache:leader:" + ocid
        );
    }

    private Object fetchAndCacheData(ProceedingJoinPoint joinPoint, String ocid, Class<?> returnType, RCountDownLatch latch) throws Throwable {
        Object result = joinPoint.proceed();

        if (result instanceof CompletableFuture<?> future) {
            // ✅ P0: .join()을 완전히 제거하고 비동기 체이닝으로 위임
            return future.handle((res, ex) -> {
                try {
                    if (ex == null) cacheService.saveCache(ocid, (EquipmentResponse) res);
                    return res;
                } finally {
                    finalizeLatch(latch); // 비동기 완료 후 래치 해제
                }
            });
        }

        // 동기 로직의 경우 - LogicExecutor.executeWithFinally 사용
        return executor.executeWithFinally(
                () -> {
                    EquipmentResponse response = (EquipmentResponse) result;
                    cacheService.saveCache(ocid, response);
                    return wrap(response, returnType);
                },
                () -> finalizeLatch(latch),
                "NexonCache:syncCache:" + ocid
        );
    }

    private Object executeAsFollower(String ocid, Class<?> returnType, RCountDownLatch latch) {
        return executor.execute(() -> {
            log.info("😴 [Follower] 대장 완료 대기 중...: {}", ocid);
            boolean completed = latch.await(5, TimeUnit.SECONDS);

            return getCachedResult(ocid, returnType)
                    .orElseGet(() -> {
                        // ✅ P0: 대기 후에도 캐시가 없으면 조용히 null을 반환하지 않고 명시적 실패 처리
                        if (!completed) throw new InternalSystemException("NexonCache Follower Timeout: " + ocid);
                        throw new InternalSystemException("NexonCache Leader Failed: " + ocid);
                    });
        }, "NexonCache:follower:" + ocid);
    }

    private void finalizeLatch(RCountDownLatch latch) {
        latch.countDown();
        // ✅ P0: delete() 대신 짧은 expire로 정리 (레이스 컨디션 방지)
        String latchKey = latch.getName();
        redissonClient.getKeys().expire(latchKey, 10, TimeUnit.SECONDS);
        log.debug("🚀 [Leader] 모든 Follower에게 완료 신호 전송 및 10초 뒤 만료 설정");
    }

    /**
     * 캐시 조회
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
     * 응답 래핑 (CompletableFuture 또는 일반 객체)
     *
     * @param res 응답 객체
     * @param type 반환 타입
     * @return 래핑된 응답
     */
    private Object wrap(EquipmentResponse res, Class<?> type) {
        return CompletableFuture.class.isAssignableFrom(type)
                ? CompletableFuture.completedFuture(res)
                : res;
    }
}
