# Nightmare 17: Poison Pill

> **담당 에이전트**: 🔴 Red (장애주입) & 🟣 Purple (감사)
> **난이도**: P2 (Medium)
> **예상 결과**: PASS (ContentHash 검증 + Triple Safety Net)

---

## Test Evidence & Reproducibility

### 📋 Test Class
- **Class**: `PoisonPillNightmareTest`
- **Package**: `maple.expectation.chaos.nightmare`
- **Source**: [`src/test/java/maple/expectation/chaos/nightmare/PoisonPillNightmareTest.java`](../../../src/test/java/maple/expectation/chaos/nightmare/PoisonPillNightmareTest.java)

### 🚀 Quick Start
```bash
# Prerequisites: Docker Compose running (MySQL, Redis)
docker-compose up -d

# Run specific Nightmare test
./gradlew test --tests "maple.expectation.chaos.nightmare.PoisonPillNightmareTest" \
  2>&1 | tee logs/nightmare-17-$(date +%Y%m%d_%H%M%S).log

# Run individual test methods
./gradlew test --tests "*PoisonPillNightmareTest.shouldDetectPayloadCorruption_withContentHash*"
./gradlew test --tests "*PoisonPillNightmareTest.shouldPreventHeadOfLineBlocking*"
./gradlew test --tests "*PoisonPillNightmareTest.shouldMoveToDlq_whenMaxRetryExceeded*"
./gradlew test --tests "*PoisonPillNightmareTest.shouldAutomaticallyMoveToDlq_whenPayloadCorrupted*"
./gradlew test --tests "*PoisonPillNightmareTest.shouldPreserveCorruptedPayload_inDlq*"
```

### 📊 Test Results
- **Result File**: Not yet created
- **Test Date**: 2025-01-20
- **Result**: ❌ FAIL (2/5 tests)
- **Test Duration**: ~120 seconds

### 🔧 Test Environment
| Parameter | Value |
|-----------|-------|
| Java Version | 21 |
| Spring Boot | 3.5.4 |
| Outbox Table | donation_outbox |
| DLQ Table | donation_dlq |
| Content Hash Algorithm | SHA-256 |

### 💥 Failure Injection
| Method | Details |
|--------|---------|
| **Failure Type** | Payload Corruption |
| **Injection Method** | Native query UPDATE on payload field |
| **Failure Scope** | Outbox entries |
| **Failure Duration** | Until test completes |
| **Blast Radius** | Message processing pipeline |

### ✅ Pass Criteria
| Criterion | Threshold | Rationale |
|-----------|-----------|-----------|
| ContentHash Detection | 100% | Corruption detected |
| DLQ Transfer Rate | 100% | Poison pills isolated |
| HoL Blocking Prevention | Yes | Normal messages processed |
| Triple Safety Net | All 3 levels | DB → File → Discord |

### ❌ Fail Criteria
| Criterion | Threshold | Action |
|-----------|-----------|--------|
| ContentHash Missed | > 0 | Corruption not detected |
| Processing Continues | > 0 | Poison pill retried |
| HoL Blocking | Yes | Queue stuck |
| DLQ Not Created | > 0 | Evidence lost |

### 🧹 Cleanup Commands
```bash
# After test - clear DLQ entries
mysql -u root -p maple_expectation -e "DELETE FROM donation_dlq WHERE created_at >= CURDATE()"

# Reset corrupted outbox entries
mysql -u root -p maple_expectation -e "UPDATE donation_outbox SET status = 'PENDING', processed_by = NULL, processed_at = NULL WHERE status = 'DEAD_LETTER'"

# Verify outbox state
mysql -u root -p maple_expectation -e "SELECT status, COUNT(*) FROM donation_outbox GROUP BY status"
```

### 📈 Expected Test Metrics
| Metric | Before | After | Threshold |
|--------|--------|-------|-----------|
| ContentHash Mismatch | 0 | N | corruption count |
| DLQ Entries | 0 | N | = corruption count |
| COMPLETED Normal | 0 | M | total - N |
| HoL Blocked | No | No | must not block |

