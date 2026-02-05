# MapleExpectation 로드맵

> 최종 업데이트: 2026-02-05
> **Documentation Version:** 1.0

## Terminology

| 용어 | 정의 |
|------|------|
| **Stateful 컴포넌트** | 인메모리 상태를 가진 컴포넌트 (분산 장애 요인) |
| **CQRS** | Command Query Responsibility Segregation |
| **ADR** | Architecture Decision Record |
| **Scale-out** | 수평 확장 (여러 인스턴스로 부하 분산) |

## 개요

본 문서는 MapleExpectation 프로젝트의 기술 부채 해소 및 기능 개선을 위한 로드맵입니다.
우선순위는 **보안 → 안정성 → 데이터 무결성 → 관측성 → 성능 → 코드 품질 → Scale-out** 순서를 따릅니다.

---

## 아키텍처 비전

본 프로젝트는 단순히 기능을 구현하는 것을 넘어, **"고가용성 분산 환경에서도 데이터 정합성과 성능을 보장하는 견고한 시스템"** 구축을 목표로 합니다.

### 핵심 원칙

1. **보안 우선**: 인증/인가 없는 API는 절대 허용하지 않음
2. **안정성 > 기능**: 새 기능보다 기존 기능의 안정성이 우선
3. **데이터 무결성**: 데이터 유실/불일치는 즉시 대응
4. **관측성 먼저**: 개선하기 전에 먼저 측정할 수 있어야 함
5. **점진적 개선**: 빅뱅 리팩토링보다 작은 단위의 지속적 개선

---

## Phase 1: 보안 & 즉각적 안정성 (즉시, 1주 내)

> **목표**: 프로덕션 보안 취약점 및 서비스 다운 리스크 제거

