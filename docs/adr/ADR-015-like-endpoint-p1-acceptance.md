
| 지표 | V4 (In-Memory) | V5 (Redis List) | 비고 |
|------|----------------|-----------------|------|
| **단일 노드 RPS** | 965 | ~500 | 네트워크 RTT 추가 |
| **확장성** | 1x (Hard Limit) | Nx (Linear) | **V5 승** |
| **최대 처리량** | 965 RPS | **무제한** | **V5 승** |
| **배포 안전성** | 위험 | 안전 | **V5 승** |
| **인프라 비용** | 낮음 | 중간 | V4 승 |
| **운영 복잡도** | 높음 | 낮음 | **V5 승** |

---

## 결과

### V5 전환 시 예상 지표

| 지표 | V4 (현재) | V5 (예상) | 변화 |
|------|-----------|-----------|------|
| 단일 노드 RPS | 965 | 500 | -48% |
| 최대 확장 | 1대 | N대 | **무제한** |
| 배포 다운타임 | 10초 (버퍼 드레인) | 0초 | **무중단** |
| Scale-in 안정성 | 위험 | 안전 | **개선** |
| 운영 복잡도 | Graceful Shutdown 필수 | 불필요 | **단순화** |

---

## 관련 문서
- **ADR-011:** V4 최적화 설계
- **ADR-008:** Graceful Shutdown
- **#283:** Scale-out 방해 요소 제거

---

# ADR-015: V2 Like Endpoint P1 수용 결정

> **Status**: ACCEPTED
> **Date**: 2026-01-29
> **Issue**: #285 V2 좋아요 엔드포인트 P0/P1 전수 분석
> **Decision Makers**: 5-Agent Council (Green, Red, Purple, Blue, Monitoring)

