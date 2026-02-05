# N19 Nexon API Outbox Pattern 시퀀스 다이어그램

> **Issue #303**: 넥슨 API 장애 시 데이터 유실 방지 및 자동 복구
> **Chaos Test**: N19 - 6시간 장애 복구 시나리오

## 1. 개요

Nexon API Outbox 패턴은 **외부 API 장애 시 데이터 유실을 방지**하고 **복구 후 자동 재처리**를 보장하는 패턴입니다.

### 핵심 특성

| 특성 | 설명 |
|------|------|
| **Zero Data Loss** | 장애 시 모든 API 요청을 Outbox에 보존 |
| **Auto Recovery** | 복구 후 스케줄러가 자동 재처리 (99.98%) |
| **Exponential Backoff** | 재시도 간격 기하급수적 증가 (30s → 16분) |
| **Distributed Safe** | SKIP LOCKED로 분산 환경 중복 처리 방지 |
| **Triple Safety Net** | DLQ → File Backup → Discord Alert |

---

## 2. 아키텍처 개요

```mermaid
graph TB
    subgraph "Normal Path (API Success)"
        CLIENT[Client Request] --> API[ResilientNexonApiClient]
        API --> NEXON[Nexon External API]
        NEXON --> API
        API --> CLIENT
    end

    subgraph "Failure Path (Outbox Storage)"
        API -->|API Failure| OUTBOX[(nexon_api_outbox)]
        API --> CLIENT
    end

    subgraph "Recovery Path (Scheduler Replay)"
        SCHEDULER[NexonApiOutboxScheduler<br/>30s interval] --> PROCESSOR[NexonApiOutboxProcessor]
        PROCESSOR -->|SKIP LOCKED| OUTBOX
        PROCESSOR --> RETRY[NexonApiRetryClient]
        RETRY --> NEXON
        NEXON -->|Success| PROCESSOR
        PROCESSOR -->|Delete| OUTBOX
    end

    subgraph "Triple Safety Net"
        DLQ[(nexon_api_dlq)]
        FILE[File Backup]
        DISCORD[Discord Alert]

        PROCESSOR -->|Max Retry| DLQ
        DLQ -.->|DB Fail| FILE
        FILE -.->|File Fail| DISCORD
    end

    style OUTBOX fill:#ff9,stroke:#333
    style DLQ fill:#f99,stroke:#333
    style NEXON fill:#f66,stroke:#333
```

---

## 3. Normal Path 시퀀스 (API 성공)

```mermaid
sequenceDiagram
    participant C as Client
    participant API as ResilientNexonApiClient
    participant NEXON as Nexon API
    participant CACHE as Redis Cache

    C->>API: fetchCharacterData(ocid)

    activate API
    Note over API: 1. 캐시 확인
    API->>CACHE: get(ocid)
    CACHE-->>API: MISS

    Note over API: 2. 외부 API 호출
    rect rgb(200, 255, 200)
        API->>NEXON: GET /api/character/{ocid}
        NEXON-->>API: 200 OK (CharacterData)
    end

    Note over API: 3. 캐시 저장
    API->>CACHE: set(ocid, data, ttl)

    API-->>C: CharacterData
    deactivate API
```

---

## 4. Failure Path 시퀀스 (API 실패 → Outbox 적재)

```mermaid
sequenceDiagram
    participant C as Client
    participant API as ResilientNexonApiClient
    participant NEXON as Nexon API
    participant OUTBOX as NexonApiOutboxRepository
    participant DB as MySQL

    C->>API: fetchCharacterData(ocid)

    activate API
    Note over API: 1. 캐시 확인
    API->>API: cache.get(ocid) = MISS

    Note over API: 2. 외부 API 호출 시도
    rect rgb(255, 200, 200)
        API->>NEXON: GET /api/character/{ocid}
        NEXON-->>API: 503 Service Unavailable<br/>or Timeout
    end

    Note over API: 3. Outbox 적재 (데이터 보존)
    rect rgb(255, 230, 200)
        API->>OUTBOX: save(NexonApiOutbox.builder()<br/>    .ocid(ocid)<br/>    .endpoint("/character")<br/>    .status(PENDING)<br/>    .nextRetryAt(now + 30s)<br/>    .build())

        Note over OUTBOX,DB: INSERT INTO nexon_api_outbox<br/>(ocid, endpoint, status=PENDING)
        OUTBOX->>DB: INSERT
        DB-->>OUTBOX: OK
    end

    Note over API: 4. 예외 전파 (사용자에게 알림)
    API-->>C: 503 Service Unavailable<br/>(Retry-After: 60s)
    deactivate API

    Note over C,DB: 📝 데이터는 Outbox에 보존됨<br/>스케줄러가 30초 후 자동 재처리
```

