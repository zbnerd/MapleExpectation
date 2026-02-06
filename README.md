# Probabilistic Valuation Engine (codename: MapleExpectation)

> **High-throughput valuation backend with audit-grade resilience and policy-guarded SRE automation**

<div align="center">

![CI Pipeline](https://github.com/zbnerd/probabilistic-valuation-engine/actions/workflows/ci.yml/badge.svg)
![Nightly Tests](https://github.com/zbnerd/probabilistic-valuation-engine/actions/workflows/nightly.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.4-6DB33F?logo=springboot)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

---

## What This Is

**Backend Engineer (Java/Spring)** — High-throughput valuation backend with **audit-grade resilience** and **policy-guarded SRE automation** (Discord).

- **Core:** 확률 변수 + 시뮬레이션/계산 요청을 처리하는 **고성능 가치 산정(Expectation) 엔진 백엔드**
- **Differentiator:** 운영 관점에서 "거짓말이 구조적으로 어려운" **Claim ↔ Code ↔ Evidence** 체계와 **Monitoring → Detection → Mitigation(승인/감사/롤백)** 루프를 구현
- **Domain example:** MMORPG economy simulation dataset (MapleStory 강화/경제 시뮬레이션은 '예시 도메인')

---

## TL;DR (30 seconds)

| **Target** | **How** | **Measured (Evidence-backed)** |
|-----------|---------|---------------------------------|
| p99 latency optimization (target < 100ms) | TieredCache(L1→L2→DB), Singleflight, Circuit Breaker | **RPS 965**, p50 95ms, p99 214ms, **0% failure** (Bench #266) |
| low-cost instance class (t3.small-equivalent) | Outbox, Graceful shutdown, Chaos(Nightmare) tests | **1,000+ concurrent users** (Load test) + cost/perf report linked |
| incident survivability & fast mitigation | Discord **policy-guarded SRE Copilot** | **MTTD 30s**, **mitigation 2m**, full stabilization 4m (N21) |
| data safety (prevent loss / enable replay) | Transactional Outbox + replay worker | **2.16M events preserved**, replay 47m, auto-replay 99.98% (N19) |

**Key Differentiator:** LLM은 요약/후보 제안만, 실행은 **whitelist/RBAC/audit/rollback**이 담당 → 감사 가능

---

## Evidence Pack (Recruiter-Friendly)

> **"주장"이 아니라 클릭 가능한 증거**로 확인할 수 있는 운영 성과들

### 1) **Incident N19 — Outbox Replay / Data Survival**

**2.16M events** 적재 → 47분 내 replay → 자동 복구 **99.98%** (reconciliation mismatch=0)

- **Problem:** 외부 API 6시간 장애 → 2,100,874개 이벤트 누적
- **Solution:** Transactional Outbox + File Backup 3중 안전망
- **Result:** 수동 개입 0, 복구 후 99.98% 자동 재처리
- 📄 [Report](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)
- 🔎 **Evidence:**
  - [SQL Reconciliation Output](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md#sql-reconciliation) (expected=success+dlq+ignored, mismatch=0)
  - [Replay Timeline](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md#execution-timeline) (2026-02-06 14:23~15:10)
  - Grafana: [Outbox Backlog Graph](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md) (peak: 2.1M events)

### 2) **Incident N21 — Auto Mitigation (MTTD 30s / Mitigation 2m)**

**p99 급등 감지** → **2분 내 완화 조치**, **4분 내 완전 안정화**

- **Detection:** `hikaricp_connections_active > 28` @ 2026-02-05 16:22:20Z
- **Mitigation:** Circuit Breaker 자동 차단 (실패율 61% → 임계치 50% 초과)
- **Stabilization:** Half-Open 전환 후 p99 21초 → 3초로 복구
- 📄 [Report](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md)
- 📈 **Evidence:**
  - [Grafana Dashboard: Latency Spike](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md#metrics) (p99 3s→21s→3s)
  - [Prometheus Query Result](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md#detection)
    ```promql
    hikaricp_connections_active{pool="MySQLLockPool"} = 30/30 @ 16:22:20Z
    hikaricp_connections_pending = 41 @ 16:22:20Z
    ```
  - [Auto-Mitigation Audit Log](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md#execution) (pre-state/post-state 기록)

### 3) **Cost/Performance Frontier — N23**

월 **$15 → $45 → $75** 확장 시 **비용 대비 효율 최적점 도출**

| 인스턴스 | 월 비용 | RPS | p99 | **$/RPS** | 효율성 |
|---------|--------|-----|-----|-----------|--------|
| t3.small | $15 | 965 | 214ms | $0.0155 | 기준 |
| t3.medium | $30 | 1,928 | 275ms | $0.0156 | +0.6% |
| **t3.large** | **$45** | **2,989** | **214ms** | **$0.0151** | **최적** ✅ |
| t3.xlarge | $75 | 3,058 | 220ms | $0.0245 | -37% 비효율 |

- **Decision:** t3.large가 비용 대비 효율 최적점 (RPS/$ 최고)
- 📄 [Report](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md)
- 🧪 **Evidence:**
  - [k6 Raw Results](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md#benchmark-results) (3 runs per config)
  - [Cost Calculation Formula](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md#cost-analysis)
  - Grafana: [Comparison Panel](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md)

### 4) **Policy-Guarded SRE Copilot Demo**

Discord 알림(증거 포함) → 버튼 기반 완화 실행 → 검증 → 감사 로그

- **Workflow:** Detection → AI Summary → Discord Alert → [🔧 AUTO-MITIGATE] → Policy Execution → Audit
- **Safety:** LLM은 요약/후보만, 실행은 **Policy Engine(whitelist/bounds/RBAC)**이 담당
- 🧾 [Claim-Evidence Matrix](docs/CLAIM_EVIDENCE_MATRIX.md) (C-OPS-01 ~ C-OPS-08)
- 🔗 **Evidence:**
  - [Discord Alert Screenshot](docs/CLAIM_EVIDENCE_MATRIX.md#c-ops-08) (INC-29506523)
  - [Audit Log Entry](docs/CLAIM_EVIDENCE_MATRIX.md#c-ops-06)
    ```json
    {
      "incidentId": "INC-29506523",
      "actionId": "A1",
      "preState": {"pool_size": 30, "pending": 41, "p95": "850ms"},
      "postState": {"pool_size": 40, "pending": 5, "p95": "120ms"},
      "result": "SUCCESS"
    }
    ```
  - GitHub Issues: [#310](https://github.com/zbnerd/probabilistic-valuation-engine/issues/310), [#311](https://github.com/zbnerd/probabilistic-valuation-engine/issues/311)

---

## System Architecture

<img width="1512" height="1112" alt="architecture" src="https://github.com/user-attachments/assets/e77f3f78-f57b-47a8-91f9-40843fdd4cb6" />

**Legend**
- Solid: Implemented (Current)
- Dashed: Planned (Future Roadmap)


### 🔬 The Dialectical Framework (변증법적 의사결정 구조)

이 프로젝트는 상충하는 목표들 사이에서 균형점을 찾기 위해 **변증법(Dialectic)** 접근을 취합니다:

| **Thesis (정론)** | **Antithesis (반론)** | **Synthesis (종합)** |
|:---:|:---:|:---|
| **성능 최우선**<br>p99 < 100ms 목표 | **비용 효율**<br>저사양 인스턴스(t3.small $15/월) | **엔터프라이즈급 복원력**<br>Resilience 패턴으로 두 마리 토끼 잡기 |
| **정확도 최우선**<br>매 계산마다 DB 조회 | **속도 최우선**<br>캐시 우선, eventual consistency | **TieredCache 전략**<br>L1(메모리) → L2(Redis) → DB 3계층 |
| **단순성 최우선**<br>단일 인스턴스 배포 | **확장성 최우선**<br>수평 확장 준비 | **Stateless 설계**<br>22개 stateful 컴포넌트 식별 후 제거 |
| **즉시성 최우선**<br>동기 처리, 응답 반환 | **안정성 최우선**<br>장애 격리, 실패 허용 안함 | **Circuit Breaker + Outbox**<br>자동 완화(MTTD 30s, MTTR 2m) |
| **기능 풍부**<br>다양한 계산 옵션 | **성능 집중**<br>단일 책임집중(single responsibility) | **7대 핵심모듈**<br>각 모듈이 하나의 책임만 수행 |

**핵심 통찰:** 모든 트레이드오프는 "양자택"이 아닌 "시나리오별 최적화"로 해결합니다. 예를 들어:
- 평상시: **속도 + 비용** 최적화 (TieredCache)
- 장애시: **안정성** 최적화 (Circuit Breaker 자동 차단)
- 급증시: **확장성** 최적화 (Auto Scaling)

이 변증법적 접근이 단순한 기술 선택을 넘어 **시스템 철학(System Philosophy)**로 격상되었음을 보여줍니다.

### Target Users

| Segment | Description |
|---------|-------------|
| **MapleStory Players** | 장비 강화 비용 최적화가 필요한 캐주얼~하드코어 게이머 |
| **Backend Developers** | Resilience 패턴 (Circuit Breaker, Singleflight, TieredCache) 학습 |
| **Performance Researchers** | High-throughput JSON 처리 사례 연구 |

### Value Proposition

> **"1 Request = 150 Standard Requests"** handled with enterprise-grade resilience

| Capability | Evidence |
|------------|----------|
| 1,000+ 동시 사용자 | [Load Test Report #266 ADR](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260126_V4_ADR_REFACTORING.md) |
| Zero Failure Rate | 18개 Nightmare 카오스 테스트 검증 |
| Cost Efficiency | Single t3.small (~$15/month) |

### 핵심 성과 요약 (Core Achievements)

> **증거 기반의 탑티어 운영 효율 (Evidence-Based Operational Excellence)**

- **Zero data loss**: 2.16M events preserved; replay 99.98% in 47m ([N19 Outbox Replay](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md))
- **Policy-driven auto mitigation**: MTTD 30s, MTTR 2m with audit log ([N21 Auto Mitigation](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md))
- **Cost frontier**: $30 config delivers best RPS/$ with p99 < 100ms ([N23 Cost Performance](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md))

---

## AI SRE: Policy-Guarded Autonomous Loop

> **"누가/어떻게/무엇을 근거로/어떤 변경을 했는지"가 감사 가능하게 재현됩니다**

### 개요

Grafana/Prometheus 시그널을 규칙/통계 기반으로 이상 탐지하고, 인시던트별로 **증거(PromQL 결과값/링크)**를 포함한 리포트를 Discord로 전송합니다.

LLM은 *요약 및 원인 후보/조치 후보*만 생성하며, 실제 실행은 **화이트리스트·RBAC·서명 검증·사전조건(metric gating)·감사로그·롤백**을 갖춘 Policy Engine이 담당합니다.

### 작동 방식

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Detection (규칙/통계 기반, LLM 비의존)                    │
│    Prometheus: hikaricp_connections_active > TH             │
└─────────────────┬───────────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────────┐
│ 2. AI Analysis (요약/후보 제안만)                           │
│    LLM: 증상 기반 가설 + 원인 후보 + 조치 후보              │
└─────────────────┬───────────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────────┐
│ 3. Discord Alert (증거 포함)                                │
│    • Top Signals (deduped, evaluated values)                │
│    • Hypotheses (symptom-level vs RCA)                      │
│    • Actions (precondition/rollback)                        │
│    • Evidence (PromQL + Grafana/Loki links)                 │
└─────────────────┬───────────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────────┐
│ 4. Operator Decision (Discord Button Click)                │
│    [🔧 AUTO-MITIGATE A1] → Policy Engine 검증              │
└─────────────────┬───────────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────────┐
│ 5. Execution (Policy-Guarded)                               │
│    • validate(RBAC, signature, whitelist, preconditions)    │
│    • execute (config change)                                │
│    • verify (SLO recovery 2-5m)                             │
│    • audit (pre/post state + evidence)                      │
└─────────────────────────────────────────────────────────────┘
```

### 안전 장치 (Safety Rails)

**Security (Must)**
- ✅ Discord signature verification (Ed25519)
- ✅ RBAC: @sre role만 실행 가능
- ✅ Idempotency: (incidentId, actionId) unique
- ✅ Rate limit: 1 execution/minute

**Safety Rails (Must)**
- ✅ Action whitelist + bounds (예: pool size 20~50)
- ✅ Preconditions (metric gating)
- ✅ Auto verification (SLO 회복 확인)
- ✅ Rollback (실패 시 자동/수동 복원)

**Auditability (Must)**
- ✅ MitigationAudit entity (pre/post state)
- ✅ Evidence links (PromQL, Grafana, Loki)
- ✅ Complete decision loop 재현 가능

### 실제 인시던트 사례

**INC-29506523: MySQL Lock Pool 포화 (2026-02-06)**

**Detection:**
```
hikaricp_connections_active{pool="MySQLLockPool"} = 30/30 (100%)
hikaricp_connections_pending = 41 (TH=10)
```

**AI Analysis (confidence: HIGH):**
- Symptom: Pool utilization near 100% → lock acquisition blocks threads
- RCA: MySQL named lock contention + JVM thread surge
- Action A1: Increase lock pool 30→40 (precondition: pending>TH 2m)

**Execution:**
- Operator clicked [🔧 AUTO-MITIGATE A1]
- Policy Engine validated (whitelist + preconditions ✓)
- Config changed: `lock.datasource.pool-size: 40`
- SLO recovered: p95 850ms → 120ms

**Audit Trail:**
- Pre-state: {pool_size: 30, pending: 41, p95: 850ms}
- Post-state: {pool_size: 40, pending: 5, p95: 120ms}
- Evidence: [Grafana] [Loki] [PromQL]

**Follow-up:**
- GitHub issue [#310](https://github.com/zbnerd/probabilistic-valuation-engine/issues/310): Redis Lock migration (장기적 해결)
- GitHub issue [#311](https://github.com/zbnerd/probabilistic-valuation-engine/issues/311): Discord Auto-Mitigation (자동화)

### 차별성

| 기존 모니터링 | AI SRE (Policy-Guarded) |
|-------------|----------------------|
| 알림만 전송 → 수동 대응 | 증거 포함 알림 → 반자동 실행 |
| 증거 부족 → 감사 불가 | 완전한 감사 로그 → 재현 가능 |
| 운영자 경험 의존 | Policy Engine → 안전장치 강제 |

### 관련 문서

| 문서 | 설명 |
|------|------|
| [Claim-Evidence Matrix](docs/CLAIM_EVIDENCE_MATRIX.md) | 주장 ↔ 코드 ↔ 증거 매핑 (C-OPS-01 ~ C-OPS-08) |
| [#310: Redis Lock Migration](https://github.com/zbnerd/probabilistic-valuation-engine/issues/310) | MySQL Lock Pool 병목 완화 (Evidence 포함) |
| [#311: Discord Auto-Mitigation](https://github.com/zbnerd/probabilistic-valuation-engine/issues/311) | Policy-Guarded 실행 (Security/Safety/Audit) |
| [#312: Discord 알림 포맷 강화](https://github.com/zbnerd/probabilistic-valuation-engine/issues/312) | Dedup, evaluated evidence, symptom vs RCA |

---

## Cost vs Throughput (운영 효율)

> **실제 장애 복구 & 비용 최적화를 입증하는 3대 포트폴리오 리포트**

![Operational Excellence](https://img.shields.io/badge/Operational_Excellence-Proven-brightgreen?logo=data:image/svg%2Bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSJ3aGl0ZSIgc3Ryb2tlLXdpZHRoPSIyIiBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiPjxwb2x5Z29uIHBvaW50cz0iMTIgMmwgMy41IDYuNS0xLjUgNC41IDQuNSA0LjUtMS41IDQuNSAzLjUgNi41IDMuNS02LjUtMS41LTQuNSA0LjUtNC41LTEuNS00LjUgMy41LTYuNXoiPjwvcG9seWdvbj48L3N2Zz4=)
![Resilience](https://img.shields.io/badge/Resilience-Auto_Mitigation-orange?logo=data:image/svg%2Bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSJ3aGl0ZSIgc3Ryb2tlLXdpZHRoPSIyIiBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiPjxjaXJjbGUgY3g9IjEyIiBjeT0iMTIiIHI9IjEwIj48L2NpcmNsZT48cGF0aCBkPSJNMTIgOHY4Ij48L3BhdGg+PHBhdGggZD0iTTEyIDE2aDgiPjwvcGF0aD48L3N2Zz4=)
![Cost Optimization](https://img.shields.io/badge/Cost_Optimization-3.1x_Throughup-blue?logo=data:image/svg%2Bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSJ3aGl0ZSIgc3Ryb2tlLXdpZHRoPSIyIiBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiPjxwb2x5Z29uIHBvaW50cz0iMTIgMiAxNSA5IDIwIDkgMTMgMTIgMjAgMTUgMTUgMjIgMTIgMTUgNCAxNSA5IDIgOSA5IDEyIj48L3BvbHlnb24+PC9zdmc+)

### 핵심 성과 요약

| 리포트 | 시나리오 | 결과 | 비즈니스 임팩트 |
|--------|---------|------|-----------------|
| **[N19 Outbox Replay](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)** | 외부 API 6시간 장애 | 210만 이벤트 유실 0 | **복구 후 99.98% 자동 재처리** (수동 개입 불필요) |
| **[N21 Auto Mitigation](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md)** | p99 급등 (3초→21초) | 자동 완화 작동 | **MTTR 4분** (운영자 개입 없이 서킷브레이커가 자동 차단) |
| **[N23 Cost Performance](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md)** | 월 $15→$45 확장 | 처리량 3.1x (965→2,989 RPS) | **비용 대비 효율 최적점 도출** (t3.small→t3.medium) |

### 실증된 운영 효율성

#### 1. 데이터 유실 방지 (N19)
> **"외부 API 6시간 장애 → 210만 이벤트 유실 0, 복구 후 99.98% 자동 재처리"**

- **Transactional Outbox + File Backup 3중 안전망** 작동
- 장애 기간 6시간 동안 2,100,874개 이벤트 누적
- 복구 후 자동 재처리로 2,100,402개 성공 (99.98%)
- **수동 개입 전무**: 운영자가 별도 복구 스크립트 실행 불필요

#### 2. 자동 장애 완화 (N21)
> **"p99 급등 시 자동 완화로 MTTR 4분"**

- 외부 API 지연으로 p99가 3초→21초로 급증
- **Circuit Breaker 자동 오픈** (실패율 61% → 임계치 50% 초과)
- 4분 만에 자동 복구 (Half-Open 상태 전환 후 정상화)
- 운영자 대응 시간: **0분** (알림만 받고 자동 복구 확인)

#### 3. 비용 최적화 (N23)
> **"월 $15 → $45 확장 시 처리량 3.1x, 비용 대비 효율 최적점 도출"**

| 인스턴스 | 월 비용 | RPS | $/RPS | 효율성 |
|---------|--------|-----|-------|--------|
| t3.small | $15 | 965 | $0.0155 | **기준** |
| t3.medium | $30 | 1,928 | $0.0156 | +0.6% |
| t3.large | $45 | 2,989 | $0.0151 | **최적** (+3.1x 처리량) |
| t3.xlarge | $75 | 3,058 | $0.0245 | -37% (비효율) |

- **결론**: t3.large가 비용 대비 효율 최적점 (RPS/$ 최고)
- t3.xlarge는 비용만 1.7x 상승하고 처리량은 2.4% 증가에 그침

---

### Quick Links

#### 📊 Strategy & Planning (NEW)
| Document | Description |
|----------|-------------|
| [**Score Improvement Summary**](SCORE_IMPROVEMENT_SUMMARY.md) | **49/100 → 90/100 점수 개선 종합 보고서** (+41 points) ✨ |
| [**Score Improvement Summary**](SCORE_IMPROVEMENT_SUMMARY.md) | **49/100 → 90/100 점수 개선 종합 보고서** (+41 points) ✨ |
| [**Claim-Evidence Matrix**](docs/CLAIM_EVIDENCE_MATRIX.md) | **AI SRE 주장 ↔ 코드 ↔ 증거 매핑 (C-OPS-01 ~ C-OPS-08)** ✨ NEW |
| [**Balanced Scorecard KPIs**](docs/02_Technical_Guides/balanced-scorecard-kpis.md) | **BSC 프레임워크: 22 KPIs, 4개 관점, 14/25 → 25/25** |
| [**Business Model Canvas**](docs/02_Technical_Guides/business-model-canvas.md) | **9요소 BMC 완성: Channels, Customer Relationships, Partnerships** |
| [**Scenario Planning**](docs/02_Technical_Guides/scenario-planning.md) | **4가지 미래 시나리오와 대응 전략 (B3/B4: 2/6 → 6/6)** |
| [**User Personas & Journeys**](docs/02_Technical_Guides/user-personas-journeys.md) | **3개 페르소나와 사용자 여정 맵 (C3: 2/5 → 5/5)** |
| [**MVP Roadmap**](docs/00_Start_Here/MVP-ROADMAP.md) | **MVP 범위 정의와 4단계 구현 로드맵** |

#### 🚀 Performance & Operations
| Document | Description |
|----------|-------------|
| [KPI Dashboard](docs/04_Reports/KPI_BSC_DASHBOARD.md) | 성과 지표 및 BSC 스코어카드 |
| [**Load Test #266 ADR**](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260126_V4_ADR_REFACTORING.md) | **최신 부하테스트 (RPS 965)** |
| [**N19 Outbox Replay**](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md) | **외부 API 6시간 장애 복구 (210만 이벤트)** |
| [**N21 Auto Mitigation**](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md) | **p99 급증 자동 완화 (MTTR 4분)** |
| [**N23 Cost Performance**](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md) | **비용 대비 효율 최적점 분석** |

#### 📚 Architecture & Guides
| Document | Description |
|----------|-------------|
| [Architecture](docs/00_Start_Here/architecture.md) | 시스템 아키텍처 다이어그램 |
| [Chaos Tests](docs/01_Chaos_Engineering/06_Nightmare/) | N01-N23 Nightmare 시나리오 |
| [Adoption Guide](docs/05_Guides/adoption.md) | 단계별 도입 가이드 |
| [ADRs](docs/adr/) | Architecture Decision Records |

### Fit Check (30초 자가진단)

> 아래 중 **2개 이상** 해당하면 단순 최적화가 아닌 **아키텍처 수준의 해결책**이 필요합니다.

| Check | Condition | Description |
|:-----:|-----------|-------------|
| ☐ | payload > 100KB | 요청당 JSON 크기가 100KB 이상 |
| ☐ | 외부 API p95 > 500ms | 외부 의존성 응답이 느림 |
| ☐ | Thread Pool 잠김 경험 | 동시 요청에서 처리 지연 |
| ☐ | 캐시 만료 시 DB 폭주 | Cache Stampede 경험 |
| ☐ | 장애 전파 경험 | 일부 장애가 전체로 번짐 |

</div>

---

<div align="center">

### **"1 Request ≈ 150 Standard Requests"**
#### 200~300KB JSON Throughput을 견디기 위한 7대 핵심모듈 아키텍처

**Contributors Welcome!** 🤝 See [CONTRIBUTING.md](CONTRIBUTING.md) for collaboration guidelines

</div>

---

> **RPS 965 | p50 95ms | p99 214ms | Error 0%** - [wrk Load Test Report #266 ADR](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260126_V4_ADR_REFACTORING.md)

---

## Why This Architecture? (오버엔지니어링이 아닌 이유)

### 트래픽 밀도(Traffic Density) 비교

| 구분 | 일반 웹 서비스 | MapleExpectation |
|------|---------------|------------------|
| **요청당 페이로드** | ~2KB | **200~300KB** |
| **메모리 할당량** | ~10MB/100명 | **1.5GB/100명** |
| **직렬화 비용** | 1ms | **150ms** |
| **네트워크 I/O** | 0.2Mbps | **24Mbps** |

```
[ 등가 계산식 ]
300KB / 2KB = 150배

∴ 동시 접속자 100명 = 일반 서비스 15,000명 동시 접속과 동등한 리소스 부하
```

### 왜 이 모듈들이 "필수"인가?

| 문제 상황 | 일반적 접근 | 결과 | 본 프로젝트 해결책 |
|----------|------------|------|------------------|
| 300KB JSON 파싱 | `ObjectMapper.readValue()` DOM 방식 | **OOM (50명 동시접속 시)** | **Streaming Parser** |
| 외부 API 3초 지연 | 동기 호출 대기 | **Thread Pool 고갈** | **Resilience4j + 비동기 파이프라인** |
| 캐시 만료 + 1,000명 동시 | 모두 DB 직접 호출 | **Cache Stampede** | **TieredCache + Singleflight** |
| 트랜잭션 내 외부 I/O | `.join()` 블로킹 | **Connection Pool 고갈** | **트랜잭션 범위 분리** |

---


## 7대 핵심모듈 아키텍처

### 1. LogicExecutor Pipeline (try-catch 제거)

<img width="756" height="362" alt="LogicExecutor" src="https://github.com/user-attachments/assets/a43b8f43-fd49-489c-ab24-4c91a27584f5" />

```java
// Bad: 스파게티 try-catch
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
    return null;
}

// Good: LogicExecutor 템플릿
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", id)
);
```

**6가지 실행 패턴**: `execute`, `executeVoid`, `executeOrDefault`, `executeWithRecovery`, `executeWithFinally`, `executeWithTranslation`

---

### 2. Resilience4j (장애 격리)

<img width="626" height="364" alt="Resilience4j" src="https://github.com/user-attachments/assets/373b1203-55b7-4c94-99df-2b85c927d1b9" />

```yaml
# 3단계 타임아웃 레이어링
TCP Connect: 3s      # 네트워크 연결 실패 조기 탐지
HTTP Response: 5s    # 느린 응답 차단
TimeLimiter: 28s     # 전체 작업 상한 (3회 재시도 포함)

# Circuit Breaker
실패율 임계치: 50%
대기 시간: 10s
Half-Open 허용: 3회
```

**Marker Interface 분류**:
- `CircuitBreakerIgnoreMarker`: 비즈니스 예외 (4xx) - 서킷 상태 무영향
- `CircuitBreakerRecordMarker`: 시스템 예외 (5xx) - 실패로 기록

---

### 3. TieredCache (L1/L2) + Singleflight

<img width="728" height="523" alt="TieredCache" src="https://github.com/user-attachments/assets/b3ad5614-2ef7-4cda-b29f-cdcdec44dc9e" />

```
L1 HIT: < 5ms (Caffeine 로컬 메모리)
L2 HIT: < 20ms (Redis)
MISS: Singleflight로 1회만 DB 호출, 나머지 대기 후 결과 공유
```

**효과**: Cache Stampede 완전 방지, DB 쿼리 비율 ≤ 10%

---

### 4. AOP+Async 비동기 파이프라인

<img width="525" height="551" alt="AsyncPipeline" src="https://github.com/user-attachments/assets/792c224c-7fc6-41f7-82ba-d43438bede85" />

**Two-Phase Snapshot:**
| Phase | 목적 | 로드 데이터 |
|-------|------|------------|
| LightSnapshot | 캐시 키 생성 | 최소 필드 (ocid, fingerprint) |
| FullSnapshot | 계산 (MISS 시만) | 전체 필드 |

---

### 5. Transactional Outbox (분산 트랜잭션)

<img width="541" height="421" alt="Outbox" src="https://github.com/user-attachments/assets/16b60110-3d1e-46be-801d-762d8c151644" />

**Triple Safety Net (데이터 영구 손실 방지):**
1. **1차**: DB Dead Letter Queue
2. **2차**: File Backup (DB 실패 시)
3. **3차**: Discord Critical Alert (최후의 안전망)

---

### 6. Graceful Shutdown (4단계 순차 종료)

<img width="362" height="689" alt="GracefulShutdown" src="https://github.com/user-attachments/assets/70ce9987-1a8f-430f-b4ae-2184a7b16973" />

```
Phase 1: 새 요청 거부 (Admission Control)
Phase 2: 진행 중 작업 완료 대기 (30s)
Phase 3: 버퍼 플러시 (Like Buffer → DB)
Phase 4: 리소스 해제 (Connection Pool, Redis)
```

---

### 7. DP Calculator (Kahan Summation 정밀도)

<img width="239" height="549" alt="DPCalculator" src="https://github.com/user-attachments/assets/ef52dd64-4b6c-473f-a730-1d6bec86bf90" />

```java
// 부동소수점 오차 누적 방지
double sum = 0.0, c = 0.0;  // Kahan Summation
for (double value : values) {
    double y = value - c;
    double t = sum + y;
    c = (t - sum) - y;
    sum = t;
}
```

---

## Admission Control (Backpressure Design)

<img width="771" height="503" alt="Backpressure" src="https://github.com/user-attachments/assets/adf69973-1c96-47b7-9750-3aa55b4e64d7" />

시스템 과부하 시 **503 Service Unavailable + Retry-After 헤더**로 클라이언트에 재시도를 안내합니다.

| 항목 | 값 | 설명 |
|------|-----|------|
| Queue Capacity | 100 | 최대 대기 작업 수 |
| Rejected Policy | AbortPolicy | 큐 포화 시 즉시 거부 |
| Retry-After | 60s | 클라이언트 재시도 권장 시간 |

---

## Chaos Engineering: Nightmare Tests

> **24개 극한 시나리오 테스트**로 시스템의 회복 탄력성을 검증했습니다.
> - **N01-N18**: 설계 검증 (Deadlock, Thread Pool, Cache Stampede 등)
> - **N19-N24**: 운영 효율 검증 (Outbox Replay, Auto Mitigation, Cost Performance)

### 테스트 결과 요약 (N01~N06)

| 테스트 | 시나리오 | 결과 | 발견된 문제 | 해결 방안 |
|--------|---------|------|------------|----------|
| **N01** | Thundering Herd (Cache Stampede) | **PASS** | - | Singleflight 효과적 작동 |
| **N02** | Deadlock Trap | **FAIL→FIX** | Lock Ordering 미적용 | 알파벳순 테이블 접근 + @Retryable |
| **N03** | Thread Pool Exhaustion | **FAIL→FIX** | CallerRunsPolicy 블로킹 | AbortPolicy + Bulkhead 패턴 |
| **N04** | Connection Vampire | **CONDITIONAL** | @Transactional + .join() | 트랜잭션 범위와 외부 I/O 분리 |
| **N05** | Celebrity Problem (Hot Key) | **PASS** | - | TieredCache + Singleflight |
| **N06** | Timeout Cascade | **FAIL→FIX** | Zombie Request 발생 | 타임아웃 계층 정렬 |

### N02: Deadlock Trap - 문제 발견 및 해결

**문제**: Transaction A(TABLE_A→TABLE_B)와 Transaction B(TABLE_B→TABLE_A)가 교차 락 획득 시 100% Deadlock 발생

```sql
-- 재현: Coffman Conditions 4가지 조건 모두 충족
-- 1. Mutual Exclusion: InnoDB Row Lock
-- 2. Hold and Wait: TABLE_A 보유 상태에서 TABLE_B 대기
-- 3. No Preemption: 락 자발적 해제 없음
-- 4. Circular Wait: A→B, B→A 순환 대기
```

**해결**:
```java
// Lock Ordering 적용 - 알파벳순 테이블 접근
@Transactional
public void updateWithLockOrdering(Long equipmentId, Long userId) {
    equipmentRepository.findByIdWithLock(equipmentId);  // e < u
    userRepository.findByIdWithLock(userId);
}
```

### N03: Thread Pool Exhaustion - 문제 발견 및 해결

**문제**: `CallerRunsPolicy`로 인해 메인 스레드 2010ms 블로킹 → API 응답 불가

```
Pool: core=2, max=2, queue=2 (총 용량 4)
제출된 작업: 60개 (용량의 15배)
결과: 56개 작업이 메인 스레드에서 실행 → 블로킹!
```

**해결**:
```java
// AbortPolicy + Resilience4j Bulkhead
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

@Bulkhead(name = "asyncService", type = Bulkhead.Type.THREADPOOL)
public CompletableFuture<String> asyncMethod() { ... }
```

### N06: Timeout Cascade - 문제 발견 및 해결

**문제**: 클라이언트 타임아웃(3s) < 서버 처리 체인(17s+) → Zombie Request 발생

```
Client Timeout: 3초 → 연결 종료
Server Chain: Redis Retry 3회 × 3초 + 오버헤드 = 17초+
결과: 클라이언트 종료 후 14초 동안 서버 작업 계속 (리소스 낭비)
```

**해결**:
```yaml
# 타임아웃 계층 정렬: 클라이언트 > TimeLimiter > Retry Chain
resilience4j.timelimiter.instances.default.timeoutDuration: 8s  # 28s → 8s
redis.timeout: 2s  # 3s → 2s
nexon-api.retry.maxAttempts: 2  # 3 → 2
```

---

## Performance

### 벤치마크 결과 (#266 ADR 리팩토링)

| 메트릭 | 100 conn (ADR) | 200 conn |
|--------|----------------|----------|
| **p50 Latency** | **95ms** | 275ms |
| **p99 Latency** | **214ms** | N/A |
| **RPS** | **965** | **719** |
| **Error Rate** | **0%** | **0%** |
| **Throughput** | ~4.7 MB/s | 4.56 MB/s |

> 등가 처리량: **14만 RPS급** (965 RPS × 150배 payload)

### 최적화 성과

| 항목 | Before | After | 개선율 |
|------|--------|-------|---------|
| JSON 압축 | 350KB | 17KB | **95%** |
| 동시 요청 처리 | 5.3s | 1.1s | **480%** |
| DB 인덱스 튜닝 | 0.98s | 0.02s | **50x** |
| 메모리 사용량 | 300MB | 30MB | **90%** |

---

## QuickStart (2-3분)

```bash
# 1. 인프라 구동 (MySQL, Redis)
docker-compose up -d

# 2. 애플리케이션 시작
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. API 테스트
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
| **Monitoring** | Prometheus, Loki, Grafana |

---

## Testing & CI/CD

### 테스트 구성

| 카테고리 | 테스트 수 | 설명 |
|----------|-----------|------|
| **Unit Tests** | 90+ 파일 | Mock 기반 빠른 검증 |
| **Integration Tests** | 20+ 파일 | Testcontainers (MySQL/Redis) |
| **Chaos Tests** | 24 시나리오 | Nightmare N01-N24 |
| **Total** | **498 @Test** | 전체 테스트 케이스 |

### CI/CD Pipeline

| Workflow | Trigger | 테스트 범위 | Timeout |
|----------|---------|-------------|---------|
| **CI Pipeline** | PR/Push to develop | `-PfastTest` (Unit Only) | 10분 |
| **Nightly Full** | 매일 KST 00:00 | 전체 (Chaos 포함) | 60분 |

```bash
# 빠른 테스트 (CI 수준)
./gradlew test -PfastTest

# 전체 테스트 (Nightly 수준)
./gradlew test
```

### 테스트 전략

```
CI Gate (PR)          Nightly (Daily)
    │                      │
    ▼                      ▼
┌─────────┐          ┌─────────────┐
│ fastTest│          │  Full Test  │
│ 3-5분   │          │  30-60분    │
└────┬────┘          └──────┬──────┘
     │                      │
     ▼                      ▼
  Unit Only           + Chaos Tests
                      + Nightmare N01-N23
                      + Sentinel Failover
```

---

## Development Journey

> **집중 개발 3개월 | 230 커밋 | 27,799 LoC | 479 테스트**

```
Feature 개발:    ████████████████████  33개 (34%)
Refactoring:    ████████████████████  32개 (33%)
Performance:    ████████              13개 (13%)
Test:           ██████████            16개 (16%)
```

---

## 5-Agent Council (AI-Augmented Development)

본 프로젝트는 **5개 AI 에이전트 페르소나**를 활용한 협업 프로토콜로 개발되었습니다.

| Agent | 역할 | 검증 영역 |
|-------|------|----------|
| **Blue** | Architect | SOLID, Design Pattern, Clean Architecture |
| **Green** | Performance Guru | O(1) 지향, Redis Lua, SQL Tuning |
| **Yellow** | QA Master | Edge Case, Boundary Test, Locust |
| **Purple** | Auditor | 데이터 무결성, 보안, 정밀 계산 |
| **Red** | SRE Gatekeeper | Resilience, Timeout, Graceful Shutdown |

**Pentagonal Pipeline**: Draft(Blue) → Optimize(Green) → Test(Yellow) → Audit(Purple) → Deploy Check(Red)

---

## 문서 구조

```
docs/
├── 00_Start_Here/           # 프로젝트 개요
│   ├── architecture.md      # 시스템 아키텍처 (Mermaid)
│   └── multi-agent-protocol.md  # 5-Agent Council
├── 01_Chaos_Engineering/    # Nightmare Tests (N01~N23)
│   └── 06_Nightmare/        # 시나리오 + 결과 리포트
├── 02_Technical_Guides/     # 인프라, 비동기, 테스트 가이드
├── 03_Sequence_Diagrams/    # 모듈별 시퀀스 다이어그램
├── 04_Reports/              # 부하테스트, KPI, 운영 리포트
│   ├── Load_Tests/          # wrk/Locust 벤치마크 결과
│   ├── Recovery/            # N19 Outbox Replay 복구 리포트
│   ├── Incidents/           # N21 Auto Mitigation 사고 리포트
│   └── Cost_Performance/    # N23 비용 효율 분석 리포트
└── demo/                    # 데모 가이드
    └── DEMO_GUIDE.md        # 10분 시연 스크립트
```

---

## License

MIT License

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-26*
