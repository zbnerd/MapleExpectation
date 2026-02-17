# 02. Memory Hierarchy and Cache: 계층형 캐시의 심화 학습

> **"CPU는 눈 깜짝할 사이에 레지스터를 읽지만, RAM은 영원처럼 느립니다. 우리는 이 격차를 메우기 위해 캐시라는 이름의 기적을 만들었습니다."**

---

## 1. The Problem (본질: 왜 캐시가 필요한가?)

### 1.1 CPU와 메모리의 속도 격차 (The Memory Wall)

```
┌──────────────────────────────────────────────────────────┐
│  계층            접근 시간      크기       비용 (CPU 사이클)  │
├──────────────────────────────────────────────────────────┤
│  L1 Cache        1-4 cycles     64 KB       $ (빠름)      │
│  L2 Cache        10-20 cycles   256 KB      $$            │
│  L3 Cache        40-75 cycles   8-32 MB     $$$           │
│  Main Memory     200+ cycles    8-64 GB     $$$$ (느림)   │
│  SSD/NVMe       100,000+       1-4 TB      $$$$$         │
│  Disk           10,000,000+    10 TB+      $$$$$$$       │
└──────────────────────────────────────────────────────────┘
```

**핵심 질문**: "왜 CPU는 1990년 이후 100,000배 빨라졌는데, RAM은 100배밖에 빨라지지 않았는가?"

**답**: 전기의 속도는 빛의 속도로 제한되지만, 데이터 경로(Wire)가 점점 길어지고 있기 때문입니다.

### 1.2 웹 애플리케이션에서의 메모리 계층

**MapleExpectation의 캐시 계층:**

```
┌──────────────────────────────────────────────────────────┐
│  계층            기술          용량       Hit Rate        │
├──────────────────────────────────────────────────────────┤
│  L1 (Local)       Caffeine      10,000건   ~85%           │
│  L2 (Distributed) Redis         1,000,000건 ~14%          │
│  L3 (Persistent)  MySQL         무제한      ~1% (Miss)    │
└──────────────────────────────────────────────────────────┘
```

**전체 Hit Rate = 99%** (L1 + L2)

### 1.3 Cache Stampede (캐시 스탬프드) 문제

**상황**: L1/L2 모두 Miss인 상태에서 100개의 요청이 동시에 들어옴

```
Timeline:
T0: 100개 요청 동시 도착
T1: L1 Miss 전체 → L2 조회 시도
T2: L2 Miss 전체 → DB 조회 시작
T3: DB에 100개의 동일 쿼리 폭주 (Storm!) 💥
T4: 100개의 응답이 L2에 동시에 기록 (Race Condition)
T5: 100개의 응답이 각 인스턴스의 L1에 기록 (중복 저장)
```

**결과**: DB 과부하 + Redis/네트워크 폭주 + 불필요한 중복 계산

---

## 2. The CS Principle (원리: 이 코드는 무엇에 기반하는가?)

### 2.1 Locality of Reference (참조의 지역성 원칙)

**Temporal Locality (시간적 지역성)**: "최근에 접근한 데이터는 다시 접근할 가능성이 높다"

```
Example: 장비 강화 시스템
- 사용자가 "캐릭터 123" 조회
- 10초 내에 같은 캐릭터의 "인벤토리", "스탯", "장비" 조회
→ 모든 데이터를 L1에 유지 (Caffeine의 time-based eviction)
```

**Spatial Locality (공간적 지역성)**: "인접한 데이터도 함께 접근할 가능성이 높다"

```
Example: 장비 목록 조회
- SELECT * FROM equipment WHERE character_id = 123
- 결과: 장비 ID 101, 102, 103 (연속된 ID)
→ Redis Pipeline으로 일괄 조회 (Batch Get)
```

### 2.2 Cache Coherence (캐시 일관성) 문제

**분산 환경에서의 L1/L2 불일치:**

```
Instance A (서울 리전)          Instance B (부산 리전)
┌──────────────┐              ┌──────────────┐
│ L1: level=15 │              │ L2: level=15 │
│ L2: level=15 │              │ L1: level=14 │ ← Stale!
└──────────────┘              └──────────────┘
       ▲                             │
       │                             ▼
    UPDATE                      Pub/Sub 수신
 level=16                     "invalidate:123"
       │                             │
       └──────────Pub/Sub─────────────┘
```

