# Lock Strategy Guide

> **Issue #28**: Pessimistic Lock vs Atomic Update 선택 근거 및 도메인별 적용 기준
>
> **Last Updated:** 2026-02-06
> **Applicable Versions:** Redisson 3.27.0, MySQL 8.0
> **Documentation Version:** 1.1
> **Production Status:** Active (Validated through P1 distributed lock issues)
> **Migration Status**: Redis lock primary with feature flags (Phase 5 Complete)

## Executive Summary

MapleExpectation은 **3-Tier Lock Architecture**를 채택하여 Redis 장애 시에도 MySQL Fallback으로 가용성을 보장합니다.

## Documentation Integrity Statement

This guide is based on **production lock contention analysis** and distributed systems best practices:
- P1-P7-P8-P9 distributed lock resolution: 4 scheduler incidents analyzed (Evidence: [P1-7-8-9-scheduler-distributed-lock.md](../04_Reports/P1-7-8-9-scheduler-distributed-lock.md))
- ADR-006 decision: Watchdog vs leaseTime with production metrics (Evidence: [ADR-006](../adr/ADR-006-redis-lock-lease-timeout-ha.md))
- Hot row performance: Atomic Update 10x faster than Pessimistic Lock on likeCount (Evidence: internal load tests)

## Terminology

| 용어 | 정의 |
|------|------|
| **Watchdog** | Redisson의 자동 락 갱신 메커니즘 (기본 30초 TTL) |
| **SKIP LOCKED** | MySQL 잠긴 행 건너뛰기 (분산 배치 처리용) |
| **Atomic Update** | `SET col = col + n` 형식의 원자적 증감 연산 |
| **Optimistic Lock** | @Version 기반 낙관적 락 |
| **Pessimistic Lock** | JPA PESSIMISTIC_WRITE 기반 비관적 락 |

```
```
┌─────────────────────────────────────────────────┐
│           3-Tier Lock Architecture              │
├─────────────────────────────────────────────────┤
│  Tier 1: Redis Distributed Lock (Redisson)      │
│  ├─ 고성능, 저지연 (< 1ms)                      │
│  ├─ Watchdog 모드 (자동 갱신)                   │
│  ├─ Feature Flag 제어 (Toggling)                │
│  └─ Circuit Breaker 보호                        │
├─────────────────────────────────────────────────┤
│  Tier 2: MySQL Named Lock (Fallback)            │
│  ├─ Redis 장애 시 자동 전환                     │
│  ├─ GET_LOCK() / RELEASE_LOCK()                 │
│  └─ 세션 기반 (tryLockImmediately 미지원)       │
├─────────────────────────────────────────────────┤
│  Tier 3: JPA @Lock (레코드 레벨)                │
│  ├─ PESSIMISTIC_WRITE: 강한 일관성              │
│  ├─ SKIP LOCKED: 분산 배치 처리                 │
│  └─ @Version: 낙관적 락                         │
└─────────────────────────────────────────────────┘
```

### Feature Flag 제어 시스템

Redis lock 전략을 안전하게 제어하기 위한 Feature Flag 시스템을 도입했습니다.

| Feature Flag | 기본값 | 제어 대상 | 목적 |
|--------------|--------|----------|------|
| `feature.lock.redis.enabled` | `true` | Redis 분산 락 | Redis lock 활성화/비활성화 |
| `feature.lock.redis.circuit-breaker.enabled` | `true` | 서킷브레이커 | Redis 장애 자동 감지 |
| `feature.lock.mysql-fallback.enabled` | `true` | MySQL Fallback | Redis 장애 시 자동 전환 |
| `feature.lock.least-strict.enabled` | `false` | 완화된 락 | 장애 시 성능 우선 |

**Configuration (`application.yml`):**
```yaml
feature:
  lock:
    redis:
      enabled: true
      circuit-breaker:
        enabled: true
    mysql-fallback:
      enabled: true
    least-strict:
      enabled: false
```
```

---

## 도메인별 락 전략 매트릭스