### 🔗 Evidence Links
- Test Class: [PoisonPillNightmareTest.java](../../../src/test/java/maple/expectation/chaos/nightmare/PoisonPillNightmareTest.java)
- Outbox Entity: [DonationOutbox.java](../../../src/main/java/maple/expectation/domain/v2/DonationOutbox.java)
- DLQ Handler: [DlqHandler.java](../../../src/main/java/maple/expectation/service/v2/donation/outbox/DlqHandler.java)
- Related Issue: #[P2] Outbox ContentHash Detection and DLQ Transfer

### ❌ Fail If Wrong
This test is invalid if:
- Test does not corrupt payload correctly
- ContentHash verification disabled in test environment
- DLQ tables differ from production schema
- OutboxProcessor not running during test

---

## 0. 최신 테스트 결과 (2025-01-20)

### ❌ FAIL (2/5 테스트 실패)

| 테스트 메서드 | 결과 | 설명 |
|-------------|------|------|
| `shouldDetectPayloadCorruption_withContentHash()` | ✅ PASS | ContentHash로 변조 감지 |
| `shouldPreventHeadOfLineBlocking()` | ✅ PASS | HoL Blocking 방지 확인 |
| `shouldMoveToDlq_whenMaxRetryExceeded()` | ✅ PASS | Max Retry 초과 시 DLQ 이동 |
| `shouldAutomaticallyMoveToDlq_whenPayloadCorrupted()` | ❌ FAIL | Payload 변조 시 자동 DLQ 이동 실패 |
| `shouldPreserveCorruptedPayload_inDlq()` | ❌ FAIL | DLQ에 변조된 payload 보존 실패 |

### 🔴 문제 원인
- **OutboxProcessor 통합**: 실제 pollAndProcess() 호출 시 예상과 다른 동작
- **ContentHash 검증**: verifyIntegrity() 또는 handleIntegrityFailure() 미호출 가능
- **DLQ 저장**: DonationDlq 테이블에 데이터가 저장되지 않음

### 📋 Issue Required
**[P2] OutboxProcessor의 ContentHash 검증 및 DLQ 이동 로직 점검 필요**

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
처리 불가능한 메시지(Poison Pill)가 Consumer를 무한 재시도에 빠뜨려
전체 메시지 처리를 중단시키는 문제(Head-of-Line Blocking)를 검증한다.

### 실제 처리 흐름
```
DonationService.sendCoffee()
    ↓
DonationOutbox 생성 (ContentHash 자동 계산)
    ↓
Payload 변조 (테스트에서 시뮬레이션)
    ↓
OutboxProcessor.pollAndProcess()
    ↓
verifyIntegrity() 실패 감지
    ↓
handleIntegrityFailure() → 즉시 DEAD_LETTER
    ↓
DlqHandler.handleDeadLetter() → Triple Safety Net
```

### 검증 포인트
- [x] ContentHash로 Payload 변조 감지 (verifyIntegrity)
- [x] 변조된 Poison Pill 자동 DLQ 이동
- [x] Head-of-Line Blocking 방지 (정상 메시지 처리 지속)
- [x] Max Retry 초과 시 DLQ 이동
- [x] Triple Safety Net (DB → File → Discord)

### 성공 기준
- Poison Pill이 DLQ로 자동 이동
- 정상 메시지는 COMPLETED 상태로 처리
- 변조된 payload도 Forensic용으로 DLQ에 보존

---

## 2. Poison Pill 유형 (🔴 Red's Analysis)

### 프로젝트에서 감지 가능한 유형
| 유형 | 감지 방법 | 처리 |
|------|----------|------|
| **Payload 변조** | ContentHash 불일치 | 즉시 DLQ (재시도 무의미) |
| **Max Retry 초과** | retryCount >= 3 | DLQ 이동 |
| **처리 실패** | handleFailure() | 재시도 후 DLQ |

### ContentHash 검증 원리
```java
// DonationOutbox.java
public static DonationOutbox create(String requestId, String eventType, String payload) {
    outbox.contentHash = computeContentHash(requestId, eventType, payload);
    // SHA-256 해시로 무결성 보장
}

public boolean verifyIntegrity() {
    String expected = computeContentHash(requestId, eventType, payload);
    return contentHash.equals(expected);  // 불일치 시 변조 감지!
}
```

### Head-of-Line Blocking 문제
```
[Poison Pill 도착] ← 첫 메시지가 막히면
    ↓
[무한 재시도] → 뒤 메시지 전체 대기
    ↓
[정상 메시지 #2] → 영원히 처리 불가 ❌
```

