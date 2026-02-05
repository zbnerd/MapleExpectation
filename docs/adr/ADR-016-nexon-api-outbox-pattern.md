# ADR-016: Nexon API Outbox Pattern 적용

## 상태
Accepted

## 맥락 (Context)

**외부 API 장애 시 데이터 유실 방지를 위해 Nexon API Outbox 패턴을 도입해야 합니다.**

**관찰된 문제:**
- 외부 API 6시간 장애 발생 시 210만 요청 처리 불가
- 넥슨 API 호출 실패 시 사용자 데이터 요청이 영구 손실
- 재시도 로직 부재로 장애 복구 후에도 자동 재처리 불가
- 수동 복구 스크립트 실행 필요 (운영 부담)

**README 정의:**
> Nexon API Outbox: 넥슨 API 호출 실패 시 Outbox 적재 후 스케줄러가 자동 재처리

**Chaos Test Evidence:**
- N19: 6시간 장애 시뮬레이션 → 2,134,221 이벤트 유실 0, 복구 후 99.98% 자동 재처리
- 보고서: [RECOVERY_REPORT_N19_OUTBOX_REPLAY.md](../04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)

**Issue Reference:**
- #303: 스케줄러 분산 락 P1-7/8/9

## 검토한 대안 (Options Considered)

### 옵션 A: 즉시 실패 (Fail-Fast)
```java
public CharacterData fetchCharacter(String ocid) {
    try {
        return nexonApiClient.fetch(ocid);
    } catch (Exception e) {
        throw new ServerException("API 호출 실패", e);  // 사용자 에러 응답
    }
}
```
- 장점: 구현 간단
- 단점: 장애 시 모든 요청 실패, 복구 불가
- **결론: 사용자 경험 악화**

### 옵션 B: 동기 재시도 (Sync Retry)
```java
@Retryable(maxAttempts = 3)
public CharacterData fetchCharacter(String ocid) {
    return nexonApiClient.fetch(ocid);
}
```
- 장점: 일시적 장애에 대응
- 단점: 6시간 장애 시 모든 요청 타임아웃, Thread Pool 고갈
- **결론: 장기 장애 대응 불가**

### 옵션 C: Outbox + 비동기 재처리 (Async Replay)
```java
// 1. API 실패 시 Outbox 적재
// 2. 스케줄러가 주기적으로 재처리
// 3. 성공 시 Outbox 삭제, 실패 시 재시도 카운트 증가
```
- 장점: 장애 기간 모든 데이터 보존, 복구 후 자동 재처리
- 단점: 구현 복잡도 증가
- **결론: 채택 (운영 효율성 확보)**

## 결정 (Decision)

**NexonApiOutbox + SKIP LOCKED + File Backup + Discord Alert를 적용합니다.**

### 1. NexonApiOutbox 엔티티
```java
// maple.expectation.domain.v2.NexonApiOutbox
@Entity
@Table(name = "nexon_api_outbox",
       indexes = {
           @Index(name = "idx_pending_retry", columnList = "status, next_retry_at, id"),
           @Index(name = "idx_ocid", columnList = "ocid")
       })
public class NexonApiOutbox {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;  // Optimistic Locking

    @Column(nullable = false, length = 100)
    private String ocid;

    @Column(nullable = false)
    private String endpoint;

    @Column(columnDefinition = "TEXT")
    private String requestPayload;

    @Column(columnDefinition = "TEXT")
    private String responsePayload;  // 성공 시 응답 캐싱

    @Enumerated(EnumType.STRING)
    private OutboxStatus status = OutboxStatus.PENDING;

    private String lockedBy;
    private LocalDateTime lockedAt;
    private int retryCount = 0;
    private int maxRetries = 10;  // 10회 재시도 (최대 16분)
    private LocalDateTime nextRetryAt;
    private String lastError;

    public enum OutboxStatus {
        PENDING,      // 처리 대기
        PROCESSING,   // 처리 중
        COMPLETED,    // 완료
        FAILED,       // 실패 (재시도 예정)
        DEAD_LETTER   // 최대 재시도 초과
    }

    /**
     * Exponential Backoff 재시도 간격
     * 1차: 30초, 2차: 60초, 3차: 120초, ..., 10차: 16분
     */
    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = truncate(error, 500);
        this.status = shouldMoveToDlq() ? OutboxStatus.DEAD_LETTER : OutboxStatus.FAILED;
        this.nextRetryAt = LocalDateTime.now()
                .plusSeconds((long) Math.pow(2, retryCount) * 30);
        this.lockedBy = null;
        this.lockedAt = null;
    }

    private boolean shouldMoveToDlq() {
        return retryCount >= maxRetries;
    }
}
```

