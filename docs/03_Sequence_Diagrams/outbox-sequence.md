# Transactional Outbox Pattern 시퀀스 다이어그램

> **Issue #80, #81, #127**: 도네이션 처리의 데이터 일관성 및 멱등성 보장
>
> **Last Updated:** 2026-02-05
> **Code Version:** MapleExpectation v1.x
> **Diagram Version:** 1.0

## 1. 개요

Transactional Outbox 패턴은 **분산 시스템에서 데이터 일관성을 보장**하기 위한 패턴입니다.
비즈니스 트랜잭션과 이벤트 발행을 **동일 DB 트랜잭션**에서 원자적으로 처리합니다.

## Terminology

| 용어 | 정의 |
|------|------|
| **At-Least-Once** | 최소 1회 전달 보장 (중복 가능) |
| **멱등성 (Idempotency)** | requestId 기반 중복 처리 방지 |
| **Content Hash** | SHA-256 기반 개별 레코드 무결성 검증 |
| **Exponential Backoff** | 재시도 간격 기하급수적 증가 |
| **Triple Safety Net** | DLQ → File Backup → Discord Alert |

### 핵심 특성

| 특성 | 설명 |
|------|------|
| **At-Least-Once Delivery** | 최소 1회 전달 보장 (중복 가능) |
| **멱등성 (Idempotency)** | requestId 기반 중복 처리 방지 |
| **Content Hash** | 개별 레코드 무결성 검증 (SHA-256) |
| **Exponential Backoff** | 재시도 간격 기하급수적 증가 (30s, 60s, 120s...) |
| **Triple Safety Net** | DLQ → File Backup → Discord Alert |

---

## 2. 아키텍처 개요

```mermaid
graph TB
    subgraph "Write Path (Same Transaction)"
        CLIENT[Client Request] --> SERVICE[DonationService]
        SERVICE --> HISTORY[(donation_history)]
        SERVICE --> OUTBOX[(donation_outbox)]
        HISTORY -.->|ACID| OUTBOX
    end

    subgraph "Read Path (Polling)"
        SCHEDULER[OutboxScheduler<br/>10s interval] --> PROCESSOR[OutboxProcessor]
        PROCESSOR -->|SKIP LOCKED| OUTBOX
        PROCESSOR --> NOTIFY[Notification<br/>Discord/Log]
    end

    subgraph "Triple Safety Net"
        DLQ[(donation_dlq)]
        FILE[File Backup]
        DISCORD[Discord Alert]

        PROCESSOR -->|Max Retry| DLQ
        DLQ -.->|DB Fail| FILE
        FILE -.->|File Fail| DISCORD
    end

    style OUTBOX fill:#ff9,stroke:#333
    style DLQ fill:#f99,stroke:#333
```

---

## 3. Write Path 시퀀스 (도네이션 요청)

```mermaid
sequenceDiagram
    participant C as Client
    participant S as DonationService
    participant P as DonationProcessor
    participant H as HistoryRepository
    participant O as OutboxRepository
    participant DB as MySQL

    C->>S: sendCoffee(guestUuid, adminFp, amount, requestId)

    activate S
    Note over S: @Transactional 시작
    Note over S: @Locked(guestUuid) 분산 락

    S->>S: validateAdmin(adminFingerprint)

    rect rgb(200, 255, 200)
        Note over S,DB: 멱등성 체크 (requestId)
        S->>H: existsByRequestId(requestId)
        H->>DB: SELECT EXISTS
        DB-->>H: false
        H-->>S: false (신규 요청)
    end

    rect rgb(200, 230, 255)
        Note over S,DB: 비즈니스 로직 실행
        S->>P: executeTransferToAdmin()
        P->>DB: UPDATE member SET point = point - amount WHERE uuid = ?
        P->>DB: UPDATE member SET point = point + amount WHERE fingerprint = ?
    end

    rect rgb(255, 255, 200)
        Note over S,DB: 동일 트랜잭션에서 Outbox 저장
        S->>H: save(DonationHistory)
        H->>DB: INSERT INTO donation_history

        S->>O: save(DonationOutbox)
        Note over O: Content Hash 자동 생성<br/>SHA-256(requestId|eventType|payload)
        O->>DB: INSERT INTO donation_outbox<br/>(status=PENDING)
    end

    Note over S: @Transactional COMMIT
    deactivate S

    S-->>C: 200 OK
```

---

## 4. Read Path 시퀀스 (Outbox Polling)

