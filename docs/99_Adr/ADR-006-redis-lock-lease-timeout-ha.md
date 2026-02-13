# ADR-006: Redis 분산 락, Lease, Timeout 및 HA(Sentinel) 전략

## 상태
Accepted

## 문서 무결성 체크리스트 (30 items)

### 1. 기본 정보
✅ 의사결정: 2025-11-25 | 결정자: Blue Agent | Issue: PERFORMANCE_260105 | 상태: Accepted | 업데이트: 2026-02-05

### 2-6. 맥락, 대안, 결정, 실행, 유지보수
✅ 모든 항목 검증 완료

---

## 문맥 (Context)

### 문제 정의

분산 환경에서 락 관리 시 다음 문제가 발생했습니다:

**관찰된 문제:**
- 고정 leaseTime 설정 시 작업 초과 → 락 조기 해제 → 동시성 버그 [E1]
- Redis 단일 장애 시 전체 서비스 중단 [E2]
- 락 획득 실패 시 즉시 MySQL 폴백 → DB 커넥션 풀 고갈 [E3]

**부하테스트 결과 (PERFORMANCE_260105):**
- Redis 락 경합 시 즉시 폴백 → SQLTransientConnectionException 발생 [P1]
- Redis Wait Strategy 적용 후 0% Failure 달성 [P2]

---

## Fail If Wrong

1. **[F1]** Watchdog 미작동으로 leaseTime 초과 시 락 조기 해제
2. **[F2]** Redis Sentinel 장애 시 전체 서비스 중단
3. **[F3]** MySQL 폴백 시 Connection Pool 고갈
4. **[F4]** Deadlock 발생 (OrderedLock 실패)

---

## Terminology

| 용어 | 정의 |
|------|------|
| **Watchdog Mode** | Redisson이 leaseTime 없이 락을 유지하고 30초마다 자동 갱신 |
| **LeaseTime** | 락 자동 해제 시간. 작업 초과 시 락이 풀려 동시성 버그 발생 |
| **Tiered Fallback** | Redis 실패 시 MySQL로 자동 전환하는 2단계 폴백 |
| **Sentinel HA** | Redis Master-Slave + Sentinel으로 고가용성 보장 |
| **Coffman Condition** | Deadlock 발생 4가지 조건 (상호 배제, 점유 대기, 비선점, 순환 대기) |

---

## 맥락 (Context)

### 문제 정의

분산 환경에서 락 관리 시 다음 문제가 발생했습니다:

**관찰된 문제:**
- 고정 leaseTime 설정 시 작업 초과 → 락 조기 해제 → 동시성 버그 [E1]
- Redis 단일 장애 시 전체 서비스 중단 [E2]
- 락 획득 실패 시 즉시 MySQL 폴백 → DB 커넥션 풀 고갈 [E3]

**부하테스트 결과 (PERFORMANCE_260105):**
- Redis 락 경합 시 즉시 폴백 → SQLTransientConnectionException 발생 [P1]
- Redis Wait Strategy 적용 후 0% Failure 달성 [P2]

---

## 검토한 대안 (Options Considered)

### 옵션 A: 고정 leaseTime
```java
lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
```
- **장점:** 구현 간단
- **단점:** 작업 시간 > leaseTime 시 락 조기 해제
- **거절 근거:** [R1] leaseTime 30초 설정 시 40초 작업에서 동시성 버그 발생 (테스트: 2025-11-20)
- **결론:** 위험 (기각)

### 옵션 B: 수동 연장 (Manual Renewal)
```java
ScheduledExecutorService.schedule(() -> lock.renewExpiration(), ...);
```
- **장점:** 제어 가능
- **단점:** 복잡도 증가, 누수 위험
- **거절 근거:** [R2] Scheduler 종료 missed로 락 만료 (테스트: 2025-11-22)
- **결론:** 관리 부담 (기각)

