# ADR-010: Transactional Outbox Pattern 설계

## 상태
Accepted

## 맥락 (Context)

분산 환경에서 이벤트 전달 보장 시 다음 문제가 발생했습니다:

**관찰된 문제:**
- Dual-Write 문제: DB 저장 성공 + 알림 전송 실패 → 불일치
- JVM 크래시 시 PROCESSING 상태로 영구 고착 (Zombie)
- 분산 환경에서 중복 처리 발생

**README 정의:**
> Transactional Outbox: DB와 이벤트 발행의 원자성 보장

**Issue Reference:**
- #80: Transactional Outbox 구현
- #229: Stalled 상태 복구 시 무결성 검증 강화

## 검토한 대안 (Options Considered)

### 옵션 A: 이벤트 직접 발행
```java
@Transactional
public void donate() {
    repository.save(donation);
    eventPublisher.publish(new DonationEvent(...));  // 실패 시?
}
```
- 장점: 구현 간단
- 단점: DB 커밋 후 이벤트 발행 실패 시 불일치
- **결론: 원자성 미보장**

### 옵션 B: Change Data Capture (CDC)
```java
// Debezium 등 CDC 도구 사용
// DB 변경 로그 → Kafka → 이벤트 발행
```
- 장점: 완전한 원자성
- 단점: 인프라 복잡도 증가, 운영 부담
- **결론: 오버 엔지니어링**

### 옵션 C: Transactional Outbox + SKIP LOCKED
- 장점: 동일 트랜잭션 내 저장, DB 레벨 중복 처리 방지
- 단점: 폴링 지연 (10초)
- **결론: 채택**

## 결정 (Decision)

**Transactional Outbox + SKIP LOCKED + Content Hash + Triple Safety Net을 적용합니다.**

### 1. DonationOutbox 엔티티 (Financial-Grade)
```java
// maple.expectation.domain.v2.DonationOutbox
@Entity
@Table(name = "donation_outbox",
       indexes = {
           @Index(name = "idx_pending_poll", columnList = "status, next_retry_at, id"),
           @Index(name = "idx_locked", columnList = "locked_by, locked_at")
       })
public class DonationOutbox {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;  // Optimistic Locking

    @Column(nullable = false, unique = true)
    private String requestId;

    @Column(nullable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    /**
     * Content Hash (분산 환경 안전)
     * SHA-256(requestId | eventType | payload)
     */
    @Column(nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    private OutboxStatus status = OutboxStatus.PENDING;

    private String lockedBy;
    private LocalDateTime lockedAt;
    private int retryCount = 0;
    private int maxRetries = 3;
    private LocalDateTime nextRetryAt;

    public enum OutboxStatus {
        PENDING,     // 처리 대기
        PROCESSING,  // 처리 중 (락 보유)
        COMPLETED,   // 완료
        FAILED,      // 실패 (재시도 예정)
        DEAD_LETTER  // 최대 재시도 초과 → DLQ
    }

    /**
     * Exponential Backoff 재시도 간격
     * 1차: 30초, 2차: 60초, 3차: 120초
     */
    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = truncate(error, 500);
        this.status = shouldMoveToDlq() ? OutboxStatus.DEAD_LETTER : OutboxStatus.FAILED;
        this.nextRetryAt = LocalDateTime.now()
                .plusSeconds((long) Math.pow(2, retryCount) * 30);
        clearLock();
    }
}
```

### 2. SKIP LOCKED 쿼리 (분산 중복 처리 방지)
```java
// maple.expectation.repository.v2.DonationOutboxRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP LOCKED
@Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses " +
       "AND o.nextRetryAt <= :now ORDER BY o.id")
List<DonationOutbox> findPendingWithLock(
        @Param("statuses") List<OutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        Pageable pageable);
```

**SKIP LOCKED 동작:**
```
[Instance A] SELECT ... FOR UPDATE SKIP LOCKED → Row 1, 2, 3 획득
[Instance B] SELECT ... FOR UPDATE SKIP LOCKED → Row 4, 5, 6 획득 (이미 잠긴 1,2,3 스킵)
```

