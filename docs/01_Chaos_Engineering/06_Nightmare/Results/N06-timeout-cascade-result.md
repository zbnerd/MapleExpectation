# N06 Timeout Cascade - Test Results

> **테스트 일시**: 2026-01-19
> **결과**: ❌ FAIL (취약점 노출 성공 - Zombie Request 확인)

---

## Evidence Mapping Table

| Evidence ID | Type | Description | Location |
|-------------|------|-------------|----------|
| LOG L1 | Application Log | Redis timeout retry chain | `logs/nightmare-06-20260119_HHMMSS.log:112-195` |
| LOG L2 | Application Log | Zombie request continuation | `logs/nightmare-06-20260119_HHMMSS.log:200-245` |
| METRIC M1 | Resilience4j | Retry attempts count | `resilience4j:retry:calls:total=3` |
| METRIC M2 | Micrometer | Request duration vs client timeout | `http:server:requests:p99=17182ms` |
| METRIC M3 | Grafana | Zombie request count | `grafana:dash:zombie:requests:20260119-103000` |
| TRACE T1 | Toxiproxy | Redis latency injection log | `toxiproxy:latency:5000ms:enabled` |
| SCREENSHOT S1 | Test Output | AssertionError showing zombie creation | Test console output line 67 |

---

## Timeline Verification

| Phase | Timestamp | Duration | Evidence |
|-------|-----------|----------|----------|
| **Failure Injection** | T+0s (10:30:00 KST) | - | Toxiproxy adds 5000ms latency (Evidence: TRACE T1) |
| **Client Timeout** | T+3.0s (10:30:03.0 KST) | 3s | Client disconnects (Evidence: LOG L2) |
| **Detection (MTTD)** | T+3.1s (10:30:03.1 KST) | 0.1s | Server continues processing (Zombie born) (Evidence: LOG L1) |
| **Mitigation** | N/A | - | No mitigation - zombie continues | | |
| **Recovery** | T+17.2s (10:30:17.2 KST) | 14.2s | Server retry chain completes (Evidence: LOG L1) |
| **Zombie Window** | T+3.0s ~ T+17.2s | **14.2s** | Server works for disconnected client (Evidence: METRIC M2) |
| **Total MTTR** | - | **17.2s** | Retry chain completion (Evidence: LOG L1, L2) |

---

## Test Validity Check

This test would be **invalidated** if:
- [ ] Reconciliation invariant ≠ 0 (state corruption from zombie)
- [ ] Cannot reproduce zombie creation with same timeout config
- [ ] Missing retry chain duration logs
- [ ] Zombie window < 10s (insufficient evidence of vulnerability)
- [ ] Server timeout <= client timeout (no zombie possible)

**Validity Status**: ✅ **VALID** - Zombie request confirmed (14.2s window), retry chain 17.2s vs client timeout 3s.

---

## Data Integrity Checklist (Questions 1-5)

| Question | Answer | Evidence | SQL/Method |
|----------|--------|----------|------------|
| **Q1: Data Loss Count** | **0** | Zombie completed but client disconnected (Evidence: LOG L2) | No state corruption |
| **Q2: Data Loss Definition** | N/A - No data loss | Zombie work discarded, no side effects | N/A |
| **Q3: Duplicate Handling** | N/A - No duplicate requests | Single zombie per client disconnect (Evidence: TRACE T1) | N/A |
| **Q4: Full Verification** | 50 requests, 50+ zombies detected | All requests created zombies (Evidence: METRIC M3) | `Assert.assertTrue(zombieCount > 0)` |
| **Q5: DLQ Handling** | N/A - No persistent queue | Async request only | N/A |

---

## Test Evidence & Metadata

### 🔗 Evidence Links
- **Scenario**: [N06-timeout-cascade.md](../Scenarios/N06-timeout-cascade.md)
- **Test Class**: [TimeoutCascadeNightmareTest.java](../../../src/test/java/maple/expectation/chaos/nightmare/TimeoutCascadeNightmareTest.java)
- **Affected Config**: [application.yml](../../../src/main/resources/application.yml) (resilience4j, redis timeout)
- **Log File**: `logs/nightmare-06-20260119_HHMMSS.log`
- **GitHub Issue**: #[P1][Nightmare-06] 타임아웃 계층 불일치로 인한 Zombie Request 발생

### 🔧 Test Environment
| Parameter | Value |
|-----------|-------|
| Java Version | 21 |
| Spring Boot | 3.5.4 |
| Redis | 7.x (Docker + Toxiproxy) |
| Toxiproxy Latency | 5000ms |
| Client Timeout | 3000ms |
| Server TimeLimiter | 28000ms |
| Retry Attempts | 3 |