```mermaid
sequenceDiagram
    participant SCH as OutboxScheduler
    participant PROC as OutboxProcessor
    participant REPO as OutboxRepository
    participant DB as MySQL

    loop Every 10 seconds
        SCH->>PROC: pollAndProcess()

        activate PROC
        PROC->>REPO: findPendingWithLock(PENDING/FAILED, now, LIMIT 100)

        rect rgb(255, 230, 200)
            Note over REPO,DB: SKIP LOCKED 쿼리<br/>(분산 환경 중복 처리 방지)
            REPO->>DB: SELECT * FROM donation_outbox<br/>WHERE status IN ('PENDING','FAILED')<br/>AND next_retry_at <= NOW()<br/>ORDER BY id<br/>FOR UPDATE SKIP LOCKED<br/>LIMIT 100
        end

        DB-->>REPO: [Outbox entries]
        REPO-->>PROC: List<DonationOutbox>

        loop For each entry
            PROC->>PROC: verifyIntegrity()
            Note over PROC: Content Hash 검증<br/>실패 시 즉시 DLQ

            alt Integrity OK
                PROC->>REPO: markProcessing(instanceId)
                REPO->>DB: UPDATE status=PROCESSING, locked_by=?

                PROC->>PROC: sendNotification(entry)
                Note over PROC: Best-effort 알림

                PROC->>REPO: markCompleted()
                REPO->>DB: UPDATE status=COMPLETED
            else Integrity FAIL
                PROC->>PROC: handleIntegrityFailure()
                Note over PROC: 즉시 DEAD_LETTER 이동
            end
        end
        deactivate PROC
    end
```

---

## 5. Stalled Recovery 시퀀스 (JVM 크래시 대응)

```mermaid
sequenceDiagram
    participant SCH as OutboxScheduler
    participant PROC as OutboxProcessor
    participant REPO as OutboxRepository
    participant DB as MySQL

    Note over SCH: Every 5 minutes

    SCH->>PROC: recoverStalled()

    activate PROC
    PROC->>REPO: resetStalledProcessing(5분 전)

    rect rgb(255, 200, 200)
        Note over REPO,DB: PROCESSING 상태에서<br/>5분 이상 멈춘 항목 복구
        REPO->>DB: UPDATE donation_outbox<br/>SET status = 'PENDING',<br/>    locked_by = NULL,<br/>    locked_at = NULL<br/>WHERE status = 'PROCESSING'<br/>  AND locked_at < NOW() - INTERVAL 5 MINUTE
    end

    DB-->>REPO: affected rows
    REPO-->>PROC: recovered count

    alt recovered > 0
        PROC->>PROC: log.warn("Stalled 복구: N건")
        PROC->>PROC: metrics.incrementStalledRecovered(N)
    end
    deactivate PROC
```

---

## 6. Triple Safety Net 시퀀스 (데이터 영구 손실 방지)

```mermaid
sequenceDiagram
    participant PROC as OutboxProcessor
    participant DLQ as DlqHandler
    participant REPO as DonationDlqRepository
    participant FILE as FileBackupService
    participant DISCORD as DiscordAlertService
    participant DB as MySQL

    Note over PROC: Max Retry 초과<br/>또는 무결성 검증 실패

    PROC->>DLQ: handleDeadLetter(entry, reason)

    activate DLQ

    rect rgb(200, 255, 200)
        Note over DLQ,DB: 1차: DB DLQ 저장
        DLQ->>REPO: save(DonationDlq.from(entry))
        REPO->>DB: INSERT INTO donation_dlq

        alt DB 성공
            DB-->>REPO: OK
            DLQ->>DLQ: metrics.incrementDlq()
            DLQ->>DLQ: log.warn("Entry moved to DLQ")
        else DB 실패
            DB-->>REPO: SQLException
        end
    end

    rect rgb(255, 255, 200)
        Note over DLQ,FILE: 2차: File Backup (DB 실패 시)
        DLQ->>FILE: appendOutboxEntry(requestId, payload)

        alt File 성공
            FILE-->>DLQ: OK
            DLQ->>DLQ: metrics.incrementFileBackup()
            DLQ->>DLQ: log.warn("File Backup 성공")
        else File 실패
            FILE-->>DLQ: IOException
        end
    end

    rect rgb(255, 200, 200)
        Note over DLQ,DISCORD: 3차: Critical Alert (최후의 안전망)
        DLQ->>DISCORD: sendCriticalAlert(title, desc, exception)
        DLQ->>DLQ: metrics.incrementCriticalFailure()
        DLQ->>DLQ: log.error("🚨 All safety nets failed!")
    end

    deactivate DLQ
```

---

## 7. 상태 전이 다이어그램