**해결책: Redis Pub/Sub 기반 무효화**

```java
// Instance A: 업데이트 시
l2.put("char:123", newData);
redisson.getTopic("cache:invalidate").publish(
    CacheInvalidationEvent.of("char:123")
);

// Instance B: Pub/Sub 수신
@RedisPubSubListener("cache:invalidate")
public void onInvalidation(CacheInvalidationEvent event) {
    l1.evict(event.getKey());  // L1만 무효화 (L2는 Pub/Sub로 이미 최신)
}
```

### 2.3 Belady's Optimal Algorithm (이론적 최적 알고리즘)

**가정**: "미래를 아는 Oracle이 있다면, 언제 캐시를 비울까?"

**답**: "가장 먼 미래에 다시 접근할 데이터를 지워라"

```
Cache Hit 시뮬레이션 (용량 3):

접근 순서: A, B, C, A, B, D, A, B, C, D, E

┌─────┬───────┬───────┬───────┬─────────┐
│Time │ LRU   │ FIFO  │ Optimal│참고     │
├─────┼───────┼───────┼───────┼─────────┤
│ 1   │ A     │ A     │ A     │ Hit: A  │
│ 2   │ A,B   │ A,B   │ A,B   │ Hit: B  │
│ 3   │ A,B,C │ A,B,C │ A,B,C │ Hit: C  │
│ 4   │ A,B,C │ A,B,C │ A,B,C │ Hit: A  │
│ 5   │ A,B,C │ A,B,C │ A,B,C │ Hit: B  │
│ 6   │ D,B,C │ D,A,C │ A,B,D │ Miss: D │
│     │(evictA)│(evictB)│(evictC)│         │
└─────┴───────┴───────┴───────┴─────────┘
```

**Belady의 Fault**: "LRU는 미래를 모르기 때문에, C를 지워야 할 때 A를 지운다"

**현실의 선택**: Caffeine은 **W-TinyLFU** (Window-Tiny Least Frequently Used)를 사용
- 빈도(Frequency) + 시간(Recency)의 하이브리드
- LRU보다 10-25% 더 높은 Hit Rate

---

## 3. Internal Mechanics (내부: Spring & Redis는 어떻게 동작하는가?)

### 3.1 Caffeine Cache의 내부 구조

**Caffeine은 ConcurrentHashMap + W-TinyLFU를 사용합니다.**

```java
// Caffeine 내부 구조 (개념적)
class CaffeineCache<K, V> {
    ConcurrentHashMap<K, Node<K, V>> table;

    // Frequency Sketch (0.001% 오차 허용)
    CountMinSketch frequencySketch;

    // Window Queue (최근 접근 추적)
    RingBuffer<Node> windowQueue;

    // Probation Queue (관찰 기간)
    RingBuffer<Node> probationQueue;

    // Protected Queue (자주 접근하는 데이터)
    RingBuffer<Node> protectedQueue;
}
```

**W-TinyLFU의 Eviction 과정:**

```
┌─────────────┐
│  New Item   │ → 입구
└──────┬──────┘
       ▼
┌─────────────┐
│   Window    │ → 최근 접근 (관찰 기간)
└──────┬──────┘
       ▼ (적응 시도)
┌─────────────┐
│  Probation  │ → 관찰 대상 (후보)
└──────┬──────┘
       ▼ (빈도 증가)
┌─────────────┐
│  Protected  │ → 보호 (자주 접근)
└──────┬──────┘
       ▼ (용량 초과 시)
      Evict
```

### 3.2 Redis의 Eviction Policy

**Redis는 8가지 Eviction 전략을 지원합니다:**

```
1. noeviction          (메모리 꽬 차면 쓰기 거부)
2. allkeys-lru        (전체 키에서 LRU)
3. allkeys-lfu        (전체 키에서 LFU)
4. allkeys-random     (전체 키에서 Random)
5. volatile-lru       (TTL 있는 키에서 LRU)
6. volatile-lfu       (TTL 있는 키에서 LFU)
7. volatile-random    (TTL 있는 키에서 Random)
8. volatile-ttl       (TTL 가장 짧은 키부터)
```

**MapleExpectation의 선택: `allkeys-lru`**

```redis
# redis.conf
maxmemory 2gb
maxmemory-policy allkeys-lru
```

