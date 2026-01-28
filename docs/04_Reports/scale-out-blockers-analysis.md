# Scale-out 방해 요소 전수 분석 리포트

> **분석 일자:** 2026-01-28
> **분석 범위:** `src/main/java` 전체 (global, service, scheduler, config, aop, monitoring 패키지)
> **관련 이슈:** [#283](https://github.com/zbnerd/MapleExpectation/issues/283)

---

## 요약

| 분류 | P0 (Critical) | P1 (High) | 합계 |
|------|:---:|:---:|:---:|
| In-Memory 상태 | 6 | 6 | 12 |
| Feature Flag 기본값 | 2 | 2 | 4 |
| Scheduler 중복 실행 | 2 | 3 | 5 |
| 기타 (모니터링/추적) | 0 | 3 | 3 |
| **합계** | **8** | **14** | **22** |

---

## P0 (Critical: Scale-out 즉시 불가)

### P0-1: AlertThrottler — 전역 일일 카운터

**파일:** `monitoring/throttle/AlertThrottler.java:33-34`

```java
private final AtomicInteger dailyAiCallCount = new AtomicInteger(0);
private final Map<String, Instant> lastAlertTimeByPattern = new ConcurrentHashMap<>();
```

| 항목 | 내용 |
|------|------|
| **문제** | 각 인스턴스가 독립적인 dailyAiCallCount 보유. 2개 인스턴스 시 AI 호출 한도 100 → 200으로 증가 |
| **영향** | AI SRE 알림 오버쿼터 (비용 초과), 스로틀 타임스탬프도 인스턴스별 독립 |
| **해결** | Redis AtomicLong `ai:throttle:daily-count:{yyyy-MM-dd}` + TTL 자동 만료 |

---

### P0-2: InMemoryBufferStrategy — Scale-out 미지원

**파일:** `global/queue/strategy/InMemoryBufferStrategy.java:57-69`

```java
private final ConcurrentLinkedQueue<QueueMessage<T>> mainQueue = new ConcurrentLinkedQueue<>();
private final ConcurrentHashMap<String, QueueMessage<T>> inflightMap = new ConcurrentHashMap<>();
private final ConcurrentLinkedQueue<QueueMessage<T>> dlq = new ConcurrentLinkedQueue<>();
```

| 항목 | 내용 |
|------|------|
| **문제** | 코드 주석 자체가 "한계점 (Scale-out 불가)" 경고. 버퍼가 JVM 메모리에만 존재 |
| **영향** | 배포/장애 시 데이터 유실, 인스턴스 간 메시지 중복 처리 |
| **해결** | `RedisBufferStrategy`로 전환 (이미 구현됨), `app.buffer.redis.enabled=true` 기본값 |

---

### P0-3: LikeBufferStorage / LikeRelationBuffer — Feature Flag 종속

**파일:** `service/v2/cache/LikeBufferStorage.java:35-41`, `service/v2/cache/LikeRelationBuffer.java:56-76`

```java
// LikeBufferStorage
private final Cache<String, AtomicLong> likeCache = Caffeine.newBuilder()
    .expireAfterAccess(1, TimeUnit.MINUTES).maximumSize(1000).build();

// LikeRelationBuffer
private final ConcurrentHashMap<String, Boolean> localPendingSet = new ConcurrentHashMap<>();
```

| 항목 | 내용 |
|------|------|
| **문제** | `app.buffer.redis.enabled=false` 기본값 시 Caffeine 로컬 캐시로 동작 |
| **영향** | 인스턴스별 독립 좋아요 카운터 → DB에 부분 반영, 순위 오류 |
| **해결** | `redis.enabled` 기본값 `true`로 변경, In-Memory는 dev/test 전용 |

---

### P0-4: SingleFlightExecutor — In-Memory inFlight Map

**파일:** `global/concurrency/SingleFlightExecutor.java:52`

```java
private final ConcurrentHashMap<String, InFlightEntry<T>> inFlight = new ConcurrentHashMap<>();
```

| 항목 | 내용 |
|------|------|
| **문제** | 진행 중인 계산을 인스턴스 메모리에만 저장. 인스턴스 B에 동일 요청 시 2번 계산 |
| **영향** | Single-flight 효과 상실, 계산 중복 N배, API 오버로드 |
| **해결** | Redis 기반 Distributed Single-Flight 구현 (`single-flight:{keyHash}` + TTL) |

---

### P0-5: AiSreService — Virtual Thread Executor 제한 없음

**파일:** `monitoring/ai/AiSreService.java:65`

```java
private final Executor aiExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

| 항목 | 내용 |
|------|------|
| **문제** | Virtual Thread Executor가 인스턴스 필드로 제한 없이 생성. LLM 호출(5-10초) 누적 |
| **영향** | JVM OOM, CPU 100% 스파이크 → 전체 서비스 장애 |
| **해결** | Bean 기반 전환 + maxThreads 제한 (Semaphore) |

---

### P0-6: LoggingAspect — volatile running 플래그

**파일:** `aop/aspect/LoggingAspect.java:30`

```java
private volatile boolean running = false;
```

| 항목 | 내용 |
|------|------|
| **문제** | SmartLifecycle의 start/stop이 인스턴스별 독립 동작 |
| **영향** | 인스턴스 A 종료 중에도 B는 계속 요청 수신. 배포 중 요청 손실 |
| **해결** | Redis shutdown flag 또는 K8s readiness probe로 트래픽 차단 |

---

### P0-7: CompensationLogService — Consumer Group 중복 처리

**파일:** `global/resilience/CompensationLogService.java:64-74`

```java
@PostConstruct
public void initConsumerGroup() {
    // 각 인스턴스의 @PostConstruct에서 실행
    stream.createGroup(StreamCreateGroupArgs.name(properties.getSyncConsumerGroup()).makeStream());
}
```

| 항목 | 내용 |
|------|------|
| **문제** | 여러 인스턴스가 동시 생성 시도. Pending 처리 시 동일 메시지 중복 처리 가능 |
| **영향** | Compensation Log 중복 동기화, 데이터 정합성 깨짐 |
| **해결** | unique consumerId(instanceId) 사용, 분산 락으로 초기화 단일 실행 |

---

### P0-8: DynamicTTLManager — 이벤트 중복 처리

**파일:** `global/resilience/DynamicTTLManager.java:80-101`

```java
@Async
@EventListener
public void onMySQLDown(MySQLDownEvent event) {
    RLock lock = redissonClient.getLock(properties.getTtlLockKey());
    boolean acquired = lock.tryLock(...);
    // ...
}
```

| 항목 | 내용 |
|------|------|
| **문제** | MySQL DOWN 이벤트가 모든 인스턴스에서 독립 발생 및 처리 |
| **영향** | 여러 인스턴스가 동시 TTL SCAN → 불필요한 Redis 부하, 순서 미보장 |
| **해결** | MySQL Health 상태를 Redis에 중앙 저장, 리더 인스턴스만 이벤트 발행 |

---

## P1 (High: 데이터 불일치/중복 실행 위험)

### P1-1: RateLimiter — 인스턴스별 ProxyManager

**파일:** `global/ratelimit/strategy/IpBasedRateLimiter.java:30`, `UserBasedRateLimiter.java:30`

| 항목 | 내용 |
|------|------|
| **문제** | 같은 IP → 인스턴스 A: 60 RPS + B: 40 RPS = 실제 100 RPS (한도 초과) |
| **해결** | Bucket4j 분산 설정 검증, Redis Lua Script 원자적 제어 |

### P1-4: LikeBufferConfig — matchIfMissing=false

**파일:** `config/LikeBufferConfig.java:59`

```java
@ConditionalOnProperty(name = "app.buffer.redis.enabled", havingValue = "true", matchIfMissing = false)
```

| 항목 | 내용 |
|------|------|
| **문제** | 기본값 In-Memory → 프로덕션에서 설정 누락 시 Scale-out 불가 |
| **해결** | `matchIfMissing=true`로 변경, In-Memory는 `@Profile("local")` 전용 |

### P1-5: RedisBufferConfig — 동일 Feature Flag 문제

**파일:** `config/RedisBufferConfig.java:34`

| 항목 | 내용 |
|------|------|
| **문제** | P1-4와 동일. Redis 활성화가 명시적이어야 작동 |
| **해결** | `matchIfMissing=true` 변경 |

### P1-7: BufferRecoveryScheduler — 분산 락 없이 동시 실행

**파일:** `scheduler/BufferRecoveryScheduler.java:82-120`

```java
@Scheduled(fixedRateString = "${scheduler.buffer-recovery.retry-rate:5000}")
public void processRetryQueue() { }

@Scheduled(fixedRateString = "${scheduler.buffer-recovery.redrive-rate:30000}")
public void redriveExpiredInflight() { }
```

| 항목 | 내용 |
|------|------|
| **문제** | 분산 락 없이 모든 인스턴스에서 5초/30초 간격으로 동시 실행 |
| **해결** | `@Locked` 분산 락 적용 또는 리더 패턴 |

### P1-8: LikeSyncScheduler — 부분 분산화

**파일:** `scheduler/LikeSyncScheduler.java:65-127`

```java
@Scheduled(fixedRate = 1000)
public void localFlush() { }       // 모든 인스턴스에서 실행

@Scheduled(fixedRate = 3000)
public void globalSyncCount() { }  // PartitionedFlushStrategy (분산화됨)
```

| 항목 | 내용 |
|------|------|
| **문제** | `globalSyncCount()`와 `globalSyncRelation()`이 동일 3초 주기 → 경합 가능 |
| **해결** | globalSync 일정 조정 (stagger), 파티션 기반 처리 검증 |

### P1-9: OutboxScheduler — 중복 폴링

**파일:** `scheduler/OutboxScheduler.java:42-57`

```java
@Scheduled(fixedRate = 10000)
public void pollAndProcess() { }

@Scheduled(fixedRate = 300000)
public void recoverStalled() { }
```

| 항목 | 내용 |
|------|------|
| **문제** | 모든 인스턴스에서 동시에 Outbox 테이블 쿼리 → 중복 처리 |
| **해결** | 분산 락 또는 `HASH(outbox.id) % instance_count` 파티셔닝 |

### P1-10: ExpectationBatchWriteScheduler — Shutdown 동기화

**파일:** `scheduler/ExpectationBatchWriteScheduler.java:81-90`

| 항목 | 내용 |
|------|------|
| **문제** | `isShuttingDown()` 플래그가 로컬 메모리에만 존재 |
| **해결** | Redis shutdown flag `system:shutdown:{instanceId}` |

### P1-11: MDCFilter — ThreadLocal 분산 추적 불가

**파일:** `global/filter/MDCFilter.java`

| 항목 | 내용 |
|------|------|
| **문제** | MDC ThreadLocal은 인스턴스 간 trace-id 연결 불가 |
| **해결** | OpenTelemetry 도입, trace-id 요청 헤더 전파 (장기) |

### P1-12: ExecutorConfig — 메트릭 분산

**파일:** `config/ExecutorConfig.java:321-394`

| 항목 | 내용 |
|------|------|
| **문제** | Micrometer 메트릭이 인스턴스별 기록 → 합산 모니터링 수동 |
| **해결** | Prometheus + Grafana 인스턴스별 집약 대시보드 구성 |

---

## 추가 발견 항목 (2차 심층 분석)

### P1-13: RedisExpectationWriteBackBuffer — synchronized 로컬 상태

**파일:** `global/queue/expectation/RedisExpectationWriteBackBuffer.java:166-212`

```java
private final List<String> inflightMessageIds = new ArrayList<>();

synchronized (inflightMessageIds) {
    inflightMessageIds.clear();
    for (QueueMessage<ExpectationWriteTask> message : messages) {
        tasks.add(message.payload());
        inflightMessageIds.add(message.msgId());
    }
}
```

| 항목 | 내용 |
|------|------|
| **문제** | INFLIGHT 추적이 인스턴스 로컬 `ArrayList`에만 존재. 분산 환경에서 ACK 불일치 |
| **해결** | Redis Stream Consumer Group의 pending entries로 대체 (native 기능) |

### P1-14: LookupTableInitializer — AtomicBoolean readiness

**파일:** `config/LookupTableInitializer.java:64`

```java
private final AtomicBoolean initialized = new AtomicBoolean(false);

public boolean isReady() {
    return initialized.get() && starforceLookupTable.isInitialized();
}
```

| 항목 | 내용 |
|------|------|
| **문제** | Lookup Table 초기화 상태가 인스턴스별 독립. Rolling Update 시 readiness 불일치 |
| **해결** | K8s Startup Probe + Readiness Probe 분리, 또는 Redis 전역 초기화 상태 관리 |

### P1-15: ExpectationWriteBackBuffer — volatile + Phaser 혼합

**파일:** `service/v4/buffer/ExpectationWriteBackBuffer.java:51-77`

```java
private final ConcurrentLinkedQueue<ExpectationWriteTask> queue = new ConcurrentLinkedQueue<>();
private final Phaser shutdownPhaser = new Phaser() { ... };
private volatile boolean shuttingDown = false;
```

| 항목 | 내용 |
|------|------|
| **문제** | `shuttingDown` 플래그와 Phaser 모두 인스턴스 로컬. 분산 shutdown 불균형 → 일부 데이터만 flush |
| **해결** | Redis Distributed Lock으로 단일 leader shutdown 조정, Phaser → Redis pending count |

### P1-16: GracefulShutdownCoordinator — volatile running

**파일:** `global/shutdown/GracefulShutdownCoordinator.java:35`

```java
private volatile boolean running = false;
```

| 항목 | 내용 |
|------|------|
| **문제** | SmartLifecycle `running` 상태가 인스턴스 로컬. Load Balancer가 종료 중인 인스턴스에 트래픽 전송 가능 |
| **해결** | Redis `{instanceId}:lifecycle:running` 키로 상태 관리, K8s readiness probe 연동 |

### P1-17: ExpectationBatchShutdownHandler — running=false 조기 설정

**파일:** `service/v4/buffer/ExpectationBatchShutdownHandler.java:52`

```java
private volatile boolean running = true;

@Override
public void stop() {
    executor.executeVoid(() -> {
        // 3-phase shutdown (비동기)
    }, context);
    this.running = false;  // ← 3-phase 완료 전에 즉시 false
}
```

| 항목 | 내용 |
|------|------|
| **문제** | `running=false`가 3-phase shutdown 완료 전 즉시 설정 → SmartLifecycle 순서 위반, 데이터 유실 |
| **해결** | shutdown 완료 후에만 `running=false` 설정, CountDownLatch 또는 CompletableFuture 대기 |

---

## 최종 요약 테이블

| # | 컴포넌트 | 레벨 | 패턴 | 해결 방향 |
|---|---------|------|------|----------|
| P0-1 | AlertThrottler | P0 | In-Memory | Redis AtomicLong |
| P0-2 | InMemoryBufferStrategy | P0 | Feature Flag | Redis 강제 |
| P0-3 | LikeBufferStorage/Relation | P0 | Feature Flag | Redis 강제 |
| P0-4 | SingleFlightExecutor | P0 | In-Memory | Redis Single-Flight |
| P0-5 | AiSreService | P0 | In-Memory | Bean + maxThreads |
| P0-6 | LoggingAspect | P0 | In-Memory | Redis flag |
| P0-7 | CompensationLogService | P0 | Scheduler | Consumer Group 분산 |
| P0-8 | DynamicTTLManager | P0 | Scheduler | 이벤트 중앙화 |
| P1-1 | RateLimiter | P1 | In-Memory | Bucket4j 분산 설정 |
| P1-4 | LikeBufferConfig | P1 | Feature Flag | matchIfMissing=true |
| P1-5 | RedisBufferConfig | P1 | Feature Flag | matchIfMissing=true |
| P1-7 | BufferRecoveryScheduler | P1 | Scheduler | @Locked |
| P1-8 | LikeSyncScheduler | P1 | Scheduler | 일정 조정 |
| P1-9 | OutboxScheduler | P1 | Scheduler | 파티셔닝 |
| P1-10 | ExpectationBatchWriteScheduler | P1 | In-Memory | Redis 플래그 |
| P1-11 | MDCFilter | P1 | ThreadLocal | OpenTelemetry |
| P1-12 | ExecutorConfig | P1 | 모니터링 | 대시보드 집약 |
| P1-13 | RedisExpectationWriteBackBuffer | P1 | synchronized | Redis Stream pending |
| P1-14 | LookupTableInitializer | P1 | In-Memory | K8s Probe 분리 |
| P1-15 | ExpectationWriteBackBuffer | P1 | In-Memory | Redis leader shutdown |
| P1-16 | GracefulShutdownCoordinator | P1 | In-Memory | Redis lifecycle 키 |
| P1-17 | ExpectationBatchShutdownHandler | P1 | In-Memory | shutdown 완료 대기 |

> **총계: P0 8개 + P1 14개 = 22개 항목**

---

## 해결 우선순위

### Sprint 1 — Feature Flag 정리 (Low Risk)
- [ ] P0-2: `InMemoryBufferStrategy`에 `@Profile("local")` 적용
- [ ] P0-3: `LikeBufferConfig` / `RedisBufferConfig`의 `matchIfMissing=true`
- [ ] P0-5: `AiSreService` Virtual Thread Executor Bean 기반 + maxThreads 제한
- [ ] P1-4, P1-5: Feature Flag 기본값 검증 테스트

### Sprint 2 — In-Memory → Redis (Medium Risk)
- [ ] P0-1: `AlertThrottler` → Redis AtomicLong + TTL 자동 만료
- [ ] P0-4: `SingleFlightExecutor` → Redis Distributed Single-Flight
- [ ] P0-6: `LoggingAspect.running` → Redis shutdown flag
- [ ] P1-10: `ExpectationBatchWriteScheduler.isShuttingDown()` → Redis 플래그

### Sprint 3 — Scheduler 분산화 (High Risk)
- [ ] P0-7: `CompensationLogService` → unique consumerId 파티션 분산
- [ ] P0-8: `DynamicTTLManager` → Redis 중앙 상태 + 리더 이벤트
- [ ] P1-7: `BufferRecoveryScheduler` → `@Locked` 분산 락
- [ ] P1-8: `LikeSyncScheduler` → globalSync 경합 해소
- [ ] P1-9: `OutboxScheduler` → 분산 락 또는 파티셔닝

### 관찰 항목 (모니터링 강화)
- [ ] P1-1: RateLimiter Bucket4j 분산 설정 검증
- [ ] P1-11: MDCFilter → OpenTelemetry 분산 추적 (장기)
- [ ] P1-12: ExecutorConfig 메트릭 인스턴스별 합산 대시보드

---

## 관련 문서

- [#283 Scale-out 방해 요소 제거](https://github.com/zbnerd/MapleExpectation/issues/283)
- [#282 멀티 모듈 전환](https://github.com/zbnerd/MapleExpectation/issues/282)
- [#126 Pragmatic CQRS](https://github.com/zbnerd/MapleExpectation/issues/126)
- [ADR-014: 멀티 모듈 전환](../adr/ADR-014-multi-module-cross-cutting-concerns.md)