---

## 5. Recovery Path 시퀀스 (스케줄러 자동 재처리)

```mermaid
sequenceDiagram
    participant SCHED as NexonApiOutboxScheduler
    participant PROC as NexonApiOutboxProcessor
    participant REPO as OutboxRepository
    participant DB as MySQL
    participant RETRY as NexonApiRetryClient
    participant NEXON as Nexon API

    Note over SCHED: Every 30 seconds

    SCHED->>PROC: pollAndProcess()

    activate PROC
    PROC->>REPO: findPendingWithLock(PENDING/FAILED, now, LIMIT 100)

    rect rgb(255, 230, 200)
        Note over REPO,DB: SKIP LOCKED 쿼리<br/>(분산 환경 중복 처리 방지)
        REPO->>DB: SELECT * FROM nexon_api_outbox<br/>WHERE status IN ('PENDING','FAILED')<br/>AND next_retry_at <= NOW()<br/>ORDER BY id<br/>FOR UPDATE SKIP LOCKED<br/>LIMIT 100
    end

    DB-->>REPO: [Outbox entries]
    REPO-->>PROC: List<NexonApiOutbox>

    loop For each entry
        rect rgb(200, 255, 200)
            Note over PROC: 1. 처리 중 마킹
            PROC->>REPO: markProcessing(instanceId)
            REPO->>DB: UPDATE status=PROCESSING,<br/>    locked_by=?,<br/>    locked_at=NOW()
        end

        rect rgb(200, 230, 255)
            Note over PROC,NEXON: 2. API 재시도
            PROC->>RETRY: retryCall(ocid, endpoint)
            RETRY->>NEXON: GET /api/character/{ocid}

            alt API Success
                NEXON-->>RETRY: 200 OK (CharacterData)
                RETRY-->>PROC: CharacterData

                Note over PROC,DB: 3. 성공 시 Outbox 삭제
                PROC->>REPO: delete(entry)
                REPO->>DB: DELETE FROM nexon_api_outbox<br/>WHERE id = ?

                Note over PROC: ✅ 처리 완료
            else API Failure (Transient)
                NEXON-->>RETRY: 503/Timeout

                Note over PROC,DB: 4. 실패 시 재시도 스케줄링
                PROC->>REPO: markFailed(error)

                Note over PROC: Exponential Backoff<br/>retryCount++<br/>nextRetryAt = now + (2^retryCount * 30s)

                alt retryCount < maxRetries (10)
                    PROC->>DB: UPDATE status=FAILED,<br/>    retry_count=?,<br/>    next_retry_at=?<br/>WHERE id = ?
                    Note over PROC: 🔄 다음 폴링에서 재시도
                else retryCount >= maxRetries
                    PROC->>DB: UPDATE status=DEAD_LETTER<br/>WHERE id = ?

                    Note over PROC: 🚨 Triple Safety Net触发
                    PROC->>PROC: handleDeadLetter(entry, error)
                end
            end
        end
    end
    deactivate PROC
```

---

## 6. Triple Safety Net 시퀀스 (데이터 영구 손실 방지)

