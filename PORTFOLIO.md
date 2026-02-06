# Portfolio — Probabilistic Valuation Engine + Policy-Guarded SRE Copilot

> **Backend Engineer (Java/Spring)** — High-throughput valuation backend with audit-grade resilience

## 10-Second Summary

- **Java 21 + Spring Boot 3.5.4** 기반 **고성능 연산/가치 산정 백엔드**
- 운영 관점에서 **데이터 생존(Outbox) + 자동 완화(Discord) + 비용-성능 의사결정(N23)**을 "증거"로 남김
- LLM은 요약/후보 제안만, 실행은 **Whitelist/RBAC/Audit/Rollback**이 담당 → **감사 가능**

## Why This Is Enterprise-Relevant

| **Aspect** | **What This Demonstrates** |
|-----------|---------------------------|
| **p99 latency / DB protection** | 고성능 처리량과 안정성 동시 달성 능력 |
| **장애 격리 / 재처리** | Circuit Breaker, Outbox로 **데이터 유실 0** |
| **비용-성능 프론티어** | '늘리는 것'이 아니라 **최적점 선택** 의사결정 |
| **Incident 검증 가능** | 문서가 "서술"이 아니라 **SQL/로그/메트릭 링크**로 증명 |
| **운영 Decision Loop** | 탐지→판단→조치→검증→감사 **전체 자동화** |

## Proof (Evidence Pack)

### 1) N19 Outbox Replay — Data Survival

**2.16M events** preserved → replay 47m → reconciliation mismatch=0

- **What:** 외부 API 6시간 장애 동안 2,100,874개 이벤트 누적
- **How:** Transactional Outbox + File Backup 3중 안전망
- **Result:** 복구 후 자동 재처리 2,100,402개 (99.98%), 수동 개입 **0**
- 📄 [Report](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)

### 2) N21 Auto Mitigation — MTTR 4분

**Circuit Breaker 자동 오픈** → p99 급등 감지 → 자동 복구

- **What:** 외부 API 지연으로 p99가 3초→21초로 급증
- **How:** Prometheus 기반 룰/휴리스틱 탐지 → 실패율 61% 감지 → 자동 차단
- **Result:** 4분 만에 Half-Open 전환 후 정상화, 운영자 대응 시간 **0분**
- 📄 [Report](docs/04_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md)

### 3) N23 Cost/Performance Frontier

**비용 최적점 도출** — $30 config delivers best RPS/$

- **What:** 1→2→3 인스턴스 스케일에서 비용 대비 효율 측정
- **How:** wrk 부하테스트 + RPS/p99/p99.9 지표 수집 + 월 비용 산식
- **Result:** t3.large가 최적 (RPS/$ 0.0151), t3.xlarge는 비효율(-37%)
- 📄 [Report](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md)

### 4) Discord Button-Based Mitigation — Policy-Guarded

**Discord 클릭 1회** → 안전한 remediation 실행 → 완전한 감사 로그

- **What:** INC-29506523 (MySQL Lock Pool 포화) 실제 인시던트 대응
- **How:**
  1. Detection: `hikaricp_connections_active` = 30/30 (100%)
  2. AI Analysis (confidence: HIGH) → 제안 3개
  3. Operator clicks [🔧 AUTO-MITIGATE A1]
  4. Policy Engine 검증 (whitelist/bounds/RBAC/signature)
  5. Config changed: pool 30→40
  6. SLO recovered: p95 850ms→120ms
- **Safety:** LLM은 요약/후보만, 실행은 **Policy Engine**이 담당
- 🧾 [Claim-Evidence Matrix](docs/CLAIM_EVIDENCE_MATRIX.md) (8 Claims with code/evidence)

## Architecture Snapshot

```
Request → [API] → [Cache] → [Engine] → [Lock] → [DB]
                  ↓          ↓         ↓        ↓
               [TieredCache] [Outbox] [Outbox Worker]
               (L1→L2→DB)    (Durability)
```

**Core Modules (Single Responsibility):**
- **TieredCache:** L1(Caffeine) → L2(Redis) → DB, stampede 방지
- **Singleflight:** DB 쿼리 중복 실행 방지
- **Circuit Breaker:** 장애 격리, 자동 차단
- **Outbox + Worker:** 데이터 생존 + 재처리
- **SRE Copilot:** Detection → AI Summary → Discord → Button → Policy Action → Audit

## Interview-Ready Talking Points

### "Zero data loss는 주장하지 않았나요?"

**아닙니다.**
- **Reconciliation invariant + SQL 전수 검증 mismatch=0**로 종료 조건 정의
- 2,100,874 events 중 2,100,402 성공 (99.98%)로 **구체적 숫자로 증명**
- [N19 Report](docs/04_Reports/Recovery/RECOVERY_REPORT_N19_OUTBOX_REPLAY.md)에서 SQL 쿼리, 로그, 그래프 확인 가능

### "AI가 실행하는 거 아닌가요?"

**아닙니다.**
- LLM은 **요약/후보 제안**만 담당
- 실제 실행은 **Policy Engine**(whitelist/bounds/RBAC/signature verification)이 담당
- [Claim-Evidence Matrix C-OPS-05](docs/CLAIM_EVIDENCE_MATRIX.md#c-ops-05)에서 코드/증거 확인 가능
- GitHub [#311](https://github.com/zbnerd/MapleExpectation/issues/311)에 Threat Model 명시

### "비용 최적화는 그냥 늘린 거 아닌가요?"

**아닙니다.**
- '늘리는 것'이 아니라 **frontier(최적점)**을 선택
- N23에서 t3.large($45)가 t3.xlarge($75)보다 **3.1x 높은 처리량**을 증명
- [N23 Report](docs/04_Reports/Cost_Performance/COST_PERF_REPORT_N23.md)에서 RPS/$ 표 확인

## Domain Note

- **데이터 도메인:** MMORPG economy simulation (**예시 도메인**)
- **핵심:** 도메인이 아니라 **운영/성능/복원력/감사 가능성**
- **Codename:** `MapleExpectation` (내부 문서에서 사용)

이 프로젝트는 "게임"이 아니라 **"고부하에서도 안정적으로 동작하는 백엔드 시스템"**을 증명합니다.

## Links

- **[Full README](README.md)** — Complete architecture and tech stack
- **[Architecture Diagram](docs/00_Start_Here/architecture.md)** — System architecture (Mermaid)
- **[Chaos Tests](docs/01_Chaos_Engineering/06_Nightmare/)** — N01-N24 Nightmare scenarios
- **[Score Improvement](SCORE_IMPROVEMENT_SUMMARY.md)** — 49/100 → 90/100 (+41 points)

---

*Last Updated: 2026-02-06*
