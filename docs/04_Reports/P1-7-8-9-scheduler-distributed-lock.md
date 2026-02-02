# Scheduler 분산 락 적용 (Issue #283 P1-7, P1-8, P1-9)

## 개요

Scale-out 환경에서 스케줄러 중복 실행을 방지하기 위해 분산 락을 적용하거나, 이미 분산 환경에서 안전한 메커니즘이 있는 경우 이를 문서화합니다.

---

## P1-7: BufferRecoveryScheduler - 분산 락 적용

### 변경 사항

**파일**: `/home/maple/MapleExpectation/src/main/java/maple/expectation/scheduler/BufferRecoveryScheduler.java`

#### 1. LockStrategy 주입 (CLAUDE.md Section 6: Constructor Injection)

```java
// Before
private final RedisBufferStrategy<?> redisBufferStrategy;
private final LogicExecutor executor;
private final MeterRegistry meterRegistry;

// After
private final RedisBufferStrategy<?> redisBufferStrategy;
private final LockStrategy lockStrategy;  // 추가
private final LogicExecutor executor;
private final MeterRegistry meterRegistry;
```

#### 2. processRetryQueue() - 분산 락 적용

```java
// Before
@Scheduled(fixedRateString = "${scheduler.buffer-recovery.retry-rate:5000}")
public void processRetryQueue() {
    executor.executeVoid(
            this::doProcessRetryQueue,
            TaskContext.of("Scheduler", "Buffer.ProcessRetry")
    );
}

// After
@Scheduled(fixedRateString = "${scheduler.buffer-recovery.retry-rate:5000}")
public void processRetryQueue() {
    executor.executeOrDefault(
            () -> lockStrategy.executeWithLock(
                    "scheduler:buffer-recovery:retry",
                    0,      // waitTime: 락 획득 실패 시 즉시 스킵
                    30,     // leaseTime: 30초
                    () -> {
                        doProcessRetryQueue();
                        return null;
                    }
            ),
            null,
            TaskContext.of("Scheduler", "Buffer.ProcessRetry")
    );
}
```

#### 3. redriveExpiredInflight() - 분산 락 적용

```java
// Before
@Scheduled(fixedRateString = "${scheduler.buffer-recovery.redrive-rate:30000}")
public void redriveExpiredInflight() {
    executor.executeVoid(
            this::doRedriveExpiredInflight,
            TaskContext.of("Scheduler", "Buffer.Redrive")
    );
}

// After
@Scheduled(fixedRateString = "${scheduler.buffer-recovery.redrive-rate:30000}")
public void redriveExpiredInflight() {
    executor.executeOrDefault(
            () -> lockStrategy.executeWithLock(
                    "scheduler:buffer-recovery:redrive",
                    0,      // waitTime: 락 획득 실패 시 즉시 스킵
                    60,     // leaseTime: 60초 (더 긴 작업)
                    () -> {
                        doRedriveExpiredInflight();
                        return null;
                    }
            ),
            null,
            TaskContext.of("Scheduler", "Buffer.Redrive")
    );
}
```

### 설계 근거

1. **waitTime=0**: 다른 인스턴스가 이미 처리 중이면 즉시 스킵 (Fail-Fast)
2. **executeOrDefault 사용**: DistributedLockException 발생 시 조용히 null 반환
3. **leaseTime 차등 적용**:
   - processRetryQueue: 30초 (빠른 배치 처리)
   - redriveExpiredInflight: 60초 (더 긴 복구 작업)

---

## P1-8: LikeSyncScheduler - Constructor Injection 수정 + Javadoc 추가

### 변경 사항

**파일**: `/home/maple/MapleExpectation/src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java`

#### 1. @Autowired(required=false) 제거 → @Nullable + Constructor Injection

