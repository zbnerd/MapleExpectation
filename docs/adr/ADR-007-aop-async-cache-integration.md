# ADR-007: NexonDataCacheAspect 및 비동기 컨텍스트 관리

## 상태
Accepted

## 문서 무결성 체크리스트

✅ All 30 items verified
- Date: 2025-12-01 (PR #264)
- Decision Maker: Blue Agent (AOP/Cache)
- Related: #264 L1 Fast Path, ADR-003 SingleFlight

---

## Fail If Wrong

1. **[F1]** AOP가 ThreadLocal 값을 유실하여 L2 캐시 저장 실패
2. **[F2]** Follower timeout으로 Latch 영구 잠김 (Zombie)
3. **[F3]** @NexonDataCache 사용 시 ConcurrentModificationException
4. **[F4]** 비동기 경로에서 L2 중복 저장 발생

---

## Terminology

| 용어 | 정의 |
|------|------|
| **AOP (Aspect-Oriented Programming)** | 횡단 관심사(캐시, 로깅 등)를 비즈니스 로직에서 분리 |
| **ThreadLocal** | 스레드별로 격리된 변수 저장소. 비동기 전환 시 유실 위험 |
| **Snapshot/Restore** | ThreadLocal 값을 캡처하고 다른 스레드에서 복원하는 패턴 |
| **Leader/Follower** | SingleFlight에서 첫 요청자는 실제 작업, 나머지는 결과 대기 |
| **Latch TTL** | Redisson CountDownLatch의 자동 해제 시간 (Leader 크래시 대비) |

---

## 맥락 (Context)

### 문제 정의

TieredCache와 Singleflight 패턴(ADR-003)을 Nexon API 호출에 적용할 때 다음 문제가 발생했습니다:

**관찰된 문제:**
- AOP와 CompletableFuture 결합 시 ThreadLocal 컨텍스트 유실 [E1]
- 동기/비동기 반환 타입 혼재로 래핑 로직 복잡 [E2]
- 경로별 L2 캐시 저장 정책 분기 필요 (Equipment 조회 vs Expectation 계산) [E3]

**부하테스트 결과 (#264, #266):**
- L1 Fast Path로 응답 시간 27ms → 5ms (5.4x 개선) [P1]
- AOP 캡슐화로 코드 중복 제거 [P2]

---

## 검토한 대안 (Options Considered)

### 옵션 A: @Cacheable + @Async 조합
```java
@Cacheable("equipment")
@Async
public CompletableFuture<Equipment> getEquipment(String ocid) { ... }
```
- **장점:** 선언적, 간단
- **단점:** 프록시 중첩 순서 문제, ThreadLocal 유실
- **거절 근거:** [R1] @Async가 @Cacheable보다 먼저 실행되어 캐시 무효 (테스트: 2025-11-28)
- **결론:** 제어 불가 (기각)

### 옵션 B: 서비스 레이어에서 수동 캐시 관리
```java
public Equipment getEquipment(String ocid) {
    Equipment cached = cache.get(ocid);
    if (cached != null) return cached;
    // ... API 호출 및 캐시 저장
}
```
- **장점:** 명시적 제어
- **단점:** 보일러플레이트 코드 산재, 일관성 부족
- **거절 근거:** [R2] 10개 서비스 메서드 중 4개에서 캐시 누락 발견 (Code Review: 2025-11-29)
- **결론:** 유지보수 어려움 (기각)

### 옵션 C: 커스텀 AOP Aspect + Redisson Latch
- **장점:** 선언적 사용, 분산 락 통합, 컨텍스트 관리 일원화
- **단점:** 구현 복잡도
- **채택 근거:** [C1] AOP로 캡슐화 후 ThreadLocal 유실 해결
- **결론:** 채택

### Trade-off Analysis

| 평가 기준 | @Cacheable+@Async | 수동 관리 | 커스텀 AOP | 비고 |
|-----------|-------------------|-----------|-----------|------|
| **ThreadLocal 보존** | ❌ 유실 | ✅ 가능 | **✅ 자동** | C 승 |
| **코드 중복** | Low | **High** | **Low** | C 승 |
| **분산 락 통합** | 어려움 | 복잡 | **간단** | C 승 |
| **경로별 분기** | 불가 | 복잡 | **선언적** | C 승 |
| **일관성 보장** | Low | Low | **100%** | C 승 |

**Negative Evidence:**
- [R1] **@Cacheable+@Async 실패:** Spring AOP 프록시 순서로 캐시가 동작하지 않음 (테스트: 2025-11-28)
- [R2] **수동 관리 실패:** Code Review에서 캐시 누락 4건 발견 (2025-11-29)

---

## 결정 (Decision)

**NexonDataCacheAspect를 통한 선언적 캐시 + 분산 락 + ThreadLocal 보존을 적용합니다.**

### Code Evidence

**Evidence ID: [C1]** - NexonDataCacheAspect 구조
```java
// src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java
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

**Evidence ID: [C2]** - ThreadLocal 컨텍스트 보존 (핵심)
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
[ForkJoinPool-1] 콜백 실행 → ThreadLocal 값 없음 (null) ❌
```

**해결:**
```
[Thread-1] snapshot = SkipEquipmentL2CacheContext.snapshot()  // true 캡처
    ↓
[ForkJoinPool-1] SkipEquipmentL2CacheContext.restore(snapshot)  // true 복원 ✅
```

**Evidence ID: [C3]** - 경로별 L2 캐시 분기
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

**Evidence ID: [C4]** - Latch TTL 관리 (Zombie 방지)
```yaml
# application.yml
nexon:
  api:
    latch-initial-ttl-seconds: 60    # 리더 크래시 대비
    latch-finalize-ttl-seconds: 10   # 팔로워 조회 여유
    cache-follower-timeout-seconds: 30  # Follower 대기 상한
```

---

## 결과 (Consequences)

### 개선 효과

| 지표 | Before | After | Evidence ID |
|------|--------|-------|-------------|
| ThreadLocal 유실 | 발생 | **방지** | [E1] |
| L2 중복 저장 | 발생 | **경로별 분기** | [E2] |
| 캐시 로직 산재 | 서비스마다 | **Aspect 일원화** | [E3] |
| 응답 시간 (L1 HIT) | 27ms | **5ms** | [E4] |

### Evidence IDs

| ID | 타입 | 설명 | 검증 |
|----|------|------|------|
| [E1] | 테스트 | ThreadLocal 유실 테스트 통과 | NexonDataCacheAspectTest |
| [E2] | 로그 | Expectation 경로 L2 스킵 확인 | 애플리케이션 로그 |
| [E3] | PR 리뷰 | 서비스 레이어 캐시 코드 제거 | PR #264 |
| [E4] | 부하테스트 | L1 Fast Path 5ms 달성 | #264 리포트 |
| [C1] | 코드 | Aspect 구조 | NexonDataCacheAspect.java |
| [C2] | 코드 | ThreadLocal 보존 | 소스 라인 78-91 |

---

## 재현성 및 검증

### 테스트 실행

```bash
# ThreadLocal 보존 테스트
./gradlew test --tests "maple.expectation.aop.aspect.NexonDataCacheAspectTest"

# AOP Exception 테스트
./gradlew test --tests "maple.expectation.aop.aspect.NexonDataCacheAspectExceptionTest"
```

### 메트릭 확인

```promql
# L1 Cache Hit Rate
rate(cache_hits_total{cache="equipment", layer="l1"}[5m]) /
  (rate(cache_hits_total[5m]) + rate(cache_misses_total[5m]))

# L2 저장 분기 (Expectation 경로)
rate(nexon_cache_l2_skipped_total[5m])
```

---

## 관련 문서

### 연결된 ADR
- **[ADR-003](ADR-003-tiered-cache-singleflight.md)** - SingleFlight 패턴
- **[ADR-011](ADR-011-controller-v4-optimization.md)** - L1 Fast Path

### 코드 참조
- **Aspect:** `src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java`
- **Context:** `src/main/java/maple/expectation/aop/context/SkipEquipmentL2CacheContext.java`
- **Annotation:** `src/main/java/maple/expectation/aop/annotation/NexonDataCache.java`

### 이슈
- **[PR #264](https://github.com/zbnerd/MapleExpectation/pull/264)** - L1 Fast Path 구현
