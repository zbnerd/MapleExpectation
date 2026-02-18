# MapleExpectation SRE/Infrastructure Operations Guide

> **Document Purpose:** SRE 및 인프라 엔지니어를 위한 운영 노하우 및 모범 사례 정리
>
> **Analysis Date:** 2026-02-17
>
> **Scope:** Redis, TieredCache, Thread Pool, AOP, Security, Resilience4j

---

## Executive Summary

MapleExpectation은 **AWS t3.small (1 vCPU, 2GB RAM)** 사양에서 **719 RPS, 1,000+ 동시 사용자**를 처리하는 고성능 시스템입니다. 이 문서는 SRE 관점에서 핵심 인프라 구성요소의 운영 노하우를 정리합니다.

### Key Performance Metrics
| Metric | Value | Target |
|--------|-------|--------|
| **Max Throughput** | 719 RPS | > 500 RPS |
| **L1 Cache Hit Rate** | 85-95% | > 80% |
| **P99 Latency** | < 500ms | < 500ms |
| **GZIP Compression** | 90% reduction | > 85% |
| **Redis Lock Latency (p95)** | 12ms | < 10ms |

---

## 1. Redis (Redisson 3.27.0) 운영 가이드

### 1.1 Redis HA 아키텍처

```
┌─────────────────────────────────────────────────────┐
│              Redis Sentinel HA Cluster             │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐      │
│  │Sentinel 1│    │Sentinel 2│    │Sentinel 3│      │
│  │:26379   │    │:26380   │    │:26381   │      │
│  └────┬────┘     └────┬────┘     └────┬────┘      │
│       │               │               │            │
│       └───────────────┴───────────────┘            │
│                       │                             │
│            ┌──────────▼──────────┐                 │
│            │  Master (Primary)   │                 │
│            │  172.20.0.10:6379   │                 │
│            └──────────┬──────────┘                 │
│                       │ Replicate                  │
│            ┌──────────▼──────────┐                 │
│            │   Slave (Replica)   │                 │
│            │       :6380         │                 │
│            └─────────────────────┘                 │
└─────────────────────────────────────────────────────┘
```

### 1.2 Redisson 설정 핵심 파라미터

**파일:** `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/config/RedissonConfig.java`

```java
// Sentinel 모드 설정
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis://172.20.0.10:26379", ...)
    .setCheckSentinelsList(false)           // Sentinel 리스트 자동 갱신 비활성화
    .setScanInterval(1000)                  // 1초마다 Master 상태 확인
    .setReadMode(ReadMode.MASTER)           // Master에서만 읽기 (일관성 우선)
    .setRetryAttempts(3)                    // 실패 시 3회 재시도
    .setRetryInterval(1500)                 // 1.5초 간격으로 재시도
    .setTimeout(8000)                       // 8초 타임아웃 (Issue #225)
    .setConnectTimeout(5000)                // 5초 연결 타임아웃
    .setMasterConnectionPoolSize(64)        // Master 커넥션 풀
    .setMasterConnectionMinimumIdleSize(24); // 최소 유휴 커넥션
```

### 1.3 Redis 사용 패턴

| 사용처 | 데이터 구조 | 용도 | Hash Tag | 성능 특성 |
|--------|-----------|------|-----------|-----------|
| **L2 Cache** | String | Equipment, OCID, Expectation 캐싱 | 사용 안 함 | O(1) 조회 |
| **Like Buffer** | Sorted Set | 좋아요 버퍼 (timestamp 정렬) | `{buffer:likes}` | ZADD O(log N) |
| **Distributed Lock** | RLock | 분산 락 (Watchdog 모드) | `{cache:sf:...}` | 자동 갱신 |
| **Leader Latch** | RCountDownLatch | SingleFlight Leader/Follower | `{latch:eq:...}` | CountDownLatch |
| **Rate Limit** | RBucket | 사용자별 요청 제한 (Bucket4j) | - | Token Bucket |

### 1.4 Lua Script 활용 (원자적 연산)

**파일:** `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/redis/script/RedissonLikeAtomicOperations.java`

**특징:**
- **SHA Caching:** `scriptLoad()` + `evalSha()`로 네트워크 오버헤드 최소화
- **NOSCRIPT 자동 복구:** Redis 재시작 시 스크립트 자동 재로드
- **Hash Tag:** 모든 키에 `{buffer:likes}` 태그로 Redis Cluster CROSSSLOT 에러 방지

