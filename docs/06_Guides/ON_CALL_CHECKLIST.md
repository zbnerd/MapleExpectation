# 🔧 On-call Engineer Checklist

**버전**: 1.0
**마지막 업데이트**: 2026-02-05
**적용 범위**: MapleExpectation Production Operations

---

## 📋 목차

1. [일일 점검 (Daily Checklist)](#1-일일-점검-daily-checklist)
2. [주간 점검 (Weekly Checklist)](#2-주간-점검-weekly-checklist)
3. [장애 대응 절차 (Incident Response)](#3-장애-대응-절차-incident-response)
4. [Escalation Path](#4-escalation-path)
5. [공통 장애 모드 및 대응 (Common Failure Modes)](#5-공통-장애-모드-및-대응-common-failure-modes)
6. [연락처 (Contact Information)](#6-연락처-contact-information)
7. [권장 도구 (Recommended Tools)](#7-권장-도구-recommended-tools)

---

## 1. 일일 점검 (Daily Checklist)

### 1.1 아침 점검 (09:00 KST, 소요 5분)

**시스템 건강성 확인**

- [ ] **Grafana Dashboard 확인**
  - [ ] p99 응답 시간 < 100ms (baseline: 50ms)
  - [ ] 에러율 < 0.1% (baseline: 0.05%)
  - [ ] RPS > 0 (트래픽 정상 유입 확인)
  - [ ] Dashboard: `maple-expectation-production`

- [ ] **알림 확인**
  - [ ] PagerDuty/Slack 알림 없는지 확인
  - [ ] 지난 24시간 Critical/Warning 경고 수집

- [ ] **Outbox Queue 확인**
  - [ ] PENDING + FAILED status 수 < 100건
  - [ ] DLQ(DEAD_LETTER) 수 < 10건
  - [ ] Replay lag (oldest PENDING timestamp) < 5분
  - [ ] Query: Section 8.1 참조

**비정상 발견 시**: → [장애 대응 절차](#3-장애-대응-절차-incident-response) 이동

### 1.2 점심 점검 (12:00 KST, 소요 3분)

**리소스 사용량 확인**

- [ ] **AWS CloudWatch 확인**
  - [ ] CPU 사용률 < 60% (t3.small: 2 vCPU)
  - [ ] 메모리 사용률 < 70% (2GB RAM)
  - [ ] DB 연결 풀 사용률 < 80%

- [ ] **Redis 상태**
  - [ ] 메모리 사용률 < 70%
  - [ ] eviction 발생 없음
  - [ ] replication lag < 1초

### 1.3 저녁 점검 (18:00 KST, 소요 3분)

**일일 요약 및 인수인계**

- [ ] **Slack #on-call 채널에 일일 요약 작성**
  - [ ] 발생한 장애 (있는 경우)
  - [ ] 확인된 이상 징후
  - [ ] 다음 on-call engineer에게 인계 필요 사항

- [ ] **Long-running task 확인**
  - [ ] Nightmare 테스트 실행 중인지 확인
  - [ ] 배포 진행 중인지 확인

---

## 2. 주간 점검 (Weekly Checklist)

**매주 금요일 15:00 KST, 소요 30분**

### 2.1 데이터 정합성 검증

- [ ] **Reconciliation Invariant 확인**
  - [ ] `SELECT COUNT(*) FROM donation_outbox WHERE processed = false` 결과 0인지 확인
  - [ ] N19 Chaos Test 기준: 2,160,000 events 보존 확인 (주간 누적)
  - [ ] 불일치 발견 시 → [Data Loss Investigation](#53-data-loss-investigation) 참조

### 2.2 성능 회귀 확인

- [ ] **이번 주 vs 저번 주 메트릭 비교**
  - [ ] p99 응답 시간 regression > 20% 없는지 확인
  - [ ] 에러율 증가 없는지 확인
  - [ ] Throughput(RPS) 감소 없는지 확인

### 2.3 보안 점검

- [ ] **보안 로그 확인**
  - [ ] 실패한 인증 시도 > 100건/일 없는지 확인
  - [ ] 의심스러운 API 호출 패턴 없는지 확인
  - [ ] DLQ 데이터 접근 로그 확인

### 2.4 문서 업데이트

- [ ] **주간 발생 장애 문서화**
  - [ ] 발생한 장애의 Root Cause Analysis 작성
  - [ ] Action Items 업데이트
  - [ ] Runbook 개선 사항 반영

### 2.5 용량 계획 (Capacity Planning)

- [ ] **현재 리소스 사용량 추세 분석**
  - [ ] CPU/메모리/디스크 사용량 증가율 확인
  - [ ] 2주내 리소스 고갈 예상되면 Scale-out 계획 수립
  - [ ] Section 8.2 용량 계산 참조

---

## 3. 장애 대응 절차 (Incident Response)

### 3.1 장애 심각도 수준 (Severity Levels)

| Level | 이름 | 조건 | 대응 목표 | Escalation |
|-------|------|------|----------|------------|
| **SEV-0** | Critical | 서비스 완전 중단 | 15분 내 시작 | 즉시 Engineering Lead |
| **SEV-1** | High | 핵심 기능 불가 | 30분 내 시작 | 1시간 후 Engineering Lead |
| **SEV-2** | Medium | 기능 저하 (성능/일부 기능) | 1시간 내 시작 | 4시간 후 Team Lead |
| **SEV-3** | Low | 사소한 문제 | 다음 영업일 | 주간 회의 때 보고 |

### 3.2 SEV-0/1 장애 대응 절차 (15분 시작 목표)

**Step 1: 장애 인지 및 확인 (0-5분)**

- [ ] PagerDuty/Slack 알림 수신 확인
- [ ] Grafana Dashboard에서 현상 확인
- [ ] 영향 범위 파악 (사용자 수, 영향 지역)

**Step 2: 장애 선언 및 역할 분담 (5-10분)**

- [ ] Slack #incidents 채널에 장애 선언
  - 형식: `[SEV-X] 장애 선언: <짧은 설명>`
- [ ] Incident Commander 지정 (보통 On-call Engineer)
- [ ] 역할 분담:
  - **Incident Commander**: 전체 조율
  - **Communication Lead**: 사용자 커뮤니케이션
  - **Technical Lead**: 기술적 조사 및 복구

**Step 3: 초기 진단 및 완화 (10-30분)**

- [ ] 증상 수집 (로그, 메트릭, 트레이스)
- [ ] 잠정적 Root Cause 추정
- [ ] 완화책(Mitigation) 실행 가능 여부 확인
- [ ] 완화책 실행 (가능한 경우)

**Step 4: Root Cause 분석 및 완전 복구 (30분-2시간)**

- [ ] Root Cause 확정
- [ ] 영구적 수정(Permanent Fix) 적용
- [ ] 복구 검증
- [ ] 서비스 정상 선언

**Step 5: 사후 분석 (Post-Incident, 2-24시간 이내)**

- [ ] Incident Report 작성 (template: `docs/98_Templates/Chaos_Report_Template.md`)
- [ ] Postmortem 회의 예약
- [ ] Action Items 식별 및 할당

### 3.3 N21 Auto-Mitigation 실행 가이드

**자동 완화 조건** (Evidence: N21_INCIDENT_REPORT)

- [ ] MTTD (Mean Time To Detect): 30초
- [ ] MTTR (Mean Time To Resolve): 2분
- [ ] 자동 승인 조건:
  - Symptom: p99 > 1,000ms (10배 악화)
  - Confidence: ≥ 80%
  - Data loss: 0건 확인 (SQL: `SELECT COUNT(*) FROM donation_outbox WHERE processed = false`)

**실행 절차**:

1. **증상 기반 분류** (T+30s)
   - [ ] Grafana에서 p99 spike 확인
   - [ ] Cache miss rate surge 확인
   - [ ] DB connection pool saturation 확인

2. **자동 완화 실행** (T+60s)
   - [ ] Circuit Breaker 확인 (auto-mitigation-programmatic)
   - [ ] 완화책 승인 (confidence ≥ 80%)
   - [ ] 조치 실행: Pool size 증설 (10 → 20)

3. **복구 확인** (T+2m)
   - [ ] p99 복구 (< 100ms)
   - [ ] Cache miss rate 복구 (< 10%)
   - [ ] Zero data loss 확인

**Rollback 조건** (Evidence: N21_INCIDENT_REPORT, Section 5):

- [ ] p99가 5분 동안 개선 없음
- [ ] 에러율 > 5% 지속
- [ ] Data loss 발생
- [ ] 조치: `curl -X POST http://localhost:8080/actuator/configprops` 이전 설정 복원

---

## 4. Escalation Path

### 4.1 Escalation Tree

```
On-call Engineer
    ↓ (즉시, SEV-0)
Engineering Lead / Tech Lead
    ↓ (1시간, 개선 없음)
Engineering Manager
    ↓ (4시간, 개선 없음)
CTO / VP Engineering
```

### 4.2 Escalation 트리거

**즉시 Escalation (SEV-0)**:
- 서비스 완전 중단
- Data loss 발생
- 보안 침해 의심

**1시간 후 Escalation (SEV-1)**:
- 핵심 기능 불가 지속
- 첫 번째 완화책 실패

**4시간 후 Escalation (SEV-2)**:
- 기능 저하 지속
- 원인 불명

**주간 회의 때 보고 (SEV-3)**:
- 사소한 문제
- 일상적인 장애

### 4.3 Escalation 메시지 템플릿

**Slack #incidents**

```markdown
@here [ESCALATION] SEV-X: <장애 제목>

현재 상황: <2-3문장 요약>
경과 시간: <분/시간>
시도한 완화책: <목록>
다음 단계: <계획>
도움 필요: @Engineering Lead
```

---

## 5. 공통 장애 모드 및 대응 (Common Failure Modes)

### 5.1 Outbox Replay 장애

**증상**:
- PENDING/FAILED status 지속적 증가
- Replay lag > 10분
- DLQ 증가

**진단**:
```sql
-- PENDING 수 확인
SELECT COUNT(*) FROM nexon_api_outbox WHERE status IN ('PENDING', 'FAILED');

-- Oldest PENDING 확인
SELECT MIN(created_at) FROM nexon_api_outbox WHERE status = 'PENDING';

-- DLQ 수 확인
SELECT COUNT(*) FROM nexon_api_outbox WHERE status = 'DEAD_LETTER';
```

**완화**:
1. Scheduler 상태 확인: `curl http://localhost:8080/actuator/health`
2. Scheduler 재시작 (필요 시): `systemctl restart maple-expectation`
3. Batch size 조정 (Evidence: ADR-016): `application.yml` `outbox.replay.batch-size=200`

**영구적 수정**:
- External API 복구 확인
- Network 연결 확인
- DB connection pool 확인

### 5.2 Redis Connection 장애

**증상**:
- Cache miss rate > 50%
- `RedisConnectionException` 로그
- 응답 시간 2배 이상 증가

**진단**:
```bash
# Redis 연결 확인
redis-cli -h localhost -p 6379 ping

# Redis 메모리 확인
redis-cli -h localhost -p 6379 INFO memory

# Redis connection 수 확인
redis-cli -h localhost -p 6379 INFO clients
```

**완화**:
1. Redis 재시작: `systemctl restart redis`
2. Redis failover (cluster mode): `redis-cli --cluster failover`
3. Cache warmup: `curl -X POST http://localhost:8080/actuator/cache/warmup`

**영구적 수정**:
- Redisson 재연결 설정 확인 (Evidence: ADR-006)
- Cluster 헬스 체크

### 5.3 Database Connection Pool 고갈

**증상**:
- `PoolExhaustedException` 로그
- 요청 타임아웃 증가
- Active connections = max pool size

**진단**:
```sql
-- Active connections 확인
SHOW PROCESSLIST;

-- Connection pool 사용률 (Grafana)
Dashboard: "maple-expectation-database"
Panel: "Connection Pool Usage"
```

**완화**:
1. Pool size 증설 (Actuator refresh):
   ```bash
   curl -X POST http://localhost:8080/actuator/configprops \
     -H "Content-Type: application/json" \
     -d '{"spring.datasource.hikari.maximum-pool-size": 20}'
   ```
2. Long-running query 종료
3. Short-term connection kill (필요 시)

**영구적 수정**:
- Slow query 최적화
- Connection pool size tuning (Evidence: `load-test/high-traffic-performance-analysis.md`)

### 5.4 Circuit Breaker Open

**증상**:
- `CircuitBreakerOpenException` 로그
- 요청 실패 급증
- External API 호출 실패

**진단**:
```bash
# Circuit Breaker 상태 확인
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

**완화**:
1. External API 복구 대기 (자동 half-open)
2. 수동 reset (필요 시):
   ```bash
   curl -X POST http://localhost:8080/actuator/circuitbreakers/reset
   ```

**영구적 수정**:
- External API 장애 원인 해결
- Circuit Breaker 설정 조정 (Evidence: ADR-005)

### 5.5 Data Loss Investigation

**트리거**: Reconciliation invariant mismatch ≠ 0

**진단 절차**:
```sql
-- 1. Expected events 수 확인
SELECT COUNT(*) AS expected_events
FROM nexon_api_outbox
WHERE created_at >= '2026-02-05 00:00:00' AND created_at < '2026-02-06 00:00:00';

-- 2. Processed events 수 확인
SELECT COUNT(*) AS processed_success
FROM nexon_api_outbox
WHERE status = 'COMPLETED'
AND updated_at >= '2026-02-05 00:00:00';

-- 3. DLQ events 수 확인
SELECT COUNT(*) AS dlq_events
FROM nexon_api_outbox
WHERE status = 'DEAD_LETTER'
AND updated_at >= '2026-02-05 00:00:00';

-- 4. mismatch 계산
-- mismatch = expected_events - (processed_success + dlq_events)
-- mismatch ≠ 0 이면 Data Loss 발생
```

**Data Loss 발견 시**:
1. **즉시 Escalation** (SEV-0)
2. **영향 범위 파악**: lost OCIDs 목록 추출
3. **수동 복구**: External API 재요청
4. **Root Cause 분석**: 왜 loss가 발생했는가?
   - Outbox INSERT 실패?
   - Replay 중 data corruption?
   - DB 트랜잭션 rollback?

---

## 6. 연락처 (Contact Information)

> **⚠️ 보안**: 실제 운영 시에는 민감한 정보를 암호화하거나 비밀 저장소에 저장하세요.

### 6.1 On-call Schedule

| 기간 | On-call Engineer | Slack | 연락처 |
|------|------------------|-------|--------|
| 2026-02-05 ~ 2026-02-12 | @engineer1 | @engineer1 | +82-10-XXXX-XXXX |
| 2026-02-12 ~ 2026-02-19 | @engineer2 | @engineer2 | +82-10-XXXX-XXXX |

### 6.2 Escalation Contacts

| 역할 | 이름 | Slack | 연락처 |
|------|------|-------|--------|
| Engineering Lead | @tech-lead | @tech-lead | +82-10-XXXX-XXXX |
| Engineering Manager | @eng-manager | @eng-manager | +82-10-XXXX-XXXX |
| CTO | @cto | @cto | +82-10-XXXX-XXXX |

### 6.3 Emergency Contacts

| 상황 | 연락처 |
|------|--------|
| Data center 장애 | AWS Support +81-3-XXXX-XXXX |
| Security incident | security@company.com |
| Legal issue | legal@company.com |

---

## 7. 권장 도구 (Recommended Tools)

### 7.1 모니터링 (Monitoring)

- **Grafana**: 메트릭 시각화
  - Dashboard: `maple-expectation-production`
  - Panels: p99 latency, error rate, RPS, cache hit rate, DB connection pool

- **Prometheus**: 메트릭 수집
  - Exporters: Spring Actuator, Redis Exporter, MySQL Exporter

- **Loki**: 로그 집계
  - Query: `{job="maple-expectation"} |= "ERROR"`

### 7.2 알림 (Alerting)

- **PagerDuty**: On-call 알림
- **Slack**: #incidents, #on-call 채널
- **Email**: Non-critical 알림

### 7.3 장애 대응 (Incident Response)

- **Slack Workflow Builder**: Incident declaration template
- **Google Docs**: Incident Report 협업 작성
- **Confluence**: Runbook 저장소

### 7.4 데이터베이스 (Database)

- **MySQL Workbench**: SQL 쿼리 실행
- **redis-cli**: Redis 진단

### 7.5 배포 (Deployment)

- **GitHub Actions**: CI/CD
- **AWS CodeDeploy**: Blue/Green deployment

---

## 8. 부록 (Appendix)

### 8.1 자주 사용하는 SQL 쿼리

```sql
-- Outbox health check
SELECT
  status,
  COUNT(*) AS count,
  MIN(created_at) AS oldest,
  MAX(created_at) AS newest
FROM nexon_api_outbox
GROUP BY status;

-- Replay lag 확인
SELECT
  TIMESTAMPDIFF(MINUTE, MIN(created_at), NOW()) AS lag_minutes
FROM nexon_api_outbox
WHERE status = 'PENDING';

-- DLQ 분석
SELECT
  last_error,
  COUNT(*) AS count
FROM nexon_api_outbox
WHERE status = 'DEAD_LETTER'
AND updated_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY last_error
ORDER BY count DESC;

-- Reconciliation invariant (N19 기준)
SELECT
  (SELECT COUNT(*) FROM nexon_api_outbox
   WHERE created_at >= '2026-02-05 14:00:00' AND created_at < '2026-02-05 20:35:00') AS expected_events,
  (SELECT COUNT(*) FROM nexon_api_outbox
   WHERE status = 'COMPLETED' AND updated_at >= '2026-02-05 20:35:00') AS processed_success,
  (SELECT COUNT(*) FROM nexon_api_outbox
   WHERE status = 'DEAD_LETTER' AND updated_at >= '2026-02-05 20:35:00') AS dlq_events;
```

### 8.2 용량 계산 (Capacity Planning)

**현재 사양**: AWS t3.small (2 vCPU, 2GB RAM)

**CPU 사용량**:
- Baseline: 20% (40 RPS)
- 240 RPS: 60% (target)
- Headroom: 40% → 최대 320 RPS까지 가능

**메모리 사용량**:
- Heap: 1GB
- Off-heap: 500MB (OS + Native)
- Headroom: 512MB

**Scale-out 트리거**:
- CPU > 80% (지속 10분)
- Memory > 85%
- Response time p99 > 200ms

**권장**: CloudWatch Alarm 설정
```json
{
  "alarm_name": "cpu-high",
  "metric": "CPUUtilization",
  "threshold": 80,
  "period": 600,
  "evaluation_periods": 1
}
```

### 8.3 Runbook 인덱스

| Runbook | 위치 | 설명 |
|---------|------|------|
| Outbox Replay | `docs/03_Technical_Guides/infrastructure.md#section-8-2` | Outbox pattern 가이드 |
| Redis Cache | `docs/03_Technical_Guides/infrastructure.md#section-17` | Tiered cache 가이드 |
| Circuit Breaker | `docs/03_Technical_Guides/resilience.md` | Resilience patterns |
| Graceful Shutdown | `docs/01_Adr/ADR-008-durability-graceful-shutdown.md` | Shutdown 절차 |
| Scheduler | `docs/05_Reports/scale-out-blockers-analysis.md#p1-7-8-9` | Distributed lock 가이드 |

---

*이 checklist는 MapleExpectation 프로젝트의 운영 안정성을 위해 작성되었습니다.*
*모든 피드백은 `docs/98_Templates/ISSUE_TEMPLATE.md`를 통해 제출해주세요.*

**버전 관리**:
- v1.0 (2026-02-05): 최초 작성
- 변경 이력은 `git log docs/05_Guides/ON_CALL_CHECKLIST.md`로 확인 가능