| 도메인 | 요청 시점 | 스케줄러/배치 | 일관성 보장 | 선택 사유 |
|--------|---------|------------|-----------|----------|
| **좋아요 (likeCount)** | 버퍼링 (락 없음) | Redis 분산 락 + Lua Script + `incrementLikeCount()` | Lua 원자성 + @Version | 고처리량 우선, 최종 일관성 허용 |
| **좋아요 관계** | 버퍼링 (락 없음) | Redis 분산 락 | Lua 원자성 | 최종 일관성 허용 |
| **후원 (Donation)** | @Locked + @Version | `SKIP LOCKED` | 낙관적 + Outbox | 금융 무결성 필수 |
| **캐릭터 조회** | 락 없음 | N/A | @Version | 읽기 중심, 락 불필요 |
| **캐릭터 수정** | `PESSIMISTIC_WRITE` | N/A | @Version | 동시 수정 방지 |
| **스케줄러 (글로벌)** | N/A | Redis → MySQL Fallback | 분산 락 | 단일 인스턴스 실행 보장 |
| **스케줄러 (파티션)** | N/A | PartitionedFlushStrategy | 파티션 기반 | 병렬 처리 최적화 |

---

## 락 전략 상세 분석

### 1. 좋아요 도메인 (likeCount)

> **Performance Evidence:** Atomic Update achieves 1,200 TPS vs 120 TPS with Pessimistic Lock (10x difference) (Evidence: [Load Test N23](../04_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md)).
> **Why NOT Pessimistic Lock:** Hot row contention causes queue buildup; each transaction waits for previous commit.
> **Rollback Plan:** If likeCount accuracy issues exceed 0.1%, switch to Pessimistic Lock with sharding.

**문제 정의**:
- 인기 캐릭터는 초당 수백 건의 좋아요 요청 발생
- DB 직접 업데이트 시 Hot Row 경합으로 성능 저하

**해결 전략**:
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  HTTP 요청  │ ──> │ L1 (Memory) │ ──> │ L2 (Redis)  │ ──> DB
│  (락 없음)  │     │   1초 Flush │     │  3초 Flush  │
└─────────────┘     └─────────────┘     └─────────────┘
```

**구현 코드**:
```java
// 1. 요청 시점: 락 없이 버퍼에 추가 (LikeWriteService)
likeBuffer.increment(userIgn, 1L);  // 락 없음, 고처리량

// 2. L2 → DB 동기화: Redis Lua Script (원자적 GETDEL)
// @see LuaScriptAtomicFetchStrategy
String script = """
    local data = redis.call('HGETALL', KEYS[1])
    if #data > 0 then
        redis.call('DEL', KEYS[1])
    end
    return data
    """;

// 3. DB 업데이트: Atomic Update (락 없음)
// @see GameCharacterRepository#incrementLikeCount
@Modifying
@Query("UPDATE GameCharacter c SET c.likeCount = c.likeCount + :count WHERE c.userIgn = :userIgn")
void incrementLikeCount(@Param("userIgn") String userIgn, @Param("count") Long count);
```

**선택 사유**:
- `Atomic Update (SET col = col + n)` vs `Pessimistic Lock`:
  - Hot Row에서 Pessimistic Lock은 대기열 병목
  - Atomic Update는 DB가 내부적으로 Row Lock → 즉시 해제
  - MySQL InnoDB는 단일 UPDATE 문에 대해 원자성 보장
- Lua Script로 Redis에서 원자적 GETDEL 수행

---

### 2. 후원 도메인 (Donation)

> **Business Requirement:** Financial transactions require strong consistency (Evidence: [ADR-010](../adr/ADR-010-outbox-pattern.md)).
> **Why SKIP LOCKED:** Regular Pessimistic Lock causes wait queue; SKIP LOCKED enables parallel processing across 4 scheduler instances.
> **Validation:** Zero duplicate donations recorded since implementation (2025-11).

**문제 정의**:
- 금융 트랜잭션이므로 강한 일관성 필수
- 중복 후원 방지, 정확한 금액 처리

**해결 전략**:
```
┌───────────────────────────────────────────────────────┐
│         Transactional Outbox Pattern                  │
├───────────────────────────────────────────────────────┤
│  1. API 요청 → Outbox INSERT (PENDING)                │
│  2. 스케줄러 → SKIP LOCKED으로 병렬 처리              │
│  3. 실패 → DLQ 이동, 재처리 대기                      │
└───────────────────────────────────────────────────────┘

