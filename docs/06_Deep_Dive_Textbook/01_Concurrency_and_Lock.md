# 01. Concurrency and Lock: 분산 락의 심화 학습

> **"단일 서버의 Mutex는 이제 그만. 여러분은 지금 분산 환경에서 동시성 제어의 지옥을 걷고 있습니다."**

---

## 1. The Problem (본질: 우리는 무엇과 싸우고 있는가?)

### 1.1 단일 서버 시대의 끝

단일 서버 환경에서의 동시성 제어는 간단했습니다. `synchronized` 키워드, `ReentrantLock`, 또는 DB의 `SELECT FOR UPDATE` 하나면 충분했습니다. 하지만:

**"서버가 하나면 Single Point of Failure입니다."**

트래픽이 100 RPS를 넘어가는 순간, 우리는 수평 확장(Scale-Out)을 시작합니다. 그리고 그 순간부터 **"동시성 제어의 지옥"**이 시작됩니다.

### 1.2 분산 환경에서의 동시성 문제

**상황**: 3개의 서버 인스턴스가 동시에 "캐릭터 ID 123의 장비 강화" 요청을 받습니다.

```
┌─────────┐     ┌─────────┐     ┌─────────┐
│Server A │     │Server B │     │Server C │
└────┬────┘     └────┬────┘     └────┬────┘
     │               │               │
     ▼               ▼               ▼
  "강화 시도"      "강화 시도"      "강화 시도"
     │               │               │
     └───────────────┴───────────────┘
                         ▼
                공유 자원: DB 행 (row)
                문제: 3번의 강화가 동시에 실행됨!
```

**서버 내부의 `synchronized`는 무용지물입니다.** 각 서버의 JVM은 독립적인 메모리 공간을 가지므로, 서로의 락 상태를 알 수 없습니다.

### 1.3 해결책의 두 갈래

**A. MySQL Named Lock (DB 락)**

```sql
SELECT GET_LOCK('equipment:123', 10);  -- 락 획득 시도
-- 강화 로직 실행
SELECT RELEASE_LOCK('equipment:123');  -- 락 해제
```

**장점**: 별도 인프라 불필요, ACID 보장
**단점**:
- DB 연결 유지 필요 (Connection Pool 고갈)
- 락 획득 실패 시 전체 트랜잭션 재시도
- DB 부하 증가 (락 관리 쿼리)

**B. Redis Distributed Lock (Pub/Sub + Spin Lock)**

```
1. SETNX lock:equipment:123 "server-a:thread-1"
2. 성공 시: 락 획득, 작업 실행 후 DEL lock:equipment:123
3. 실패 시: 짧은 대기 후 재시도 (Spin Lock)
```

**장점**: 낮은 지연(Latency), Connection Pool 절약
**단점**: Redis 장애 시 락 정합성 깨짐 위험

---

## 2. The CS Principle (원리: 이 코드는 무엇에 기반하는가?)

### 2.1 Coffman Conditions: 교착상태(Deadlock)의 4가지 필요조건

**Deadlock은 다음 4가지가 **모두** 성립할 때만 발생합니다:**

1. **Mutual Exclusion (상호 배제)**: 자원은 한 번에 한 프로세스만 사용 가능
2. **Hold and Wait (점유 대기)**: 자원을 가진 상태에서 다른 자원을 기다림
3. **No Preemption (비선점)**: 다른 프로세스의 자원을 강제로 뺏을 수 없음
4. **Circular Wait (순환 대기)**: P1 → P2 → P3 → P1 형태의 대기 사이클

**우리의 무기: Condition #4 깨기 (Lock Ordering)**

```java
// 나쁜 예: 순환 대기 유발
Thread A: lock(account1) → lock(account2)
Thread B: lock(account2) → lock(account1)  // 💥 DEADLOCK!

// 좋은 예: 순서 보장
Thread A: lock(min(account1, account2)) → lock(max(account1, account2))
Thread B: lock(min(account1, account2)) → lock(max(account1, account2))  // ✅ 안전!
```

