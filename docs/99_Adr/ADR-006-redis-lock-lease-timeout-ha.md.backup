# ADR-006: Redis 분산 락, Lease, Timeout 및 HA(Sentinel) 전략

## 상태
Accepted

## 맥락 (Context)

분산 환경에서 락 관리 시 다음 문제가 발생했습니다:

**관찰된 문제:**
- 고정 leaseTime 설정 시 작업 초과 → 락 조기 해제 → 동시성 버그
- Redis 단일 장애 시 전체 서비스 중단
- 락 획득 실패 시 즉시 MySQL 폴백 → DB 커넥션 풀 고갈

**부하테스트 결과 (PERFORMANCE_260105):**
- Redis 락 경합 시 즉시 폴백 → SQLTransientConnectionException 발생
- Redis Wait Strategy 적용 후 0% Failure 달성

## 검토한 대안 (Options Considered)

### 옵션 A: 고정 leaseTime
```java
lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
```
- 장점: 구현 간단
- 단점: 작업 시간 > leaseTime 시 락 조기 해제
- **결론: 위험**

### 옵션 B: 수동 연장 (Manual Renewal)
```java
ScheduledExecutorService.schedule(() -> lock.renewExpiration(), ...);
```
- 장점: 제어 가능
- 단점: 복잡도 증가, 누수 위험
- **결론: 관리 부담**

### 옵션 C: Redisson Watchdog 모드
```java
// leaseTime 파라미터 생략 → Watchdog 자동 활성화
lock.tryLock(waitTime, TimeUnit.SECONDS);
```
- 장점: 30초마다 자동 갱신, 스레드 종료 시 자동 해제
- 단점: 기본 30초 대기
- **결론: 채택**

## 결정 (Decision)

**Redisson Watchdog 모드 + ResilientLockStrategy(Tiered Fallback) + Sentinel HA를 적용합니다.**

### 1. Watchdog 모드 (자동 갱신)
```java
// maple.expectation.global.lock.RedisDistributedLockStrategy
@Override
protected boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable {
    RLock lock = redissonClient.getLock(lockKey);
    // ✅ Watchdog 모드: leaseTime 생략 → 30초마다 자동 갱신
    // ❌ 이전: lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS) → 작업 초과 시 락 해제됨
    return lock.tryLock(waitTime, TimeUnit.SECONDS);
}
```

### 2. ResilientLockStrategy (Tiered Fallback)
```java
// maple.expectation.global.lock.ResilientLockStrategy
@Primary
public class ResilientLockStrategy extends AbstractLockStrategy {

    // Tier 1: Redis 우선 시도 (Circuit Breaker 적용)
    // Tier 2: Redis 실패 시 MySQL Named Lock 폴백

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
        return executor.executeWithFallback(
            // Tier 1: Redis
            () -> circuitBreaker.executeCheckedSupplier(() ->
                redisLockStrategy.executeWithLock(key, waitTime, leaseTime, task)
            ),
            // Tier 2: MySQL Fallback (인프라 예외만)
            (t) -> handleFallback(t, key, "executeWithLock",
                () -> mysqlLockStrategy.executeWithLock(key, waitTime, leaseTime, task)
            ),
            context
        );
    }
}
```

### 3. 예외 필터링 정책
```java
// 비즈니스 예외 (ClientBaseException): Fallback 금지, 즉시 전파
// 인프라 예외 (RedisException, CallNotPermittedException): MySQL Fallback 허용
// Unknown 예외 (NPE 등): 즉시 전파 (버그 조기 발견)

private boolean isInfrastructureException(Throwable cause) {
    return cause instanceof DistributedLockException
            || cause instanceof CallNotPermittedException
            || cause instanceof RedisException
            || cause instanceof RedisTimeoutException;
}
```

### 4. Redis Sentinel HA 설정
```yaml
# application-prod.yml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: ${REDIS_SENTINEL_NODES}  # sentinel1:26379,sentinel2:26379,sentinel3:26379
```

### 5. Lock Wait Timeout 계층
```yaml
# application.yml
spring:
  datasource:
    hikari:
      # MySQL 세션 lock_wait_timeout (MDL Freeze 방지)
      connection-init-sql: "SET SESSION lock_wait_timeout = 8"

nexon:
  api:
    cache-follower-timeout-seconds: 30  # Follower 대기 상한
    latch-initial-ttl-seconds: 60       # 래치 초기 TTL
    latch-finalize-ttl-seconds: 10      # 래치 정리 후 TTL
```

### 6. 다중 락 순서 보장 (Deadlock 방지)
```java
// Coffman Condition #4 (Circular Wait) 방지
@Override
public <T> T executeWithOrderedLocks(
        List<String> keys,
        long totalTimeout, TimeUnit timeUnit, long leaseTime,
        ThrowingSupplier<T> task) throws Throwable {

    // 키를 알파벳순으로 정렬 후 순차 획득
    List<String> sortedKeys = keys.stream().sorted().toList();
    // ...
}
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| 작업 초과 시 | 락 조기 해제 (버그) | **자동 갱신** |
| Redis 장애 시 | 전체 중단 | **MySQL 폴백** |
| DB 커넥션 고갈 | 발생 | **Circuit Breaker로 차단** |
| 락 경합 처리 | 즉시 폴백 | **Wait Strategy** |

**부하테스트 효과:**
- PERFORMANCE_260105: Redis Wait Strategy로 0% Failure 달성
- Connection Pool 안정 유지 (Timeout 0건)

## 참고 자료
- `maple.expectation.global.lock.RedisDistributedLockStrategy`
- `maple.expectation.global.lock.ResilientLockStrategy`
- `maple.expectation.global.lock.MySqlNamedLockStrategy`
- `maple.expectation.global.lock.OrderedLockExecutor`
- `docs/01_Chaos_Engineering/06_Nightmare/N02_Deadlock_Trap/`
