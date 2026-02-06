# Claim-Evidence Matrix: AI SRE 운영 증거 체계

> **"누가/어떻게/무엇을 근거로/어떤 변경을 했는지"가 감사 가능하게 재현됩니다**

이 문서는 AI SRE 시스템의 **주장(Claim) ↔ 코드 ↔ 증거(Evidence)** 매핑을 제공합니다. 면접관/운영자가 시스템의 신뢰성을 한 번에 확인할 수 있도록 합니다.

## 목적

면접관/운영자가 다음을 확인할 수 있습니다:
1. **시스템이 주장하는 바가 무엇인가?** (Claims)
2. **그 주장을 뒷받침하는 코드가 어디에 있는가?** (Code)
3. **실제 운영에서 증거가 되는 사례가 있는가?** (Evidence)

---

## Claim-Evidence Matrix

### C-OPS-01: Incident alert는 dedup된 Top Signals + evaluated evidence를 포함한다

**Claim:**
중복 제거된 상위 시그널과 평가된 즠거값(타임스탬프 포함)을 Discord 알림에 포함한다.

**Code:**
- `src/main/java/.../monitoring/copilot/service/MonitoringPipelineService.java`
  - `deduplicateSignals()` - metric + labels 기준 dedup
- `src/main/java/.../monitoring/copilot/client/PrometheusClient.java`
  - `evaluateQuery()` - PromQL 실행 + 결과값 반환
- `src/main/java/.../monitoring/copilot/notifier/DiscordNotifier.java`
  - `sendIncidentAlert()` - 평가된 즠거 포함

**Evidence:**
- Discord incident `INC-29506523` (2026-02-06)
  - `hikaricp_connections_active` = 30 @ 16:22:20Z
  - `hikaricp_connections_pending` = 41 @ 16:22:20Z