```java
// 좋아요 동기화 원자적 연산 예시
String sha = scriptProvider.getTransferSha();
RScript script = redissonClient.getScript(StringCodec.INSTANCE);
Long result = script.evalSha(
    RScript.Mode.READ_WRITE,
    sha,
    RScript.ReturnType.INTEGER,
    Arrays.asList(LuaScripts.Keys.HASH, LuaScripts.Keys.TOTAL_COUNT), // Hash Tag 포함
    userIgn,
    String.valueOf(count)
);
```

**운영 메트릭:**
- `like.sync.lua.duration` - Lua 실행 시간 (script 태그로 분류)
- `like.sync.compensation.count` - 복구 실행 횟수

### 1.5 Redis 장애 대응 절차

#### 장애 감지
```promql
# Redis 연결 실패율
rate(redis_connection_failures_total[5m]) > 0.01

# Lua Script NOSCRIPT 에러
increase(redis_noscript_errors_total[5m]) > 0
```

#### 장애 대응
1. **Sentinel 자동 Failover** (Quorum 2/3)
   - Master 장애 감지: 10초 내
   - Slave 승격: 5초 내
   - 애플리케이션 자동 재연결

2. **수동 개입 필요 시**
```bash
# Sentinel 상태 확인
redis-cli -p 26379 SENTINEL masters
redis-cli -p 26379 SENTINEL slaves mymaster

# 수동 Failover (긴급 시)
redis-cli -p 26379 SENTINEL failover mymaster
```

3. **Redis Cluster Cross-slot 에러**
```bash
# Hash Tag 사용 확인
redis-cli --scan --pattern "equipment:*" | head -10

# 잘못된 키 패턴 수정 (Hash Tag 누락)
# Before: equipment:12345
# After:  {equipment}:12345
```

### 1.6 Redis 모니터링 대시보드

**필수 메트릭:**
```
# Connection Pool
redisson_active_connections{pool="master"}
redisson_idle_connections{pool="master"}

# Command Statistics
redis_command_duration_seconds{command="GET",quantile="p95"}
redis_command_duration_seconds{command="ZADD",quantile="p99"}

# Lua Scripts
redis_lua_script_executions_total{script="transfer",status="success"}
redis_lua_script_duration_seconds{script="transfer"}

# Cache Operations
cache_hit_total{layer="L2"}
cache_miss_total{layer="L2"}
cache_l2_failure_total  # Redis 장애 시 증가
```

---

## 2. TieredCache 및 Cache Stampede 방지

### 2.1 2계층 캐시 아키텍처

```
┌───────────────────────────────────────────────────────────┐
│                    TieredCache                            │
├───────────────────────────────────────────────────────────┤
│                                                           │
│  [Request]                                                │
│      │                                                    │
│      ▼                                                    │
│  ┌─────────┐  L1 HIT: < 5ms     ┌─────────┐              │
│  │  L1     │────────────────────▶│ Response │              │
│  │Caffeine │  85-95% hit rate    └─────────┘              │
│  │(Local)  │                                           │
│  └────┬────┘                                           │
│       │ MISS                                           │
│       ▼                                                │
│  ┌─────────┐  L2 HIT: < 20ms    ┌─────────┐              │
│  │  L2     │────────────────────▶│ Response │              │
│  │  Redis  │  + Backfill L1     └─────────┘              │
│  │(Dist.)  │                                           │
│  └────┬────┘                                           │
│       │ MISS                                           │
│       ▼                                                │
│  ┌─────────────────┐                                   │
│  │ SingleFlight    │  동일 요청 병합                    │
│  │ (Distributed)   │  Leader만 실행                     │
│  └────┬────────────┘                                   │
│       │                                                │
│       ▼                                                │
│  ┌─────────┐                                           │
│  │Source   │  API / DB                                │
│  └─────────┘                                           │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

### 2.2 Cache Stampede 방지 메커니즘

**문제 상황:** 캐시 만료 시점에 100개의 동시 요청이 들어오면 DB/API에 100회 호출

**해결:** SingleFlight + Distributed Lock
```java
// 파일: TieredCache.java
public <T> T get(Object key, Callable<T> valueLoader) {
    // 1. L1 → L2 조회 (Optional 체이닝)
    T cached = getCachedValueFromLayers(key);
    if (cached != null) return cached;

    // 2. 분산 락으로 Single-flight
    RLock lock = redissonClient.getLock("cache:sf:" + key);
    if (lock.tryLock(5, TimeUnit.SECONDS)) {
        return executeAsLeader(key, valueLoader);  // Leader: 실제 계산
    }
    return executeAsFollower(key);  // Follower: Leader 결과 대기
}
```

**성능 개선 효과:**
- Before: DB 호출 100회, p99 2,340ms
- After: DB 호출 1회, p99 180ms (**-92%**)

### 2.3 캐시 설정 (CacheConfig.java)

| Cache Name | L1 TTL | L1 Max | L2 TTL | 용도 |
|------------|--------|--------|--------|------|
| `equipment` | 5 min | 5,000 | 10 min | Nexon API 장비 데이터 |
| `cubeTrials` | 10 min | 5,000 | 20 min | Cube 확률 계산 |
| `ocidCache` | 30 min | 5,000 | 60 min | OCID 매핑 |
| `expectationV4` | 60 min | 5,000 | 60 min | 기대값 계산 결과 |

### 2.4 L1/L2 일관성 보장 (P0-1, P0-3)

**핵심 원칙:** L2(Redis)를 source of truth로 간주

```java
// put() 순서: L2 → L1
public void put(Object key, Object value) {
    boolean l2Success = l2.put(key, value);  // 먼저 L2 저장
    if (l2Success) {
        l1.put(key, value);  // L2 성공 시에만 L1 저장
        publishEvictEvent(key);  // 원격 인스턴스 L1 무효화
    }
}