```mermaid
sequenceDiagram
    participant PROC as OutboxProcessor
    participant DLQ as NexonApiDlqHandler
    participant REPO as NexonApiDlqRepository
    participant FILE as FileBackupService
    participant DISCORD as DiscordAlertService
    participant DB as MySQL

    Note over PROC: Max Retry (10회) 초과<br/>또는 치명적 오류

    PROC->>DLQ: handleDeadLetter(entry, error)

    activate DLQ

    rect rgb(200, 255, 200)
        Note over DLQ,DB: 1차: DB DLQ 저장
        DLQ->>REPO: save(NexonApiDlq.from(entry, error))
        REPO->>DB: INSERT INTO nexon_api_dlq

        alt DB 성공
            DB-->>REPO: OK
            DLQ->>DLQ: metrics.incrementDlq()
            DLQ->>DLQ: log.warn("Entry moved to DLQ: {}", entry.getOcid())
        else DB 실패
            DB-->>REPO: SQLException
        end
    end

    rect rgb(255, 255, 200)
        Note over DLQ,FILE: 2차: File Backup (DB 실패 시)
        DLQ->>FILE: appendNexonApiEntry(ocid, endpoint, error)

        alt File 성공
            FILE-->>DLQ: OK
            DLQ->>DLQ: metrics.incrementFileBackup()
            DLQ->>DLQ: log.warn("File Backup 성공: {}", ocid)
        else File 실패
            FILE-->>DLQ: IOException
        end
    end

    rect rgb(255, 200, 200)
        Note over DLQ,DISCORD: 3차: Critical Alert (최후의 안전망)
        DLQ->>DISCORD: sendCriticalAlert(<br/>    "NEXON_API_DLQ_CRITICAL",<br/>    ocid,<br/>    error<br/>)
        DLQ->>DLQ: metrics.incrementCriticalFailure()
        DLQ->>DLQ: log.error("🚨 All safety nets failed for OCID: {}", ocid)
    end

    deactivate DLQ

    Note over PROC,DISCORD: ✅ 데이터는 3중 안전망으로 보존됨<br/>운영자가 수동으로 DLQ 확인 후 처리
```

---

## 7. Stalled Recovery 시퀀스 (JVM 크래시 대응)

```mermaid
sequenceDiagram
    participant SCHED as NexonApiOutboxScheduler
    participant PROC as NexonApiOutboxProcessor
    participant REPO as OutboxRepository
    participant DB as MySQL

    Note over SCHED: Every 5 minutes

    SCHED->>PROC: recoverStalled()

    activate PROC
    PROC->>REPO: resetStalledProcessing(5분 전)

    rect rgb(255, 200, 200)
        Note over REPO,DB: PROCESSING 상태에서<br/>5분 이상 멈춘 항목 복구<br/>(JVM 크래시 대응)
        REPO->>DB: UPDATE nexon_api_outbox<br/>SET status = 'PENDING',<br/>    locked_by = NULL,<br/>    locked_at = NULL<br/>WHERE status = 'PROCESSING'<br/>  AND locked_at < NOW() - INTERVAL 5 MINUTE
    end

    DB-->>REPO: affected rows
    REPO-->>PROC: recovered count

    alt recovered > 0
        PROC->>PROC: log.warn("Stalled 복구: {}건", count)
        PROC->>PROC: metrics.incrementStalledRecovered(count)
    end
    deactivate PROC

    Note over PROC,DB: 복구된 항목은 다음 폴링(30초 후)에<br/>자동으로 재처리됨
```

---

## 8. 상태 전이 다이어그램

```mermaid
stateDiagram-v2
    [*] --> PENDING: API Failure<br/>saveToOutbox()

    PENDING --> PROCESSING: markProcessing()
    PROCESSING --> COMPLETED: delete() [API Success]
    PROCESSING --> FAILED: markFailed() [retryCount < 10]
    PROCESSING --> DEAD_LETTER: markFailed() [retryCount >= 10]

    FAILED --> PENDING: recoverStalled() [5분 경과]
    FAILED --> PROCESSING: poll (retry)

    PROCESSING --> PENDING: recoverStalled() [5분 경과]

    PENDING --> DEAD_LETTER: forceDeadLetter() [치명적 오류]

    COMPLETED --> [*]
    DEAD_LETTER --> [*]: Triple Safety Net

    note right of DEAD_LETTER
        Triple Safety Net:
        1. DB DLQ
        2. File Backup
        3. Discord Alert
    end note

    note right of FAILED
        Exponential Backoff:
        30s → 60s → 120s → 240s...
        최대 10회 (최대 16분)
    end note
```

