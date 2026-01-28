# 좋아요 실시간 동기화 시퀀스 다이어그램 (Issue #278)

## 개요

Scale-out 환경에서 인스턴스 간 좋아요 수를 실시간으로 동기화합니다.
Redis Pub/Sub를 사용하여 L1(Caffeine) 캐시 무효화 이벤트를 전파합니다.

## 아키텍처 개요

```
[Instance A] 좋아요 토글
     │
     ├─1─→ HINCRBY {likes}:buffer → newDelta 반환
     │
     └─2─→ PUBLISH {likes}:events {userIgn, newDelta, eventType}
                    │
     ┌──────────────┘
     ▼
[Instance B] RTopic Listener
     │
     └─3─→ L1 Cache Evict (character 캐시)
```

## 좋아요 토글 시퀀스

```mermaid
sequenceDiagram
    participant C as Client
    participant A as Instance A
    participant R as Redis
    participant B as Instance B

    C->>A: POST /api/v2/characters/{ign}/like

    A->>A: CharacterLikeService.toggleLike()

    alt 좋아요 상태 확인
        A->>R: HGET {likes}:relations
        R-->>A: exists/not-exists
    end

    alt 좋아요 추가
        A->>R: SADD {likes}:relations
        A->>R: HINCRBY {likes}:buffer +1
        R-->>A: newDelta
    else 좋아요 취소
        A->>R: SREM {likes}:relations
        A->>R: HINCRBY {likes}:buffer -1
        R-->>A: newDelta
    end

    A->>R: PUBLISH {likes}:events {userIgn, newDelta, LIKE/UNLIKE}
    R-->>B: Event (RTopic)

    B->>B: LikeEventSubscriber.onEvent()

    alt Self-skip 확인
        Note over B: sourceInstanceId == myInstanceId → 무시
    else 다른 인스턴스 이벤트
        B->>B: L1 Cache Evict (character, characterBasic)
    end

    A-->>C: {liked: true/false, likeCount: N}
```

## Self-skip 메커니즘

```mermaid
sequenceDiagram
    participant A as Instance A
    participant R as Redis Pub/Sub
    participant B as Instance B
    participant C as Instance C

    A->>R: PUBLISH (sourceInstanceId=A)

    R-->>A: Event (to self)
    R-->>B: Event
    R-->>C: Event

    Note over A: sourceInstanceId == A<br/>→ Self-skip

    B->>B: L1 Cache Evict
    C->>C: L1 Cache Evict
```

## Graceful Degradation

```mermaid
sequenceDiagram
    participant A as Instance A
    participant R as Redis

    A->>R: PUBLISH {likes}:events

    alt Redis 정상
        R-->>A: clientsReceived = N
        A->>A: 메트릭: like.event.publish.success++
    else Redis 장애
        R--xA: Timeout/Error
        A->>A: 메트릭: like.event.publish.failure++
        Note over A: 좋아요 기능은 정상 동작<br/>(이벤트 발행만 실패)
    end
```

## 캐시 무효화 전략

| 계층 | 무효화 방법 | TTL Fallback |
|------|-------------|--------------|
| L1 (Caffeine) | Pub/Sub 이벤트 | 5분 |
| L2 (Redis) | 공유 캐시 (무효화 불필요) | 30분 |

**규칙:** Pub/Sub 정상 동작 시 즉시 무효화. 유실 시 TTL로 자연 만료.

## 메트릭

| 메트릭 | 타입 | 설명 |
|--------|------|------|
| `like.event.publish{status=success}` | Counter | 발행 성공 |
| `like.event.publish{status=failure}` | Counter | 발행 실패 |
| `like.event.received` | Counter | 수신 수 |

**유실률 모니터링:**
```
(publish - received) / publish > 1% → 알림
```

## 설정

```yaml
# application.yml
like:
  realtime:
    enabled: true  # Pub/Sub 활성화

app:
  instance-id: ${HOSTNAME:${random.uuid}}  # Self-skip용
```

## 관련 파일

- `src/main/java/maple/expectation/service/v2/like/realtime/LikeEventPublisher.java`
- `src/main/java/maple/expectation/service/v2/like/realtime/LikeEventSubscriber.java`
- `src/main/java/maple/expectation/service/v2/like/realtime/impl/RedisLikeEventPublisher.java`
- `src/main/java/maple/expectation/service/v2/like/realtime/impl/RedisLikeEventSubscriber.java`
- `src/main/java/maple/expectation/service/v2/like/realtime/dto/LikeEvent.java`
- `src/main/java/maple/expectation/config/LikeRealtimeSyncConfig.java`
- `src/main/java/maple/expectation/service/v2/auth/CharacterLikeService.java`

## 5-Agent Council 합의

| Agent | 역할 | 결정 |
|-------|------|------|
| Blue (Architect) | 설계 | Strategy 패턴으로 Pub/Sub 구현체 교체 가능 |
| Green (Performance) | 성능 | RTopic은 Redis 네이티브 래퍼, 오버헤드 최소 |
| Yellow (QA) | 테스트 | 단위 테스트 + 통합 테스트 (Testcontainers) |
| Purple (Data) | 일관성 | Hash Tag `{likes}:*`로 클러스터 슬롯 보장 |
| Red (SRE) | 안정성 | Graceful Degradation, 메트릭 모니터링 |