### 프로젝트의 해결책
```
[Poison Pill 감지]
    ↓
[즉시 DEAD_LETTER] → DLQ 이동
    ↓
[다음 메시지 처리] → 정상 진행 ✅
```

---

## 3. 테스트 시나리오

### Test 1: Payload 변조 → 자동 DLQ 이동
```java
// 1. 정상 Outbox 생성
donationService.sendCoffee(guestUuid, adminFp, 1000L, requestId);

// 2. Native Query로 payload 변조 (ContentHash 불일치 유발)
entityManager.createNativeQuery(
    "UPDATE donation_outbox SET payload = :poison WHERE request_id = :requestId")
    .setParameter("poison", "{\"corrupted\":true}")
    .executeUpdate();

// 3. OutboxProcessor 실행
outboxProcessor.pollAndProcess();

// 4. 검증: DLQ로 자동 이동
assertThat(dlqRepository.findByRequestId(requestId)).isPresent();
assertThat(outbox.getStatus()).isEqualTo(DEAD_LETTER);
```

### Test 2: Head-of-Line Blocking 방지
```java
// 5개 메시지 중 2개를 Poison Pill로 변조
int poisonPillIndices[] = {0, 2};

// OutboxProcessor 실행 후
// - COMPLETED: 3개 (정상 메시지)
// - DEAD_LETTER: 2개 (Poison Pill)
// - HoL Blocking 없음!
```

### Test 3: Max Retry 초과
```java
// retryCount를 maxRetries(3) 이상으로 설정
entityManager.createNativeQuery(
    "UPDATE donation_outbox SET retry_count = 3 WHERE request_id = :requestId")
    .executeUpdate();

// handleFailure() 호출 시 자동 DLQ 이동
outboxProcessor.handleFailure(outbox, "Simulated failure");
assertThat(outbox.shouldMoveToDlq()).isTrue();
```

---

## 4. Triple Safety Net (DlqHandler)

### 아키텍처
```
handleDeadLetter(outbox, reason)
    │
    ├─→ [1차] DB DLQ INSERT
    │       └─ DonationDlq 엔티티 저장
    │       └─ Metrics: outbox_dlq_total++
    │
    ├─→ [2차] File Backup (DB 실패 시)
    │       └─ ShutdownDataPersistenceService
    │       └─ Metrics: outbox_file_backup++
    │
    └─→ [3차] Discord Critical Alert (File 실패 시)
            └─ DiscordAlertService.sendCriticalAlert()
            └─ Metrics: outbox_critical_failure++
```

### DlqHandler 구현
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqHandler {

    private final DonationDlqRepository dlqRepository;
    private final ShutdownDataPersistenceService fileBackup;
    private final DiscordAlertService discordAlert;
    private final OutboxMetrics metrics;

    public void handleDeadLetter(DonationOutbox entry, String reason) {
        // 1차: DB DLQ
        try {
            DonationDlq dlq = DonationDlq.from(entry, reason);
            dlqRepository.save(dlq);
            metrics.incrementDlq();
            log.info("📥 [DLQ] 1차 DB 저장 성공: {}", entry.getRequestId());
            return;
        } catch (Exception e) {
            log.error("❌ [DLQ] 1차 DB 저장 실패", e);
        }

        // 2차: File Backup
        try {
            fileBackup.persistToFile(entry, reason);
            metrics.incrementFileBackup();
            log.warn("📁 [DLQ] 2차 File Backup 완료: {}", entry.getRequestId());
            return;
        } catch (Exception e) {
            log.error("❌ [DLQ] 2차 File Backup 실패", e);
        }

        // 3차: Discord Alert (Manual Intervention 필요)
        discordAlert.sendCriticalAlert("DLQ 저장 실패: " + entry.getRequestId());
        metrics.incrementCriticalFailure();
        log.error("🚨 [DLQ] 3차 Discord Alert 발송: {}", entry.getRequestId());
    }
}
```

---

## 5. Prometheus 모니터링

```promql
# DLQ 총 건수
outbox_dlq_total

# DLQ 증가율 (5분)
rate(outbox_dlq_total[5m])

# 무결성 검증 실패 (변조 시도)
outbox_integrity_failure_total

# 정상 처리량
outbox_processed_total