// evict() 순서: L2 → L1 → Pub/Sub
public void evict(Object key) {
    l2.evict(key);  // 먼저 L2 제거 (backfill 방지)
    l1.evict(key);  // 그 다음 L1 제거
    publishEvictEvent(key);  // 원격 인스턴스 L1 무효화
}
```

### 2.5 캐시 모니터링

**필수 메트릭:**
```promql
# Cache Hit Rate
rate(cache_hit_total{layer="L1"}[5m]) /
  (rate(cache_hit_total{layer="L1"}[5m]) + rate(cache_miss_total{layer="L1"}[5m]))

# Cache Stampede 발생 횟수
rate(cache_lock_failure_total[5m])

# L2 장애 시 Graceful Degradation
rate(cache_l2_failure_total[5m])
```

**Alert 규칙:**
```yaml
# L1 Hit Rate 저하
- alert: LowCacheHitRate
  expr: rate(cache_hit_total{layer="L1"}[5m]) < 0.8
  for: 5m
  annotations:
    summary: "L1 Cache Hit Rate below 80%"

# Cache Stampede 의심
- alert: CacheStampedeDetected
  expr: rate(cache_lock_failure_total[1m]) > 10
  annotations:
    summary: "Multiple lock acquisition failures detected"
```

---

## 3. 비동기 파이프라인 및 Thread Pool 전략

### 3.1 Executor 구조

```
┌─────────────────────────────────────────────────────────┐
│                LogicExecutor Pipeline                   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  [Service Layer]                                        │
│       │                                                 │
│       ▼                                                 │
│  ┌──────────────────┐                                  │
│  │ LogicExecutor    │  예외 처리, 로깅, 메트릭         │
│  │ (Primary Bean)   │  - execute()                     │
│  └──────────────────┘  - executeOrDefault()            │
│       │                 - executeWithRecovery()         │
│       │                                                 │
│       ├─────────────────────────────────────┐           │
│       │                                     │           │
│       ▼                                     ▼           │
│  ┌──────────────┐                    ┌──────────────┐ │
│  │alertTaskExec │                    │aiTaskExecutor│ │
│  │  Core: 2     │  Discord/Slack    │ Semaphore:10 │ │
│  │  Max: 4      │  알림 전송        │ Virtual      │ │
│  │ Queue: 200   │  (Best-effort)   │ Threads      │ │
│  └──────────────┘                    └──────────────┘ │
│       │                                     │           │
│       ▼                                     ▼           │
│  ┌──────────────────────────────────────────────────┐  │
│  │expectationComputeExecutor                        │  │
│  │  Core: 4, Max: 8, Queue: 200                     │  │
│  │  데드라인 30초 강제 (orTimeout)                  │  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 Thread Pool 설정

**파일:** `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/config/ExecutorConfig.java`

| Executor | Core | Max | Queue | KeepAlive | Rejection Policy | 용도 |
|----------|------|-----|-------|-----------|------------------|------|
| **alertTaskExecutor** | 2 | 4 | 200 | 30s | AbortPolicy + Metrics | Discord 알림 |
| **aiTaskExecutor** | Virtual Threads | Semaphore: 10 | - | Semaphore Timeout | AI LLM 호출 |
| **expectationComputeExecutor** | 4 | 8 | 200 | 30s | AbortPolicy + Metrics | 기대값 계산 |