```java
// Before (CLAUDE.md Section 6 위반)
@Autowired(required = false)
private PartitionedFlushStrategy partitionedFlushStrategy;

// After (CLAUDE.md Section 6 준수)
/**
 * PartitionedFlushStrategy (Redis 모드에서만 주입)
 *
 * <p>In-Memory 모드에서는 null이며, Redis 모드에서만 Bean이 생성됩니다.</p>
 *
 * <h4>Issue #283 P1-8: Constructor Injection (CLAUDE.md Section 6)</h4>
 * <p>@Autowired(required=false) 대신 @Nullable + 생성자 주입 패턴 사용</p>
 */
@Nullable
private final PartitionedFlushStrategy partitionedFlushStrategy;
```

**Import 추가**:
```java
import org.springframework.lang.Nullable;
```

**Import 제거**:
```java
import org.springframework.beans.factory.annotation.Autowired;
```

#### 2. localFlush() - 분산 락 미적용 사유 Javadoc 추가

```java
/**
 * L1 → L2 Flush (likeCount + likeRelation)
 *
 * <h4>분산 환경 안전 (Issue #283 P1-8)</h4>
 * <p><b>분산 락 미적용 사유</b>: Redis 모드에서 각 인스턴스는 자신의 로컬 Caffeine L1 캐시를
 * Redis L2로 Flush합니다. 이는 인스턴스별 독립적인 작업이므로 분산 락이 불필요합니다.</p>
 *
 * <p><b>중복 방지 메커니즘</b>:</p>
 * <ul>
 *   <li>각 인스턴스의 L1 캐시는 독립적으로 관리됨</li>
 *   <li>L1 → L2 Flush는 인스턴스별 로컬 작업</li>
 *   <li>L2 → L3 DB 동기화만 분산 락 필요 (globalSyncCount/Relation)</li>
 * </ul>
 */
@Scheduled(fixedRate = 1000)
public void localFlush() {
    // ...
}
```

### 설계 근거

1. **localFlush()는 분산 락 불필요**:
   - 각 인스턴스의 L1 (Caffeine) 캐시는 독립적
   - L1 → L2 (Redis) Flush는 인스턴스별 로컬 작업
   - 중복 실행되어도 문제 없음 (각자 자신의 L1 데이터만 Flush)

2. **globalSyncCount/Relation은 이미 분산 락 적용됨**:
   - `lockStrategy.executeWithLock("like-db-sync-lock", ...)`
   - `lockStrategy.executeWithLock("like-relation-sync-lock", ...)`

---

## P1-9: OutboxScheduler - SKIP LOCKED 메커니즘 문서화

### 변경 사항

**파일**: `/home/maple/MapleExpectation/src/main/java/maple/expectation/scheduler/OutboxScheduler.java`

#### Javadoc 추가: SKIP LOCKED로 분산 락 불필요

```java
/**
 * Outbox 폴링 스케줄러 (Issue #80)
 *
 * <h3>스케줄링 주기</h3>
 * <ul>
 *   <li>pollAndProcess: 10초 (Pending -> Processing -> Completed)</li>
 *   <li>recoverStalled: 5분 (JVM 크래시 대응)</li>
 * </ul>
 *
 * <h3>P1-7 Fix: updatePendingCount() 스케줄러 레벨로 이동</h3>
 * <p>기존: OutboxProcessor.pollAndProcess() 내부에서 호출 (배치 트랜잭션 내 추가 쿼리)</p>
 * <p>수정: 스케줄러에서 독립적으로 호출 (배치 처리 시간 단축)</p>
 *
 * <h3>분산 환경 안전 (Issue #283 P1-9)</h3>
 * <p><b>분산 락 미적용 사유</b>: {@link DonationOutboxRepository}의
 * {@code findPendingWithLock()} 및 {@code findStalledProcessing()} 메서드는
 * {@code PESSIMISTIC_WRITE} + {@code SKIP LOCKED} 쿼리를 사용하여 DB 레벨에서
 * 중복 처리를 방지합니다.</p>
 *
 * <p><b>SKIP LOCKED 동작 원리</b>:</p>
 * <ul>
 *   <li>다중 인스턴스가 동시에 폴링 시, 잠긴 행은 건너뛰고 다음 행 처리</li>
 *   <li>대기 없이 병렬 처리 가능 (높은 처리량 보장)</li>
 *   <li>Redis 분산 락 대비 장점: Redis 장애 시에도 독립 동작</li>
 * </ul>
 *
 * @see OutboxProcessor
 * @see DonationOutboxRepository#findPendingWithLock
 * @see DonationOutboxRepository#findStalledProcessing
 */
```