### 2. SKIP LOCKED 쿼리 (분산 환경 안전)
```java
// maple.expectation.repository.v2.NexonApiOutboxRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("SELECT o FROM NexonApiOutbox o WHERE o.status IN :statuses " +
       "AND o.nextRetryAt <= :now ORDER BY o.id")
List<NexonApiOutbox> findPendingWithLock(
        @Param("statuses") List<OutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        Pageable pageable);
```

**SKIP LOCKED 동작:**
```
[Instance A] SELECT ... FOR UPDATE SKIP LOCKED → Row 1-100 획득
[Instance B] SELECT ... FOR UPDATE SKIP LOCKED → Row 101-200 획득
```

### 3. ResilientNexonApiClient (Outbox 적재 포인트)
```java
// maple.expectation.external.impl.ResilientNexonApiClient
@Component
@RequiredArgsConstructor
public class ResilientNexonApiClient {

    private final NexonApiOutboxRepository outboxRepository;
    private final LogicExecutor executor;

    public <T> T executeWithOutbox(String ocid, String endpoint,
                                   Supplier<T> apiCall, Class<T> responseType) {
        return executor.executeOrDefault(
            () -> {
                try {
                    // 1. API 호출 시도
                    return apiCall.get();
                } catch (Exception e) {
                    // 2. 실패 시 Outbox 적재
                    saveToOutbox(ocid, endpoint, e);
                    throw e;  // 호출자에게 예외 전파
                }
            },
            null,
            TaskContext.of("NexonApi", "ExecuteWithOutbox", ocid)
        );
    }

    private void saveToOutbox(String ocid, String endpoint, Exception error) {
        executor.executeVoid(
            () -> {
                NexonApiOutbox outbox = NexonApiOutbox.builder()
                        .ocid(ocid)
                        .endpoint(endpoint)
                        .status(OutboxStatus.PENDING)
                        .nextRetryAt(LocalDateTime.now().plusSeconds(30))
                        .lastError(error.getMessage())
                        .build();
                outboxRepository.save(outbox);
            },
            TaskContext.of("NexonApi", "SaveToOutbox", ocid)
        );
    }
}
```

### 4. NexonApiOutboxProcessor (재처리 로직)
```java
// maple.expectation.service.v2.outbox.NexonApiOutboxProcessor
@Component
@RequiredArgsConstructor
public class NexonApiOutboxProcessor {

    private final NexonApiOutboxRepository outboxRepository;
    private final NexonApiRetryClient retryClient;
    private final LogicExecutor executor;

    private static final int BATCH_SIZE = 100;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void pollAndProcess() {
        List<NexonApiOutbox> pending = outboxRepository.findPendingWithLock(
                List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
                LocalDateTime.now(),
                PageRequest.of(0, BATCH_SIZE)
        );

        for (NexonApiOutbox entry : pending) {
            processEntry(entry);
        }
    }

    private void processEntry(NexonApiOutbox entry) {
        executor.executeVoid(
            () -> {
                // 1. 처리 중 마킹
                entry.markProcessing(instanceId);
                outboxRepository.save(entry);

                // 2. API 재시도
                try {
                    String response = retryClient.retryCall(entry.getOcid(), entry.getEndpoint());
                    entry.markCompleted(response);
                } catch (Exception e) {
                    entry.markFailed(e.getMessage());

                    // DLQ 이동 시 Triple Safety Net
                    if (entry.getStatus() == OutboxStatus.DEAD_LETTER) {
                        handleDeadLetter(entry, e);
                    }
                }

                outboxRepository.save(entry);
            },
            TaskContext.of("OutboxProcessor", "ProcessEntry", entry.getId())
        );
    }
}
```