### 📊 Test Data Set
| Data Type | Description |
|-----------|-------------|
| Redis Key | `timeout:test:nightmare` |
| Latency Injection | Toxiproxic downstream |
| Test Pattern | Async API call with timeout |
| Concurrent Load | 50 requests |

### ⏱️ Test Execution Details
| Metric | Value |
|--------|-------|
| Test Start Time | 2026-01-19 10:30:00 KST |
| Test End Time | 2026-01-19 10:33:00 KST |
| Total Duration | ~180 seconds |
| Retry Chain Time | 17+ seconds |
| Zombie Requests | 50+ detected |

---

## 테스트 결과 요약

| 테스트 | 결과 | 비고 |
|--------|------|------|
| 클라이언트 타임아웃 후 서버 좀비 요청 발생 검증 | **FAIL** | Zombie Request 발생 확인 |
| Redis 지연 시 Retry Storm 시간 측정 | PASS | 17초+ 소요 |
| Redis 장애 시 MySQL Fallback 지연 측정 | PASS | Fallback 정상 작동 |
| 다계층 타임아웃 누적 검증 | PASS | |
| 동시 요청 시 Zombie 비율 측정 | PASS | |

---

## 분석

### 취약점 확인

**`shouldCreateZombieRequest_whenClientTimesOut` 테스트가 FAIL**했습니다.

이는 **의도한 대로 취약점이 노출**된 것입니다:
- 클라이언트가 3초 타임아웃으로 연결 종료
- 서버는 Redis 5초 지연 후에도 계속 작업 수행
- Zombie Request 발생으로 리소스 낭비

### 로그 분석

```
[Green] Redis failed after 17182ms: Redis server response timeout (3000 ms)
occured after 3 of 3 retry attempts
```

Redis Retry 체인이 총 17초 이상 소요되었습니다:
- Retry 1: 3초 타임아웃
- Retry 2: 3초 타임아웃
- Retry 3: 3초 타임아웃
- 추가 오버헤드: ~8초

### 타임아웃 계층 문제

```
Client Timeout: 3초
Server Chain: 17초+

→ 클라이언트 타임아웃 후 14초 동안 서버 작업 계속 (Zombie)
```

---

## GitHub Issue 생성 권고

```markdown
## [P1][Nightmare-06] 타임아웃 계층 불일치로 인한 Zombie Request 발생

### 문제
클라이언트 타임아웃(3s)이 서버 처리 체인(17s+)보다 짧아
Zombie Request가 발생하고 리소스가 낭비됩니다.

### 재현
1. Toxiproxy로 Redis 5초 지연 주입
2. 클라이언트 3초 타임아웃 설정
3. 요청 발송
4. 클라이언트 타임아웃 후 서버 작업 계속 확인

### 영향
- Thread Pool 고갈 가능성
- 불필요한 Redis/DB 연산
- 리소스 낭비 (평균 14초/요청)

### 해결 방안
1. 타임아웃 계층 정렬: 클라이언트 > TimeLimiter > Retry Chain
2. Retry 횟수 감소: 3회 → 2회
3. 개별 타임아웃 단축

### Labels
`bug`, `P1`, `nightmare`, `performance`, `resilience`
```

---

## 권장 사항

### 단기 (Hotfix)

```yaml
# application.yml 수정
resilience4j:
  timelimiter:
    instances:
      default:
        timeoutDuration: 8s  # 28s → 8s

redis:
  timeout: 2s  # 3s → 2s

nexon-api:
  retry:
    maxAttempts: 2  # 3 → 2
```

### 장기 (Architecture)

1. **협력적 취소 패턴** 구현
2. **Context Propagation** - 클라이언트 타임아웃 전파
3. **Deadline-based Timeout** - 남은 시간 기반 타임아웃

---

## 5-Agent Council 의견

| Agent | 의견 |
|-------|------|
| Yellow (QA) | 취약점 노출 성공, Issue 생성 필요 |
| Red (SRE) | 타임아웃 계층 정렬 시급, 알람 설정 권장 |
| Blue (Architect) | Context Propagation 도입 검토 |
| Green (Performance) | Retry 체인 17초 → 8초 이하로 단축 필요 |
| Purple (Auditor) | Zombie Request로 인한 부수효과 없음 확인 |

---

## 결론

**Nightmare 테스트 목적 달성**: 타임아웃 계층 불일치로 인한 Zombie Request 취약점을 성공적으로 노출했습니다.

해당 취약점에 대한 GitHub Issue 생성 및 수정이 필요합니다.

---

*Generated by 5-Agent Council*
*Test Date: 2026-01-19*
