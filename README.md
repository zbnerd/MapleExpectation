# Probabilistic Valuation Engine (codename: MapleExpectation)

> **7개 공통 인프라 모듈 + 성능 최적화 + 장애 격리** — 다른 서비스가 가져다 쓸 수 있는 구조로 설계된 백엔드

<div align="center">

![CI Pipeline](https://github.com/zbnerd/probabilistic-valuation-engine/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.4-6DB33F?logo=springboot)
![License](https://img.shields.io/badge/License-MIT-blue)

**RPS 965 | p50 95ms | p99 214ms | Error 0%** — [Load Test Report](docs/05_Reports/04_06_Load_Tests/LOAD_TEST_REPORT_20260126_V4_ADR_REFACTORING.md)

</div>

---

## AI SRE: Policy-Guarded Autonomous Loop

> **"AI가 장애를 감지 → 분석 → 제안 → 실행 → 감사하는 자율 운영 루프"**

MapleExpectation은 AI SRE(System Reliability Engineering)를 구현하여 **자동 장애 탐지, 분석, 완화**를 사전 정의된 정책 기반으로 수행합니다. 이 시스템은 인간의 감시 없이도 운영 환경에서 안전하게 동작하도록 설계되었습니다.

### 동작 방식 (Monitoring → Detection → Analysis → Proposal → Execution → Audit)

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────────┐
│   Monitoring    │───▶│  Detection   │───▶│   Analysis     │
│ • Prometheus    │     │ • Threshold   │     │ • AI SRE       │
│ • Grafana Dash  │     │ • Z-score     │     │ • MitigationPlan│
│ • 15s 주기      │     │ • Hybrid     │     │ • Confidence    │
└─────────────────┘     └──────────────┘     └─────────────────┘
         ↓                      ↓                      ↓
┌─────────────────┐     ┌──────────────┐     ┌─────────────────┐
│   Proposal      │◀─── │  De-dup      │◀─── │   Discord       │
│ • Action A, B   │     │ • 1h memory  │     │ • Incident ID   │
│ • Risk Level    │     │ • Signature  │     │ • Evidence     │
│ • Rollback      │     │ • Track      │     │ • Action Button │
└─────────────────┘     └──────────────┘     └─────────────────┘
         ↓                      ↓                      ↓
┌─────────────────┐     ┌──────────────┐     ┌─────────────────┐
│   Execution     │───▶│   Audit      │───▶│   Verification  │
│ • RBAC Check    │     │ • Pre/Post   │     │ • SLO Recovery  │
│ • Whitelist     │     │ • Timestamp  │     │ • Auto-Rollback │
│ • Precondition  │     │ • Evidence    │     │ • 2~5분 검증    │
└─────────────────┘     └──────────────┘     └─────────────────┘
```

### Safety Rails: 4중 보호 장치

AI SRE 시스템은 **4중 안전장치**를 통해 무분별한 자동 실행을 방지합니다:

#### 1. **Policy Engine** - 실행 전 정책 검증
```java
// 모든 액션은 정책 엔진을 통과해야 실행됨
policyEngine.validate(action, incidentContext);
// 검증 항목: Risk Level, Precondition, RBAC, Bounds
```

#### 2. **Whitelist** - 허용된 액션만 실행 가능
```yaml
# application.yml
app:
  mitigation:
    allowed-actions:
      - "hikari-pool-size-adjustment"     # DB 커넥션 풀 크기 조정
      - "admission-control-tuning"         # 입장 통제 강화
      - "cache-ttl-extension"              # 캐시 TTL 연장
      # 금지된 액션: 서비스 재시작, 데이터베이스 변경
```

#### 3. **RBAC (Role-Based Access Control)** - 역할 기반 권한
```java
// Discord 인터랙션은 @sre 역할만 허용
@RequiresRole("sre")
public ResponseEntity<?> executeAction(@RequestBody DiscordActionRequest request) {
    // 실행: @sre 역할 보유자만 자동 완화 버튼 클릭 가능
}
```

#### 4. **Audit Log** - 모든 실행 감사 추적
```json
{
  "incidentId": "INC-29506523",
  "actionId": "A1",
  "preState": {"pool_size": 30, "pending": 41},
  "postState": {"pool_size": 40, "pending": 5},
  "executedBy": "@sre-bot",
  "timestamp": "2026-02-06T16:22:20Z",
  "evidence": "PromQL: hikaricp_connections_active=30/30"
}
```

### 실제 인시던트 사례: INC-29506523

**시간**: 2026-02-06 16:22:20
**문제**: MySQL 커넥션 풀 100% 포화 → HikariCP 대기열 41개

#### 📊 Detection (탐지)
```
Prometheus 쿼리 실행:
- hikaricp_connections_active = 30/30 (100% utilized) ❌
- hikaricp_connections_pending = 41 > 10 threshold ⚠️
- Z-score = 4.2 > 3.0 threshold ❌
→ AnomalyEvent 생성
```

#### 🤖 Analysis (분석)
**AI 분 결과 (Z.ai GLM-4.7):**
```
Hypothesis 1 (HIGH): DB Pool saturation → connection leak detected
Hypothesis 2 (MEDIUM): Sudden traffic spike → pool too small

Proposed Action A1: Increase Hikari pool 30→40 [RISK: LOW]
- Precondition: pending>10 for 2min AND p95>200ms ✅
- Rollback: pool>35 for 5min OR error-rate>3% for 5min
```

#### 🔧 Action (실행)
```bash
Discord: /approve INC-29506523-A1
RBAC Check: @sre role OK
Whitelist: hikari-pool-size-adjustment OK
Precondition: pending=41 > 10 ✅
Execution: dataSource.setMaximumPoolSize(40)
Result: pending=5 (87% 개선)
```

#### ✅ Result (결과)
```
이전 상태: p99=2.1s, error-rate=0.5%
2분 후:    p99=180ms, error-rate=0.1%
SLO 회복: ✅ 안정화
```

### 관련 이슈 & 문서

| 이슈 | 내용 | 상태 |
|------|------|------|
| [#310](https://github.com/zbnerd/MapleExpectation/issues/310) | Redis Lock migration 계획 | ✅ Closed |
| [#311](https://github.com/zbnerd/MapleExpectation/issues/311) | Discord Auto-Mitigation Safety Rails | ✅ Closed |
| [#312](https://github.com/zbnerd/MapleExpectation/issues/312) | Signal Deduplication 구현 | ⏳ In Progress |
| [#313](https://github.com/zbnerd/MapleExpectation/issues/313) | AI Response Validation 강화 | ⏳ In Progress |
| [#316](https://github.com/zbnerd/MapleExpectation/issues/316) | Mitigation Audit 확장 | ⏳ In Progress |

📄 [AI SRE 운영 증거 체계](docs/CLAIM_EVIDENCE_MATRIX.md)
📄 [AI SRE 구현 가이드](docs/03_Technical_Guides/monitoring-copilot-implementation.md)

---

## What This Is

200~300KB JSON을 처리하는 연산 백엔드입니다. 일반 API보다 큰 페이로드를 안정적으로 처리하기 위해, **반복되는 인프라 패턴을 공통 모듈로 추출**하고, 각 모듈이 독립적으로 재사용 가능한 구조로 설계했습니다.

**도메인:** MMORPG economy simulation (예시 도메인). 핵심은 도메인이 아니라 **공통 인프라 설계 + 장애 격리 + 데이터 생존**입니다.

---

## Core Problem & Solution

| 문제 | 가설 | 해결 | 성과 |
|:---:|:---|:---|:---:|
| **서비스마다 다른 예외 처리** | "공통 실행기를 만들면 에러 추적이 가능해진다" | **[LogicExecutor](#1-logicexecutor--cross-cutting-실행-프레임워크)**: 6가지 실행 패턴 | **35+ 서비스 적용** |
| **Redis 장애 → 전체 중단** | "장애 격리를 모듈화하면 서비스별 대응 불필요" | **[ResilientLockStrategy](#2-resilientlockstrategy--장애-격리-락-전략)**: Redis→MySQL 자동 전환 | **장애 전파 차단** |
| **Cache Stampede** | "DB 호출을 1회로 제한하면 폭주를 막을 수 있다" | **[TieredCache + Singleflight](#3-tieredcache--3계층-캐시--singleflight)**: 3계층 캐시 | **DB 쿼리 ≤ 10%** |
| **데이터 유실** | "3중 안전망이면 유실을 구조적으로 방지할 수 있다" | **[Outbox](#6-transactional-outbox--데이터-생존)**: DB→File→Alert | **210만 건 유실 0** |
| **비용 vs 성능** | "'늘리는 것'이 아니라 최적점을 찾아야 한다" | **[비용 분석](#비용-성능-최적점-분석-n23)**: wrk + RPS/$ 산식 | **t3.large 최적** |

---

## 공통 인프라 모듈 (Platform Components)

> 7개 모듈을 직접 설계하고, 각각 독립적으로 재사용 가능한 구조로 구현했습니다.

### 1. LogicExecutor — Cross-Cutting 실행 프레임워크

**문제:** 35+ 서비스에서 try-catch 패턴이 제각각 → 장애 시 에러 추적 불가

```java
// Before: 서비스마다 다른 try-catch
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
    return null;
}

// After: LogicExecutor — 자동 메트릭/로깅/에러 분류
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", id)
);
```

**6가지 실행 패턴:** `execute`, `executeVoid`, `executeOrDefault`, `executeWithRecovery`, `executeWithFinally`, `executeWithTranslation`

**설계 철학: "다른 개발자가 실수 없이 쓸 수 있는 API"**

- **6가지 실행 패턴 분리**: `execute()`, `executeOrDefault()`, `executeWithRecovery()` 등
  상황별로 명확히 구분하여 개발자가 헷갈리지 않게 함
- **TaskContext 강제**: 모든 실행에 도메인, 작업명, 식별자를 강제하여
  구조화된 로그 자동 생성 (디버깅 시간 50% 단축)
- **예외 변환 분리**: `executeWithTranslation()`으로 기술적 예외(IOException)를
  도메인 예외로 변환하는 책임을 명확히 함

<img width="756" height="362" alt="LogicExecutor" src="https://github.com/user-attachments/assets/a43b8f43-fd49-489c-ab24-4c91a27584f5" />

---

### 2. ResilientLockStrategy — 장애 격리 락 전략

**문제:** Redis 장애 시 락을 사용하는 모든 서비스가 중단

**해결:** Redis 실패 → MySQL Named Lock fallback + CircuitBreaker 자동 전환

```
정상:     Redis Lock (빠름)
          ↓ Redis 장애 감지