### 3.3 Backpressure 및 Rejection Handling

**RejectionPolicyFactory** - 폭주 시 드롭/종료 전략
```java
// Alert Executor: Best-effort (샘플링 로깅 + 메트릭)
public RejectedExecutionHandler createAlertAbortPolicy() {
    return new RejectedExecutionHandler() {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                // 10% 샘플링 로깅
                if (ThreadLocalRandom.current().nextDouble() < 0.1) {
                    log.warn("[AlertExecutor] Task rejected");
                }
                alertRejectedCounter.increment();
            }
        }
    };
}

// Expectation Executor: 즉시 실패 (메트릭 기록)
public RejectedExecutionHandler createExpectationAbortPolicy() {
    return (r, e) -> {
        expectationRejectedCounter.increment();
        throw new RejectedExecutionException("Expectation compute queue full");
    };
};
```

### 3.4 ThreadLocal 전파 (P0-4/B2)

**TaskDecorator** - MDC + Cache Context 전파
```java
public class ContextPropagatingTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        // 현재 스레드의 ThreadLocal 캡처
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        Boolean skipL2 = SkipEquipmentL2CacheContext.snapshot();

        return () -> {
            try {
                // 콜백 스레드에서 복원
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                SkipEquipmentL2CacheContext.restore(skipL2);
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

### 3.5 Thread Pool 모니터링

**필수 메트릭:**
```promql
# Thread Pool 상태
hikaricp_connections_active{pool="alert"} / hikaricp_connections_max{pool="alert"}
executor_pool_size{executor="expectation"}
executor_queue_size{executor="expectation"}

# Rejection Rate
rate(executor_rejected_total{executor="expectation"}[5m])

# Task Duration
executor_task_duration_seconds{executor="alert",quantile="p95"}
```

**Alert 규칙:**
```yaml
# Queue 포화
- alert: ExecutorQueueFull
  expr: executor_queue_size{executor="expectation"} > 180
  for: 1m
  annotations:
    summary: "Expectation executor queue 90% full"

# Rejection 증가
- alert: HighRejectionRate
  expr: rate(executor_rejected_total[5m]) > 1
  annotations:
    summary: "Tasks being rejected at > 1/sec"
```

---

## 4. AOP/Facade 패턴 활용

### 4.1 AOP 레이어 구조

```
┌────────────────────────────────────────────────────────┐
│                   AOP Layer                           │
├────────────────────────────────────────────────────────┤
│                                                        │
│  [Controller]                                          │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ @Order(0) LockAspect                            │  │
│  │ - @Locked annotation 처리                       │  │
│  │ - Redisson 분산 락 획득                         │  │
│  │ - 락 실패 시 Fallback 실행                      │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ NexonDataCacheAspect (V2)                      │  │
│  │ - @NexonDataCache annotation 처리               │  │
│  │ - L1/L2 캐시 조회                              │  │
│  │ - Leader/Follower 패턴                          │  │
│  │ - ThreadLocal 컨텍스트 보존 (비동기 콜백)      │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ TraceAspect                                     │  │
│  │ - 실행 시간 측정                               │  │
│  │ - Slow Query 로깅                              │  │
│  │ - Micrometer 메트릭 기록                        │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  [Service Layer]                                      │
│                                                        │
└────────────────────────────────────────────────────────┘
```

### 4.2 @Locked Annotation - 분산 락

**파일:** `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/aop/aspect/LockAspect.java`

**사용 예:**
```java
@Locked(
    key = "#ocid",           // SpEL 표현식으로 동적 키 생성
    waitTime = 5L,           // 최대 5초 대기
    leaseTime = 10L          // 10초 후 자동 해제
)
public Character getCharacter(String ocid) {
    // 비즈니스 로직
}
```

**Fallback 전략:** 락 획득 실패 시 락 없이 직접 조회 (가용성 우선)

### 4.3 ThreadLocal 컨텍스트 관리 (NexonDataCacheAspect)

**문제:** CompletableFuture 비동기 콜백에서 ThreadLocal 유실
```java
// 문제 상황
Thread-1: SkipEquipmentL2CacheContext.set(true)
Thread-1: CompletableFuture.supplyAsync(...)
ForkJoinPool-1: 콜백 실행 → ThreadLocal 값 null ❌
```

**해결:** Snapshot/Restore 패턴
```java
// 현재 스레드 컨텍스트 캡처
Boolean skipContextSnap = SkipEquipmentL2CacheContext.snapshot();