**LRU 구현 (Redis 4.0+)**: Approximated LRU ( Sampling 기반)

```
Redis LRU Algorithm:
1. 5개의 키를 랜덤 샘플링
2. 가장 오래된 키 선택
3. 1/100 확률으로 더 많은 샘플링 (정확도 향상)
```

**정확도 vs 성능의 Trade-off**:
- 완벽한 LRU: 모든 키의 접근 시간 저장 → O(N) 메모리
- 근사 LRU: 일부만 샘플링 → O(1) 메모리, 99% 정확도

### 3.3 SerDe (Serialization/Deserialization) 오버헤드

**Java Object → Redis String 변환 비용:**

```
┌─────────────────────┐
│  Java Object        │
│  EquipmentData {    │
│    id: 123L         │
│    name: "검"       │
│    stats: {...}     │
│  }                  │
└──────┬──────────────┘
       │ Jackson Serialize
       ▼
┌─────────────────────┐
│  JSON String        │
│  {"id":123,...}     │  (약 500 bytes)
└──────┬──────────────┘
       │ Redis SET
       ▼
┌─────────────────────┐
│  Redis Memory       │
│  (RAM)              │
└─────────────────────┘
```

**비용 분석:**
- Jackson Serialize: ~50μs (마이크로초)
- Network I/O (Loopback): ~100μs
- Redis Command Execution: ~200μs
- **합계: ~350μs** (L2 Miss 시)

**L1 Hit 시:** Caffeine에서 직접 읽기 → **~0.5μs** (700배 빠름!)

---

## 4. Alternative & Trade-off (비판: 왜 이 방법을 선택했는가?)

### 4.1 Caffeine vs Guava Cache

| 측정 항목 | Caffeine | Guava Cache |
|---------|----------|-------------|
| **Hit Rate** | 25% 더 높음 (W-TinyLFU) | LRU만 지원 |
| **Write Throughput** | 10배 더 높음 | 낮음 |
| **메모리 오버헤드** | 낮음 (Ring Buffer) | 높음 (ConcurrentHashMap) |
| **API 호환성** | Guava와 유사 | - |

**선택 이유**: Caffeine은 "Zero Overhead"를 목표로 설계되어, Spring Boot 2.0+의 기본 캐시로 채택됨

### 4.2 Redis vs Memcached

| 측정 항목 | Redis | Memcached |
|---------|-------|-----------|
| **데이터 구조** | String, Hash, List, Set, ZSet | Binary blob만 |
| **Persistence** | RDB + AOP (영속화) | 없음 |
| **Replication** | Master-Slave, Sentinel | 없음 |
| **Cluster** | Redis Cluster (Slot 기반) | Client-side Sharding |
| **단순 조회 속도** | 느림 (기능이 많음) | 빠름 |

**선택 이유**:
- Pub/Sub 기반 캐시 무효화 필요 (Memcached는 불가)
- Cluster 환경에서의 안정성 (Redis Sentinel)
- 복잡한 데이터 구조 (Hash, ZSet for Ranking)

### 4.3 Cache Stampede 방지: Single-flight Pattern

**문제**: L2 Miss 시 여러 스레드가 동일한 DB 조회

**해결**: Redisson Distributed Lock

```java
// TieredCache의 Single-flight 구현
public ValueWrapper get(Object key) {
    // 1. L1 조회
    ValueWrapper value = l1.get(key);
    if (value != null) return value;

    // 2. L2 조회
    value = l2.get(key);
    if (value != null) {
        l1.put(key, value);  // Backfill
        return value;
    }

    // 3. L1/L2 모두 Miss → 분산 락 획득
    RLock lock = redisson.getLock("cache:lock:" + key);
    try {
        lock.lock(5, TimeUnit.SECONDS);  // P0-4: 5초로 단축

        // 4. 락 획득 후 재확인 (Double-check)
        value = l2.get(key);
        if (value != null) return value;

        // 5. DB 조회
        value = loadFromDatabase(key);

        // 6. L2 → L1 순서 저장 (일관성)
        l2.put(key, value);
        l1.put(key, value);

        return value;
    } finally {
        lock.unlock();
    }
}
```

**Trade-off**:
- **장점**: DB 부하 100배 감소 (100개 요청 → 1개 DB 쿼리)
- **단점**: 락 경쟁 시 Latency 증가 (최대 5초 대기)

---

