# Nightmare 13: Zombie Outbox

> **담당 에이전트**: 🔴 Red (장애주입) & 🟣 Purple (감사)
> **난이도**: P1 (High)
> **예상 결과**: CONDITIONAL PASS

---

## Test Evidence & Reproducibility

### 📋 Test Class
- **Class**: `ZombieOutboxNightmareTest`
- **Package**: `maple.expectation.chaos.nightmare`
- **Source**: [`module-chaos-test/src/chaos-test/java/maple/expectation/chaos/nightmare/ZombieOutboxNightmareTest.java`](../../../../../module-chaos-test/src/chaos-test/java/maple/expectation/chaos/nightmare/ZombieOutboxNightmareTest.java)

### 🚀 Quick Start
```bash
# Prerequisites: Docker Compose running (MySQL, Redis)
docker-compose up -d

# Run specific Nightmare test
./gradlew test --tests "maple.expectation.chaos.nightmare.ZombieOutboxNightmareTest" \
  2>&1 | tee logs/nightmare-13-$(date +%Y%m%d_%H%M%S).log

# Run individual test methods
./gradlew test --tests "*ZombieOutboxNightmareTest.shouldCreateZombieOutbox_whenJvmCrash*"
./gradlew test --tests "*ZombieOutboxNightmareTest.shouldRecoverZombie_byScheduler*"
./gradlew test --tests "*ZombieOutboxNightmareTest.shouldMaintainDataIntegrity_afterZombieRecovery*"
./gradlew test --tests "*ZombieOutboxNightmareTest.shouldRecoverMultipleZombies_createdByRealDonations*"
```

### 📊 Test Results
- **Result File**: Results not yet published
- **Test Date**: 2025-01-20
- **Result**: ❌ FAIL (2/4 tests)
- **Test Duration**: ~150 seconds

### 🔧 Test Environment
| Parameter | Value |
|-----------|-------|
| Java Version | 21 |
| Spring Boot | 3.5.4 |
| MySQL | 8.0 (Docker) |
| Outbox Table | donation_outbox |
| Stale Threshold | 5 minutes (configurable) |

### 💥 Failure Injection
| Method | Details |
|--------|---------|
| **Failure Type** | JVM Crash Simulation |
| **Injection Method** | PROCESSING status without completion |
| **Failure Scope** | Outbox entries |
| **Failure Duration** | Until scheduler runs |
| **Blast Radius** | Message delivery pipeline |

### ✅ Pass Criteria
| Criterion | Threshold | Rationale |
|-----------|-----------|-----------|
| Zombie Recovery Rate | 100% | All stalled items recovered |
| Data Integrity | 100% | No message loss |
| Stalled Detection | < 10min | Configurable threshold |

### ❌ Fail Criteria
| Criterion | Threshold | Action |
|-----------|-----------|--------|
| Zombie Recovery Rate | < 100% | Messages lost |
| Permanent PROCESSING | > 0 | Stalled items not detected |
| Data Inconsistency | > 0 | Integrity violation |

### 🧹 Cleanup Commands
```bash
# After test - clear zombie outbox entries
mysql -u root -p maple_expectation -e "DELETE FROM donation_outbox WHERE status = 'PROCESSING'"

# Reset stale entries to PENDING
mysql -u root -p maple_expectation -e "UPDATE donation_outbox SET status = 'PENDING', processed_by = NULL, processed_at = NULL WHERE status = 'PROCESSING'"

# Verify outbox state
mysql -u root -p maple_expectation -e "SELECT status, COUNT(*) FROM donation_outbox GROUP BY status"
```

### 📈 Expected Test Metrics
| Metric | Before | After | Threshold |
|--------|--------|-------|-----------|
| PROCESSING Entries | 0 | N+ | N/A |
| PENDING Entries | 0 | 0 | = 0 |
| Stalled Recovered | 0 | N | = N |

### 🔗 Evidence Links
- Test Class: [ZombieOutboxNightmareTest.java](../../../../../module-chaos-test/src/chaos-test/java/maple/expectation/chaos/nightmare/ZombieOutboxNightmareTest.java)
- Outbox Entity: [NexonApiOutbox.java](../../../../../module-infra/src/main/java/maple/expectation/domain/v2/NexonApiOutbox.java)
- Scheduler: [NexonApiOutboxScheduler.java](../../../../../module-app/src/main/java/maple/expectation/scheduler/NexonApiOutboxScheduler.java)
- Related Issue: #[P1] Outbox Zombie Recovery Data Integrity

### ❌ Fail If Wrong
This test is invalid if:
- Test does not simulate JVM crash correctly
- Stale threshold differs from production
- Scheduler not running during test
- Database constraints prevent PROCESSING state

---

## 0. 최신 테스트 결과 (2025-01-20)

### ❌ FAIL (2/4 테스트 실패)