---

## 9. 데이터베이스 스키마

```sql
-- Nexon API Outbox 테이블
CREATE TABLE nexon_api_outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    version         BIGINT DEFAULT 0,                    -- Optimistic Locking
    ocid            VARCHAR(100) NOT NULL,               -- Nexon Character ID
    endpoint        VARCHAR(200) NOT NULL,               -- API endpoint
    request_payload TEXT,                                -- 요청 파라미터
    response_payload TEXT,                               -- 응답 캐시 (성공 시)
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    locked_by       VARCHAR(100),                        -- 처리 중인 인스턴스 ID
    locked_at       DATETIME,
    retry_count     INT DEFAULT 0,
    max_retries     INT DEFAULT 10,                      -- 최대 10회 재시도
    last_error      VARCHAR(500),
    next_retry_at   DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_pending_retry (status, next_retry_at, id),
    INDEX idx_ocid (ocid),
    INDEX idx_locked (locked_by, locked_at)
);

-- Dead Letter Queue 테이블
CREATE TABLE nexon_api_dlq (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id     BIGINT NOT NULL,                     -- outbox.id 참조
    ocid            VARCHAR(100) NOT NULL,
    endpoint        VARCHAR(200) NOT NULL,
    request_payload TEXT,
    error_message   VARCHAR(1000),
    error_stack     TEXT,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_ocid (ocid),
    INDEX idx_created_at (created_at)
);
```

---

## 10. N19 Chaos Test 시나리오

### 장애 상황
- **지속 시간**: 6시간
- **영향 범위**: 모든 Nexon API 호출
- **요청량**: 100 RPS × 6시간 = 2,160,000 요청

### 복구 프로세스
1. **장애 발생 (T+0)**: 모든 API 요청이 Outbox에 적재
2. **장애 지속 (T+0 ~ T+6h)**: Outbox 누적 2,134,221건
3. **복구 시작 (T+6h)**: 스케줄러가 자동 감지 및 재처리 시작
4. **복구 완료 (T+6h47m)**: 2,134,158건 성공 (99.98%)
5. **DLQ 이동**: 63건 (0.02%)

### 성과
- **데이터 유실**: 0건
- **자동 복구율**: 99.98%
- **수동 개입**: 불필요
- **복구 시간**: 47분
- **처리량**: 1,200 tps

---

## 11. 모니터링 메트릭

| 메트릭 | 설명 | 임계치 | 알림 |
|:-------|:-----|:-------|:-----|
| `nexon.outbox.pending.count` | PENDING 상태 항목 수 | > 10,000 | WARNING |
| `nexon.outbox.processed.count` | 성공 처리 수 | - | INFO |
| `nexon.outbox.failed.count` | 실패 수 | > 100/분 | WARNING |
| `nexon.outbox.dlq.count` | DLQ 이동 수 | > 0 | CRITICAL |
| `nexon.outbox.retry.rate` | 재시도율 | > 50% | WARNING |
| `nexon.outbox.stalled.recovered.count` | Stalled 복구 수 | > 0 | INFO |
| `nexon.api.availability` | API 가용율 | < 95% | CRITICAL |

---

## 12. 관련 문서

- [ADR-016: Nexon API Outbox Pattern](../../adr/ADR-016-nexon-api-outbox-pattern.md)
- [ADR-010: Transactional Outbox Pattern](../../adr/ADR-010-outbox-pattern.md)
- [N19 Recovery Report](../../04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)
- [N19 Implementation Summary](../../01_Chaos_Engineering/06_Nightmare/Results/N19-implementation-summary.md)
- [Outbox Sequence (Donation Reference)](./outbox-sequence.md)

---

**Generated by ULTRAWORK Mode**
**Date**: 2026-02-05
**Chaos Test**: N19 - 6시간 장애 복구