**`LockStrategy.executeWithOrderedLocks()`의 핵심 아이디어:**

> **"모든 스레드가 전역적으로 동일한 순서로 락을 획득하도록 강제하면, 순환 대기는 불가능해진다."**

### 2.2 Spin Lock vs Blocking Lock

**Spin Lock**: 락 획득할 때까지 CPU를 낭비하며 계속 시도
- **장점**: 문맥 교환(Context Switching) 없음, 짧은 대기에 유리
- **단점**: CPU 효율 낮음, 긴 대기에 부적합

**Blocking Lock**: 락이 해제될 때까지 스레드를 Sleep (WAITING 상태)
- **장점**: CPU 효율 높음, 긴 대기에 적합
- **단점**: 문맥 교환 비용, 깨우기(Wakeup) 지연

**Redis Lua Script + Pub/Sub 하이브리드:**

```lua
-- Spin Lock (초반 50ms)
while retry < max_spin do
  if redis.call("SETNX", KEYS[1], ARGV[1]) == 1 then
    return "LOCKED"
  end
  retry = retry + 1
  redis.call("PTTL", KEYS[1])  -- 짧은 대기
end

-- Fallback: Pub/Sub 구독 (Blocking Lock)
redis.call("SUBSCRIBE", "lock:channel:" .. KEYS[1])
-- 락 해제 알림 대기
```

### 2.3 Amdahl's Law: 병렬화의 한계

$$S(N) = \frac{1}{(1-P) + \frac{P}{N}}$$

- $S(N)$: N개의 프로세서를 사용했을 때의 성능 향상비
- $P$: 병렬화 가능한 코드 비율
- $N$: 프로세서 개수

**락 경쟁이 심각할 때:** $P$ (실제 작업)가 20%라면, $N$이 100이 되어도 전체 성능은 최대 5배밖에 향상되지 않습니다.

> **교훈: "락 경쟁을 줄이는 것이 병렬 처리보다 중요하다."**

---

## 3. Internal Mechanics (내부: Spring & Redis는 어떻게 동작하는가?)

### 3.1 Spring AOP Proxy 생성 과정

```java
@Lock(name = "equipment", key = "#characterId")
public void enhanceEquipment(Long characterId) { ... }
```

**Spring이 실행하는 작업:**

1. **Bean 후처리 (BeanPostProcessor)**:
   - `@Lock` 애너테이션 스캔
   - JDK Dynamic Proxy 또는 CGLIB 프록시 생성

2. **Proxy Chain 구성**:
   ```
   Original Bean
       ↓
   [LockInterceptor]  // 락 획득/해제 로직
       ↓
   [TransactionInterceptor]  // 트랜잭션 시작/커밋
       ↓
   Actual Method Invocation
   ```

3. **Method Invocation 시**:
   ```java
   // Spring이 생성한 Proxy 코드 (개념적)
   public void enhanceEquipment(Long characterId) {
       LockContext ctx = lockStrategy.tryLock("equipment:" + characterId);
       try {
           target.enhanceEquipment(characterId);  // 실제 메서드 호출
       } finally {
           lockStrategy.unlock(ctx);
       }
   }
   ```

### 3.2 Redis Single Thread Event Loop

**Redis는 싱글 스레드입니다.**

```
┌─────────────────────────────────────┐
│  Redis Event Loop (Single Thread)   │
├─────────────────────────────────────┤
│  1. File Event (Accept New Conn)    │
│  2. Client A: SETNX lock:123        │ ← 처리
│  3. Client B: SETNX lock:123        │ ← 대기 (응답 지연)
│  4. Client C: GET key               │ ← 대기
│  5. File Event (Accept New Conn)    │
└─────────────────────────────────────┘
```

**왜 Redis는 싱글 스레드인가?**
- Atomicity 보장 (Mutex 불필요)
- Context Switching 오버헤드 제거
- CPU Cache 친화적 (Locality 높음)

**Lock 요청의 처리 순서:**
1. Client A: `SETNX lock:123 "A"` → Redis가 즉시 처리 → "1" (성공) 반환
2. Client B: `SETNX lock:123 "B"` → Redis가 처리 → "0" (실패) 반환