return future.handle((res, ex) -> {
    // 콜백 스레드에서 복원
    SkipEquipmentL2CacheContext.restore(skipContextSnap);
    return processAsyncCallback(res, ex);
});
```

### 4.4 AOP Ordering

**중요:** AOP 체인 순서가 올바르게 동작하려면 `@Order` 명시
```java
@Aspect
@Order(0)  // 가장 먼저 실행 (락 획득)
public class LockAspect { }

@Aspect
@Order(1)  // 캐시 조회
public class NexonDataCacheAspect { }

@Aspect
@Order(2)  // 메트릭 기록
public class TraceAspect { }
```

---

## 5. 보안 (Spring Security 6.x, JWT, 필터 체인)

### 5.1 보안 필터 체인

```
┌────────────────────────────────────────────────────────┐
│              Security Filter Chain                    │
├────────────────────────────────────────────────────────┤
│                                                        │
│  [HTTP Request]                                       │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ RateLimitingFilter                              │  │
│  │ - Bucket4j 기반 Rate Limiting                   │  │
│  │ - IP-based / User-based 제한                    │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ JwtAuthenticationFilter                         │  │
│  │ - JWT 토큰 검증                                 │  │
│  │ - SecurityContext 인증 설정                     │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ MDCFilter                                        │  │
│  │ - TraceId, UserIgn MDC 설정                     │  │
│  │ - 로그 추�적 가능                               │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  [Controller]                                        │
│                                                        │
└────────────────────────────────────────────────────────┘
```

### 5.2 Prometheus 엔드포인트 보안

**파일:** `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/security/filter/PrometheusSecurityFilter.java`

**보안 계층:**
1. **IP Whitelist** - 신뢰할 수 있는 프록시/내부 네트워크만 허용
2. **X-Forwarded-For Validation** - 헤더 스푸핑 방지
3. **Rate Limiting** - DoS 방어

**신뢰할 수 있는 네트워크:**
```yaml
prometheus:
  security:
    enabled: true
    trusted-proxies: "127.0.0.1,::1,localhost"
    internal-networks: "172.16.0.0/12,10.0.0.0/8,192.168.0.0/16"
```

**IP 검증 로직:**
```java
private boolean validateClientIp(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    String xForwardedFor = request.getHeader("X-Forwarded-For");

    // X-Forwarded-For에서 원본 IP 추출
    String clientIp = extractClientIp(xForwardedFor, remoteAddr);

    // localhost 허용
    if (isLocalhost(clientIp)) return true;

    // 신뢰할 수 있는 프록시 확인
    if (trustedProxies.contains(clientIp)) return true;

    // 내부 네트워크 확인 (CIDR)
    if (isInternalNetwork(clientIp)) return true;

    return false;
}
```

### 5.3 JWT 인증 흐름

```
┌──────────────────────────────────────────────────────────┐
│                    JWT Authentication                   │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  [Login Request]                                         │
│       │                                                  │
│       ▼                                                  │
│  ┌─────────────────────────────────────────────────┐    │
│  │ AuthenticationManager                            │    │
│  │ - 사용자 인증 (DB 조회)                          │    │
│  │ - FingerprintGenerator로 디바이스 지문 생성     │    │
│  └─────────────────────────────────────────────────┘    │
│       │                                                  │
│       ▼                                                  │
│  ┌─────────────────────────────────────────────────┐    │
│  │ JwtTokenProvider                                 │    │
│  │ - JWT Access Token 생성 (1h)                    │    │
│  │ - JWT Refresh Token 생성 (14d)                  │    │
│  └─────────────────────────────────────────────────┘    │
│       │                                                  │
│       ▼                                                  │
│  [Response: { accessToken, refreshToken }]             │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Subsequent Request                                │    │
│  │ - Authorization: Bearer <accessToken>            │    │
│  └─────────────────────────────────────────────────┘    │
│       │                                                  │
│       ▼                                                  │
│  ┌─────────────────────────────────────────────────┐    │
│  │ JwtAuthenticationFilter                          │    │
│  │ - JWT 서명 검증                                  │    │
│  │ - SecurityContext 인증 설정                     │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 5.4 접근 제어 규칙

