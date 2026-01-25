# ADR-003: 다계층 캐시 및 SingleFlight 패턴 도입

## 상태
Accepted

## 맥락 (Context)

캐시 만료 시점에 동시 요청이 몰리면 Cache Stampede가 발생합니다.

관찰된 문제:
- 인기 캐릭터 조회 캐시 TTL 만료 직후 동시 요청 폭주
- 결과: DB/외부 API에 동시 요청 → 과부하
- Thread Pool 고갈 및 응답 지연

## 검토한 대안 (Options Considered)

### 옵션 A: TTL 랜덤화
```java
Duration ttl = Duration.ofMinutes(5 + random.nextInt(2));
```
- 장점: 만료 시점 분산
- 단점: 여전히 동시 만료 가능
- **결론: 부분적 해결**

### 옵션 B: Cache Aside + synchronized
```java
synchronized (key.intern()) {
    if (cache.get(key) == null) {
        cache.put(key, fetchFromDb());
    }
}
```
- 장점: 중복 조회 방지
- 단점: 분산 환경에서 동작 안 함
- **결론: 확장성 부족**

### 옵션 C: TieredCache + SingleFlight + Redisson 분산락
- 장점: 중복 요청 완벽 제거, L1으로 추가 속도 향상, 분산 환경 지원
- 단점: 구현 복잡도
- **결론: 채택**

## 결정 (Decision)

**L1(Caffeine) + L2(Redis) 다계층 캐시와 SingleFlight를 조합합니다.**

### 캐시 조회 흐름
```
[Request]
    ↓
[L1 Cache - Caffeine]  ← HIT: < 5ms
    ↓ miss
[L2 Cache - Redis]     ← HIT: < 20ms
    ↓ miss
[SingleFlight]         ← 동일 key 요청 병합
    ↓
[External API / DB]
```

### 실제 캐시 설정 (CacheConfig.java)
| Cache Name | L1 TTL | L1 Max | L2 TTL | 용도 |
|------------|--------|--------|--------|------|
| `equipment` | 5 min | 5,000 | 10 min | Nexon API 장비 데이터 |
| `cubeTrials` | 10 min | 5,000 | 20 min | Cube 확률 계산 |
| `ocidCache` | 30 min | 5,000 | 60 min | OCID 매핑 |
| `expectationV4` | 60 min | 5,000 | 60 min | 기대값 계산 결과 |

### TieredCacheManager 구현
```java
// maple.expectation.global.cache.TieredCacheManager
@RequiredArgsConstructor
public class TieredCacheManager extends AbstractCacheManager {
    private final CacheManager l1Manager;      // Caffeine
    private final CacheManager l2Manager;      // Redis
    private final LogicExecutor executor;
    private final RedissonClient redissonClient;  // 분산 락용
    private final MeterRegistry meterRegistry;    // 메트릭 수집용

    // 인스턴스 풀링으로 O(1) 조회
    private final ConcurrentMap<String, Cache> cachePool = new ConcurrentHashMap<>();

    @Override
    public Cache getCache(String name) {
        return cachePool.computeIfAbsent(name, this::createTieredCache);
    }
}
```

### SingleFlightExecutor 구현
```java
// maple.expectation.global.concurrency.SingleFlightExecutor
public class SingleFlightExecutor<T> {
    private final int followerTimeoutSeconds;
    private final Executor executor;
    private final Function<String, T> timeoutFallback;
    private final ConcurrentHashMap<String, InFlightEntry<T>> inFlight = new ConcurrentHashMap<>();

    public CompletableFuture<T> executeAsync(
            String key,
            Supplier<CompletableFuture<T>> asyncSupplier) {

        InFlightEntry<T> existing = inFlight.putIfAbsent(key, newEntry);

        if (existing == null) {
            return executeAsLeader(key, newEntry, asyncSupplier);  // 실제 계산
        }
        return executeAsFollower(key, existing.promise());  // Leader 결과 대기
    }
}
```

### Follower 타임아웃 격리 (P1 Fix)
```java
// PR #160: 각 follower에게 독립적인 Future 생성 (공유 promise 보호)
private CompletableFuture<T> executeAsFollower(String key, CompletableFuture<T> leaderFuture) {
    CompletableFuture<T> isolatedFuture = new CompletableFuture<>();
    leaderFuture.whenComplete((result, error) -> {
        if (error != null) isolatedFuture.completeExceptionally(error);
        else isolatedFuture.complete(result);
    });

    return isolatedFuture
            .orTimeout(followerTimeoutSeconds, TimeUnit.SECONDS)
            .exceptionallyCompose(e -> handleFollowerException(key, e));
}
```

## 결과 (Consequences)

| 시나리오 | Before | After |
|----------|--------|-------|
| 캐시 만료 + 100 동시 요청 | DB 100회 | **DB 1회** |
| p99 응답시간 | 2,340ms | **180ms** |
| DB 부하 | 스파이크 발생 | **안정** |

Chaos Test N01 (Thundering Herd), N05 (Hot Key)에서 검증 완료.

## 참고 자료
- `maple.expectation.global.cache.TieredCacheManager`
- `maple.expectation.global.cache.TieredCache`
- `maple.expectation.global.concurrency.SingleFlightExecutor`
- `maple.expectation.config.CacheConfig`
- `docs/01_Chaos_Engineering/06_Nightmare/`