| 테스트 메서드 | 결과 | 설명 |
|-------------|------|------|
| `shouldCreateZombieOutbox_whenJvmCrash()` | ✅ PASS | JVM 크래시 시 Zombie 생성 확인 |
| `shouldRecoverZombie_byScheduler()` | ✅ PASS | 스케줄러 복구 동작 확인 |
| `shouldMaintainDataIntegrity_afterZombieRecovery()` | ❌ FAIL | Zombie 복구 후 데이터 무결성 검증 실패 |
| `shouldRecoverMultipleZombies_createdByRealDonations()` | ❌ FAIL | 다중 Zombie 동시 복구 실패 |

### 🔴 문제 원인
- **실제 DonationService 통합**: sendCoffee() 호출 시 의존성 문제 발생 가능
- **테스트 데이터 정리**: 이전 테스트의 Outbox 데이터가 영향을 줌
- **Stale Threshold**: 5분 대기 시간이 테스트 환경에 부적합

### 📋 Issue Required
**[P1] Outbox Zombie 복구 시 데이터 무결성 보장 필요**

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
JVM 크래시 시 PROCESSING 상태에서 고착된 Outbox 항목(Zombie)이
정상적으로 복구되는지 검증한다.

### 검증 포인트
- [ ] PROCESSING 상태 고착 후 복구
- [ ] 다중 인스턴스 크래시 복구
- [ ] Stale Threshold(5분) 적절성
- [ ] 복구 후 데이터 무결성

### 성공 기준
- Stalled 항목이 PENDING/FAILED로 복구됨
- 메시지 손실 없음

---

## 2. Zombie 발생 시나리오

### 문제 상황
```
1. Outbox 항목 처리 시작 → status: PROCESSING
2. JVM 크래시 (OOM, kill -9, 하드웨어 장애)
3. 항목이 PROCESSING 상태로 영구 고착
4. 재처리되지 않아 메시지 손실
```

### Zombie 상태
```sql
SELECT * FROM donation_outbox
WHERE status = 'PROCESSING'
AND processed_at < NOW() - INTERVAL 5 MINUTE;
```

---

## 3. 복구 메커니즘

### recoverStalled() 동작
```java
@Transactional
public void recoverStalled() {
    LocalDateTime staleTime = LocalDateTime.now().minus(STALE_THRESHOLD);
    int recovered = outboxRepository.resetStalledProcessing(staleTime);

    if (recovered > 0) {
        log.warn("♻️ [Outbox] Stalled 상태 복구: {}건", recovered);
    }
}
```

### SQL 쿼리
```sql
UPDATE donation_outbox
SET status = 'PENDING',
    processed_by = NULL,
    processed_at = NULL
WHERE status = 'PROCESSING'
AND processed_at < :staleTime;
```

---

## 4. 그라파나 대시보드 (🟢 Green's Analysis)

### 프로메테우스 쿼리
```promql
# Outbox 처리량
outbox_processed_total
outbox_failed_total

# Stalled 복구
outbox_stalled_recovered_total

# 현재 Pending 수
outbox_pending_count
```

### 알람 설정
```yaml
- alert: ZombieOutboxDetected
  expr: outbox_pending_count > 100
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Outbox Zombie 가능성"
```

---

## 5. 관련 CS 원리

### Outbox Pattern
분산 트랜잭션 대안으로, 이벤트를 로컬 테이블에 저장 후 별도 프로세스가 발행.

### At-Least-Once Delivery
메시지가 최소 1회 전달됨을 보장. 중복 가능하나 손실 없음.

### Idempotent Consumer
중복 메시지를 안전하게 처리할 수 있는 소비자 설계.

---

## 6. 이슈 정의 (실패 시)

### 📌 문제 정의
JVM 크래시 후 Outbox 항목이 PROCESSING에서 복구되지 않음.

### ✅ Action Items
- [ ] STALE_THRESHOLD 적절성 검토 (현재 5분)
- [ ] recoverStalled() 스케줄러 주기 확인
- [ ] 다중 인스턴스 환경 테스트
- [ ] Zombie 모니터링 알람 추가

---

## 7. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **FAIL**

Zombie Outbox 복구 시 **DistributedLockException** 발생으로
데이터 무결성 검증 및 다중 Zombie 동시 복구 테스트 실패.

### 기술적 인사이트
- **락 경합 문제**: 테스트 환경에서 분산 락 획득 실패
- **Stale Threshold 부적합**: 5분 대기 시간이 테스트 환경에 적합하지 않음
- **데이터 정리 필요**: 이전 테스트의 Outbox 데이터가 영향을 미침
- **실제 DonationService 통합**: sendCoffee() 호출 시 의존성 문제 발생

### 권장 개선 사항
1. **테스트 격리 강화**: @BeforeEach에서 Outbox 테이블 완전 초기화
2. **Stale Threshold 조정**: 테스트용 짧은 threshold 설정 (예: 10초)
3. **락 획득 재시도**: 실패 시 재시도 로직 추가
4. **모니터링 알람**: `outbox_stalled_recovered_total` 메트릭 감시

---

## Fail If Wrong

This test is invalid if:
- [ ] Test does not simulate JVM crash correctly
- [ ] Stale threshold differs from production setting
- [ ] Scheduler not running during test
- [ ] Database constraints prevent PROCESSING state
- [ ] Clock skew affects stale detection

---

*Generated by 5-Agent Council*