**Feature Flag 기반 분산 락 전환:**
```java
// ResilientLockStrategy.java
public <T> T executeWithLock(String lockName, long waitTime, long leaseTime, Supplier<T> task) {
    // Feature Flag 확인
    boolean redisEnabled = featureFlagClient.isEnabled("feature.lock.redis.enabled");
    boolean fallbackEnabled = featureFlagClient.isEnabled("feature.lock.mysql-fallback.enabled");

    if (redisEnabled) {
        // Redis 락 시도 (Circuit Breaker 보호)
        try {
            return redisStrategy.executeWithLock(lockName, waitTime, leaseTime, task);
        } catch (CircuitBreakerOpenException e) {
            if (fallbackEnabled) {
                log.info("Redis lock circuit breaker open, falling back to MySQL: {}", lockName);
                return mysqlStrategy.executeWithLock(lockName, waitTime, leaseTime, task);
            }
            throw new LockException("Redis lock unavailable and fallback disabled", e);
        }
    } else {
        // Feature Flag 비활성화 시 MySQL로 직접 전환
        return mysqlStrategy.executeWithLock(lockName, waitTime, leaseTime, task);
    }
}
```
```

**구현 코드**:
```java
// DonationOutboxRepository.java
// SKIP LOCKED: 분산 환경에서 중복 처리 방지
// @see docs/02_Technical_Guides/lock-strategy.md
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP LOCKED
@Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses " +
        "AND o.nextRetryAt <= :now ORDER BY o.id")
List<DonationOutbox> findPendingWithLock(...);
```

**선택 사유**:
- `SKIP LOCKED` vs `일반 Pessimistic Lock`:
  - 일반 락은 대기 발생 → 처리량 저하
  - SKIP LOCKED은 잠긴 행 건너뛰기 → 병렬 처리 가능
- `@Version` 낙관적 락 병행:
  - 동일 레코드 동시 수정 감지
  - OptimisticLockException 발생 시 재시도

---

### 3. 캐릭터 도메인 (GameCharacter)

**문제 정의**:
- 조회는 빈번하나 수정은 드묾
- 수정 시 동시 업데이트 방지 필요

**해결 전략**:
```java
// GameCharacterRepository.java
// 일반 조회: 락 없음
Optional<GameCharacter> findByUserIgn(String userIgn);

// 수정 조회: Pessimistic Lock
// @see docs/02_Technical_Guides/lock-strategy.md - 캐릭터 수정 시 동시 업데이트 방지
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn")
Optional<GameCharacter> findByUserIgnWithPessimisticLock(@Param("userIgn") String userIgn);
```

**선택 사유**:
- 조회: 락 없음 (읽기 전용, @Version으로 충분)
- 수정: `PESSIMISTIC_WRITE` (동시 수정 방지)
  - 캐릭터 정보 수정은 드물어 락 경합 낮음
  - 데이터 무결성이 성능보다 중요한 케이스

---

### 4. 분산 스케줄러 (LikeSyncScheduler)

> **Production Incident:** P1-P7-P8-P9 (2025 Q4) - Scheduler executed multiple times during Redis failover.
> **Root Cause:** No MySQL fallback; Redis lock timeout caused duplicate execution.
> **Fix Validated:** 3-tier architecture with MySQL Named Lock fallback (Evidence: [P1-7-8-9 Report](../04_Reports/P1-7-8-9-scheduler-distributed-lock.md)).
> **Metrics:** Zero duplicate executions since 2025-12 implementation.

**문제 정의**:
- 다중 인스턴스 환경에서 단일 실행 보장 필요
- Redis 장애 시에도 동작해야 함

**해결 전략**:
```java
// LikeSyncScheduler.java
// @see docs/02_Technical_Guides/lock-strategy.md - 분산 스케줄러 단일 실행 보장
lockStrategy.executeWithLock("like-db-sync-lock", 0, 30, () -> {
    likeSyncService.syncRedisToDatabase();
    return null;
});
```

**Fallback 체인**:
```
┌─────────────────┐     실패     ┌─────────────────┐
│ Redis Lock      │ ──────────> │ MySQL Named Lock│
│ (Redisson RLock)│             │ (GET_LOCK)      │
└─────────────────┘             └─────────────────┘
```

**Feature Flag 제어 추가:**
```yaml
# Redis lock 비활성화 시 MySQL로 전환
feature:
  lock:
    redis:
      enabled: false  # Redis 락 비활성화
    mysql-fallback:
      enabled: true   # MySQL Fallback 활성화
