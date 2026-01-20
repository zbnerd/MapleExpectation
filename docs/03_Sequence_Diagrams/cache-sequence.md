# TieredCache Single-flight 시퀀스 다이어그램

## 개요

Multi-Layer 캐시(L1: Caffeine, L2: Redis)와 분산 Single-flight 패턴으로 **Cache Stampede**를 방지합니다.

## 캐시 조회 시퀀스

```mermaid
sequenceDiagram
    participant C as Caller
    participant TC as TieredCache
    participant L1 as Caffeine (L1)
    participant L2 as Redis (L2)
    participant Lock as Redisson Lock
    participant API as External API

    C->>TC: get(key, valueLoader)

    TC->>L1: get(key)
    alt L1 HIT
        L1-->>TC: ValueWrapper
        TC-->>C: value (0ms)
    else L1 MISS
        TC->>L2: get(key)
        alt L2 HIT
            L2-->>TC: ValueWrapper
            TC->>L1: put(key, value)
            Note over TC,L1: L1 Backfill
            TC-->>C: value
        else L2 MISS
            TC->>Lock: tryLock(key, waitTime)
            alt Lock 획득 (Leader)
                Lock-->>TC: true
                TC->>L2: get(key)
                Note over TC,L2: Double-check
                alt 여전히 MISS
                    TC->>API: valueLoader.call()
                    API-->>TC: value
                    TC->>L2: put(key, value)
                    TC->>L1: put(key, value)
                end
                TC->>Lock: unlock()
                TC-->>C: value
            else Lock 대기 (Follower)
                Lock-->>TC: false (timeout)
                TC->>L2: get(key)
                Note over TC,L2: Leader가 저장 완료
                L2-->>TC: ValueWrapper
                TC->>L1: put(key, value)
                TC-->>C: value
            end
        end
    end
```

## Watchdog 모드 (락 자동 갱신)

```mermaid
sequenceDiagram
    participant C as Caller
    participant Lock as RLock
    participant WD as Watchdog Thread
    participant Redis as Redis

    C->>Lock: tryLock(waitTime, TimeUnit)
    Note over Lock: leaseTime 생략 = Watchdog 활성화

    Lock->>Redis: SET key NX PX 30000
    Redis-->>Lock: OK

    activate WD
    loop 매 10초
        WD->>Redis: PEXPIRE key 30000
        Note over WD: 락 TTL 30초로 갱신
    end
    deactivate WD

    Note over C: 작업 완료 (45초 소요)

    C->>Lock: unlock()
    Lock->>Redis: DEL key
    Note over WD: Watchdog 중단
```

## Write Order 규칙 (L2 → L1)

```mermaid
sequenceDiagram
    participant TC as TieredCache
    participant L2 as Redis (L2)
    participant L1 as Caffeine (L1)

    TC->>L2: put(key, value)
    alt L2 성공
        L2-->>TC: OK
        TC->>L1: put(key, value)
        L1-->>TC: OK
    else L2 실패
        L2--xTC: Exception
        Note over TC,L1: L1 저장 스킵<br/>(불일치 방지)
    end
```

## TTL 규칙

| 계층 | TTL | 이유 |
|------|-----|------|
| L1 (Caffeine) | 5분 | 로컬 메모리 절약 |
| L2 (Redis) | 30분 | L1 만료 시 Fallback |

**규칙:** L1 TTL ≤ L2 TTL (L2가 항상 Superset)

## 설정

```yaml
# application.yml
cache:
  equipment:
    l1-ttl: 300        # 5분
    l2-ttl: 1800       # 30분
    lock-wait-time: 30  # 초
```

## E2E 테스트 결과

| 시나리오 | 결과 | 증거 |
|---------|------|------|
| RD-S01: L1 캐시 HIT | PASS | `fetchWithCache` 0ms |
| AO-G01: Two-Phase Snapshot | PASS | 1차: 617ms → 2차: 1ms |

## 관련 파일

- `src/main/java/maple/expectation/global/cache/TieredCache.java`
- `src/main/java/maple/expectation/global/cache/TieredCacheManager.java`
- `src/main/java/maple/expectation/config/RedissonConfig.java`