자동 전환: MySQL Named Lock (안전)
          ↓ CircuitBreaker Half-Open
자동 복구: Redis Lock (빠름)
```

**3-tier 예외 분류 정책** (Issue #130에서 도출):
- **인프라 예외** (RedisException, RedisTimeoutException 등) → MySQL fallback
- **비즈니스 예외** (ClientBaseException, CompletionException 래핑 포함) → 즉시 전파, fallback 없음
- **알 수 없는 예외** (NPE, IllegalArgumentException 등) → 보수적 처리, fallback 없음


**Marker Interface 분류:**
- `CircuitBreakerIgnoreMarker`: 비즈니스 예외 (4xx) — 서킷 상태 무영향
- `CircuitBreakerRecordMarker`: 시스템 예외 (5xx) — 실패로 기록

---

### 3. TieredCache — 3계층 캐시 + Singleflight

```
L1 HIT: < 5ms   (Caffeine 로컬 메모리)
L2 HIT: < 20ms  (Redis)
MISS:   Singleflight로 1회만 DB 호출 → 나머지 대기 후 결과 공유
```

**효과:** Cache Stampede 완전 방지, DB 쿼리 비율 ≤ 10%

<img width="728" height="523" alt="TieredCache" src="https://github.com/user-attachments/assets/b3ad5614-3ef7-4cda-b29f-cdcdec44dc9e" />

---

### 4. Rate Limiting 3-tier — API 보호

| 계층 | 역할 | 설명 |
|------|------|------|
| **Facade** | 진입점 | 요청 수신 + 제한 여부 판단 위임 |
| **Service** | 정책 관리 | 사용자/API별 제한 정책 적용 |
| **Strategy** | 실행 | 고정 윈도우 / 슬라이딩 윈도우 / 토큰 버킷 교체 가능 |

계층별 독립 정책 — 서비스 팀이 자신의 API에 맞는 전략만 선택하면 됨

---

### 5. 나머지 공통 모듈

| 모듈 | 역할 | 핵심 설계 |
|------|------|----------|
| **IdempotencyGuard** | SETNX 기반 멱등성 보장 | PROCESSING → COMPLETED 상태 머신, TTL 관리 |
| **PartitionedFlushStrategy** | 분산 락 + 보상 트랜잭션 | 락 실패 시 데이터 복원, 부분 실패 시 실패 항목만 복원 |
| **WriteBackBuffer** | 비동기 쓰기 버퍼 (MQ 패턴) | publish → consume → ACK/NACK → DLQ → Retry |

---

### 6. Transactional Outbox — 데이터 생존

**Triple Safety Net (3중 안전망):**
1. **1차:** DB Dead Letter Queue
2. **2차:** File Backup (DB 실패 시)
3. **3차:** Discord Critical Alert (최후의 안전망)

<img width="541" height="421" alt="Outbox" src="https://github.com/user-attachments/assets/16b60110-3d1e-46be-801d-762d8c151644" />

**왜 이 설계가 금융 시스템에 중요한가:**
- **무결성**: Content Hash(SHA-256)로 데이터 변조 감지 → 주문/정산 데이터 위변조 방지
- **멱등성**: requestId UNIQUE 제약으로 중복 처리 방지 → 결제 중복 승인 방지
- **감사 가능성**: DLQ에 실패 원인과 payload 보존 → 규제 기관 제출용 증거 확보
- **At-Least-Once**: 동일 트랜잭션에 Outbox 저장 → 메시지 유실 방지 (재무역 조회 불가)

**검증 (N19):** 외부 API 6시간 장애 → 2,160,000개 이벤트 누적 → 복구 후 99.997% 자동 재처리, 수동 개입 0
📄 [Recovery Report](docs/05_Reports/04_07_Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)

---

### 7. Graceful Shutdown — 4단계 순차 종료

```
Phase 1: 새 요청 거부 (Admission Control)
Phase 2: 진행 중 작업 완료 대기 (30s)
Phase 3: 버퍼 플러시 (WriteBackBuffer → DB)
Phase 4: 리소스 해제 (Connection Pool, Redis)
```

<img width="362" height="689" alt="GracefulShutdown" src="https://github.com/user-attachments/assets/70ce9987-1c96-47be-801d-762d8c151644" />

---

## 트러블슈팅 경험

### Issue #130: 비즈니스 예외가 인프라 장애로 오분류

**증상:** `CharacterNotFoundException`(비즈니스 예외)이 발생했는데 MySQL fallback이 동작

**원인 분석:**
```
비동기 실행 중 비즈니스 예외 발생
  → CompletionException으로 래핑됨
    → 예외 분류 로직이 "인프라 장애"로 판단
      → 불필요한 MySQL fallback → MySQL 부하 증가