### 3. OutboxProcessor (폴링 및 처리)
```java
// maple.expectation.service.v2.donation.outbox.OutboxProcessor
@ObservedTransaction("scheduler.outbox.poll")
@Transactional(isolation = Isolation.READ_COMMITTED)
public void pollAndProcess() {
    List<DonationOutbox> pending = outboxRepository.findPendingWithLock(
            List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
            LocalDateTime.now(),
            PageRequest.of(0, BATCH_SIZE)
    );

    for (DonationOutbox entry : pending) {
        processEntry(entry);
    }
}

private boolean processEntry(DonationOutbox entry) {
    // 1. 무결성 검증 (Content Hash)
    if (!entry.verifyIntegrity()) {
        handleIntegrityFailure(entry);  // 즉시 DLQ
        return false;
    }

    // 2. 처리 시작 마킹
    entry.markProcessing(instanceId);
    outboxRepository.save(entry);

    // 3. 알림 전송 (Best-effort)
    sendNotification(entry);

    // 4. 처리 완료 마킹
    entry.markCompleted();
    outboxRepository.save(entry);
    return true;
}
```

### 4. Stalled 상태 복구 (#229)
```java
/**
 * JVM 크래시 대응: PROCESSING 상태로 5분 이상 고착된 항목 복구
 *
 * Purple Agent 요구사항: 복구 전 Content Hash 기반 무결성 검증
 */
@ObservedTransaction("scheduler.outbox.recover_stalled")
@Transactional
public void recoverStalled() {
    LocalDateTime staleTime = LocalDateTime.now().minus(STALE_THRESHOLD);  // 5분
    List<DonationOutbox> stalledEntries = outboxRepository.findStalledProcessing(staleTime);

    for (DonationOutbox entry : stalledEntries) {
        // 무결성 검증 추가 (#229)
        if (!entry.verifyIntegrity()) {
            handleIntegrityFailure(entry);  // 즉시 DLQ
            continue;
        }

        // 무결성 통과 → 상태 복원
        entry.resetToRetry();
        outboxRepository.save(entry);
    }
}
```

### 5. Triple Safety Net (DLQ 처리)
```java
// maple.expectation.service.v2.donation.outbox.DlqHandler
/**
 * P0 - 데이터 영구 손실 방지
 * 1차: DB DLQ INSERT
 * 2차: File Backup (DLQ 실패 시)
 * 3차: Discord Critical Alert + Metric
 */
public void handleDeadLetter(DonationOutbox entry, String reason) {
    // 1차 시도: DB DLQ
    executor.executeOrCatch(
        () -> {
            DonationDlq dlq = DonationDlq.from(entry, reason);
            dlqRepository.save(dlq);
            metrics.incrementDlq();
            return null;
        },
        dbEx -> handleDbDlqFailure(entry, reason, context),  // 2차 시도
        context
    );
}

private Void handleDbDlqFailure(...) {
    // 2차 시도: File Backup
    executor.executeOrCatch(
        () -> {
            fileBackupService.appendOutboxEntry(entry.getRequestId(), entry.getPayload());
            return null;
        },
        fileEx -> handleCriticalFailure(entry, reason, fileEx),  // 3차 시도
        context
    );
    return null;
}

private Void handleCriticalFailure(...) {
    // 3차: Discord Alert + 메트릭 기록
    discordAlertService.sendCriticalAlert("OUTBOX CRITICAL FAILURE", ...);
    metrics.incrementCriticalFailure();
    return null;
}
```

### 6. 스케줄링 주기
```java
// maple.expectation.scheduler.OutboxScheduler
@Scheduled(fixedRate = 10000)  // 10초마다 폴링
public void pollAndProcess() {
    executor.executeVoid(outboxProcessor::pollAndProcess, context);
}

@Scheduled(fixedRate = 300000)  // 5분마다 Stalled 복구
public void recoverStalled() {
    executor.executeVoid(outboxProcessor::recoverStalled, context);
}
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| 이벤트 전달 보장 | 불확실 | **At-least-once** |
| Dual-Write 문제 | 발생 | **방지** |
| Zombie 상태 | 영구 고착 | **5분 내 복구** |
| 분산 중복 처리 | 발생 | **SKIP LOCKED 방지** |
| 데이터 영구 손실 | 가능 | **Triple Safety Net** |

## 참고 자료
- `maple.expectation.domain.v2.DonationOutbox`
- `maple.expectation.service.v2.donation.outbox.OutboxProcessor`
- `maple.expectation.service.v2.donation.outbox.DlqHandler`
- `maple.expectation.repository.v2.DonationOutboxRepository`
- `maple.expectation.scheduler.OutboxScheduler`
- `docs/03_Sequence_Diagrams/outbox-sequence.md`
