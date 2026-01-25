# ADR-007: AOP + Async + Cache 결합부 설계

## 상태
Accepted

## 맥락 (Context)

Nexon API 데이터 캐싱과 비동기 처리를 결합할 때 다음 문제가 발생했습니다:

**관찰된 문제:**
- Cache Stampede: 캐시 만료 + 100명 동시 요청 → API 100회 호출
- AOP와 CompletableFuture 결합 시 ThreadLocal 컨텍스트 유실
- 동기/비동기 반환 타입 혼재로 래핑 로직 복잡

**README 문제 정의:**
> 캐시 만료 + 1,000명 동시 요청 → 모두 DB 직접 호출 → Cache Stampede

**부하테스트 결과 (#266):**
- Singleflight + TieredCache로 DB 호출 97% 감소
- L1 Fast Path로 응답 시간 27ms → 5ms (5.4x 개선)

## 검토한 대안 (Options Considered)

### 옵션 A: @Cacheable + @Async 조합
```java
@Cacheable("equipment")
@Async
public CompletableFuture<Equipment> getEquipment(String ocid) { ... }
```
- 장점: 선언적, 간단
- 단점: 프록시 중첩 순서 문제, Stampede 방지 안됨
- **결론: 제어 불가**

### 옵션 B: 수동 캐시 + synchronized
```java
synchronized (key.intern()) {
    if (cache.get(key) == null) {
        cache.put(key, fetchFromApi());
    }
}
```
- 장점: Stampede 방지
- 단점: 분산 환경 미지원, 성능 저하
- **결론: 확장성 부족**

### 옵션 C: AOP Aspect + Redisson Latch + Singleflight
- 장점: 분산 Stampede 방지, L1/L2 계층, 비동기 지원
- 단점: 구현 복잡도
- **결론: 채택**

## 결정 (Decision)

**NexonDataCacheAspect + Redisson CountDownLatch + TieredCache 조합을 적용합니다.**

### 1. NexonDataCacheAspect 흐름
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

    // 2. Leader/Follower 분기
    if (latch.trySetCount(1)) {
        return executeAsLeader(joinPoint, ocid, returnType, latch);
    }
    return executeAsFollower(ocid, returnType, latch);
}
```

### 2. Leader/Follower 패턴 (Singleflight)
```
[동시 요청 100개] → [Latch 경쟁]
      ↓
┌─────────────────────────────────────┐
│ Leader (1명)                        │
│  - Nexon API 호출                   │
│  - 결과 캐시 저장                   │
│  - Latch countDown()                │
└─────────────────────────────────────┘
      ↓
┌─────────────────────────────────────┐
│ Followers (99명)                    │
│  - Latch await() 대기               │
│  - 캐시에서 결과 조회               │
└─────────────────────────────────────┘
```

### 3. ThreadLocal 컨텍스트 보존
```java
// 비동기 콜백에서 컨텍스트 복원
private Object handleAsyncResult(CompletableFuture<?> future, String ocid, RCountDownLatch latch) {
    // 현재 컨텍스트 스냅샷
    Boolean skipContextSnap = SkipEquipmentL2CacheContext.snapshot();

    return future.handle((res, ex) -> executor.executeWithFinally(
            () -> {
                // 컨텍스트 복원
                SkipEquipmentL2CacheContext.restore(skipContextSnap);
                return processAsyncCallback(res, ex, ocid);
            },
            () -> finalizeLatch(latch),
            context
    ));
}
```

### 4. Latch TTL 관리 (Zombie 방지)
```yaml
# application.yml
nexon:
  api:
    latch-initial-ttl-seconds: 60    # 리더 크래시 대비
    latch-finalize-ttl-seconds: 10   # 팔로워 조회 여유
    cache-follower-timeout-seconds: 30  # Follower 대기 상한
```

### 5. L1 Fast Path (#264)
```java
// Controller Level - GZIP 데이터 직접 반환
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<?>> getExpectation(...) {
    // Fast Path: L1 캐시에서 GZIP 직접 반환 (역직렬화 스킵)
    if (isGzipAccepted(acceptEncoding)) {
        Optional<byte[]> gzipData = service.getGzipFromL1CacheDirect(cacheKey);
        if (gzipData.isPresent()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .body(gzipData.get())
            );
        }
    }
    // Full Path: 비동기 파이프라인
    return service.calculateAsync(userIgn).thenApply(this::toResponse);
}
```

### 6. 경로별 L2 캐시 분기
```java
// Expectation 경로: L2 저장 스킵 (DB에 이미 저장)
if (SkipEquipmentL2CacheContext.enabled()) {
    log.debug("[NexonCache] L2 save skipped (Expectation path): {}", ocid);
    return;
}
cacheService.saveCache(ocid, response);
```

## 결과 (Consequences)

| 시나리오 | Before | After |
|----------|--------|-------|
| 캐시 만료 + 100 동시 요청 | API 100회 | **API 1회** |
| L1 HIT 응답 시간 | 27ms | **5ms** |
| DB 호출 비율 | 100% | **3%** |

**부하테스트 효과 (#266):**
- RPS 555 → 719 (+30%)
- 10만 RPS급 등가 처리량 달성

## 참고 자료
- `maple.expectation.aop.aspect.NexonDataCacheAspect`
- `maple.expectation.aop.context.SkipEquipmentL2CacheContext`
- `maple.expectation.global.cache.TieredCacheManager`
- `maple.expectation.service.v2.cache.EquipmentCacheService`
- `docs/01_Chaos_Engineering/06_Nightmare/N01_Thundering_Herd/`
- `docs/01_Chaos_Engineering/06_Nightmare/N05_Hot_Key/`