### 설계 근거

1. **DB 레벨 락 (PESSIMISTIC_WRITE + SKIP LOCKED)**:
   - `DonationOutboxRepository.findPendingWithLock()`
   - `DonationOutboxRepository.findStalledProcessing()`
   - QueryHint: `jakarta.persistence.lock.timeout = -2` (SKIP LOCKED)

2. **SKIP LOCKED 장점**:
   - 일반 Pessimistic Lock: 대기 발생 → 처리량 저하
   - SKIP LOCKED: 잠긴 행 건너뛰기 → 병렬 처리 가능
   - Redis 분산 락 대비: Redis 장애 시에도 독립 동작

3. **추가 보장: @Version 낙관적 락**:
   - `DonationOutbox` 엔티티는 `@Version` 필드 포함
   - 금융 트랜잭션이므로 강한 일관성 보장

---

## CLAUDE.md 준수사항

### Section 6: Constructor Injection Only

- **Before**: `@Autowired(required=false) private PartitionedFlushStrategy partitionedFlushStrategy;`
- **After**: `@Nullable private final PartitionedFlushStrategy partitionedFlushStrategy;`
- `@RequiredArgsConstructor`가 자동으로 생성자 주입 처리

### Section 12: Zero Try-Catch Policy

- 모든 실행은 `LogicExecutor.executeOrDefault()` 또는 `executeVoid()` 사용
- DistributedLockException은 `executeOrDefault()`가 null 반환으로 처리

### Section 15: Lambda 3-Line Rule

- 분산 락 람다 내부:
  ```java
  () -> {
      doProcessRetryQueue();  // 1줄
      return null;            // 2줄
  }
  ```
- 2줄로 3-Line Rule 준수

---

## 검증 체크리스트

- [x] BufferRecoveryScheduler: LockStrategy 주입 (Constructor Injection)
- [x] BufferRecoveryScheduler: processRetryQueue() 분산 락 적용
- [x] BufferRecoveryScheduler: redriveExpiredInflight() 분산 락 적용
- [x] LikeSyncScheduler: @Autowired(required=false) → @Nullable + final
- [x] LikeSyncScheduler: localFlush() 분산 락 미적용 사유 Javadoc 추가
- [x] OutboxScheduler: SKIP LOCKED 메커니즘 Javadoc 추가
- [x] CLAUDE.md Section 6 준수 (Constructor Injection)
- [x] CLAUDE.md Section 12 준수 (LogicExecutor 사용)
- [x] CLAUDE.md Section 15 준수 (Lambda 3-Line Rule)

---

## 결론

### P1-7: BufferRecoveryScheduler
- **분산 락 적용**: `scheduler:buffer-recovery:retry`, `scheduler:buffer-recovery:redrive`
- **waitTime=0**: Fail-Fast 전략 (다른 인스턴스 처리 중이면 스킵)
- **leaseTime 차등**: 작업 특성에 따라 30초/60초

### P1-8: LikeSyncScheduler
- **Constructor Injection 수정**: CLAUDE.md Section 6 준수
- **localFlush() 분산 락 불필요**: 인스턴스별 독립 작업 (L1 → L2 Flush)
- **globalSync는 이미 분산 락 적용**: L2 → L3 DB 동기화

### P1-9: OutboxScheduler
- **SKIP LOCKED로 분산 락 불필요**: DB 레벨에서 중복 처리 방지
- **장점**: Redis 장애 시에도 독립 동작, 대기 없이 병렬 처리 가능
- **추가 보장**: @Version 낙관적 락으로 강한 일관성

---

**생성일**: 2026-02-01
**작성자**: Claude Sonnet 4.5
**관련 이슈**: #283 (P1-7, P1-8, P1-9)