| Endpoint | Access Control | Rate Limit | 설명 |
|----------|----------------|------------|------|
| `/api/public/**` | permitAll | IP-based | 공개 API |
| `/api/v2/characters/{ign}` | permitAll | IP-based | 캐릭터 조회 |
| `/api/v2/characters/*/like` | authenticated | User-based | 좋아요 (JWT 필요) |
| `/api/admin/**` | hasRole(ADMIN) | User-based | 관리자 전용 |
| `/actuator/prometheus` | IP Whitelist | None | 내부 네트워크만 |

---

## 6. Resilience4j (Circuit Breaker, Retry, TimeLimiter)

### 6.1 회복 탄력성 스택

```
┌────────────────────────────────────────────────────────┐
│              Resilience4j Stack                        │
├────────────────────────────────────────────────────────┤
│                                                        │
│  [Service Call]                                       │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ TimeLimiter (10s timeout)                       │  │
│  │ - orTimeout(10, SECONDS)                        │  │
│  │ - 타임아웃 시 TimeoutException                  │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ CircuitBreaker (50% threshold)                  │  │
│  │ - CLOSED: 정상 (정상 흐름)                      │  │
│  │ - OPEN: 장애 (5분 cooldown)                     │  │
│  │ - HALF_OPEN: 프로브 (1개 요청)                  │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  ┌─────────────────────────────────────────────────┐  │
│  │ Retry (3 attempts, exp backoff)                 │  │
│  │ - waitDuration: 1s                              │  │
│  │ - intervalFunction: exponential backoff         │  │
│  └─────────────────────────────────────────────────┘  │
│       │                                                │
│       ▼                                                │
│  [External API: Nexon Open API]                      │
│                                                        │
└────────────────────────────────────────────────────────┘
```

### 6.2 예외 계층 구조

```
BaseException (abstract)
├── ClientBaseException (4xx)
│   ├── CircuitBreakerIgnoreMarker  ← CB 무시
│   ├── CharacterNotFoundException
│   ├── SelfLikeNotAllowedException
│   └── DuplicateLikeException
│
└── ServerBaseException (5xx)
    ├── CircuitBreakerRecordMarker  ← CB 기록
    ├── ExternalServiceException
    ├── ApiTimeoutException
    └── CompressionException
```

**핵심:**
- **ClientBaseException (4xx):** 비즈니스 예외, `CircuitBreakerIgnoreMarker`로 CB 상태에 영향 주지 않음
- **ServerBaseException (5xx):** 시스템 예외, `CircuitBreakerRecordMarker`로 장애 시 CB 작동

### 6.3 Circuit Breaker 상태 전이

```
         ┌─────────────────────────────────────────┐
         │                                         │
         ▼                                         │
    [CLOSED] ◀───────────────────────────────── [HALF_OPEN]
         │  (Error Rate < 50%)                    │  (Success)
         │                                         │
         │ (Error Rate > 50%)                      │ (Failure)
         │                                         │
         ▼                                         │
       [OPEN] ─────────────────────────────────────┘
              (5분 경과)
```

### 6.4 Fallback 전략

```java
// ResilientNexonApiClient 예시
public Equipment fetchEquipment(String ocid) {
    return circuitBreaker.executeSupplier(() ->
        retry.executeSupplier(() ->
            timeLimiter.executeFutureSupplier(() ->
                nexonApiClient.getEquipment(ocid)
            )
        )
    ).recover(throwable ->
        fallbackHandler.getFromDatabase(ocid)  // DB Fallback
    ).get();
}
```

### 6.5 Resilience4j 모니터링

**필수 메트릭:**
```promql
# Circuit Breaker 상태
resilience4j_circuitbreaker_state{name="nexonApi",state="closed"}
resilience4j_circuitbreaker_state{name="nexonApi",state="open"}

# 실패율
resilience4j_circuitbreaker_failure_rate{name="nexonApi"}

# Call 통계
rate(resilience4j_circuitbreaker_calls_total{name="nexonApi",kind="successful"}[5m])
rate(resilience4j_circuitbreaker_calls_total{name="nexonApi",kind="failed"}[5m])

# Retry 통계
resilience4j_retry_calls_total{name="nexonApi",kind="successful"}
```