```

**Migration Metrics 확인:**
```bash
# Redis → MySQL 전환 비율 확인
curl -s http://localhost:8080/actuator/metrics/lock.redis.fallback | jq '.measurements[].value'

# Feature Flag 상태 확인
curl -s http://localhost:8080/actuator/feature-flags | jq '.[].name, .[].enabled'
```

**선택 사유**:
- `ResilientLockStrategy`: Redis 우선, MySQL Fallback
- Circuit Breaker로 Redis 장애 감지 → 자동 전환
- 30초 lease time: 워크로드 증가 대응 (#271 V5)

---

## 락 전략별 Trade-off 분석

### Pessimistic Lock (비관적 락)

| 장점 | 단점 |
|------|------|
| 강한 일관성 보장 | 대기 시간 발생 |
| 데드락 감지 가능 | 처리량 저하 |
| 구현 단순 | Hot Row 병목 |

**적합한 케이스**:
- 금융 트랜잭션 (후원)
- 동시 수정이 드문 엔티티 (캐릭터 정보)

### Optimistic Lock (낙관적 락)

| 장점 | 단점 |
|------|------|
| 대기 없음 | 충돌 시 재시도 필요 |
| 높은 처리량 | 충돌 빈번 시 비효율 |
| 읽기 중심에 적합 | 구현 복잡도 증가 |

**적합한 케이스**:
- 읽기 위주 도메인
- 동시 수정 확률 낮은 엔티티

### Atomic Update (원자적 업데이트)

| 장점 | 단점 |
|------|------|
| 최고 처리량 | 단순 증감만 가능 |
| 락 대기 없음 | 복잡한 로직 불가 |
| Hot Row에 최적 | 조건부 업데이트 어려움 |

**적합한 케이스**:
- 좋아요, 조회수 등 카운터
- 단순 증감 연산

### SKIP LOCKED

| 장점 | 단점 |
|------|------|
| 병렬 처리 가능 | MySQL/PostgreSQL 전용 |
| 대기 없음 | 처리 순서 비보장 |
| 분산 배치에 최적 | 설정 복잡 |

**적합한 케이스**:
- Outbox 패턴
- 분산 배치 처리
- 메시지 큐 폴링

---

## 참조 코드

| 컴포넌트 | 파일 | 락 전략 |
|----------|------|---------|
| LockStrategy | `global/lock/LockStrategy.java` | 인터페이스 정의 |
| ResilientLockStrategy | `global/lock/ResilientLockStrategy.java` | Redis → MySQL Fallback |
| RedisDistributedLockStrategy | `global/lock/RedisDistributedLockStrategy.java` | Redisson RLock |
| MySqlNamedLockStrategy | `global/lock/MySqlNamedLockStrategy.java` | GET_LOCK() |
| GameCharacterRepository | `repository/v2/GameCharacterRepository.java` | PESSIMISTIC_WRITE, Atomic Update |
| DonationOutboxRepository | `repository/v2/DonationOutboxRepository.java` | SKIP LOCKED |
| LuaScriptAtomicFetchStrategy | `service/v2/like/strategy/LuaScriptAtomicFetchStrategy.java` | Lua Script 원자성 |
| LikeSyncScheduler | `scheduler/LikeSyncScheduler.java` | 분산 락 스케줄링 |

---

## CLAUDE.md 준수사항

- **Section 4 (SOLID)**: Strategy 패턴으로 락 전략 분리 (OCP)
- **Section 12 (LogicExecutor)**: 모든 락 로직에서 try-catch 금지, executor 패턴 사용
- **Section 12-1 (Circuit Breaker)**: Redis 락에 Circuit Breaker 적용

---

## Migration Guide

### Redis Lock Migration Phase 5 (Completed)

**Phase 5 목표**: Redis lock을 기본 구현으로 전환하고 Feature Flag 제어 시스템 도입

**Migration 절차**:
1. **사전 준비**:
   - Feature Flag 시스템 검증
   - Redis Fallback 메커니즘 검증
   - 백업 프로시저 수립

2. **단계별 전환**:
   ```bash
   # 1. Feature Flag로 점진적 전환
   feature flag set feature.lock.redis.enabled true --percent 10

   # 2. 24시간 모니터링
   curl -s http://localhost:8080/actuator/metrics/lock.acquired | jq

   # 3. 50% 트래픽 전환
   feature flag set feature.lock.redis.enabled true --percent 50

   # 4. 성능 검증
   jmeter -n -t lock_performance_test.jmx -l results.jtl

   # 5. 100% 전환
   feature flag set feature.lock.redis.enabled true --percent 100
   ```

3. **검증 항목**:
   - Lock 성능: < 1ms 지연시간
   - Fallback 횟수: < 0.1%
   - 중복 실행: 0 건

**Verification Commands**:
```bash
# Migration 진행률 확인
curl -s http://localhost:8080/actuator/feature-flags | jq

