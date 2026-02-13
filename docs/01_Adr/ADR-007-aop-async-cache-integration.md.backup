# ADR-007: NexonDataCacheAspect 및 비동기 컨텍스트 관리

## 상태
Accepted

## 맥락 (Context)

TieredCache와 Singleflight 패턴(ADR-003)을 Nexon API 호출에 적용할 때 다음 문제가 발생했습니다:

**관찰된 문제:**
- AOP와 CompletableFuture 결합 시 ThreadLocal 컨텍스트 유실
- 동기/비동기 반환 타입 혼재로 래핑 로직 복잡
- 경로별 L2 캐시 저장 정책 분기 필요 (Equipment 조회 vs Expectation 계산)

**부하테스트 결과 (#266):**
- L1 Fast Path로 응답 시간 27ms → 5ms (5.4x 개선)

> **참고:** Cache Stampede 방지를 위한 Singleflight 패턴은 [ADR-003](ADR-003-tiered-cache-singleflight.md) 참조

## 검토한 대안 (Options Considered)

### 옵션 A: @Cacheable + @Async 조합
```java
@Cacheable("equipment")
@Async
public CompletableFuture<Equipment> getEquipment(String ocid) { ... }
```
- 장점: 선언적, 간단
- 단점: 프록시 중첩 순서 문제, ThreadLocal 유실
- **결론: 제어 불가**

### 옵션 B: 서비스 레이어에서 수동 캐시 관리
```java
public Equipment getEquipment(String ocid) {
    Equipment cached = cache.get(ocid);
    if (cached != null) return cached;
    // ... API 호출 및 캐시 저장
}
```
- 장점: 명시적 제어
- 단점: 보일러플레이트 코드 산재, 일관성 부족
- **결론: 유지보수 어려움**

### 옵션 C: 커스텀 AOP Aspect + Redisson Latch
- 장점: 선언적 사용, 분산 락 통합, 컨텍스트 관리 일원화
- 단점: 구현 복잡도
- **결론: 채택**

## 결정 (Decision)

**NexonDataCacheAspect를 통한 선언적 캐시 + 분산 락 + ThreadLocal 보존을 적용합니다.**

### 1. NexonDataCacheAspect 구조
```java
// maple.expectation.aop.aspect.NexonDataCacheAspect
@Around("@annotation(NexonDataCache) && args(ocid, ..)")
public Object handleNexonCache(ProceedingJoinPoint joinPoint, String ocid) {
    // 1. 캐시 조회 시도
    return getCachedResult(ocid, returnType)
            .orElseGet(() -> this.executeDistributedStrategy(joinPoint, ocid, returnType));
}

private Object executeDistributedStrategy(...) {
    String latchKey = "latch:eq:" + ocid;
    RCountDownLatch latch = redissonClient.getCountDownLatch(latchKey);

    // 2. Leader/Follower 분기 (ADR-003 Singleflight 적용)
    if (latch.trySetCount(1)) {
        return executeAsLeader(joinPoint, ocid, returnType, latch);
    }
    return executeAsFollower(ocid, returnType, latch);
}
```

### 2. ThreadLocal 컨텍스트 보존 (핵심)
```java
// 비동기 콜백에서 컨텍스트 복원
private Object handleAsyncResult(CompletableFuture<?> future, String ocid, RCountDownLatch latch) {
    // 현재 스레드의 컨텍스트 스냅샷
    Boolean skipContextSnap = SkipEquipmentL2CacheContext.snapshot();

    return future.handle((res, ex) -> executor.executeWithFinally(
            () -> {
                // 콜백 스레드에서 컨텍스트 복원
                SkipEquipmentL2CacheContext.restore(skipContextSnap);
                return processAsyncCallback(res, ex, ocid);
            },
            () -> finalizeLatch(latch),
            context
    ));
}
```

**문제 상황:**
```
[Thread-1] SkipEquipmentL2CacheContext.set(true)
    ↓
[Thread-1] CompletableFuture.supplyAsync(...)
    ↓
[ForkJoinPool-1] 콜백 실행 → ThreadLocal 값 없음 (null)
```

**해결:**
```
[Thread-1] snapshot = SkipEquipmentL2CacheContext.snapshot()  // true 캡처
    ↓
[ForkJoinPool-1] SkipEquipmentL2CacheContext.restore(snapshot)  // true 복원
```

### 3. 경로별 L2 캐시 분기
```java
// maple.expectation.aop.context.SkipEquipmentL2CacheContext
public class SkipEquipmentL2CacheContext {
    private static final ThreadLocal<Boolean> SKIP_L2 = new ThreadLocal<>();

    public static void enableSkip() { SKIP_L2.set(true); }
    public static boolean enabled() { return Boolean.TRUE.equals(SKIP_L2.get()); }
}

// Aspect에서 사용
if (SkipEquipmentL2CacheContext.enabled()) {
    log.debug("[NexonCache] L2 save skipped (Expectation path): {}", ocid);
    return;  // L2 저장 스킵
}
cacheService.saveCache(ocid, response);
```

**분기 이유:**
| 경로 | L2 저장 | 이유 |
|------|---------|------|
| Equipment 단독 조회 | O | 재사용 가능 |
| Expectation 계산 경로 | X | DB에 이미 저장됨 (중복 방지) |

### 4. Latch TTL 관리 (Zombie 방지)
```yaml
# application.yml
nexon:
  api:
    latch-initial-ttl-seconds: 60    # 리더 크래시 대비
    latch-finalize-ttl-seconds: 10   # 팔로워 조회 여유
    cache-follower-timeout-seconds: 30  # Follower 대기 상한
```

### 5. 사용 예시
```java
// 서비스에서 어노테이션만 선언
@NexonDataCache
public CompletableFuture<EquipmentResponse> getEquipment(String ocid) {
    return nexonApiClient.fetchEquipment(ocid);
}

// Expectation 계산 시 L2 스킵 활성화
public void calculateExpectation(String userIgn) {
    SkipEquipmentL2CacheContext.enableSkip();
    try {
        equipmentService.getEquipment(ocid);  // L2 저장 안 함
    } finally {
        SkipEquipmentL2CacheContext.clear();
    }
}
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| ThreadLocal 유실 | 발생 | **방지** |
| L2 중복 저장 | 발생 | **경로별 분기** |
| 캐시 로직 산재 | 서비스마다 | **Aspect 일원화** |

## 참고 자료
- `maple.expectation.aop.aspect.NexonDataCacheAspect`
- `maple.expectation.aop.context.SkipEquipmentL2CacheContext`
- `maple.expectation.aop.annotation.NexonDataCache`
- [ADR-003: TieredCache + Singleflight](ADR-003-tiered-cache-singleflight.md)