```

**해결:**
1. 3-tier 예외 분류 정책 설계 (인프라 / 비즈니스 / 알 수 없음)
2. `CompletionException` unwrap 로직 추가
3. 12개 회귀 테스트 작성 (ResilientLockStrategyExceptionFilterTest)

```java
// 테스트: 비즈니스 예외가 CompletionException으로 래핑되어도 fallback 발동하지 않음
setupExecutorFallbackPassthrough();     // Layer 1: LogicExecutor
setupCircuitBreakerPassthrough();        // Layer 2: CircuitBreaker
setupRedisExecuteWithLockPassthrough();  // Layer 3: Redis Lock

resilientLockStrategy.executeWithLock(KEY, WAIT, LEASE, () -> {
    throw new CompletionException(new ClientBaseException("Not Found"));
});

verify(mysqlLockStrategy, never()).executeWithLock(...);  // fallback 미발동 확인
```

**배움:** "예외 분류는 설계의 영역이지, catch-all로 해결할 문제가 아니다."

📄 [Postmortem Report](docs/05_Reports/04_08_Refactor/)

---

## 성능 분석

### 로컬 부하 테스트 결과

| 메트릭 | 100 conn | 200 conn |
|--------|----------|----------|
| **RPS** | **965** | 719 |
| **p50** | **95ms** | 275ms |
| **p99** | **214ms** | N/A |
| **Error Rate** | **0%** | **0%** |

> **참고:** 요청당 200~300KB 페이로드. 이 수치는 로컬 환경에서 wrk로 측정한 벤치마크 결과입니다. 실제 운영 경험은 아니며, 장애 시나리오 검증과 성능 병목 파악을 목적으로 했습니다.

📄 [Load Test Report](docs/05_Reports/04_06_Load_Tests/LOAD_TEST_REPORT_20260126_V4_ADR_REFACTORING.md)

### 최적화 성과

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| JSON 압축 | 350KB | 17KB | **95%** |
| 동시 요청 처리 | 5.3s | 1.1s | **4.8x** |
| DB 인덱스 튜닝 | 0.98s | 0.02s | **50x** |
| 메모리 사용량 | 300MB | 30MB | **90%** |

### 비용-성능 최적점 분석 (N23)

| 인스턴스 | 월 비용 | RPS | $/RPS | 판단 |
|---------|--------|-----|-------|------|
| t3.small | $15 | 965 | $0.0155 | 기준 |
| t3.medium | $30 | 1,928 | $0.0156 | 선형 확장 |
| **t3.large** | **$45** | **2,989** | **$0.0151** | **최적** ✅ |
| t3.xlarge | $75 | 3,058 | $0.0245 | -37% 비효율 |

**의사결정:** 비용 대비 효율이 꺾이는 지점을 찾아 최적점 선택. "늘리는 것"이 답이 아님을 데이터로 증명.

📄 [Cost Performance Report](docs/05_Reports/04_02_Cost_Performance/COST_PERF_REPORT_N23.md)

---

## 모니터링 + 알림

### 구현 체계

| 계층 | 구현 | 역할 |
|------|------|------|
| **메트릭 수집** | Prometheus + Micrometer | CircuitBreaker 상태, Lock 획득 시간, Queue 적체량 |
| **시각화** | Grafana | SLO 대시보드 (Latency, Traffic, Errors, Saturation) |
| **알림** | Discord | 장애 등급별 채널 분리, 증거(PromQL 결과값) 포함 |
| **자동 완화** | Circuit Breaker | 실패율 임계치 초과 시 자동 차단 |

### 자동 장애 완화 사례 (N21)

| 단계 | 시간 | 이벤트 |
|------|------|--------|
| **탐지** | 0s | `hikaricp_connections_active = 30/30` (100% 포화) |
| **자동 차단** | 30s | Circuit Breaker OPEN (실패율 61% > 임계치 50%) |
| **자동 복구** | 2m | Half-Open 전환 → 성공률 확인 |
| **안정화** | 4m | p99 21초 → 3초 복구, 운영자 대응 시간 0분 |

📄 [Incident Report N21](docs/05_Reports/04_05_Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md)

---

## Chaos Engineering: Nightmare Tests

24개 극한 시나리오로 시스템 복원 탄력성을 검증했습니다.

| 테스트 | 시나리오 | 결과 | 발견 및 해결 |
|--------|---------|------|-------------|
| **N01** | Thundering Herd (Cache Stampede) | **PASS** | Singleflight 효과적 작동 |
| **N02** | Deadlock Trap | **FAIL→FIX** | Lock Ordering 미적용 → 알파벳순 테이블 접근 |
| **N03** | Thread Pool Exhaustion | **FAIL→FIX** | CallerRunsPolicy 블로킹 → AbortPolicy + Bulkhead |
| **N04** | Connection Vampire | **CONDITIONAL** | @Transactional + .join() → 트랜잭션 범위 분리 |
| **N05** | Celebrity Problem (Hot Key) | **PASS** | TieredCache + Singleflight |
| **N06** | Timeout Cascade | **FAIL→FIX** | Zombie Request → 타임아웃 계층 정렬 |
| **N19** | Outbox Replay | **PASS** | 210만 이벤트 유실 0 |
| **N21** | Auto Mitigation | **PASS** | MTTD 30s, MTTR 4m |
| **N23** | Cost Performance | **PASS** | 비용 최적점 도출 |

### N02: Deadlock — 문제 발견 및 해결

**문제:** Transaction A(TABLE_A→TABLE_B)와 Transaction B(TABLE_B→TABLE_A)가 교차 락 → 100% Deadlock

**해결:**
```java
// Lock Ordering — 알파벳순 테이블 접근으로 순환 대기 제거
@Transactional
public void updateWithLockOrdering(Long equipmentId, Long userId) {
    equipmentRepository.findByIdWithLock(equipmentId);  // e < u
    userRepository.findByIdWithLock(userId);
}
```

### N03: Thread Pool Exhaustion — 문제 발견 및 해결

**문제:** `CallerRunsPolicy`로 메인 스레드 2010ms 블로킹 → API 응답 불가

**해결:**
```java
// AbortPolicy + Resilience4j Bulkhead로 격리
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