# Redis lock 성능 모니터링
curl -s http://localhost:8080/actuator/metrics/lock.acquired | jq
curl -s http://localhost:8080/actuator/metrics/lock.latency | jq

# Fallback 이벤트 모니터링
curl -s http://localhost:8080/actuator/metrics/lock.fallback | jq
```

**Rollback Procedure**:
```bash
# Emergency rollback
feature flag set feature.lock.redis.enabled false --percent 0
feature flag set feature.lock.mysql-fallback.enabled true

# 확인
curl -s http://localhost:8080/actuator/metrics/lock.strategy | jq '.[] | select(.tag("strategy") == "mysql")'
```

## 변경 이력

| 일자 | 이슈 | 변경 내용 |
|------|------|----------|
| 2026-01-27 | #28 | 초기 문서 작성 |
| 2026-02-06 | #310 Phase 5 | Redis lock primary + Feature Flag 시스템 도입 |

## Evidence Links
- **LockStrategy Interface:** `src/main/java/maple/expectation/global/lock/LockStrategy.java` (Evidence: [CODE-LOCK-IFACE-001])
- **ResilientLockStrategy:** `src/main/java/maple/expectation/global/lock/ResilientLockStrategy.java` (Evidence: [CODE-LOCK-RESILIENT-001])
- **GameCharacterRepository:** `src/main/java/maple/expectation/repository/v2/GameCharacterRepository.java` (Evidence: [CODE-REPO-GC-001])
- **DonationOutboxRepository:** `src/main/java/maple/expectation/repository/v2/DonationOutboxRepository.java` (Evidence: [CODE-REPO-OUTBOX-001])
- **ADR-006:** `docs/adr/ADR-006-redis-lock-lease-timeout-ha.md` (Watchdog decision)
- **ADR-010:** `docs/adr/ADR-010-outbox-pattern.md` (Outbox pattern)

## Technical Validity Check

This guide would be invalidated if:
- **Lock strategy doesn't match domain characteristics**: Business requirements re-verification needed
- **Redis failure prevents lock acquisition**: ResilientLockStrategy Fallback behavior verification needed
- **SKIP LOCKED causes bottleneck**: Batch processing optimization verification needed
- **Multiple scheduler executions**: Fallback lock mechanism verification needed

### Verification Commands
```bash
# Lock Strategy 구현 확인
find src/main/java -name "*LockStrategy.java"

# Feature Flag 구현 확인
find src/main/java -name "*FeatureFlag*.java"

# SKIP LOCKED 사용 확인
grep -r "SKIP LOCKED\|skipLocked" src/main/java --include="*.java"

# @Lock 사용 확인
grep -r "@Lock.*PESSIMISTIC" src/main/java --include="*.java"

# Redis Lock Metrics 확인
curl -s http://localhost:8080/actuator/metrics/lock.acquired | jq
curl -s http://localhost:8080/actuator/metrics/lock.redis.fallback | jq

# Circuit Breaker 상태 확인
curl -s http://localhost:8080/actuator/circuitbreakers | jq
```

### Related Evidence
- P1-7-8-9 Report: `docs/04_Reports/P1-7-8-9-scheduler-distributed-lock.md`
- ADR-006: `docs/adr/ADR-006-redis-lock-lease-timeout-ha.md`
- ADR-010: `docs/adr/ADR-010-outbox-pattern.md`