**중요**: Client B는 **블로킹되지 않습니다.** 요청을 보내고 응답을 기다리는 동안, OS는 다른 작업을 수행할 수 있습니다 (Non-blocking I/O).

### 3.3 MySQL Transaction Isolation Level

**MySQL InnoDB의 기본: REPEATABLE READ**

```sql
-- Transaction A
START TRANSACTION;
SELECT GET_LOCK('equipment:123', 10);  -- User-Level Lock ( 트랜잭션 무관)
-- 장비 강화 로직
COMMIT;

-- Transaction B (동시 실행)
START TRANSACTION;
SELECT GET_LOCK('equipment:123', 10);  -- 대기 (A가 해제할 때까지)
-- 강화 로직 실행
COMMIT;
```

**`GET_LOCK()`의 특이점:**
- 트랜잭션과 무관한 **세션 레벨 락**
- 동일 세션에서 재호출 시 이름만 같으면 같은 락 (재진입 가능)
- 트랜잭션 롤백 시에도 락은 유지됨 (명시적 RELEASE_LOCK 필요)

---

## 4. Alternative & Trade-off (비판: 왜 이 방법을 선택했는가?)

### 4.1 Redis Pub/Sub vs MySQL Named Lock

| 측정 항목 | Redis Pub/Sub | MySQL Named Lock |
|---------|---------------|------------------|
| **P99 지연시간** | 5-15ms | 20-50ms |
| **Connection Pool 점유** | ❌ 없음 | ✅ 전체 시간 점유 |
| **장애 격리** | ⚠️ Redis 다운 시 락 불가 | ✅ DB와 생존기 동일 |
| **구현 복잡도** | ⚠️ 높음 (Lua, Pub/Sub) | ✅ 낮음 (SQL 2줄) |
| **락 정합성 보장** | ⚠️ Redis Failover 시 깨짐 가능 | ✅ ACID 보장 |

**우리의 선택: Redis Pub/Sub (Redisson)**

**이유 1: Connection Pool 보존**

```java
// MySQL Named Lock (문제 상황)
@Component
public class EquipmentService {
    @Transactional
    public void enhance(Long charId) {
        // 락 획득: DB Connection 점유 시작
        jdbcTemplate.queryForObject("SELECT GET_LOCK(?, ?)", ...);
        Thread.sleep(1000);  // 강화 로직 (1초)
        // 락 해제: DB Connection 반환
        jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", ...);
    }
}

// 문제: 100 RPS 트래픽 + Connection Pool 10개 = 병목 발생!
```

**이유 2: 짧은 대기 시간의 Spin Lock 효율**

```
락 경쟁 상황 (평균 대기 50ms):

MySQL: 50ms 동안 Connection Pool 낭비
Redis: Spin Lock으로 0.1ms 간격 재시도 × 500번 = CPU 낭비?
        → Pub/Sub 구독으로 Blocking 전환 (CPU 절약)
```

### 4.2 Lost Wakeup Problem (Redis의 함정)

**문제 상황:**

```
Timeline:
T0: Client A SETNX lock:123 → 성공 (락 획득)
T1: Client B SETNX lock:123 → 실패
T2: Client B SUBSCRIBE lock:channel:123 (Pub/Sub 구독 시작)
T3: Client A DEL lock:123 + PUBLISH lock:channel:123 "unlocked"
T4: [MISSING!] Client B의 메시지가 도착하기 전에, Client C가 락을 획득
```

**해결책: Redisson의 Watchdog**

```java
// Redisson이 하는 일
RLock lock = redisson.getLock("equipment:123");
lock.lock();  // 내부적으로 "Watchdog Thread" 시작
// Watchdog는 10초마다 락의 TTL을 갱신 (무한 대기 가능)
lock.unlock();
```

**Trade-off**: Watchdog 프로세스가 추가됨 (메모리 + CPU)

---

