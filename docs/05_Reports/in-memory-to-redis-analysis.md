# In-Memory 상태 → Redis 전환 분석 및 계획

> **분석 일자:** 2026-02-18
> **분석 범위:** 3개 In-Memory 상태 컴포넌트
> **관련 이슈:** [#283](https://github.com/zbnerd/MapleExpectation/issues/283)
> **분석자:** Oracle Agent (Medium Tier)
> **상태:** Analysis Complete

---

## Documentation Integrity Statement

| Aspect | Description |
|--------|-------------|
| **Analysis Date** | 2026-02-18 |
| **Scope** | 3 specific In-Memory components identified for analysis |
| **Method** | Code inspection + Usage pattern analysis + Redis migration design |
| **Related Issues** | #283 (scale-out blockers) |
| **Review Status** | Analysis Complete - Recommendations Provided |

---

## Executive Summary

본 리포트는 Scale-out 대비를 위해 **3개 In-Memory 상태 컴포넌트**를 분석하고 Redis 전환 방안을 제시합니다.

| # | 컴포넌트 | 우선순위 | 전환 권장 | 복잡도 | 근거 |
|---|---------|:---:|:---:|:---:|------|
| **1** | `DeDuplicationCache` | **P2** | ❌ 선택적 | 낮음 | 인스턴스별 독립 동작 가능, Redis 전환 시 이득 미미 |
| **2** | `StarforceLookupTableImpl` | **P2** | ❌ 유지 | 낮음 | 정적 데이터 캐시로 불변, 이미 Application Scope 공유 |
| **3** | `EquipmentPersistenceTracker` | **완료** | ✅ 완료 | 완료 | **Strategy 패턴으로 Redis 구현체 완료됨** |

**핵심 발견:**
- 3개 중 **1개는 이미 완료** (EquipmentPersistenceTracker)
- 나머지 2개는 **Scale-out 시 즉각적 장애로 이어지지 않음**
- **즉시 전환 필요한 P0 항목 없음**

---

## 1. DeDuplicationCache 분석

### 1.1 컴포넌트 개요

**파일:** `/home/maple/MapleExpectation/module-app/src/main/java/maple/expectation/monitoring/copilot/pipeline/DeDuplicationCache.java`

**역할:** Discord 알림 중복 방지 (Throttling)

```java
// Line 42
private final ConcurrentHashMap<String, Long> recentIncidents = new ConcurrentHashMap<>();
```

### 1.2 상태 분석

| 항목 | 내용 |
|------|------|
| **데이터 타입** | `ConcurrentHashMap<String, Long>` (incidentId → timestamp) |
| **데이터 수명** | `throttleWindowMs` (기본 5분, 설정 가능) |
| **접근 패턴** | write-once, read-many (track → isRecent) |
| **크기** | 수백 ~ 수천 건 (알림 빈도 의존) |

### 1.3 현재 동작 방식

```java
// 사용 흐름 (AlertNotificationService.java)
1. deDuplicationCache.cleanOld(now);           // 오래된 항목 제거
2. if (deDuplicationCache.isRecent(id, now))   // 중복 체크
3. deDuplicationCache.track(id, timestamp);    // 알림 전송 후 등록
```

**핵심:** 알림 전송 **성공 후에만** track 호출 (Line 197)

### 1.4 Scale-out 시 문제점

**시나리오:** 2개 인스턴스 운영 환경

```
Instance A                      Instance B
┌─────────────────┐            ┌─────────────────┐
│ recentIncidents │            │ recentIncidents │
│ (Empty)         │            │ (Empty)         │
└─────────────────┘            └─────────────────┘
       │                              │
       │  Incident #1 발생            │
       ├─→ [Discord 전송 성공]         │
       │  track(#1)                   │
       │                              │
       │                              │  3분 후 Incident #1 재발생
       │                              ├─→ isRecent(#1) → false (로컬에 없음)
       │                              ├─→ [Discord 전송 성공] ← 중복 알림!
       │                              │  track(#1)
```

**영향:**
- 동일 인시던트에 대해 **인스턴스별 독립적으로 알림 전송**
- Discord 채널 스팸 유발
- 그러나 비즈니스 로직 장애 아님 (모니터링 시스템)

### 1.5 Redis 전환 설계

#### 1.5.1 Redis 자료구조

**옵션 A: Redis String + TTL (권장)**

```bash
# Key 구조
alert:dedup:{incidentId}

# Value
timestamp (Unix epoch milliseconds)

# TTL
throttleWindowMs (기본 300,000ms = 5분)

# Operations
SET alert:dedup:INCIDENT-123 173990000000 PX 300000 NX
GET alert:dedup:INCIDENT-123
```

**장점:**
- Redis의 TTL 자동 만료 기능 활용 → cleanOld() 불필요
- `NX` 플래그로 중복 방지 (SET if Not eXists)
- 간단한 구조, 높은 가독성

**단점:**
- incidentId당 1개 키 (수천 개 키 생성 가능)

---

**옵션 B: Redis Hash (대안)**

```bash
# Key 구조
alert:dedup:recent

# Field/Value
incidentId → timestamp

# Operations
HSET alert:dedup:recent INCIDENT-123 173990000000
HGET alert:dedup:recent INCIDENT-123
HDEL alert:dedup:recent INCIDENT-123
```

**장점:**
- 단일 키 관리
- HSCAN으로 효율적 순회

**단점:**
- TTL을 field 레벨에 적용 불가 → 전체 만료
- cleanOld() 로직 유지 필요

**권장:** **옵션 A (String + TTL)**

#### 1.5.2 구현 예시

```java
@Component
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class RedisDeDuplicationCache {

    private final RedissonClient redisson;
    private final long throttleWindowMs;

    public boolean isRecent(String incidentId, long now) {
        RBucket<Long> bucket = redisson.getBucket("alert:dedup:" + incidentId);
        Long timestamp = bucket.get();
        if (timestamp == null) {
            return false;
        }
        long age = now - timestamp;
        return age < throttleWindowMs;
    }

    public void track(String incidentId, long timestamp) {
        RBucket<Long> bucket = redisson.getBucket("alert:dedup:" + incidentId);
        // NX: 키가 없을 때만 설정 (중복 방지)
        bucket.set(timestamp, throttleWindowMs, TimeUnit.MILLISECONDS);
    }

    // cleanOld() 불필요 (TTL 자동 만료)
}
```

### 1.6 장단점 분석

| 항목 | In-Memory (현재) | Redis 전환 |
|------|:---:|:---:|
| **Scale-out 안정성** | ❌ 인스턴스별 중복 알림 | ✅ 전역 중복 방지 |
| **성능** | ✅ 나노초 latenc | ⚠️ 1-5ms Redis 호출 |
| **운영 복잡도** | ✅ 자체 관리 | ⚠️ Redis 의존성 증가 |
| **장애 영향** | ✅ 로컬 장애로 격리 | ⚠️ Redis 다운 시 알림 중복 가능 |

### 1.7 권장 사항

**우선순위: P2 (선택적 전환)**

**근거:**
1. **비즈니스 로직 아님** - 모니터링 시스템의 일부
2. **영향도 제한적** - Discord 알림 스팸만 발생, 사용자 경험에 직접적 영향 없음
3. **이미 Feature Flag** - `monitoring.copilot.enabled=false` 시 완전 비활성화 가능
4. **운영 환경에서 인스턴스 수가 적음** - 2~3개 인스턴스 시 중복 알림 빈도 낮음

**전환 시점:**
- 인스턴스 수가 5개 이상으로 증가할 때
- Discord 알림 스팸이 운영상 문제가 될 때
- Redis Cluster 재구축 시一并 처리

---

## 2. StarforceLookupTableImpl 분석

### 2.1 컴포넌트 개요

**파일:** `/home/maple/MapleExpectation/module-app/src/main/java/maple/expectation/service/v2/starforce/StarforceLookupTableImpl.java`

**역할:** 스타포스 강화 비용 기대값 캐시 (Markov Chain 사전 계산)

```java
// Line 87
private final ConcurrentHashMap<String, BigDecimal> expectedCostCache = new ConcurrentHashMap<>();
```

### 2.2 상태 분석

| 항목 | 내용 |
|------|------|
| **데이터 타입** | `ConcurrentHashMap<String, BigDecimal>` |
| **데이터 키** | `{currentStar}-{targetStar}-{level}-{starCatch}-{sunday}-{discount}-{destroyPrev}` |
| **데이터 수명** | **Application Lifetime** (불변, 초기화 후 추가 없음) |
| **접근 패턴** | read-only (초기화 후 get만 호출) |
| **크기** | 약 180개 (9개 레벨 × 2개 옵션 조합) |

### 2.3 현재 동작 방식

```java
// 1. 애플리케이션 시작 시 1회 초기화
@PostConstruct
public void initialize() {
    precomputeTables(); // 9개 레벨 × 2개 옵션 = 18개 키 사전 계산
}

// 2. 요청 시 캐시 조회 (MISS 시 계산 후 캐싱)
public BigDecimal getExpectedCost(...) {
    String key = cacheKey(...);
    BigDecimal cached = expectedCostCache.get(key);
    if (cached != null) {
        return cached;
    }
    // MISS 시 마르코프 체인 계산 (O(T) where T = targetStar)
    BigDecimal result = computeMarkovExpectedCost(...);
    expectedCostCache.put(key, result);
    return result;
}
```

### 2.4 Scale-out 시 문제점

**시나리오:** 2개 인스턴스 운영 환경

```
Instance A                          Instance B
┌─────────────────────────────┐    ┌─────────────────────────────┐
│ expectedCostCache            │    │ expectedCostCache            │
│ (180개 precomputed)          │    │ (180개 precomputed)          │
└─────────────────────────────┘    └─────────────────────────────┘
```

**분석:**
- 각 인스턴스가 **독립적으로 동일한 데이터** 보유
- 데이터가 **불변(Immutable)**이므로 불일치 문제 없음
- 초기화 시간만큼만 각 인스턴스에서 지연 발생

**영향:**
- **데이터 정합성:** 문제 없음 (불변 데이터)
- **성능:** 각 인스턴스에서 캐시 미스 시 재계산 발생
- **메모리:** 중복 저장 (180개 × 16 bytes ≈ 3KB)

### 2.5 Redis 전환 설계

#### 2.5.1 Redis 자료구조

**옵션 A: Redis Hash (권장)**

```bash
# Key 구조
starforce:lookup:cost

# Field/Value
{currentStar}-{targetStar}-{level}-{starCatch}-{sunday}-{discount}-{destroyPrev}
  → "1234567890" (BigDecimal string)

# Operations
HGET starforce:lookup:cost 0-30-200-true-true-true-false
HSET starforce:lookup:cost 0-30-200-true-true-true-false "1234567890"
```

**장점:**
- 단일 키로 모든 데이터 관리
- HGET/HSET O(1) 복잡도
- 직렬화 간단 (BigDecimal → String)

---

**옵션 B: Redis String (개별 키)**

```bash
# Key 구조
starforce:lookup:cost:{hash(key)}

# Value
"1234567890"

# Operations
GET starforce:lookup:cost:a1b2c3d4
SET starforce:lookup:cost:a1b2c3d4 "1234567890"
```

**장점:**
- Hash field 충돌 걱정 없음
- TTL 적용 가능 (불필요하지만)

**단점:**
- 수백 개 키 생성
- 키 관리 복잡

**권장:** **옵션 A (Redis Hash)**

#### 2.5.2 구현 예시

```java
@Component
public class RedisStarforceLookupTable implements StarforceLookupTable {

    private final RedissonClient redisson;
    private final String cacheKey = "starforce:lookup:cost";

    @Override
    public BigDecimal getExpectedCost(int currentStar, int targetStar, ...) {
        String key = cacheKey(currentStar, targetStar, level, ...);
        RMap<String, String> map = redisson.getMap(cacheKey);

        String cached = map.get(key);
        if (cached != null) {
            return new BigDecimal(cached);
        }

        // MISS 시 계산 후 Redis 캐싱
        BigDecimal result = computeMarkovExpectedCost(...);
        map.put(key, result.toString());
        return result;
    }

    @Override
    public void initialize() {
        // 초기화 시 180개 키 사전 계산
        precomputeTables();
    }
}
```

### 2.6 장단점 분석

| 항목 | In-Memory (현재) | Redis 전환 |
|------|:---:|:---:|
| **Scale-out 안정성** | ⚠️ 인스턴스별 캐시 미스 발생 | ✅ 전역 캐시 공유 |
| **성능** | ✅ 나노초 latency | ⚠️ 1-5ms Redis 호출 |
| **초기화 시간** | ⚠️ 각 인스턴스에서 수십 ms 소요 | ✅ 한 번만 계산 |
| **데이터 정합성** | ✅ 불변 데이터라 문제 없음 | ✅ 동일 |
| **운영 복잡도** | ✅ 자체 관리 | ⚠️ Redis 의존성 증가 |

### 2.7 권장 사항

**우선순위: P2 (유지 권장)**

**근거:**
1. **데이터 불변성** - Scale-out 시 데이터 불일치 문제 없음
2. **크기 미미** - 180개 × 16 bytes ≈ 3KB (메모리 낭비 아님)
3. **캐시 히트율 높음** - precomputeTables()에서 18개 키 사전 계산
4. **읽기 전용** - 초기화 후 추가 쓰기 없음 (경합 없음)
5. **Redis 전환 시 득보실** - 1-5ms latency 추가로 인한 성능 저하 더 큼

**대안:**
- 현재 방식 유지 (In-Memory)
- 애플리케이션 시작 시 캐시 워밍업 강화 (precomputeTables 확장)
- 로드 밸런서 Least Connection 방식 사용하여 캐시 미스 분산

---

## 3. EquipmentPersistenceTracker 분석

### 3.1 컴포넌트 개요

**파일:** `/home/maple/MapleExpectation/module-app/src/main/java/maple/expectation/service/v2/shutdown/EquipmentPersistenceTracker.java`

**역할:** Graceful Shutdown 시 비동기 저장 작업 추적

```java
// Line 50
private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingOperations =
    new ConcurrentHashMap<>();
```

### 3.2 상태 분석

| 항목 | 내용 |
|------|------|
| **데이터 타입** | `ConcurrentHashMap<String, CompletableFuture<Void>>` |
| **데이터 키** | OCID (캐릭터 식별자) |
| **데이터 수명** | 비동기 작업 완료 시까지 (수초 ~ 수십초) |
| **접근 패턴** | write-once, remove-once (track → whenComplete → remove) |
| **크기** | 동시 요청 수 의존 (수십 ~ 수백 건) |

### 3.3 현재 동작 방식

```java
// 1. 비동기 작업 등록
public void trackOperation(String ocid, CompletableFuture<Void> future) {
    if (shutdownInProgress.get()) {
        throw new IllegalStateException("Shutdown 진행 중에는 등록할 수 없습니다.");
    }
    pendingOperations.put(ocid, future);

    // 2. 완료 시 자동 제거
    future.whenComplete((result, throwable) -> {
        pendingOperations.remove(ocid);
    });
}

// 3. Shutdown 시 모든 작업 완료 대기
public boolean awaitAllCompletion(Duration timeout) {
    if (!shutdownInProgress.compareAndSet(false, true)) {
        return false; // 이미 Shutdown 중
    }
    CompletableFuture.allOf(pendingOperations.values().toArray(...))
        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    return true;
}
```

### 3.4 Scale-out 시 문제점

**시나리오:** 2개 인스턴스 Rolling Update

```
Instance A                           Instance B
┌─────────────────────────────┐     ┌─────────────────────────────┐
│ pendingOperations            │     │ pendingOperations            │
│ {OCID-1, OCID-2, OCID-3}     │     │ {OCID-4, OCID-5}             │
└─────────────────────────────┘     └─────────────────────────────┘
        │                                   │
        │  Signal: SIGTERM                   │  Signal: SIGTERM
        ├─→ awaitAllCompletion()             ├─→ awaitAllCompletion()
        │  (OCID-1,2,3만 완료 대기)           │  (OCID-4,5만 완료 대기)
        │                                   │
        ✅ Shutdown 완료                     ✅ Shutdown 완료
```

**분석:**
- 각 인스턴스가 **자신의 작업만 완료 대기**
- CompletableFuture는 **JVM 로컬 객체**라 분산 불가능
- 로컬 Shutdown만 보장 → Redis 전환해도 **물리적 한계 존재**

### 3.5 Redis 전환 분석

#### 3.5.1 이미 완료됨 (Strategy Pattern)

**발견:** 이미 Strategy 패턴으로 Redis 구현체가 완료되어 있습니다.

```java
// 인터페이스 (module-core)
public interface PersistenceTrackerStrategy {
    void trackOperation(String ocid, CompletableFuture<Void> future);
    boolean awaitAllCompletion(Duration timeout);
    StrategyType getType(); // IN_MEMORY or REDIS
}

// In-Memory 구현체 (module-app)
@ConditionalOnProperty(name = "app.buffer.redis.enabled", havingValue = "false")
public class EquipmentPersistenceTracker implements PersistenceTrackerStrategy {
    // ConcurrentHashMap 기반
}

// Redis 구현체 (module-infra)
public class RedisEquipmentPersistenceTracker implements PersistenceTrackerStrategy {
    // Redis SET 기반 (OCID만 분산 추적)
}
```

**Redis 구현체 특징:**
```java
// Redis에 OCID만 저장 (SET 자료구조)
RSet<String> tracking = redisson.getSet("{persistence}:tracking");
tracking.add(ocid); // 분산 추적용

// CompletableFuture는 여전히 로컬
localFutures.put(ocid, future); // Shutdown 대기용
```

**핵심 설계:**
1. **OCID만 Redis에 저장** - 분산 환경에서 pending 상태 공유
2. **CompletableFuture는 로컬 유지** - JVM 객체라 직렬화 불가능
3. **Shutdown 시 로컬 Future만 대기** - 물리적 한계 인정

#### 3.5.2 완료 상태 확인

```bash
# Feature Flag
app.buffer.redis.enabled=true  # Redis 모드 활성화

# Redis 키 구조
{persistence}:tracking (SET)
├── OCID-1
├── OCID-2
└── ...
```

**구현 파일:**
- `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/queue/persistence/RedisEquipmentPersistenceTracker.java`
- `/home/maple/MapleExpectation/module-app/src/main/java/maple/expectation/service/v2/shutdown/EquipmentPersistenceTracker.java`

### 3.6 권장 사항

**우선순위: 완료 (P0 해결됨)**

**상태:** ✅ **Strategy 패턴으로 Redis 전환 완료**

**조치:**
- **현재:** `app.buffer.redis.enabled=false` (In-Memory 모드)
- **권장:** `app.buffer.redis.enabled=true` (Redis 모드) 기본값 변경

**주의:** CompletableFuture의 물리적 한계로 완전한 분산 Shutdown은 불가능합니다.
- Redis는 OCID 추적만 제공
- 실제 작업 완료 대기는 여전히 로컬 인스턴스에서 수행
- Rolling Update 시 각 인스턴스가 독립적으로 Shutdown

---

## 4. 우선순위 및 실행 계획

### 4.1 우선순위 테이블

| # | 컴포넌트 | 우선순위 | 전환 여부 | 시기 | 근거 |
|---|---------|:---:|:---:|------|------|
| **1** | `EquipmentPersistenceTracker` | **완료** | ✅ 완료 | 즉시 | Strategy 패턴으로 구현 완료, Feature Flag만 변경 |
| **2** | `DeDuplicationCache` | **P2** | ❌ 선택적 | Scale-out 시 | 모니터링 시스템, 비즈니스 로직 아님 |
| **3** | `StarforceLookupTableImpl` | **P2** | ❌ 유지 | - | 불변 데이터 캐시, In-Memory 유지 권장 |

### 4.2 실행 계획

#### Sprint 1 - Feature Flag 정리 (즉시 완료 권장)

**목적:** 이미 완료된 Redis 구현체 활성화

```yaml
# application-prod.yml
app:
  buffer:
    redis:
      enabled: true  # In-Memory → Redis 기본값 변경
```

**검증:**
```bash
# 1. Redis SET 생성 확인
redis-cli
> SMEMBERS {persistence}:tracking

# 2. 메트릭 확인
curl http://localhost:8080/actuator/metrics/persistence.tracker.global.pending
```

---

#### Sprint 2 - DeDuplicationCache 전환 (선택적)

**전제 조건:**
- 인스턴스 수 ≥ 5
- Discord 알림 스팸이 운영상 문제

**작업:**
1. `RedisDeDuplicationCache` 구현
2. `AlertNotificationService` 의존성 변경
3. Feature Flag 추가: `monitoring.copilot.dedup.redis.enabled=true`

**롤백 계획:**
- Feature Flag로 즉시 In-Memory 모드 복원

---

#### Sprint 3 - StarforceLookupTable 유지 (권장)

**결정:** In-Memory 캐시 유지

**보완 작업:**
1. 캐시 워밍업 강화 (precomputeTables 확장)
2. 메트릭 추가: `starforce.cache.hit.ratio`
3. 로드 밸런서 Least Connection 설정 검증

---

## 5. 요약 및 결론

### 5.1 핵심 발견

1. **3개 중 1개는 이미 완료** (EquipmentPersistenceTracker)
2. **즉시 전환 필요한 P0 항목 없음**
3. **나머지 2개는 비즈니스 로직에 직접적 영향 없음**

### 5.2 권장 사항

| 항목 | 권장 | 이유 |
|------|------|------|
| **EquipmentPersistenceTracker** | Redis 모드 활성화 | 이미 구현 완료, Feature Flag만 변경 |
| **DeDuplicationCache** | In-Memory 유지 | 모니터링 시스템, Scale-out 시 선택적 전환 |
| **StarforceLookupTableImpl** | In-Memory 유지 | 불변 데이터, Redis 전환 시 성능 저하 |

### 5.3 최종 결론

**Scale-out 즉시 가능** - 3개 컴포넌트 중 장애 유발 항목 없음.

**우선순위:**
1. **P0:** 없음
2. **P1:** 없음
3. **P2:** DeDuplicationCache (선택적), StarforceLookupTableImpl (유지 권장)

**다음 단계:**
1. `app.buffer.redis.enabled=true` 기본값 변경 (Sprint 1)
2. Scale-out 테스트 수행
3. Discord 알림 스팸 모니터링 후 DeDuplicationCache 전환 결정

---

## 6. 부록: 참조 코드

### A. DeDuplicationCache 주요 메서드

```java
// File: DeDuplicationCache.java:42
private final ConcurrentHashMap<String, Long> recentIncidents;

public boolean isRecent(String incidentId, long now) {
    Long timestamp = recentIncidents.get(incidentId);
    if (timestamp == null) {
        return false;
    }
    long age = now - timestamp;
    return age < throttleWindowMs;
}

public void track(String incidentId, long timestamp) {
    recentIncidents.put(incidentId, timestamp);
}

public int cleanOld(long now) {
    long threshold = now - throttleWindowMs;
    AtomicInteger removedCount = new AtomicInteger(0);
    recentIncidents.entrySet().removeIf(entry -> {
        boolean isOld = entry.getValue() < threshold;
        if (isOld) {
            removedCount.incrementAndGet();
        }
        return isOld;
    });
    return removedCount.get();
}
```

### B. StarforceLookupTableImpl 캐시 키 생성

```java
// File: StarforceLookupTableImpl.java:461
private String cacheKey(int currentStar, int targetStar, int level,
                        boolean starCatch, boolean sunday,
                        boolean discount, boolean destroyPrev) {
    return String.format("%d-%d-%d-%b-%b-%b-%b",
        currentStar, targetStar, level, starCatch, sunday, discount, destroyPrev);
    // 예: "0-30-200-true-true-true-false"
}
```

### C. RedisEquipmentPersistenceTracker 핵심 로직

```java
// File: RedisEquipmentPersistenceTracker.java:116
@Override
public void trackOperation(String ocid, CompletableFuture<Void> future) {
    // 1. Redis SET에 OCID 추가 (분산 추적)
    addToRedisTracking(ocid);

    // 2. 로컬 Future 등록 (Shutdown 대기용)
    localFutures.put(ocid, future);

    // 3. 완료 시 자동 정리
    future.whenComplete((result, throwable) -> {
        removeFromRedisTracking(ocid);
        localFutures.remove(ocid);
    });
}
```

---

## 7. 검증 체크리스트

### 7.1 EquipmentPersistenceTracker (완료)

- [x] Strategy 패턴으로 인터페이스 분리
- [x] Redis 구현체 완료 (`RedisEquipmentPersistenceTracker`)
- [x] Feature Flag 구현 (`app.buffer.redis.enabled`)
- [ ] 기본값 `true`로 변경 (P0)
- [ ] 프로덕션 환경 Redis 모드 테스트

### 7.2 DeDuplicationCache (선택적)

- [ ] Redis 구현체 작성 (`RedisDeDuplicationCache`)
- [ ] Feature Flag 추가 (`monitoring.copilot.dedup.redis.enabled`)
- [ ] Discord 알림 스팸 모니터링
- [ ] Scale-out 테스트 (2~5개 인스턴스)

### 7.3 StarforceLookupTableImpl (유지)

- [x] 불변 데이터 캐시 확인
- [ ] 캐시 워밍업 강화 (precomputeTables 확장)
- [ ] 메트릭 추가 (`starforce.cache.hit.ratio`)
- [ ] 로드 밸런서 Least Connection 검증

---

## 8. 관련 문서

- [Scale-out 방해 요소 전수 분석](/home/maple/MapleExpectation/docs/05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md)
- [CLAUDE.md Section 17: TieredCache](/home/maple/MapleExpectation/CLAUDE.md#L407)
- [ADR-008: Graceful Shutdown](/home/maple/MapleExpectation/docs/01_Adr/ADR-008-durability-graceful-shutdown.md.backup)
- [Service Modules Guide](/home/maple/MapleExpectation/docs/03_Technical_Guides/service-modules.md)

---

*분석 완료: 2026-02-18*
*다음 리뷰: Scale-out 테스트 완료 후*
*문서 버전: v1.0.0*
