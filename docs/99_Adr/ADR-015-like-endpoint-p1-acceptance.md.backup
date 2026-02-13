# ADR-015: V2 Like Endpoint P1 수용 결정

> **Status**: ACCEPTED
> **Date**: 2026-01-29
> **Issue**: #285 V2 좋아요 엔드포인트 P0/P1 전수 분석
> **Decision Makers**: 5-Agent Council (Green, Red, Purple, Blue, Monitoring)

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

1. **Redis 모드에서 발생하지 않음**: Lua Script Atomic Toggle(`AtomicLikeToggleExecutor`)이 Redis에서 직접 상태를 확인하므로 DB 조회 없이 처리됩니다. Cold buffer 시나리오 자체가 Redis 모드에서는 존재하지 않습니다.
2. **In-Memory 모드는 단일 인스턴스 전용**: 단일 인스턴스에서 thundering herd의 심각도가 낮습니다. Connection pool 1개가 요청을 직렬화합니다.
3. **getEffectiveLikeCount()의 DB 조회는 캐싱됨**: `GameCharacterService.getCharacterIfExist()`가 Spring Cache를 활용하여 반복 DB 조회를 방지합니다.

**향후 대응**: Scale-out 환경에서 Redis cold start 시나리오가 발생하면, `SingleFlightExecutor`의 Redis 분산 전환(Scale-out 리포트 P0-4)과 함께 해결합니다.

---

### P1-6: Synchronous Controller Blocking (toggleLike)

| 항목 | 내용 |
|------|------|
| **위치** | `GameCharacterControllerV2.java:82-89` |
| **현상** | `toggleLike()`가 동기 메서드로, 톰캣 스레드를 Redis 호출 완료까지 점유 |

**수용 사유:**

1. **Java 21 Virtual Threads**: `spring.threads.virtual.enabled=true` 설정으로 톰캣이 Virtual Thread를 사용합니다. Redis I/O 대기 시 carrier thread가 해제되어 수백만 동시 요청 처리가 가능합니다.
2. **Redis Lua Script 1-3ms**: Atomic Toggle은 단일 Redis 호출(1 RTT)로 1-3ms 내에 완료됩니다. 비동기 변환의 복잡도 증가 대비 latency 개선이 미미합니다.
3. **기존 비동기 엔드포인트와 일관성**: `getCharacterEquipment()`와 `calculateTotalCost()`는 외부 API 호출(5-10초)이므로 비동기가 필수였으나, `toggleLike()`는 내부 Redis 호출만으로 비동기 변환의 ROI가 낮습니다.

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

1. **Eventual Consistency 보장**: 좋아요 카운트는 스케줄러(`LikeSyncScheduler`)가 3-5초 주기로 Redis → DB 동기화를 수행합니다. Pub/Sub 메시지 유실 시에도 최대 5초 내에 정확한 값으로 수렴합니다.
2. **Pub/Sub의 목적이 L1 캐시 무효화**: 메시지 유실 시 L1 캐시가 stale 상태로 남지만, Caffeine TTL(1분)이 자동 만료시킵니다. 사용자 경험에 미치는 영향이 5초 이내입니다.
3. **Redis Streams 전환의 높은 비용**: At-least-once 보장을 위해 Redis Streams를 사용하면 Consumer Group 관리, ACK 처리, pending message 복구 등 인프라 복잡도가 크게 증가합니다. 좋아요 이벤트의 비즈니스 중요도 대비 과도한 투자입니다.

**향후 대응**: 실시간 정확도가 비즈니스 크리티컬해지면 Redis Streams 또는 Kafka 기반 이벤트 파이프라인(ADR-013)으로 전환합니다.

---

### P1-12: Circuit Breaker Not Applied to Redis Buffer

| 항목 | 내용 |
|------|------|
| **위치** | `RedisLikeBufferStorage.java` |
| **현상** | Redis 장애 시 매 요청이 개별적으로 실패하며, Circuit Breaker가 적용되지 않음 |

**수용 사유:**

1. **LogicExecutor `executeOrDefault` 패턴이 사실상 CB 역할 수행**: 모든 Redis 호출이 `executeOrDefault(task, defaultValue, context)`로 감싸져 있어, 예외 발생 시 즉시 기본값을 반환합니다. 연속 실패가 시스템 전체에 전파되지 않습니다.
2. **AtomicLikeToggleExecutor의 DB Fallback 경로**: `CharacterLikeService.executeAtomicToggle()`에서 Redis 실패(null 반환) 시 `executeDbFallbackToggle()`로 자동 전환됩니다. Graceful Degradation이 이미 구현되어 있습니다.
3. **Buffer Strategy 인터페이스 시그니처 제약**: `LikeBufferStrategy`는 In-Memory와 Redis 구현체 모두가 사용하는 인터페이스입니다. CB 상태를 인터페이스 레벨에 노출하면 In-Memory 구현체에 불필요한 복잡도가 추가됩니다.

**대안 분석:**

| 방식 | Redis 장애 시 동작 | 복잡도 |
|------|-------------------|--------|
| executeOrDefault (현재) | 즉시 기본값 반환 | Low |
| Resilience4j CB | 서킷 오픈 → fallback | Medium |
| Custom CB + Health Check | 자동 Redis 모드 전환 | High |

→ executeOrDefault가 요청 레벨에서 동일한 보호를 제공하며, 추가 CB 레이어의 이점이 미미합니다.

---

## Consequences

- **즉시 리팩토링된 항목**: P0 8건 + P1 11건 = 19건 완료
- **수용된 항목**: P1 4건 (본 ADR에 의거)
- **Scale-out 리포트와의 관계**: P1-4와 P1-12는 Scale-out 리포트(#283)의 Sprint 2-3에서 인프라 레벨 개선 시 함께 재검토

---

## References

- [#285 V2 좋아요 엔드포인트 P0/P1 전수 분석](https://github.com/zbnerd/MapleExpectation/issues/285)
- [#283 Scale-out 방해 요소 제거](https://github.com/zbnerd/MapleExpectation/issues/283)
- [like-endpoint-p0p1-analysis.md](../04_Reports/like-endpoint-p0p1-analysis.md)
- [ADR-013 High-Throughput Event Pipeline](ADR-013-high-throughput-event-pipeline.md)