- GitHub issue [#312](https://github.com/zbnerd/MapleExpectation/issues/312): Dedup 구현

**Status:** ⏳ In Progress

---

### C-OPS-02: Detector는 룰/통계 기반으로 재현 가능하다 (LLM 비의존)

**Claim:**
이상 탐지는 규칙/통계 기반으로 재현 가능하며, LLM에 의존하지 않는다.

**Code:**
- `src/main/java/.../monitoring/copilot/client/PrometheusClient.java`
  - `queryAnomalousMetrics()` - PromQL 쿼리로 임계치 초과 검출
- `src/main/java/.../monitoring/copilot/service/MonitoringPipelineService.java`
  - `detectAnomalies()` - 통계 기반 이상 탐지 (Z-score, Moving Average)

**Evidence:**
- Load Test #266 ADR - Prometheus 쿼리로 18개 Nightmare 시나리오 탐지
- Manual reproduction: PromQL `hikaricp_connections_active > 28` 재현 가능

**Status:** ✅ Verified

---

### C-OPS-03: LLM 출력은 구조화(JSON) + 과장 금지 가드레일을 통과해야 한다

**Claim:**
LLM 출력은 JSON 구조화되어 있으며, 과장/허위 사실이 포함되지 않도록 검증된다.

**Code:**
- `src/main/java/.../monitoring/copilot/service/AiSreService.java`
  - `parseMitigationPlanJson()` - Markdown 코드 블록 제거 + JSON 파싱
  - `validateMitigationPlan()` - risk/rollback precondition 필수 검증
- `src/main/java/.../monitoring/copilot/dto/MitigationPlan.java`
  - 구조화된 출력 형식

**Evidence:**
- Discord incident `INC-29506523` - JSON 파싱 성공
- GitHub PR [#309](https://github.com/zbnerd/MapleExpectation/pull/309) - ChatGPT 통합

**Status:** ✅ Verified

---

### C-OPS-04: 모든 제안 액션은 risk/rollback/precondition을 가진다

**Claim:**
AI가 제안하는 모든 액션은 위험도, 롤백 계획, 선행 조건을 포함한다.

**Code:**
- `src/main/java/.../monitoring/copilot/dto/ProposedAction.java`
  - `risk`, `rollbackPlan`, `preconditions` 필드
- `src/main/java/.../monitoring/copilot/service/AiSreService.java`
  - `generateMitigationPlan()` - AI prompt에 risk/rollback 요구

**Evidence:**
- Discord incident `INC-29506523` - A1 액션:
  - Risk: MEDIUM
  - Preconditions: pending>TH for 2m AND p95>200ms
  - Rollback: revert to 30 if error-rate ↑ OR DB CPU > 80% for 5m
- GitHub issue [#311](https://github.com/zbnerd/MapleExpectation/issues/311) - Auto-Mitigation safety rails

**Status:** ⏳ In Progress

---

### C-OPS-05: AUTO-MITIGATE는 RBAC + signature + whitelist + bounds를 만족해야만 실행된다

**Claim:**
Discord 버튼으로 실행되는 액션은 RBAC, 서명 검증, 화이트리스트, 범위 제한을 만족할 때만 실행된다.

**Code:**
- `src/main/java/.../monitoring/copilot/controller/DiscordInteractionController.java`
  - `verifySignature()` - Ed25519 서명 검증
- `src/main/java/.../monitoring/copilot/service/MitigationExecutionService.java`
  - `validateAction()` - whitelist + bounds + RBAC
  - `checkPreconditions()` - metric gating

**Evidence:**
- GitHub issue [#311](https://github.com/zbnerd/MapleExpectation/issues/311) - Security 섹션:
  - Discord signature verification (replay window 5m)
  - RBAC: @sre role only
  - Action whitelist: lock pool size 20~50 범위만
  - Preconditions: pending>TH for 2m

**Status:** ⏳ In Progress

---

### C-OPS-06: 각 실행은 MitigationAudit에 pre/post state와 evidence를 남긴다

**Claim:**
모든 자동 완화 실행은 사전/사후 상태와 즠거 링크를 감사 로그에 남긴다.

**Code:**
- `src/main/java/.../monitoring/copilot/entity/MitigationAudit.java`
  - `preState`, `postState`, `evidenceLinks` 필드
- `src/main/java/.../monitoring/copilot/service/MitigationExecutionService.java`
  - `executeWithAudit()` - 감사 로그 기록

**Evidence:**
- GitHub issue [#311](https://github.com/zbnerd/MapleExpectation/issues/311) - Auditability 섹션
- Audit log example:
  ```json
  {
    "incidentId": "INC-29506523",
    "actionId": "A1",
    "preState": {"pool_size": 30, "pending": 41},
    "postState": {"pool_size": 40, "pending": 5},
    "result": "SUCCESS"
  }
  ```

**Status:** ⏳ In Progress

---

### C-OPS-07: 실행 후 SLO 회복 검증을 통과하지 못하면 rollback 경로가 있다

**Claim:**
액션 실행 후 SLO 회복을 검증하고, 실패 시 자동 또는 수동으로 롤백된다.

**Code:**
- `src/main/java/.../monitoring/copilot/service/MitigationExecutionService.java`
  - `verifySLO()` - 실행 후 2~5분 SLO 확인
  - `rollbackIfNeeded()` - SLO 미달 시 롤백

**Evidence:**
- GitHub issue [#311](https://github.com/zbnerd/MapleExpectation/issues/311) - Safety Rails:
  - Auto verification: 2~5분 후 p95 < 200ms 확인
  - Rollback: error-rate ↑ OR DB CPU > 80% for 5m

**Status:** ⏳ In Progress

---

### C-OPS-08: INC-29506523에서 MySQLLockPool 포화를 감지했고, #310/#311로 결정/개선을 트래킹했다

**Claim:**
실제 인시던트에서 문제를 감지하고 GitHub issue/ADR로 결정 근거를 남겼다.

**Code:**
- `src/main/java/.../monitoring/copilot/service/MonitoringPipelineService.java`
  - `detectAnomalies()` - 포화 감지
- `src/main/java/.../monitoring/copilot/client/PrometheusClient.java`
  - `hikaricp_connections_active` = 30/30

**Evidence:**
- Discord incident `INC-29506523` (2026-02-06):
  - Detection: `hikaricp_connections_active` = 30/30 (100% utilized)
  - AI Analysis (confidence: HIGH)
  - Proposed: A1 (pool 30→40), A2 (Redis migration)
- GitHub issue [#310](https://github.com/zbnerd/MapleExpectation/issues/310) - Redis Lock migration
- GitHub issue [#311](https://github.com/zbnerd/MapleExpectation/issues/311) - Discord Auto-Mitigation

**Status:** ✅ Verified

---

## 사용 방법

### 1. 면접 준비
각 Claim의 Code/Evidence 탭을 열어 답변 준비:
- "C-OPS-05 보여주세요" → Issue #311 Security 섹션 공유
- "실제로 작동하나요?" → INC-29506523 사례 설명

### 2. 운영 감사
인시던트 발생 시 C-OPS-08 참조:
- "이 액션 왜 실행되었나?" → MitigationAudit 쿼리
- "롤백 기능 있나?" → C-OPS-07 검증

### 3. 개발 우선순위
- Status가 ⏳인 Claim을 우선 구현
- Evidence가 없는 Claim은 테스트 케이스 추가

## 상태 범례

- ✅ **Verified**: 코드 + 증거 모두 존재
- ⏳ **In Progress**: 이슈 생성됨, 구현 진행 중
- ❌ **Failed**: 요구사항 충족 못 함

## 관련 이슈

| Claim | 관련 이슈 | 상태 |
|-------|---------|------|
| C-OPS-01 | [#312](https://github.com/zbnerd/MapleExpectation/issues/312) | ⏳ |
| C-OPS-04 | [#312](https://github.com/zbnerd/MapleExpectation/issues/312), [#311](https://github.com/zbnerd/MapleExpectation/issues/311) | ⏳ |
| C-OPS-05 | [#311](https://github.com/zbnerd/MapleExpectation/issues/311) | ⏳ |
| C-OPS-06 | [#311](https://github.com/zbnerd/MapleExpectation/issues/311) | ⏳ |
| C-OPS-07 | [#311](https://github.com/zbnerd/MapleExpectation/issues/311) | ⏳ |
| C-OPS-08 | [#310](https://github.com/zbnerd/MapleExpectation/issues/310), [#311](https://github.com/zbnerd/MapleExpectation/issues/311) | ✅ |

## 업데이트 규칙

1. **새 Claim 추가**: `C-OPS-{NN}` 형식 (01-99)
2. **Status 변경**: ⏳ In Progress → ✅ Verified → ❌ Failed
3. **Evidence 추가**: 실제 인시던트/PR/ADR 링크
4. **코드 리팩토링**: Code 섹션의 파일 경로/함수명 업데이트

---

*Last Updated: 2026-02-06*
*Related: [README AI SRE Section](../README.md#ai-sre-policy-guarded-autonomous-loop)*