| 순서 | 이슈 | 제목 | 우선순위 | 근거 |
|:---:|:---:|------|:---:|------|
| 1 | [#146](https://github.com/zbnerd/MapleExpectation/issues/146) | Admin/핵심 API 인증·인가 최소선 구축 | P0 | 보안 구멍은 모든 것보다 우선 - 인증 없는 API는 공격 벡터 |
| 2 | [#145](https://github.com/zbnerd/MapleExpectation/issues/145) | Nexon WebClient 무한 대기 방지 및 Timeout 강제 | P0 | 무한 대기 = 스레드 고갈 = 서비스 다운 |
| 3 | [#150](https://github.com/zbnerd/MapleExpectation/issues/150) | PermutationUtil OOM 방지 (입력 제한) | P0 | 입력 제한 없으면 DoS 공격 가능 |

### 완료 기준 (Definition of Done)
- [ ] 모든 Admin API에 인증 적용
- [ ] WebClient에 connect/read timeout 설정
- [ ] PermutationUtil 입력 크기 제한 및 검증

---

## Phase 2: 데이터 무결성 (1~2주)

> **목표**: 데이터 유실 및 불일치 방지

| 순서 | 이슈 | 제목 | 우선순위 | 근거 |
|:---:|:---:|------|:---:|------|
| 4 | [#147](https://github.com/zbnerd/MapleExpectation/issues/147) | LikeSyncService 데이터 유실 방지 (Redis 원자성) | P0 | 데이터 유실 = 사용자 신뢰 상실 |
| 5 | [#148](https://github.com/zbnerd/MapleExpectation/issues/148) | TieredCache Race Condition 제거 (L1/L2 일관성) | P0 | L1/L2 불일치 → 잘못된 데이터 노출 |
| 6 | [#130](https://github.com/zbnerd/MapleExpectation/issues/130) | Tiered Locking 정합성 결함 및 MySQL 세션 락 오류 수정 | Bug | #148과 연관, MySQL 세션 락 버그 |

### 완료 기준 (Definition of Done)
- [ ] Redis MULTI/EXEC 또는 Lua 스크립트로 원자성 보장
- [ ] TieredCache 동시성 테스트 통과
- [ ] MySQL Named Lock 세션 고정 검증

---

## Phase 3: 입력 검증 & 방어 (2~3주)

> **목표**: 외부 입력에 대한 방어 체계 구축

| 순서 | 이슈 | 제목 | 우선순위 | 근거 |
|:---:|:---:|------|:---:|------|
| 7 | [#151](https://github.com/zbnerd/MapleExpectation/issues/151) | 입력값 검증(Validation) 전면 적용 | P0/P1 | Phase 1~2 완료 후 전면 적용 |
| 8 | [#152](https://github.com/zbnerd/MapleExpectation/issues/152) | Rate Limiting 도입 | P1 | 부하 폭주 방어 (Phase 1 보안과 시너지) |
| 9 | [#153](https://github.com/zbnerd/MapleExpectation/issues/153) | CI/CD Quality Gate (Test Skip 금지) | P1 | 테스트 스킵 금지로 품질 게이트 강제 |

### 완료 기준 (Definition of Done)
- [ ] 모든 Controller DTO에 Bean Validation 적용
- [ ] IP/User 기반 Rate Limiting 구현
- [ ] CI에서 테스트 스킵 시 빌드 실패 설정

---

## Phase 4: 아키텍처 개선 (1~2개월)

> **목표**: 관측성 확보 및 코드 구조 개선

| 순서 | 이슈 | 제목 | 분류 | 근거 |
|:---:|:---:|------|:---:|------|
| 10 | [#143](https://github.com/zbnerd/MapleExpectation/issues/143) | 관측성(Observability) 인프라 구축 (Loki + Grafana + Tracing) | DevOps | 먼저 관측성 확보 → 이후 개선의 기반 |
| 11 | [#118](https://github.com/zbnerd/MapleExpectation/issues/118) | 비동기 파이프라인 전환 및 .join() 제거 | Refactor | 성능 병목 해소의 핵심 |
| 12 | [#119](https://github.com/zbnerd/MapleExpectation/issues/119) | 순환 참조 제거 및 좋아요 도메인 로직 분리 | Refactor | 코드 복잡도 감소 → 이후 리팩토링 용이 |
| 13 | [#138](https://github.com/zbnerd/MapleExpectation/issues/138) | 메트릭 카디널리티 제어 및 관측성 어스펙트 고도화 | Design | #143과 시너지 |

### 완료 기준 (Definition of Done)
- [ ] Grafana 대시보드 구축 및 알림 설정
- [ ] CompletableFuture 체인에서 .join() 제거
- [ ] 순환 참조 0건 달성
- [ ] 메트릭 카디널리티 1000 이하 유지

---

## Phase 5: 성능 최적화 (2~3개월)

> **목표**: 처리량 및 응답 시간 개선

| 순서 | 이슈 | 제목 | 분류 | 근거 |
|:---:|:---:|------|:---:|------|
| 14 | [#81](https://github.com/zbnerd/MapleExpectation/issues/81) | 트랜잭션 격리 수준 조정 (RR → RC) | Optimization | 동시성 성능 향상 |
| 15 | [#63](https://github.com/zbnerd/MapleExpectation/issues/63) | V3 스트리밍 경로 최적화 및 캐시 표준화 | Performance | V3 경로 개선 |
| 16 | [#48](https://github.com/zbnerd/MapleExpectation/issues/48) | 대량 데이터 동기화 시 DB 락 경합 최적화 | Stability | DB 부하 감소 |
| 17 | [#139](https://github.com/zbnerd/MapleExpectation/issues/139) | 큐브 기대값 엔진 고도화: DP 기반 누적 확률 연산 도입 | Refactor | 핵심 비즈니스 로직 개선 |

### 완료 기준 (Definition of Done)
- [ ] 격리 수준 변경 후 데이터 정합성 테스트 통과
- [ ] P99 응답시간 20% 개선
- [ ] 동시 1000명 부하 테스트 통과

---

## Phase 6: 코드 품질 & 장기 과제

> **목표**: 기술 부채 청산 및 장기적 확장성 확보

| 순서 | 이슈 | 제목 | 분류 |
|:---:|:---:|------|:---:|
| 18 | [#127](https://github.com/zbnerd/MapleExpectation/issues/127) | 데이터 복구 로직의 멱등성 확보 | Reliability |
| 19 | [#128](https://github.com/zbnerd/MapleExpectation/issues/128) | 엔티티-DTO 분리를 통한 API 응답 페이로드 최적화 | Performance |
| 20 | [#120](https://github.com/zbnerd/MapleExpectation/issues/120) | Rich Domain Model 전환 | Refactor |
| 21 | [#126](https://github.com/zbnerd/MapleExpectation/issues/126) | Pragmatic CQRS: 조회/처리 서버 분리 | Architecture |
| 22 | [#80](https://github.com/zbnerd/MapleExpectation/issues/80) | Transactional Outbox 패턴 도입 | Reliability |
| 23 | [#56](https://github.com/zbnerd/MapleExpectation/issues/56) | JaCoCo 테스트 커버리지 분석 및 사각지대 해소 | QA |
| 24 | [#64](https://github.com/zbnerd/MapleExpectation/issues/64) | DTO 네이밍 충돌 해결 및 Runbook 문서화 | Refactor |
| 25 | [#28](https://github.com/zbnerd/MapleExpectation/issues/28) | Pessimistic Lock vs Atomic Update 선택 근거 정리 | Design |
| 26 | [#14](https://github.com/zbnerd/MapleExpectation/issues/14) | 미사용 및 Deprecated 코드베이스 정리 | Cleanup |

---

---

## Phase 7: Scale-out 아키텍처 전환

> **목표**: 단일 서버 한계를 넘어 수평 확장(Scale-out)이 가능한 분산 아키텍처로 전환

### 의존 관계 (Critical Path)

```
                    ┌──────────────────────────────────────┐
                    │         Phase 7 Dependency Graph      │
                    └──────────────────────────────────────┘

  ┌─────────────────────────┐
  │  Step 1 (선행 조건)      │
  │                         │
  │  #283 Stateful 제거     │   P0/P1 In-Memory 컴포넌트를
  │  Scale-out 방해 요소     │   Redis 분산 저장소로 전환
  │                         │
  └────────────┬────────────┘
               │
               │ depends on
               ▼
  ┌─────────────────────────┐
  │  Step 2 (구조 분리)      │
  │                         │
  │  #282 멀티 모듈 전환     │   횡단 관심사를 maple-core로
  │  Cross-Cutting 분리     │   추출하여 공유 모듈화
  │  ADR-014               │
  │                         │
  └────────────┬────────────┘
               │
               │ depends on
               ▼
  ┌─────────────────────────┐
  │  Step 3 (서버 분리)      │
  │                         │
  │  #126 Pragmatic CQRS    │   Query Server + Worker Server
  │  조회/처리 서버 분리     │   이벤트 기반 비동기 파이프라인
  │  ADR-013               │
  │                         │
  └─────────────────────────┘
```

### Step 1: Stateful 컴포넌트 분산화 (#283)

> **전제 조건**: 없음 (즉시 착수 가능)

P0/P1 Stateful 컴포넌트를 분산 환경에서 안전하게 동작하도록 전환한다.

| Sprint | 대상 | 내용 |
|--------|------|------|
| Sprint 1 | Feature Flag 정리 | `redis.enabled` 기본값 `true` 전환 (P0-2, P0-3) |
| Sprint 2 | In-Memory → Redis | AlertThrottler, SingleFlight, Shutdown 플래그 분산화 (P0-1, P0-4, P0-6) |
| Sprint 3 | Scheduler 분산화 | 리더 선출 또는 분산 락 적용 (P0-7, P0-8, P1-7~P1-9) |

**완료 기준:**
- [ ] 모든 P0 In-Memory 상태가 Redis 전환 완료
- [ ] 2개 인스턴스 동시 기동 시 중복 처리 0건
- [ ] Scale-out 검증 부하 테스트 통과

**관련 문서:** [Scale-out 방해 요소 분석 리포트](../04_Reports/scale-out-blockers-analysis.md)

### Step 2: 멀티 모듈 전환 (#282)

> **전제 조건**: Step 1 완료 (Stateful 제거)

횡단 관심사를 공통 모듈로 추출하여 서버 분리의 기반을 구축한다.

| Phase | 대상 모듈 | 내용 |
|-------|----------|------|
| Phase 1 | maple-common | error, response, util, function (POJO) |
| Phase 2 | maple-core | executor, lock, cache, shutdown, resilience, aop (Infrastructure) |
| Phase 3 | maple-domain | entity, repository (Domain) |
| Phase 4 | maple-app 정리 | 기존 global 패키지 제거, import 경로 수정 |

**완료 기준:**
- [ ] 4개 모듈 정상 빌드
- [ ] `implementation project(':maple-core')` 한 줄로 인프라 Bean 자동 등록
- [ ] 순환 의존 없음 (Gradle dependency report)

**관련 문서:** [ADR-014: 멀티 모듈 전환](../adr/ADR-014-multi-module-cross-cutting-concerns.md)

### Step 3: Pragmatic CQRS (#126)

> **전제 조건**: Step 1 + Step 2 완료

조회 서버와 처리 서버를 물리적으로 분리하여 독립적 확장을 실현한다.

| 구성 요소 | 역할 | 모듈 구성 |
|----------|------|----------|
| Query Server (maple-api) | DB/Cache 조회 전용, 가벼운 응답 | maple-core + maple-domain |
| Worker Server (maple-worker) | 외부 API 연동, 계산, 압축 | maple-core + maple-domain |
| Message Broker | 서버 간 느슨한 결합 | Kafka / RabbitMQ |

**완료 기준:**
- [ ] 조회 서버 CPU 사용량 기존 대비 60% 절감
- [ ] 대량 업데이트 시에도 메인 API 응답 속도 일정 유지
- [ ] Worker 장애 시 조회 서비스 생존 확인

**관련 문서:** [ADR-013: 비동기 이벤트 파이프라인](../adr/ADR-013-high-throughput-event-pipeline.md)

---

## 마일스톤 요약

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│  Week 1      │  Week 2-3    │  Week 4-6    │  Month 2-3   │  Month 4+  │ Month 6+ │
├───────────────────────────────────────────────────────────────────────────────────┤
│  Phase 1     │  Phase 2     │  Phase 3     │  Phase 4     │  Phase 5-6 │ Phase 7  │
│  보안/안정성  │  데이터무결성 │  입력검증    │  아키텍처    │  성능/품질  │ Scale-out│
│  #146,145,150│  #147,148,130│  #151,152,153│  #143,118,119│  나머지     │ #283→282 │
│              │              │              │              │            │  →#126   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

---

## 기술적 임계점 분석

| 분석 차원 | 현재 상태 | 해결 후 목표 |
|:---|:---|:---|
| **보안** | 일부 API 인증 미적용 | 모든 API 인증/인가 적용 |
| **운영 안정성** | 무한 대기, OOM 리스크 존재 | 무중단 및 자가 치유(Self-healing) 시스템 |
| **데이터 정합성** | Race condition, 원자성 미보장 | 완전한 데이터 무결성 보장 |
| **관측성** | 기본 로깅만 존재 | Grafana + Loki + Tracing 완비 |
| **시스템 확장성** | Stateful 단일 서버 | Stateless CQRS 기반 수평 확장 |

---

## 변경 이력

| 날짜 | 변경 내용 |
|------|----------|
| 2026-02-05 | 문서 무결성 검사 및 버전 관리 추가 |
| 2026-01-28 | Phase 7(Scale-out) 추가 - #283, #282, #126 의존 관계 정리 |
| 2025-01-07 | 로드맵 전면 재작성 - 26개 이슈 우선순위 기반 Phase별 분류 |
| 2025-01-04 | 초기 아키텍처 비전 작성 |

## Fail If Wrong

이 로드맵이 부정확한 경우:
- **의존 관계가 잘못됨**: Phase 7 (#283 → #282 → #126) 확인
- **이슈 번호가 변경됨**: GitHub Issues와 대조
- **완료 기준이 모호함**: DoD(Definition of Done) 확인

### Verification Commands
```bash
# 의존 관계 확인
grep -A 5 "#283\|#282\|#126" docs/00_Start_Here/ROADMAP.md

# 이슈 참조 확인
grep -c "github.com" docs/00_Start_Here/ROADMAP.md

# Phase별 이슈 수 확인
grep "^|.*#" docs/00_Start_Here/ROADMAP.md | wc -l
```