**Alert 규칙:**
```yaml
# Circuit Breaker OPEN
- alert: CircuitBreakerOpen
  expr: resilience4j_circuitbreaker_state{name="nexonApi",state="open"} == 1
  for: 1m
  annotations:
    summary: "Nexon API Circuit Breaker is OPEN"

# 높은 실패율
- alert: HighFailureRate
  expr: resilience4j_circuitbreaker_failure_rate{name="nexonApi"} > 0.5
  for: 2m
  annotations:
    summary: "Circuit Breaker failure rate > 50%"
```

---

## 7. 운영 체크리스트

### 7.1 일일 운영 (Daily)

- [ ] **Cache Hit Rate 확인**
  ```bash
  curl -s http://localhost:8080/actuator/metrics/cache.hit | jq '.measurements'
  ```
  목표: L1 > 80%, L2 > 90%

- [ ] **Redis 상태 확인**
  ```bash
  redis-cli -p 6379 INFO replication
  redis-cli -p 6379 INFO stats
  ```

- [ ] **Thread Pool 상태 확인**
  ```bash
  curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq '.measurements'
  ```

- [ ] **Circuit Breaker 상태 확인**
  ```bash
  curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
  ```

### 7.2 주간 운영 (Weekly)

- [ ] **Slow Query 분석** (MySQL)
  ```bash
  mysqldumpslow -s t -t 10 /var/log/mysql/slow.log
  ```

- [ ] **GZIP 압축율 확인**
  ```sql
  SELECT AVG(LENGTH(data_gzip))/AVG(LENGTH(data_json)) as compression_ratio
  FROM equipment;
  ```
  목표: > 90%

- [ ] **Lua Script 성능 분석**
  ```bash
  curl -s http://localhost:8080/actuator/metrics/like.sync.lua.duration | jq '.measurements'
  ```

- [ ] **로그 볼륨 분석**
  ```bash
  grep ERROR /var/log/maple-expectation/application.log | wc -l
  ```

### 7.3 월간 운업 (Monthly)

- [ ] **Capacity Planning**
  - 현재 RPS: ___ / 목표: 500 RPS
  - 현재 동시 사용자: ___ / 목표: 1,000
  - CPU 사용률 (p95): ___ / 목표: < 80%

- [ ] **캐시 설정 검토**
  - TTL 적정성 검토
  - Max Size 조정 필요성 검토

- [ ] **의존성 업데이트**
  - Spring Boot: 3.5.4
  - Redisson: 3.27.0
  - Resilience4j: 2.2.0

---

## 8. 장애 대응 플레이북

### 8.1 Redis 장애 (ADR-310)

**증상:**
- Cache miss 급증
- `cache_l2_failure_total` 메트릭 증가
- 응답 시간 p99 > 1초

**대응:**
1. Tiered Fallback 자동 동작 확인 (Redis → MySQL Lock)
2. Sentinel Failover 모니터링
   ```bash
   redis-cli -p 26379 SENTINEL masters
   ```
3. Redis 복구 후 캐시 워밍업

**롤백 트리거:**
- Redis Lock latency p95 > 100ms (1시간 지속)
- Fallback 발생률 > 10% (1시간 지속)

### 8.2 Cache Stampede (N01 Chaos Test)

**증상:**
- DB/API 부하 급증
- `cache_lock_failure_total` 메트릭 증가
- Thread Pool 고갈

**대응:**
1. SingleFlight 동작 확인
   ```bash
   curl -s http://localhost:8080/actuator/metrics/singleflight.deduplication
   ```
2. L1 캐시 수동 워밍업
3. TTL 랜덤화 검토 (현재 미사용)

### 8.3 Thread Pool 고갈 (N03 Chaos Test)

**증상:**
- `executor_rejected_total` 메트릭 증가
- 요청 타임아웃
- Queue 사이즈 90% 이상

**대응:**
1. Thread Pool 설정 검토
   ```bash
   curl -s http://localhost:8080/actuator/metrics/executor.pool.size
   ```
2. Rejection Policy 로그 확인
3. Capacity scaling (t3.small → t3.medium)

### 8.4 Circuit Breaker OPEN (N03, N06)

**증상:**
- `resilience4j_circuitbreaker_state` = OPEN
- Fallback 메트릭 증가
- 외부 API 타임아웃

**대응:**
1. 외부 API 상태 확인 (Nexon Open API)
2. Cooldown 대기 (5분)
3. HALF_OPEN 상태 모니터링

---

## 9. 모니터링 대시보드 구성

### 9.1 Grafana Dashboard 목록