### 5. Triple Safety Net (DLQ 처리)
```java
// maple.expectation.service.v2.outbox.NexonApiDlqHandler
@Component
@RequiredArgsConstructor
public class NexonApiDlqHandler {

    private final NexonApiDlqRepository dlqRepository;
    private final FileBackupService fileBackupService;
    private final DiscordAlertService discordAlertService;
    private final LogicExecutor executor;

    /**
     * P0 - 데이터 영구 손실 방지
     * 1차: DB DLQ INSERT
     * 2차: File Backup
     * 3차: Discord Critical Alert
     */
    public void handleDeadLetter(NexonApiOutbox entry, Exception error) {
        executor.executeOrCatch(
            () -> {
                NexonApiDlq dlq = NexonApiDlq.from(entry, error);
                dlqRepository.save(dlq);
                metrics.incrementDlq();
                return null;
            },
            dbEx -> handleDbDlqFailure(entry, error, dbEx),
            TaskContext.of("DlqHandler", "HandleDeadLetter", entry.getId())
        );
    }

    private Void handleDbDlqFailure(NexonApiOutbox entry, Exception originalError, Exception dbError) {
        return executor.executeOrCatch(
            () -> {
                fileBackupService.appendNexonApiEntry(entry.getOcid(), entry.getEndpoint());
                return null;
            },
            fileEx -> {
                discordAlertService.sendCriticalAlert("NEXON_API_DLQ_CRITICAL", ...);
                metrics.incrementCriticalFailure();
                return null;
            },
            TaskContext.of("DlqHandler", "FileBackup", entry.getId())
        );
    }
}
```

### 6. 스케줄링 주기
```java
// maple.expectation.scheduler.NexonApiOutboxScheduler
@Component
@RequiredArgsConstructor
public class NexonApiOutboxScheduler {

    private final NexonApiOutboxProcessor processor;

    @Scheduled(fixedRate = 30000)  // 30초마다 폴링
    public void pollAndProcess() {
        executor.executeVoid(processor::pollAndProcess,
                TaskContext.of("Scheduler", "NexonApiOutboxPoll"));
    }

    @Scheduled(fixedRate = 300000)  // 5분마다 Stalled 복구
    public void recoverStalled() {
        executor.executeVoid(processor::recoverStalled,
                TaskContext.of("Scheduler", "NexonApiOutboxRecover"));
    }
}
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| 장애 시 데이터 유실 | 발생 | **0** (Outbox 보존) |
| 자동 복구 | 불가 | **99.98% 재처리** |
| 수동 개입 | 필수 | **불필요** |
| 운영 부담 | 높음 | **낮음** |
| 복구 시간 (210만 건) | N/A | **47분** |

**N19 Chaos Test 결과:**
- 장애 기간: 6시간
- 누적 이벤트: 2,134,221건
- 복구 후 성공: 2,134,158건 (99.98%)
- DLQ 이동: 63건 (0.02%)
- 복구 시간: 47분
- 처리량: 1,200 tps

## 참고 자료
- `maple.expectation.domain.v2.NexonApiOutbox`
- `maple.expectation.service.v2.outbox.NexonApiOutboxProcessor`
- `maple.expectation.scheduler.NexonApiOutboxScheduler`
- `maple.expectation.external.impl.ResilientNexonApiClient`
- [N19 Recovery Report](../04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)
- [ADR-010: Transactional Outbox Pattern](ADR-010-outbox-pattern.md)