```mermaid
stateDiagram-v2
    [*] --> PENDING: create()

    PENDING --> PROCESSING: markProcessing()
    PROCESSING --> COMPLETED: markCompleted()
    PROCESSING --> FAILED: markFailed() [retryCount < maxRetries]
    PROCESSING --> DEAD_LETTER: markFailed() [retryCount >= maxRetries]

    FAILED --> PENDING: recoverStalled() [5분 경과]
    FAILED --> PROCESSING: poll (retry)

    PROCESSING --> PENDING: recoverStalled() [5분 경과]

    PENDING --> DEAD_LETTER: forceDeadLetter() [무결성 실패]

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
    end note
```

---

## 8. 데이터베이스 스키마

```sql
-- Outbox 테이블
CREATE TABLE donation_outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    version         BIGINT DEFAULT 0,                    -- Optimistic Locking
    request_id      VARCHAR(50) NOT NULL UNIQUE,         -- 멱등성 키
    event_type      VARCHAR(50) NOT NULL,                -- DONATION_COMPLETED
    payload         TEXT NOT NULL,                       -- JSON payload
    content_hash    VARCHAR(64) NOT NULL,                -- SHA-256 무결성
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    locked_by       VARCHAR(100),                        -- 처리 중인 인스턴스 ID
    locked_at       DATETIME,
    retry_count     INT DEFAULT 0,
    max_retries     INT DEFAULT 3,
    last_error      VARCHAR(500),
    next_retry_at   DATETIME,
    created_at      DATETIME,
    updated_at      DATETIME,

    INDEX idx_pending_poll (status, next_retry_at, id),
    INDEX idx_locked (locked_by, locked_at)
);

-- Dead Letter Queue 테이블
CREATE TABLE donation_dlq (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id     BIGINT NOT NULL,                     -- outbox.id 참조
    request_id      VARCHAR(50) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    payload         TEXT NOT NULL,
    reason          VARCHAR(500),
    created_at      DATETIME,

    INDEX idx_request_id (request_id)
);
```

---

## 9. 관련 이슈/PR

| Issue | 제목 | 핵심 결정 |
|:------|:-----|:---------|
| #80 | Transactional Outbox 패턴 도입 | At-Least-Once + 멱등성 |
| #81 | DLQ Handler Triple Safety Net | DB → File → Discord |
| #127 | 멱등성 키 기반 중복 처리 방지 | requestId unique 제약 |
| #187 | Outbox 패턴 및 멱등성 구현 PR | 통합 구현 |

---

## 10. 모니터링 메트릭

| 메트릭 | 설명 | 임계치 |
|:-------|:-----|:-------|
| `outbox.pending.count` | PENDING 상태 항목 수 | > 1000 |
| `outbox.processed.count` | 성공 처리 수 | - |
| `outbox.failed.count` | 실패 수 | > 10/분 |
| `outbox.dlq.count` | DLQ 이동 수 | > 0 |
| `outbox.integrity.failure.count` | 무결성 검증 실패 | > 0 (즉시 알림) |
| `outbox.stalled.recovered.count` | Stalled 복구 수 | > 0 |

---

## 11. 참고 문서

- [Microservices Patterns - Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- [CLAUDE.md 섹션 8-1: Redis Lua Script & Cluster Hash Tag](../../CLAUDE.md)

## Evidence Links
- **DonationOutbox:** `src/main/java/maple/expectation/domain/v2/DonationOutbox.java`
- **OutboxProcessor:** `src/main/java/maple/expectation/service/v2/donation/outbox/OutboxProcessor.java`
- **DlqHandler:** `src/main/java/maple/expectation/service/v2/donation/outbox/DlqHandler.java`
- **Tests:** `src/test/java/maple/expectation/service/v2/donation/outbox/*Test.java`

## Fail If Wrong

이 다이어그램이 부정확한 경우:
- **Outbox 저장 실패 시 데이터 유실**: 트랜잭션 경계 확인
- **중복 처리 발생**: requestId unique 제약 확인
- **SKIP LOCKED 미작동**: 쿼리 구현 확인

### Verification Commands
```bash
# Outbox 스키마 확인
grep -A 30 "CREATE TABLE donation_outbox" src/main/resources/db/migration/*.sql

# SKIP LOCKED 쿼리 확인
grep -B 5 -A 15 "SKIP LOCKED\|skipLocked" src/main/java/maple/expectation/repository/v2/DonationOutboxRepository.java

# requestId unique 확인
grep -i "requestid.*unique" src/main/resources/db/migration/*.sql
```
