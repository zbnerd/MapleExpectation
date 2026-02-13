- **코드:** `src/main/java/maple/expectation/service/v2/cube/component/`

---

# ADR-010: Transactional Outbox Pattern 설계

## 상태
Accepted

## 문맥 (Context)
### 문제 정의
분산 환경에서 이벤트 전달 보장 시 문제 발생:
- Dual-Write 문제: DB 저장 성공 + 알림 전송 실패 → 불일치 [E1]
- JVM 크래시 시 PROCESSING 상태로 영구 고착 (Zombie) [E2]
- 분산 환경에서 중복 처리 발생 [E3]

**Issue Reference:**
- #80: Transactional Outbox 구현
- #229: Stalled 상태 복구 시 무결성 검증 강화

### 기술적 문제
1. **이벤트 전달 불확실성**: DB 커밋 후 이벤트 발행 실패 시 데이터 불일치
2. **프로세스 고착**: JVM 장애 시 PROCESSING 상태로 영구 멈춤
3. **분산 환경에서 중복 처리**: 여러 인스턴스가 동시에 같은 데이터 처리 시도
4. **데이터 손실 위험**: 최종 실패 시 이벤트 데이터 영구 소실

### 제약 조건
- At-least-once 전달 보장 필수
- DB 의존도 최소화 (MySQL 8.0)
- 운영 부담 증가 없이 구현
- 기존 아키텍처와의 호환성

### 대상 시스템
- 결제 알림 시스템 (Donation)
- 실시간 업데이트 트리거
- 분산 환경 (Multiple Instances)

## 결정 (Decision)
**Transactional Outbox + SKIP LOCKED + Content Hash + Triple Safety Net을 적용합니다.**

### 기술적 선택 근거
1. **원자성 보장**: DB 트랜잭션 내에서 이벤트 저장
2. **중복 처리 방지**: SKIP LOCKED로 분산 환경 안전성 확보
3. **무결성 검증**: Content Hash로 데이터 변조 방지
4. **복구 메커니즘**: Zombie 상태 자동 복구
5. **데이터 보호**: Triple Safety Net으로 영구 손실 방지

### 대안 분석
### 옵션 A: 이벤트 직접 발행
- **장점:** 구현 간단
- **단점:** DB 커밋 후 이벤트 발행 실패 시 불일치
- **거절:** [R1] 네트워크 오류 시 3건/일 불일치 발생 (테스트: 2025-11-15)
- **결론:** 원자성 미보장 (기각)

### 옵션 B: Change Data Capture (CDC)
- **장점:** 완전한 원자성
- **단점:** 인프라 복잡도 증가, 운영 부담
- **거절:** [R2] Debezium 클러스터 운영 비용 과다 (POC: 2025-11-17)
- **결론:** 오버 엔지니어링 (기각)

### 옵션 C: Transactional Outbox + SKIP LOCKED
- **장점:** 동일 트랜잭션 내 저장, DB 레벨 중복 처리 방지
- **단점:** 폴링 지연 (10초)
- **채택:** [C1] At-least-once 보장 + Triple Safety Net
- **결론:** 채택

---

## Fail If Wrong
1. **[F1]** Dual-Write 문제 발생 (DB 커밋 + 이벤트 발행 실패)
2. **[F2]** Zombie 상태 5분 이상 지속
3. **[F3]** SKIP LOCKED로 분산 중복 처리 발생
4. **[F4]** DLQ Triple Safety Net 실패로 데이터 영구 손실

---

## Terminology
| 용어 | 정의 |
|------|------|
| **Transactional Outbox** | DB와 이벤트 발행의 원자성 보장 패턴 |
| **Dual-Write** | DB + 이벤트 큐에 각각 쓰는 비원자적 작업 |
| **SKIP LOCKED** | 이미 잠긴 행을 스킵하고 다음 행을 잠그는 MySQL 기능 |
| **Zombie** | PROCESSING 상태로 영구 고착된 Outbox 항목 |
| **DLQ (Dead Letter Queue)** | 최종 실패한 메시지를 저장하는 큐 |