## 5. The Interview Defense (방어: 100배 트래픽에서 어디가 먼저 터지는가?)

### 5.1 "트래픽이 100배 증가하면?"

**실패 포인트 예측:**

1. **L1 Capacity 부족** (最先)
   - 현재: Caffeine 10,000건
   - 100배 트래픽: Hit Rate 85% → 60% 감소 (운영 데이터 집합 증가)
   - **해결**: L1 크기 증설 (10,000 → 50,000), 또는 Sharding (캐시 키 분산)

2. **Redis Network Bandwidth 병목** (次点)
   - 현재: 1Gbps, GET/SET 평균 500μs
   - 100배 트래픽: Redis가 초당 100,000명 처리 → Network Queue 발생
   - **해결**: Redis Cluster (Slot 기반 분산), Local Cache 증설

3. **Stale L1 Backdoor Cache**
   - L2 Hit 후 L1 Backfill 시, 다른 인스턴스의 L1은 여전히 Stale
   - **해결**: Pub/Sub Latency 단축 (Redis Cluster의 Local Pub/Sub)

### 5.2 "Redis가 다운되면?"

**현재 시스템의 취약점:**

```java
// TieredCache의 현재 구조
public ValueWrapper get(Object key) {
    ValueWrapper value = l1.get(key);
    if (value != null) return value;

    value = l2.get(key);  // Redis 다운 시 → RedisTimeoutException
    if (value != null) { ... }
}
```

**개선안: Fallback to DB**

```java
public ValueWrapper get(Object key) {
    ValueWrapper value = l1.get(key);
    if (value != null) return value;

    try {
        value = l2.get(key);
        if (value != null) {
            l1.put(key, value);
            return value;
        }
    } catch (RedisTimeoutException e) {
        log.warn("Redis unavailable, fallback to DB");
    }

    // Fallback: 직접 DB 조회
    value = loadFromDatabase(key);
    l1.put(key, value);  // L1만 저장 (L2는 장애 복구 대기)
    return value;
}
```

### 5.3 "캐시 Hit Rate가 급락하면?"

**상황**: L1 Hit Rate 85% → 40% 급락 (장애 발생)

**원인 분석:**

1. **Cache Poisoning**: 공격자가 의도적으로 Miss 유발
   - **해결**: Rate Limiting, Anomaly Detection

2. **Working Set Expansion**: 운영 데이터 집합이 캐시 용량 초과
   - **해결**: L1 크기 동적 조정, Hot Key만 캐싱

3. **Thundering Herd**: 특정 이벤트로 쏠림 현상
   - **해결**: Request Coalescing (100개 요청 → 1개 DB 쿼리)

**Thundering Herd 방지 구현:**

```java
// Request Coalescing (Merging)
private final ConcurrentHashMap<Object, CompletableFuture<ValueWrapper>> pendingLoads =
    new ConcurrentHashMap<>();

public ValueWrapper get(Object key) {
    ValueWrapper value = l1.get(key);
    if (value != null) return value;

    // 1. 이미 진행 중인 요청이 있으면 합류
    CompletableFuture<ValueWrapper> pending = pendingLoads.get(key);
    if (pending != null) {
        return pending.join();  // 첫 번째 요청이 완료될 때까지 대기
    }

    // 2. 첫 번째 요청만 DB 조회
    CompletableFuture<ValueWrapper> newLoad = CompletableFuture.supplyAsync(() -> {
        ValueWrapper result = loadFromDBWithLock(key);
        pendingLoads.remove(key);
        return result;
    });
    pendingLoads.put(key, newLoad);

    return newLoad.join();
}
```

---

## 요약: 핵심 take-away

1. **L1/L2 캐싱은 메모리 계층의 응용**: CPU L1/L2 → 앱 Caffeine/Redis
2. **Cache Coherence는 분산 환경의 숙제**: Pub/Sub로 L1 무효화, L2→L1 순서 저장
3. **W-TinyLFU는 LRU보다 25% 정확**: 빈도 + 시간의 하이브리드
4. **Single-flight는 Cache Stampede의 해결사**: 분산 락으로 DB 중복 조회 방지
5. **100배 트래픽 대비**: Sharding, Request Coalescing, Dynamic Sizing

---

**다음 챕터 예고**: "서킷 브레이커는 왜 실패의 확률을 줄이는가? 회복 탄력성의 공학"