| Dashboard | 목적 | 핵심 패널 |
|-----------|------|----------|
| **Cache Performance** | 캐시 히트율, Stampede | L1/L2 Hit Rate, Lock Failures |
| **Redis Operations** | Redis 명령, Lua Script | Command Duration, Script Executions |
| **Thread Pool** | Executor 상태, Rejection | Pool Size, Queue Size, Active Threads |
| **Circuit Breaker** | CB 상태, 실패율 | State, Failure Rate, Call Stats |
| **API Performance** | RPS, Latency | Request Rate, p50/p95/p99 |
| **Database** | Connection Pool, Slow Query | HikariCP, Slow Query Count |

### 9.2 Prometheus Alert 규칙

**Critical (P0):**
```yaml
# Cache Stampede
- alert: CacheStampede
  expr: rate(cache_lock_failure_total[1m]) > 10
  annotations:
    summary: "Cache stampede detected - DB overload imminent"

# Redis Down
- alert: RedisDown
  expr: redis_up == 0
  annotations:
    summary: "Redis instance is down"

# Circuit Breaker Open
- alert: CircuitBreakerOpen
  expr: resilience4j_circuitbreaker_state{state="open"} == 1
  annotations:
    summary: "Circuit breaker is OPEN - external API degraded"
```

**Warning (P1):**
```yaml
# Low Cache Hit Rate
- alert: LowCacheHitRate
  expr: rate(cache_hit_total{layer="L1"}[5m]) < 0.8
  annotations:
    summary: "L1 Cache Hit Rate below 80%"

# High Rejection Rate
- alert: HighRejectionRate
  expr: rate(executor_rejected_total[5m]) > 0.1
  annotations:
    summary: "Executor rejection rate > 0.1/sec"
```

---

## 10. 성능 최적화 팁

### 10.1 L1 Fast Path (Issue #264)

**문제:** TieredCache 오버헤드로 L1 HIT 시 27ms 소요

**해결:** L1 캐시 직접 조회
```java
// Before (TieredCache 경유)
Equipment equipment = tieredCache.get(key);

// After (L1 Direct Path - 5ms)
Cache l1Cache = tieredCacheManager.getL1CacheDirect("equipment");
ValueWrapper wrapper = l1Cache.get(key);
if (wrapper != null) {
    return (Equipment) wrapper.get();
}
```

### 10.2 GZIP Compression (90% 절감)

**적용 대상:** Equipment JSON 데이터 (350KB → 35KB)

**구현:** `@Convert` 어노테이션으로 자동 변환
```java
@Convert(converter = GzipConverter.class)
@Column(name = "data_gzip")
private String dataGzip;
```

### 10.3 SingleFlight Follower Timeout (PR #160)

**문제:** Follower가 공유 promise를 기다리다 Leader 지연 시 전체 타임아웃

**해결:** 각 Follower에게 독립적인 Future 생성
```java
private CompletableFuture<T> executeAsFollower(String key, CompletableFuture<T> leaderFuture) {
    CompletableFuture<T> isolatedFuture = new CompletableFuture<>();
    leaderFuture.whenComplete((result, error) -> {
        if (error != null) isolatedFuture.completeExceptionally(error);
        else isolatedFuture.complete(result);
    });

    return isolatedFuture
        .orTimeout(followerTimeoutSeconds, TimeUnit.SECONDS)  // 독립 타임아웃
        .exceptionallyCompose(e -> handleFollowerException(key, e));
}
```

### 10.4 Redis Lock Watchdog 모드

**장점:** leaseTime 생략으로 30초마다 자동 갱신
```java
RLock lock = redissonClient.getLock(lockKey);
lock.tryLock(waitTime, TimeUnit.SECONDS);  // Watchdog 자동 활성화
```

---

## 11. 참고 문헌

### ADR (Architecture Decision Records)
- **ADR-003:** TieredCache + SingleFlight 패턴
- **ADR-007:** AOP 및 비동기 컨텍스트 관리
- **ADR-310:** MySQL Named Lock → Redis 분산 락 마이그레이션

### Chaos Engineering (Nightmare Tests)
- **N01:** Thundering Herd (Cache Stampede)
- **N02:** Deadlock Trap
- **N03:** Thread Pool Exhaustion
- **N06:** Timeout Cascade
- **N19:** Outbox Replay (Compound Failures)

### Configuration Files
- `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/config/RedissonConfig.java`
- `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/config/ExecutorConfig.java`
- `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/cache/TieredCache.java`

---

**문서 버전:** 1.0.0
**최종 업데이트:** 2026-02-17
**유지 관리자:** SRE Team