## 5. The Interview Defense (방어: 100배 트래픽에서 어디가 먼저 터지는가?)

### 5.1 "트래픽이 100배 증가하면?"

**시나리오**: 현재 100 RPS → 10,000 RPS 급증

**실패 포인트 예측:**

1. **Redis Connection Pool 고갈** (最先)
   - 현재: `redisson.maxConnection=64`
   - 10,000 RPS에서는 각 요청마다 평균 500ms 락 대기 → 필요 연결 수 = 5,000개
   - **해결**: Connection Pool 증설, 또는 락 granularity 축소 (`equipment` → `equipment:shard:0-9`)

2. **락 경쟁 심화로 처리량 저하** (次点)
   - 특정 장비(예: "총 15강 성공 기념")에 집중 → `equipment:123`에만 쏠림
   - **해결**: Sharding (`charId % 10`으로 락 분산), 또는 Optimistic Lock으로 변경

3. **Redis Single Thread Bottleneck**
   - SETNX + DEL + PUBLISH 명령이 초당 수만 건 처리
   - **해결**: Redis Cluster로 분산 (Slot 기반 라우팅)

### 5.2 "Redis가 다운되면?"

**현재 시스템의 취약점:**

```java
// Redisson의 기본 설정
RedissonClient redisson = Redisson.create(config);
RLock lock = redisson.getLock("equipment:123");

try {
    lock.lock();  // Redis 다운 시 → RedisException
    enhanceEquipment(charId);
} catch (RedisException e) {
    // 현재: 그냥 예외 처리 (락 없이 진행? 유실?)
    // 개선 필요: Fallback to MySQL Named Lock
}
```

**개선안: Fallback Pattern**

```java
public void enhanceWithFallback(Long charId) {
    try {
        lockStrategy.lock("equipment:" + charId);
        enhanceEquipment(charId);
    } catch (RedisUnavailableException e) {
        log.warn("Redis unavailable, fallback to MySQL");
        mysqlLockStrategy.lock("equipment:" + charId);  // Fallback
        enhanceEquipment(charId);
    } finally {
        lockStrategy.unlock();
    }
}
```

### 5.3 "락 획득 실패 시 사용자 경험은?"

**나쁜 예:**

```java
try {
    lock.lock(10, TimeUnit.SECONDS);  // 10초 대기
    enhance();
} catch (LockAcquisitionException e) {
    throw new ServiceException("잠시 후 다시 시도해주세요");  // 😡 사용자 분노
}
```

**좋은 예 (Queueing + Async):**

```java
// 1. 즉시 락 획득 시도 (Wait Time = 0)
if (!lock.tryLock(0, 30, TimeUnit.SECONDS)) {
    // 2. 실패 시 큐잉 (RabbitMQ / Redis Stream)
    lockRequestQueue.publish(LockRequest.of(charId));
    return "요청이 접수되었습니다. 완료 시 알림을 보내드립니다.";
}

// 3. 백그라운드 워커가 큐 처리
@QueueListener("lock-request-queue")
public void processQueuedRequests() {
    while (!queue.isEmpty()) {
        LockRequest req = queue.poll();
        try {
            lock.lock();
            enhance(req.getCharId());
            notifySuccess(req.getUserId());
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 요약: 핵심 take-away

1. **Lock Ordering은 Deadlock 방지의 최후의 보루**: `executeWithOrderedLocks()`는 이를 구현한 우아한 해결책
2. **Redis는 Spin Lock + Pub/Sub 하이브리드**: 짧은 대기는 Spin, 긴 대기는 Blocking
3. **Connection Pool은 재화가 아닌 병목**: MySQL Named Lock의 치명적 약점
4. **Redis 다운 시 Fallback 전략 필수**: Redis가 SPOF(Single Point of Failure)가 되지 않도록
5. **100배 트래픽 대비**: Sharding, Optimistic Lock, Queueing 등 3단계 방어 전략 필요

---

**다음 챕터 예고**: "Lock은 해결했지만, 데이터 조회 속도가... 메모리 계층 구조와 캐시 일관성의 딜레마"