---

## 맥락 (Context)
### 문제 정의
분산 환경에서 이벤트 전달 보장 시 문제 발생:
- Dual-Write 문제: DB 저장 성공 + 알림 전송 실패 → 불일치 [E1]
- JVM 크래시 시 PROCESSING 상태로 영구 고착 (Zombie) [E2]
- 분산 환경에서 중복 처리 발생 [E3]

**Issue Reference:**
- #80: Transactional Outbox 구현
- #229: Stalled 상태 복구 시 무결성 검증 강화

---

## 대안 분석
### 옵션 A: 이벤트 직접 발행
- **장점:** 구현 간단
- **단점:** DB 커밋 후 이벤트 발행 실패 시 불일치
- **거절:** [R1] 네트워크 오류 시 3건/일 불일치 발생 (테스트: 2025-11-15)
- **결론:** 원자성 미보장 (기각)

### 옵션 B: Change Data Capture (CDC)
- **장점:** 완전한 원자성
- **단점:** 인프라 복잡도 증가, 운영 부담
- **거절:** [R2] Debezium 클러스터 운영 비용 과다 (POC: 2025-11-17)
- **결론:** 오버 엔지니어링 (기각)

### 옵션 C: Transactional Outbox + SKIP LOCKED
- **장점:** 동일 트랜잭션 내 저장, DB 레벨 중복 처리 방지
- **단점:** 폴링 지연 (10초)
- **채택:** [C1] At-least-once 보장 + Triple Safety Net
- **결론:** 채택

---

## 결정 (Decision)
**Transactional Outbox + SKIP LOCKED + Content Hash + Triple Safety Net을 적용합니다.**

### Code Evidence

**[C1] DonationOutbox 엔티티**
```java
// src/main/java/maple/expectation/domain/v2/DonationOutbox.java
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

    /**
     * Content Hash (분산 환경 안전)
     * SHA-256(requestId | eventType | payload)
     */
    @Column(nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    private OutboxStatus status = OutboxStatus.PENDING;

    // Exponential Backoff: 1차 30s, 2차 60s, 3차 120s
    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = truncate(error, 500);
        this.status = shouldMoveToDlq() ? OutboxStatus.DEAD_LETTER : OutboxStatus.FAILED;
        this.nextRetryAt = LocalDateTime.now().plusSeconds((long) Math.pow(2, retryCount) * 30);
        clearLock();
    }
}
```

**[C2] SKIP LOCKED 쿼리**
```java
// src/main/java/maple/expectation/repository/v2/DonationOutboxRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP LOCKED
@Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses " +
       "AND o.nextRetryAt <= :now ORDER BY o.id")
List<DonationOutbox> findPendingWithLock(
        @Param("statuses") List<OutboxStatus> statuses,
        @Param("now") LocalDateTime now,
        Pageable pageable);
```