@Bulkhead(name = "asyncService", type = Bulkhead.Type.THREADPOOL)
public CompletableFuture<String> asyncMethod() { ... }
```

### N06: Timeout Cascade — 문제 발견 및 해결

**문제:** 클라이언트 타임아웃(3s) < 서버 처리 체인(17s+) → 클라이언트 종료 후 서버가 14초 동안 무의미한 작업 지속

**해결:**
```yaml
# 타임아웃 계층 정렬: 클라이언트 > TimeLimiter > Retry Chain
resilience4j.timelimiter.instances.default.timeoutDuration: 8s
redis.timeout: 2s
nexon-api.retry.maxAttempts: 2
```

---

## Architecture

<img width="6672" height="4608" alt="image" src="https://github.com/user-attachments/assets/27d161ae-b925-4c20-a2f7-d549ee944527" />

### 비동기 파이프라인 (AOP+Async)

**Two-Phase Snapshot:**
| Phase | 목적 | 로드 데이터 |
|-------|------|------------|
| LightSnapshot | 캐시 키 생성 | 최소 필드 (ocid, fingerprint) |
| FullSnapshot | 계산 (MISS 시만) | 전체 필드 |

<img width="525" height="551" alt="AsyncPipeline" src="https://github.com/user-attachments/assets/792c224c-7fc6-41f7-82ba-d43438bede85" />

### Admission Control (Backpressure)

시스템 과부하 시 **503 + Retry-After**로 클라이언트에 재시도 안내

| 항목 | 값 |
|------|-----|
| Queue Capacity | 100 |
| Rejected Policy | AbortPolicy |
| Retry-After | 60s |

<img width="771" height="503" alt="Backpressure" src="https://github.com/user-attachments/assets/adf69973-1c96-47b7-9750-3aa55b4e64d7" />

### DP Calculator (Kahan Summation)

부동소수점 오차 누적 방지를 위한 보정 합산 알고리즘

<img width="239" height="549" alt="DPCalculator" src="https://github.com/user-attachments/assets/ef52dd64-4b6c-473f-a730-1d6bec86bf90" />

---

## Testing

### 테스트 구성

| 카테고리 | 규모 | 설명 |
|----------|------|------|
| Unit Tests | 90+ 파일 | Mock 기반 빠른 검증 |
| Integration Tests | 20+ 파일 | Testcontainers (MySQL/Redis) |
| Chaos Tests | 24 시나리오 | Nightmare N01-N24 |
| **Total** | **498 @Test** | |

### CI/CD

```
CI Gate (PR)              Nightly (Daily)
    │                          │
    ▼                          ▼
  fastTest (3-5분)        Full Test (30-60분)
  Unit Only               + Chaos N01-N24
                          + Sentinel Failover