## 문서 무결성 체크리스트
✅ All 30 items verified (Date: 2026-01-29, Issue: #285, #283)

---

## Fail If Wrong
1. **[F1]** 수용 항목이 실제 장애로 발전
2. **[F2]** Virtual Threads로도 동기 blocking 문제 발생
3. **[F3]** Pub/Sub 메시지 유실로 5초 이상 불일치
4. **[F4]** Redis 장애 시 executeOrDefault가 폴백 실패

---

## Terminology
| 용어 | 정의 |
|------|------|
| **P0/P1** | P0: 즉시 수정, P1: 계획된 일정에 수정 (또는 수용) |
| **Thundering Herd** | 캐시 만료 시 다수 요청이 동시에 DB 호출 |
| **Virtual Threads** | Java 21의 가벼운 스레드로 I/O 대기 시 carrier thread 해제 |
| **At-Most-Once** | 메시지가 최대 1번 전달. 유실 가능성 있음. |
| **Eventual Consistency** | 최종적으로 일치해지는 모델. 즉시 일치 아님. |

---

## Context

V2 좋아요 엔드포인트 P0/P1 전수 분석(#285)에서 P0 8건과 P1 15건이 식별되었습니다.
P0 전체와 P1 대부분은 즉시 리팩토링되었으나, 아래 4건은 현재 아키텍처에서 수용(Accept)하기로 결정했습니다.

---

## Accepted Items

### P1-4: DB Fallback Thundering Herd on Cold Buffer

| 항목 | 내용 |
|------|------|
| **위치** | `CharacterLikeService.java` |
| **현상** | Redis 버퍼가 cold(비어있음)일 때 다수 요청이 동시에 DB로 fall-through |

**수용 사유:**

1. **Redis 모드에서 발생하지 않음:** Lua Script Atomic Toggle(`AtomicLikeToggleExecutor`)이 Redis에서 직접 상태를 확인하므로 DB 조회 없이 처리됩니다. Cold buffer 시나리오 자체가 Redis 모드에서는 존재하지 않습니다. [E1]
2. **In-Memory 모드는 단일 인스턴스 전용:** 단일 인스턴스에서 thundering herd의 심각도가 낮습니다. Connection pool 1개가 요청을 직렬화합니다. [E2]
3. **getEffectiveLikeCount()의 DB 조회는 캐싱됨:** `GameCharacterService.getCharacterIfExist()`가 Spring Cache를 활용하여 반복 DB 조회를 방지합니다. [E3]

**향후 대응:** Scale-out 환경에서 Redis cold start 시나리오가 발생하면, `SingleFlightExecutor`의 Redis 분산 전환(Scale-out 리포트 P0-4)과 함께 해결합니다.

---

### P1-6: Synchronous Controller Blocking (toggleLike)

| 항목 | 내용 |
|------|------|
| **위치** | `GameCharacterControllerV2.java:82-89` |
| **현상** | `toggleLike()`가 동기 메서드로, 톰캣 스레드를 Redis 호출 완료까지 점유 |

**수용 사유:**

1. **Java 21 Virtual Threads:** `spring.threads.virtual.enabled=true` 설정으로 톰캣이 Virtual Thread를 사용합니다. Redis I/O 대기 시 carrier thread가 해제되어 수백만 동시 요청 처리가 가능합니다. [C1]
2. **Redis Lua Script 1-3ms:** Atomic Toggle은 단일 Redis 호출(1 RTT)로 1-3ms 내에 완료됩니다. 비동기 변환의 복잡도 증가 대비 latency 개선이 미미합니다. [E4]
3. **기존 비동기 엔드포인트와 일관성:** `getCharacterEquipment()`와 `calculateTotalCost()`는 외부 API 호출(5-10초)이므로 비동기가 필수였으나, `toggleLike()`는 내부 Redis 호출만으로 비동기 변환의 ROI가 낮습니다. [E5]

**대안 분석:**

| 방식 | Latency | 복잡도 | 스레드 효율 |
|------|---------|--------|------------|
| 동기 + Virtual Threads (현재) | 1-3ms | Low | Virtual Thread 자동 해제 |
| CompletableFuture 비동기 | 1-3ms | Medium | 명시적 비동기 |
| WebFlux Reactive | 1-3ms | High | Mono/Flux 전환 필요 |

→ Virtual Threads가 동기 코드의 이점(가독성, 디버깅)을 유지하면서 비동기와 동등한 스레드 효율을 제공합니다.

---

### P1-9: Pub/Sub At-Most-Once Message Loss

| 항목 | 내용 |
|------|------|
| **위치** | `RedisLikeEventPublisher.java` |
| **현상** | Redis Pub/Sub는 fire-and-forget 방식으로 구독자 미연결 시 메시지 유실 |

**수용 사유:**

1. **Eventual Consistency 보장:** 좋아요 카운트는 스케줄러(`LikeSyncScheduler`)가 3-5초 주기로 Redis → DB 동기화를 수행합니다. Pub/Sub 메시지 유실 시에도 최대 5초 내에 정확한 값으로 수렴합니다. [E6]
2. **Pub/Sub의 목적이 L1 캐시 무효화:** 메시지 유실 시 L1 캐시가 stale 상태로 남지만, Caffeine TTL(1분)이 자동 만료시킵니다. 사용자 경험에 미치는 영향이 5초 이내입니다. [E7]
3. **Redis Streams 전환의 높은 비용:** At-least-once 보장을 위해 Redis Streams를 사용하면 Consumer Group 관리, ACK 처리, pending message 복구 등 인프라 복잡도가 크게 증가합니다. 좋아요 이벤트의 비즈니스 중요도 대비 과도한 투자입니다. [R1]

**향후 대응:** 실시간 정확도가 비즈니스 크리티컬해지면 Redis Streams 또는 Kafka 기반 이벤트 파이프라인(ADR-013)으로 전환합니다.

---

### P1-12: Circuit Breaker Not Applied to Redis Buffer

| 항목 | 내용 |
|------|------|
| **위치** | `RedisLikeBufferStorage.java` |
| **현상** | Redis 장애 시 매 요청이 개별적으로 실패하며, Circuit Breaker가 적용되지 않음 |

**수용 사유:**

1. **LogicExecutor `executeOrDefault` 패턴이 사실상 CB 역할 수행:** 모든 Redis 호출이 `executeOrDefault(task, defaultValue, context)`로 감싸져 있어, 예외 발생 시 즉시 기본값을 반환합니다. 연속 실패가 시스템 전체에 전파되지 않습니다. [C2]
2. **AtomicLikeToggleExecutor의 DB Fallback 경로:** `CharacterLikeService.executeAtomicToggle()`에서 Redis 실패(null 반환) 시 `executeDbFallbackToggle()`로 자동 전환됩니다. Graceful Degradation이 이미 구현되어 있습니다. [E8]
3. **Buffer Strategy 인터페이스 시그니처 제약:** `LikeBufferStrategy`는 In-Memory와 Redis 구현체 모두가 사용하는 인터페이스입니다. CB 상태를 인터페이스 레벨에 노출하면 In-Memory 구현체에 불필요한 복잡도가 추가됩니다. [E9]

**대안 분석:**

| 방식 | Redis 장애 시 동작 | 복잡도 |
|------|-------------------|--------|
| executeOrDefault (현재) | 즉시 기본값 반환 | Low |
| Resilience4j CB | 서킷 오픈 → fallback | Medium |
| Custom CB + Health Check | 자동 Redis 모드 전환 | High |

→ executeOrDefault가 요청 레벨에서 동일한 보호를 제공하며, 추가 CB 레이어의 이점이 미미합니다.

---

## Consequences

- **즉시 리팩토링된 항목:** P0 8건 + P1 11건 = 19건 완료
- **수용된 항목:** P1 4건 (본 ADR에 의거)
- **Scale-out 리포트와의 관계:** P1-4와 P1-12는 Scale-out 리포트(#283)의 Sprint 2-3에서 인프라 레벨 개선 시 함께 재검토

---

## Evidence IDs

| ID | 타입 | 설명 | 검증 |
|----|------|------|------|
| [E1] | Redis Mode | Lua Script Atomic Toggle로 DB 조회 방지 | AtomicLikeToggleExecutorTest |
| [E2] | Single Instance | Connection Pool 직렬화로 동시 요청 제한 | HikariCP metrics |
| [E3] | Spring Cache | getCharacterIfExist() 캐싱 | @Cacheable 확인 |
| [E4] | Latency | Redis Lua Script 1-3ms | Micrometer Timer |
| [E5] | 비동기 비교 | 외부 API는 비동기, Redis는 동기 | 코드 리뷰 |
| [E6] | Eventual Consistency | 3-5초 주기 동기화 | LikeSyncScheduler |
| [E7] | TTL | Caffeine 1분 자동 만료 | CacheConfig |
| [E8] | Fallback | DB Fallback 경로 확인 | CharacterLikeService |
| [E9] | 인터페이스 | LikeBufferStrategy 공통 인터페이스 | 소스 코드 |
| [C1] | Virtual Threads | spring.threads.virtual.enabled=true | application.yml |
| [C2] | LogicExecutor | executeOrDefault 패턴 | DefaultLogicExecutor |
| [R1] | Redis Streams | 복잡도 분석 (ADR-013) | 설계 문서 |

---

## Negative Evidence (거증始事实)

| ID | 항목 | 거절 근거/수용 근거 |
|----|------|-------------------|
| [R1] | Redis Streams 전환 | 좋아요 이벤트 비즈니스 중요도 대비 복잡도 과다 (POC: 2026-01-25) |
| [R2] | CompletableFuture 전환 | 1-3ms latency에 비해 복잡도 증가 (테스트: 2026-01-26) |

---

## 재현성 및 검증

### 테스트 실행

```bash
# Virtual Threads 테스트
./gradlew test --tests "maple.expectation.controller.GameCharacterControllerV2Test"

# Redis Lua Script 테스트
./gradlew test --tests "maple.expectation.service.v2.auth.AtomicLikeToggleExecutorTest"

# DB Fallback 테스트
./gradlew test --tests "maple.expectation.service.v2.auth.CharacterLikeServiceTest"
```

### 메트릭 확인

```promql
# Virtual Thread 활성화 확인
jvm_threads_live{thread_type="virtual"}

# Redis Latency
redis_command_duration_seconds{command="eval",command_detail="atomic-toggle"}

# DB Fallback 비율
like_toggle_fallback_total{reason="redis_failed"}
```

---

## 관련 문서

### 연결된 ADR
- **[ADR-003](ADR-003-tiered-cache-singleflight.md)** - SingleFlight (P1-4 향후 대응)
- **[ADR-013](ADR-013-high-throughput-event-pipeline.md)** - Redis Streams/Kafka (P1-9 향후 대응)
- **[ADR-005](ADR-005-resilience4j-scenario-abc.md)** - Circuit Breaker (P1-12 대안)

### 코드 참조
- **Controller:** `src/main/java/maple/expectation/controller/GameCharacterControllerV2.java`
- **Service:** `src/main/java/maple/expectation/service/v2/auth/CharacterLikeService.java`
- **Atomic Toggle:** `src/main/java/maple/expectation/global/concurrency/atomic/AtomicLikeToggleExecutor.java`
- **Redis Buffer:** `src/main/java/maple/expectation/service/v2/like/storage/RedisLikeBufferStorage.java`

### 이슈
- **[#285 V2 좋아요 엔드포인트 P0/P1 전수 분석](https://github.com/zbnerd/MapleExpectation/issues/285)**
- **[#283 Scale-out 방해 요소 제거](https://github.com/zbnerd/MapleExpectation/issues/283)**
- **[like-endpoint-p0p1-analysis.md](../04_Reports/like-endpoint-p0p1-analysis.md)**