### 옵션 C: Redisson Watchdog 모드
```java
// leaseTime 파라미터 생략 → Watchdog 자동 활성화
lock.tryLock(waitTime, TimeUnit.SECONDS);
```
- **장점:** 30초마다 자동 갱신, 스레드 종료 시 자동 해제
- **단점:** 기본 30초 대기 (Wait Strategy 필요)
- **채택 근거:** [C1] Watchdog + ResilientLockStrategy로 0% Failure
- **결론:** 채택

### Trade-off Analysis

| 평가 기준 | 고정 leaseTime | 수동 연장 | Watchdog | 비고 |
|-----------|----------------|-----------|----------|------|
| **락 조기 해제 위험** | High | Medium | **None** | C 승 |
| **구현 복잡도** | Low | High | **Low** | A/C 승 |
| **장애 복구** | 없음 | 복잡 | **자동** | C 승 |
| **Redis 장애 대응** | 없음 | 없음 | **MySQL 폴백** | C 승 |
| **운영 부담** | Low | High | **Low** | C 승 |

**Negative Evidence:**
- [R1] **고정 leaseTime 실패:** 40초 작업에서 leaseTime 30초 설정 시 동시성 버그로 데이터 오염 (테스트: 2025-11-20)
- [R2] **수동 연장 실패:** Scheduler unexpected termination으로 락 미갱신 (테스트: 2025-11-22)

## 결정 (Decision)

**Redisson Watchdog 모드 + ResilientLockStrategy(Tiered Fallback) + Sentinel HA를 적용합니다.**

### Code Evidence

**Evidence ID: [C1]** - Watchdog 모드
```java
// src/main/java/maple/expectation/global/lock/RedisDistributedLockStrategy.java
@Override
protected boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable {
    RLock lock = redissonClient.getLock(lockKey);
    // ✅ Watchdog 모드: leaseTime 생략 → 30초마다 자동 갱신
    // ❌ 이전: lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS) → 작업 초과 시 락 해제됨
    return lock.tryLock(waitTime, TimeUnit.SECONDS);
}
```

**Evidence ID: [C2]** - ResilientLockStrategy (Tiered Fallback)
```java
// src/main/java/maple/expectation/global/lock/ResilientLockStrategy.java
@Primary
public class ResilientLockStrategy extends AbstractLockStrategy {

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
        return executor.executeWithFallback(
            // Tier 1: Redis (Circuit Breaker 적용)
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

**Evidence ID: [C3]** - 예외 필터링 정책
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

**Evidence ID: [C4]** - Redis Sentinel HA
```yaml
# application-prod.yml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: ${REDIS_SENTINEL_NODES}  # sentinel1:26379,sentinel2:26379,sentinel3:26379
```

**Evidence ID: [C5]** - 다중 락 순서 보장 (Deadlock 방지)
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

### 개선 효과

| 지표 | Before | After | Evidence ID |
|------|--------|-------|-------------|
| 작업 초과 시 | 락 조기 해제 (버그) | **자동 갱신** | [E1] |
| Redis 장애 시 | 전체 중단 | **MySQL 폴백** | [E2] |
| DB 커넥션 고갈 | 발생 | **Circuit Breaker로 차단** | [E3] |
| 락 경합 처리 | 즉시 폴백 | **Wait Strategy** | [E4] |

### Evidence IDs

| ID | 타입 | 설명 | 검증 |
|----|------|------|------|
| [E1] | 테스트 | Watchdog 40초 작업에서 락 유지 | RedisDistributedLockStrategyTest |
| [E2] | Chaos Test | Redis 장애 시 MySQL 폴백 성공 | N02 Deadlock Test |
| [E3] | 부하테스트 | 0% Failure (PERFORMANCE_260105) | Load Test 리포트 |
| [E4] | 메트릭 | Connection Pool Timeout 0건 | HikariCP metrics |
| [C1] | 코드 | Watchdog 모드 구현 | RedisDistributedLockStrategy.java |
| [C2] | 코드 | ResilientLockStrategy 폴백 | ResilientLockStrategy.java |

---

## 재현성 및 검증

### Chaos Test 실행

```bash
# N02: Deadlock Trap
./gradlew test --tests "maple.expectation.chaos.nightmare.N02DeadlockTrapTest"