```

```bash
./gradlew test -PfastTest    # CI 수준
./gradlew test               # Nightly 수준
```

---

## QuickStart

```bash
docker-compose up -d                                          # MySQL, Redis
./gradlew bootRun --args='--spring.profiles.active=local'     # 앱 시작
curl "http://localhost:8080/api/v3/characters/강은호/expectation"
```

---

## Tech Stack

| 분류 | 기술 |
|------|------|
| **Core** | Java 21, Spring Boot 3.5.4 |
| **Database** | MySQL 8.0, JPA/Hibernate |
| **Cache** | Caffeine (L1), Redis/Redisson 3.27.0 (L2) |
| **Resilience** | Resilience4j 2.2.0 (Circuit Breaker, Retry, TimeLimiter) |
| **Testing** | JUnit 5, Testcontainers, wrk |
| **Monitoring** | Prometheus, Grafana, Discord Alert |

---

## Documents

| 문서 | 설명 |
|------|------|
| [**PORTFOLIO.md**](PORTFOLIO.md) | 포트폴리오 요약 (공통 모듈 + 트러블슈팅 + 성능) |
| [Architecture](docs/00_Start_Here/architecture.md) | 시스템 아키텍처 다이어그램 |
| [Chaos Tests](docs/02_Chaos_Engineering/06_Nightmare/) | N01-N24 Nightmare 시나리오 |
| [ADRs](docs/adr/) | Architecture Decision Records |
| [Refactoring Reports](docs/05_Reports/04_08_Refactor/) | 이슈 해결 및 리팩토링 기록 |
| [N19 Recovery](docs/05_Reports/04_07_Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md) | Outbox Replay 복구 리포트 |
| [N21 Incident](docs/05_Reports/04_05_Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md) | 자동 완화 사고 리포트 |
| [N23 Cost/Perf](docs/05_Reports/04_02_Cost_Performance/COST_PERF_REPORT_N23.md) | 비용-성능 최적점 분석 |
| [Load Test](docs/05_Reports/04_06_Load_Tests/LOAD_TEST_REPORT_20260126_V4_ADR_REFACTORING.md) | wrk 부하 테스트 결과 |

---

## Development Journey

> 집중 개발 3개월 | 230 커밋 | 27,799 LoC | 498 테스트

```
Feature 개발:    ████████████████████  33개 (34%)
Refactoring:    ████████████████████  32개 (33%)
Performance:    ████████              13개 (13%)
Test:           ██████████            16개 (16%)
```

---

## License

MIT License

---

*Last Updated: 2026-02-17*