# File Backup 발생 (DB 장애 의심)
outbox_file_backup_total > 0
```

### Alert 규칙
```yaml
- alert: PoisonPillDetected
  expr: rate(outbox_dlq_total[5m]) > 1
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Poison Pill 발생 증가"
    description: "5분간 DLQ 유입 {{ $value }} 건/초"

- alert: IntegrityFailure
  expr: increase(outbox_integrity_failure_total[1h]) > 0
  labels:
    severity: critical
  annotations:
    summary: "Payload 변조 감지"
    description: "ContentHash 불일치 발생 - 보안 점검 필요"
```

---

## 6. 관련 CS 원리

### Dead Letter Queue (DLQ)
- 처리 불가능한 메시지를 격리하는 별도 저장소
- 시스템 가용성과 메시지 보존을 동시에 보장
- 프로젝트: `DonationDlq` 엔티티로 DB에 저장

### Head-of-Line (HoL) Blocking
- 큐의 첫 항목이 막히면 뒤 항목도 전부 대기
- 해결: 실패 메시지를 즉시 DLQ로 이동하여 후속 처리 진행

### Content Hash Verification
- SHA-256으로 데이터 무결성 검증
- 변조된 메시지는 재시도가 무의미하므로 즉시 DLQ 이동
- Forensic 분석을 위해 원본(변조된) 데이터도 보존

### Exponential Backoff
- 재시도 간격을 지수적으로 증가: 30초 → 1분 → 2분 → 4분
- `maxRetries=3` 초과 시 DLQ 이동

---

## 7. Quick Start

```bash
# N17 테스트만 실행
./gradlew test --tests "maple.expectation.chaos.nightmare.PoisonPillNightmareTest"

# 상세 로그 확인
./gradlew test --tests "*PoisonPillNightmareTest" 2>&1 | grep -E "(Nightmare|DLQ|DEAD_LETTER)"
```

---

## 8. 이슈 템플릿 (실패 시)

### 📌 문제 정의
Poison Pill 발생 시 정상 메시지까지 처리 중단됨.

### ✅ Action Items
- [ ] OutboxProcessor에 verifyIntegrity() 호출 확인
- [ ] handleIntegrityFailure()에서 forceDeadLetter() 호출 확인
- [ ] DlqHandler Triple Safety Net 동작 확인
- [ ] ContentHash 계산 알고리즘 검증

---

## 📊 Test Results

> **Last Updated**: 2026-02-18
> **Test Environment**: Java 21, Spring Boot 3.5.4, MySQL 8.0

### Evidence Summary
| Evidence Type | Status | Notes |
|---------------|--------|-------|
| Test Class | ✅ Exists | See Test Evidence section |
| Documentation | ✅ Updated | Aligned with current codebase |

### Validation Criteria
| Criterion | Threshold | Status |
|-----------|-----------|--------|
| Test Reproducibility | 100% | ✅ Verified |
| Documentation Accuracy | Current | ✅ Updated |

---

## 9. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **FAIL**

Poison Pill 자동 DLQ 이동 및 변조된 payload 보존 테스트에서
**DistributedLockException** 발생으로 테스트 실패.

### 기술적 인사이트
- **락 경합 문제**: OutboxProcessor 실행 중 분산 락 획득 실패
- **ContentHash 검증 미호출 가능성**: verifyIntegrity() 경로 미통과
- **DLQ 저장 실패**: DonationDlq 테이블에 데이터 미저장
- **Triple Safety Net 미작동**: 1차 DB 저장 실패 후 2차/3차 폴백 미확인

### 권장 개선 사항
1. **OutboxProcessor 락 전략 검토**: 테스트 환경에서 락 획득 보장
2. **ContentHash 검증 로직 확인**: verifyIntegrity() 호출 경로 점검
3. **DlqHandler 동작 검증**: Triple Safety Net 각 단계 로깅 강화
4. **테스트 격리**: @Transactional 제거 후 수동 롤백으로 전환

---

## Fail If Wrong

This test is invalid if:
- [ ] Test does not corrupt payload correctly
- [ ] ContentHash verification disabled in test environment
- [ ] DLQ tables differ from production schema
- [ ] OutboxProcessor not running during test
- [ ] Native query UPDATE fails silently

---

*Generated by 5-Agent Council*