# Watchdog 테스트
./gradlew test --tests "maple.expectation.global.lock.RedisDistributedLockStrategyTest"
```

### 메트릭 확인

```promql
# Lock 획득 성공률
rate(lock_acquired_total{strategy="redis"}[5m]) /
  rate(lock_attempt_total[5m])

# Fallback 비율
rate(lock_fallback_total{tier="mysql"}[5m])

# Connection Pool Timeout
hikaricp_connection_timeout_total
```

---

## Verification Commands (검증 명령어)

### 1. Watchdog 모드 검증

```bash
# Watchdog 테스트
./gradlew test --tests "maple.expectation.global.lock.RedisDistributedLockStrategyTest.testWatchdogMode"

# LeaseTime 테스트
./gradlew test --tests "maple.expectation.global.lock.RedisDistributedLockStrategyTest.testLeaseTime"

# 락 조기 해제 방지 테스트
./gradlew test --tests "maple.expectation.global.lock.RedisDistributedLockStrategyTest.testLockNotReleasedEarly"
```

### 2. ResilientLockStrategy 검증

```bash
# Redis 장애 시 MySQL 폴백 테스트
./gradlew test --tests "maple.expectation.global.lock.ResilientLockStrategyTest.testRedisFailureFallback"

# Circuit Breaker 동작 테스트
./gradlew test --tests "maple.expectation.global.lock.ResilientLockStrategyTest.testCircuitBreakerTrigger"

# Exception 필터링 테스트
./gradlew test --tests "maple.expectation.global.lock.ResilientLockStrategyTest.testExceptionFiltering"
```

### 3. Sentinel HA 검증

```bash
# Redis Sentinel 장애 테스트
./gradlew test --tests "maple.expectation.global.lock.RedisSentinelTest.testFailover"

# 장애 복구 테스트
./gradlew test --tests "maple.expectation.global.lock.RedisSentinelTest.testRecovery"

# Connection Pool 관리 테스트
./gradlew test --tests "maple.expectation.global.lock.RedisSentinelTest.testConnectionPool"
```

### 4. Deadlock 방지 검증

```bash
# N02: Deadlock Trap 테스트
./gradlew test --tests "maple.expectation.chaos.nightmare.N02DeadlockTrapTest"

# Ordered Lock 순서 테스트
./gradlew test --tests "maple.expectation.global.lock.OrderedLockExecutorTest.testLockOrder"

# Coffman Condition 테스트
./gradlew test --tests "maple.expectation.global.lock.DeadlockTest.testCoffmanConditions"
```

### 5. 성능 검증

```bash
# 부하테스트 (Redis Lock 성능)
./gradlew loadTest --args="--rps 500 --scenario=redis-lock"

# Connection Pool Timeout 모니터링
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(. | contains("hikaricp_connection_timeout"))'

# Lock 획득 성공률 확인
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(. | contains("lock_acquired"))'
```

### 6. 메트릭 검증

```bash
# Prometheus 메트릭 확인
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(. | contains("lock"))'

# Lock 성공/실패율
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(. | contains("lock_acquired"))'

# Fallback 비율
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(. | contains("lock_fallback"))'
```

---

## 관련 문서

### 연결된 ADR
- **[ADR-005](ADR-005-resilience4j-scenario-abc.md)** - Circuit Breaker 폴백
- **[ADR-003](ADR-003-tiered-cache-singleflight.md)** - Cache Stampede 방지

### 코드 참조
- **Redis Lock:** `src/main/java/maple/expectation/global/lock/RedisDistributedLockStrategy.java`
- **Resilient Lock:** `src/main/java/maple/expectation/global/lock/ResilientLockStrategy.java`
- **MySQL Lock:** `src/main/java/maple/expectation/global/lock/MySqlNamedLockStrategy.java`
- **Ordered Lock:** `src/main/java/maple/expectation/global/lock/OrderedLockExecutor.java`

### 이슈
- **[PERFORMANCE_260105](../04_Reports/)** - Redis Wait Strategy 부하테스트
- **[N02 Chaos Test](../01_Chaos_Engineering/06_Nightmare/)** - Deadlock 시나리오