**[C3] OutboxProcessor**
```java
// src/main/java/maple/expectation/service/v2/donation/outbox/OutboxProcessor.java
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
        handleIntegrityFailure(entry);
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

**[C4] Stalled 상태 복구 (#229)**
```java
@ObservedTransaction("scheduler.outbox.recover_stalled")
@Transactional
public void recoverStalled() {
    LocalDateTime staleTime = LocalDateTime.now().minus(STALE_THRESHOLD);  // 5분
    List<DonationOutbox> stalledEntries = outboxRepository.findStalledProcessing(staleTime);

    for (DonationOutbox entry : stalledEntries) {
        // 무결성 검증 추가 (#229)
        if (!entry.verifyIntegrity()) {
            handleIntegrityFailure(entry);
            continue;
        }

        // 무결성 통과 → 상태 복원
        entry.resetToRetry();
        outboxRepository.save(entry);
    }
}
```

**[C5] Triple Safety Net (DLQ 처리)**
```java
// src/main/java/maple/expectation/service/v2/donation/outbox/DlqHandler.java
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
```

---

## 결과 (Consequences)
### 긍정적 결과 (Benefits)
| 지표 | Before | After | Evidence ID |
|------|--------|-------|-------------|
| 이벤트 전달 보장 | 불확실 | **At-least-once** | [E1] |
| Dual-Write 문제 | 발생 | **방지** | [E2] |
| Zombie 상태 | 영구 고착 | **5분 내 복구** | [E3] |
| 분산 중복 처리 | 발생 | **SKIP LOCKED 방지** | [E4] |
| 데이터 영구 손실 | 가능 | **Triple Safety Net** | [E5] |

**Evidence IDs:**
- [E1] 테스트: OutboxProcessorTest 통과
- [E2] 무결성: Content Hash 검증
- [E3] 복구: Stalled Recovery 5분
- [E4] 분산: SKIP LOCKED 동시성 테스트
- [E5] DLQ: Triple Safety Net 통합 테스트

### 부작용 (Trade-offs)
| 영향도 | 내용 | 대응 방안 |
|--------|------|-----------|
| **성능** | 폴링 방식으로 지연 발생 (10s) | 배치 처리로 최적화 |
| **복잡도** | Outbox 테이블 및 상태 관리 필요 | 자동화된 복구 메커니즘 |
| **저장 공간** | 이벤트 중복 저장으로 공간 증가 | 정기적 정책으로 관리 |
| **운영** | Dead Letter Queue 모니터링 필요 | 자동 알림 시스템 연동 |

### 리스크 관리
| 리스크 | 영향도 | 완화 조치 |
|--------|--------|-----------|
| **DB 부하** | 중 | 인덱스 최적화, 배치 크기 조정 |
| **메모리 누수** | 저 | 상태 자동 정리, GC 모니터링 |
| **지연 증가** | 중 | 비동기 처리, 스레드 풀 최적화 |
| **데이터 정합성** | 고 | 주기적 검증, 재처리 메커니즘 |

### 운영 영향
- **감사 로그**: 모든 이벤트 처리 과정 추적
- **모니터링**: 성능 및 장애 지표 실시간 모니터링
- **복구**: 자동 복구 메커니즘으로 운영 부담 최소화
- **확장성**: 분산 환경에서 일관된 동작 보장

---

## Verification Commands (검증 명령어)

### 1. Outbox 기본 기능 검증

```bash
# Outbox 테스트
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.OutboxProcessorTest"

# Content Hash 검증
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.ContentHashTest"

# SKIP LOCKED 동시성 테스트
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.SkipLockedTest"
```

### 2. 복구 기능 검증

```bash
# Zombie 상태 복구 테스트
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.StalledRecoveryTest"

# Stalled Recovery 시나리오 테스트
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.RecoveryTest"

# Dead Letter Queue 테스트
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.DlqHandlerTest"
```

### 3. 장애 시나리오 검증

```bash
# DB 장애 테스트
./gradlew chaos --scenario="outbox-db-failure"

# Redis 장애 테스트
./gradlew chaos --scenario="outbox-redis-failure"

# 외부 API 장애 테스트
./gradlew chaos --scenario="outbox-api-failure"
```

### 4. 성능 검증

```bash
# 부하테스트 (Outbox 처리)
./gradlew loadTest --args="--rps 500 --scenario=outbox"

# 처리량 메트릭 확인
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(. | contains("outbox"))'

# 지연 시간 메트릭
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(. | contains("outbox latency"))'
```

### 5. 데이터 정합성 검증

```bash
# Reconciliation 테스트
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.ReconciliationTest"

# 무결성 검증
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.IntegrityTest"

# 원자성 테스트
./gradlew test --tests "maple.expectation.service.v2.donation.outbox.AtomicityTest"
```

---

## 관련 문서
- **코드:** `src/main/java/maple/expectation/domain/v2/DonationOutbox.java`
- **시퀀스:** `docs/03_Sequence_Diagrams/outbox-sequence.md`
- **이슈:** #80, #229
