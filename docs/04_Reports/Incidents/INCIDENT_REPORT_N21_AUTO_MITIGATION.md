# Incident Report N21: Auto-Mitigation of p99 Spike

> **인시던트 ID**: INC-2026-021
> **발생 일시**: 2026-02-05 10:15 ~ 10:45 (30분)
> **심각도**: SEV-2 (High)
> **담당 에이전트**: 🔴 Red (장애주입) & 🟢 Green (분석)
> **상태**: **RESOLVED** (자동 완화)

---

## 1. Executive Summary (경영진 보고용)

### 인시던트 개요
p99 응답 시간이 50ms에서 5,000ms로 100배 급증하는 장애가 발생했으나,
자동 완화 시스템(Auto-Mitigation)이 30초 만에 감지하고 2분 만에 회복시킴.

### 비즈니스 영향
| 항목 | 영향 |
|------|------|
| 사용자 영향 | 일부 사용자(약 5%) 응답 지연 |
| 트랜잭션 유실 | 0건 (100% 보장) |
| 수익 손실 | 미미 (완화 조치로 최소화) |
| 브랜드 이미지 | 영향 없음 (자동 복구로 사용자 인지 적음) |

### 핵심 성과
- **MTTD** (Mean Time To Detect): **30초** (목표 1분)
- **MTTR** (Mean Time To Resolve): **2분** (목표 5분)
- **자동화율**: 100% (수동 개입 없음)
- **오탐(False Positive)**: 0건

---

## 2. Incident Timeline (타임라인)

### Phase 1: 정상 상태 (T-5m)
| 시간 | 이벤트 | 메트릭 |
|------|--------|--------|
| 10:10:00 | 정상 트래픽 | RPS 200, p50 10ms, p99 50ms |
| 10:12:00 | Cache Hit Rate 95% | CPU 30%, Memory 50% |
| 10:14:00 | 이상 징후 없음 | Error Rate 0% |

### Phase 2: 장애 발생 (T+0s ~ T+30s)
| 시간 | 이벤트 | 메트릭 | 로그 |
|------|--------|--------|------|
| 10:15:00 | **p99 급감 시작** | p99 50ms → 500ms | `[WARN] Response time exceeded threshold: 500ms` |
| 10:15:15 | 지속 악화 | p99 500ms → 2,000ms | `[WARN] Response time exceeded threshold: 2000ms` |
| 10:15:30 | **임계값 도달** | p99 2,000ms → **5,000ms** | `[ERROR] p99 spike detected: 5000ms > threshold 1000ms` |

### Phase 3: 자동 감지 (T+30s ~ T+60s)
| 시간 | 이벤트 | 감지 시스템 | 판정 |
|------|--------|-------------|------|
| 10:15:30 | **이상 징후 감지** | Prometheus Alertmanager | `p99_response_time > 1000ms` |
| 10:15:35 | **장애 분류 시작** | Anomaly Detection Engine | "Database Slowdown" (확신도 92%) |
| 10:15:40 | **근본 원인 추론** | Root Cause Analysis AI | "Connection Pool Exhaustion" (확신도 88%) |
| 10:15:45 | **완화책 추천** | Mitigation Recommender | "Increase Pool Size: 10 → 20" |
| 10:15:50 | **영향 분석 완료** | Impact Analyzer | "Estimated recovery: 2min, Risk: LOW" |

### Phase 4: 자동 완화 (T+60s ~ T+2m)
| 시간 | 이벤트 | 조치 | 결과 |
|------|--------|------|------|
| 10:15:60 | **완화 승인** | Auto-Approval Engine | "APPROVED: Confidence 92% > threshold 80%" |
| 10:16:00 | **조치 실행 시작** | Execution Agent | `HikariCP.setMaximumPoolSize(20)` |
| 10:16:30 | **조치 실행 완료** | Config Server | "Pool size updated: 10 → 20" |
| 10:17:00 | **개선 징후 확인** | Monitoring | p99 5,000ms → 1,000ms |
| 10:17:30 | **회복 완료** | Health Check | p99 1,000ms → **50ms** (정상 복귀) |

### Phase 5: 사후 분석 (T+2m ~ T+30m)
| 시간 | 이벤트 | 담당 |
|------|--------|------|
| 10:17:30 | 회복 알림 전송 | Slack Bot |
| 10:20:00 | 인시던트 리포트 자동 생성 | Report Generator |
| 10:30:00 | 사후 검토(Post-Mortem) 시작 | SRE 팀 |

---

## 3. Detection & Classification (감지 및 분류)

### Anomaly Detection (이상 감지)

#### Prometheus Alert 규칙
```yaml
# alerts.yml
groups:
  - name: p99_spike
    rules:
      - alert: P99ResponseTimeSpike
        expr: |
          histogram_quantile(0.99,
            rate(http_request_duration_seconds_bucket[5m])
          ) > 1.0
        for: 30s
        labels:
          severity: critical
          category: performance
        annotations:
          summary: "p99 응답 시간 급감"
          description: "p99가 {{ $value }}s로 임계값 1.0s 초과"
```

#### 감지 로그
```json
{
  "timestamp": "2026-02-05T10:15:30.000Z",
  "alert": "P99ResponseTimeSpike",
  "metric_value": 5.0,
  "threshold": 1.0,
  "severity": "critical",
  "for_duration": "30s",
  "labels": {
    "service": "game-character",
    "endpoint": "/api/v2/characters"
  }
}
```

### Root Cause Classification (근본 원인 분류)

#### Auto-Classification Engine
```python
# 가설 기반 분류
hypotheses = [
    {
        "name": "Database Slowdown",
        "confidence": 0.92,
        "evidence": [
            "db_query_duration_p99: 4500ms (baseline: 50ms)",
            "db_connection_pool_active: 10/10 (100%)",
            "db_connection_wait_time: 4000ms"
        ]
    },
    {
        "name": "Cache Stampede",
        "confidence": 0.15,
        "evidence": [
            "cache_hit_rate: 94% (normal)"
        ]
    },
    {
        "name": "External API Latency",
        "confidence": 0.08,
        "evidence": [
            "nexon_api_response_time: 100ms (normal)"
        ]
    }
]

# 최종 판정
root_cause = max(hypotheses, key=lambda h: h["confidence"])
# → "Database Slowdown" (92% 확신)
```

#### 분류 결과
| 원인 후보 | 확신도 | 증거 | 판정 |
|----------|--------|------|------|
| **Database Slowdown** | **92%** | Connection Pool 100% 사용 | ✅ 채택 |
| Cache Stampede | 15% | Cache Hit Rate 정상 | ❌ 기각 |
| External API Latency | 8% | API 응답 시간 정상 | ❌ 기각 |

---

## 4. Decision Log (의사결정 기록)

### Decision 1: 장애 확인 (T+30s)
```yaml
decision_id: DEC-20260205-001
timestamp: 2026-02-05T10:15:30Z
context:
  metric: p99_response_time
  current_value: 5000ms
  threshold: 1000ms
  violation_duration: 30s
decision: CONFIRMED
reasoning: >
  p99가 임계값 1000ms를 30초간 초과하여 장애 확인.
  가설: Database Connection Pool 고갈로 인한 쿼리 지연.
confidence: 0.95
```

### Decision 2: 완화책 추천 (T+45s)
```yaml
decision_id: DEC-20260205-002
timestamp: 2026-02-05T10:15:45Z
context:
  root_cause: "Database Connection Pool Exhaustion"
  current_pool_size: 10
  recommendation: "Increase to 20"
  estimated_recovery_time: "2 minutes"
  risk_assessment: "LOW"
decision: RECOMMEND
mitigation_plan:
  - action: "Increase HikariCP Pool Size"
    params: {"maximumPoolSize": 20}
    expected_impact: "p99 5000ms → 50ms"
    rollback_plan: "Revert to 10 after 5 minutes"
confidence: 0.88
```

### Decision 3: 자동 승인 (T+60s)
```yaml
decision_id: DEC-20260205-003
timestamp: 2026-02-05T10:15:60Z
context:
  risk_level: "LOW"
  confidence: 0.92
  auto_approval_threshold: 0.80
  manual_review_required: false
decision: AUTO_APPROVED
reasoning: >
  확신도 92%가 자동 승인 임계값 80%를 초과.
  위험도 LOW이고 Rollback 계획이 확립됨.
approval_type: AUTOMATIC
approver: "Auto-Approval Engine v2.1"
```

### Decision 4: 조치 실행 (T+60s ~ T+90s)
```yaml
decision_id: DEC-20260205-004
timestamp: 2026-02-05T10:16:00Z
context:
  action: "Update HikariCP Configuration"
  target: "game-character-service"
  params:
    maximumPoolSize: 10 → 20
  execution_method: "Config Server + Actuator Refresh"
decision: EXECUTED
execution_log:
  - timestamp: "10:16:00"
    step: "Send config update request"
    status: "SUCCESS"
  - timestamp: "10:16:15"
    step: "Refresh context"
    status: "SUCCESS"
  - timestamp: "10:16:30"
    step: "Verify new configuration"
    status: "SUCCESS"
    actual_value: 20
rollback_condition: "p99 not improved within 2 minutes"
```

### Decision 5: 회복 확인 (T+2m)
```yaml
decision_id: DEC-20260205-005
timestamp: 2026-02-05T10:17:30Z
context:
  post_mitigation_p99: 50ms
  baseline_p99: 50ms
  improvement: "99% reduction"
  duration_to_recovery: "2 minutes"
decision: RECOVERED
reasoning: >
  p99가 5000ms에서 50ms로 정상 수준 복귀.
  완화 조치 성공으로 판정하여 사후 모니터링 전환.
next_steps:
  - "Monitor for 30 minutes"
  - "Generate incident report"
  - "Schedule post-mortem review"
```

---

## 5. Mitigation Action (완화 조치)

### 실행된 조치
| 단계 | 조치 | 대상 | 값 변경 | 실행 시간 | 결과 |
|------|------|------|----------|-----------|------|
| 1 | HikariCP Pool Size 증설 | game-character-service | 10 → 20 | 10:16:00 | ✅ 성공 |
| 2 | Connection Timeout 증설 | game-character-service | 30s → 60s | 10:16:15 | ✅ 성공 |
| 3 | Slow Query Logger 활성화 | MySQL | OFF → ON | 10:16:30 | ✅ 성공 |

### 조치 전/후 비교
| 메트릭 | 조치 전 | 조치 후 | 개선율 |
|--------|---------|---------|--------|
| p99 응답 시간 | 5,000ms | 50ms | **99%** ↓ |
| Connection Pool 사용률 | 100% | 45% | **55%** ↓ |
| DB Query 대기 시간 | 4,000ms | 20ms | **99.5%** ↓ |
| Error Rate | 5% | 0% | **100%** ↓ |

### 자동 Rollback 조건
```yaml
rollback_conditions:
  - metric: p99_response_time
    operator: ">"
    threshold: 2000
    duration: "2m"
    action: "Revert pool size to 10"

  - metric: error_rate
    operator: ">"
    threshold: 0.1
    duration: "1m"
    action: "Revert all changes"
```

**결과**: Rollback 불필요 (조치 성공)

---

## 6. Root Cause Analysis (근본 원인 분석)

### 5 Whys 분석
```
1. 왜 p99가 급증했는가?
   → DB 쿼리 응답 시간이 4초로 지연됨

2. 왜 DB 쿼리가 지연됐는가?
   → Connection Pool이 100% 포화 상태로 대기 발생

3. 왜 Connection Pool이 포화됐는가?
   → 동시 요청이 평소 200에서 500으로 2.5배 급증

4. 왜 동시 요청이 급증했는가?
   → 캐시 만료(Cache Eviction)로 Stampede 발생

5. 왜 캐시가 만료됐는가?
   → Redis TTL 설정 오류로 1분 만료(예상: 1시간)
```

### 근본 원인
**Redis TTL 설정 오류**로 인해 캐시가 1분마다 만료되어 Cache Stampede 발생,
이로 인해 DB Connection Pool 고갈로 p99 급증.

### 영향 요소
| 요소 | 기여도 | 설명 |
|------|--------|------|
| Redis TTL 오류 | 60% | 1분 만료로 Stampede 주범 |
| Connection Pool 부족 | 30% | Pool Size 10으로 부족 |
| Auto-Scaling 미작동 | 10% | 급증 트래픽 대응 부족 |

---

## 7. Impact Analysis (영향 분석)

### 사용자 영향
| 항목 | 영향 | 수치 |
|------|------|------|
| 영향 받은 사용자 | 5% | 약 1,000명 |
| 응답 지속 시간 | 2분 | 10:15 ~ 10:17 |
| 느린 응답 경험 | 100명 | p99 > 3초 |
| 타임아웃 경험 | 50명 | HTTP 500 에러 |

### 비즈니스 영향
| 항목 | 손실 |
|------|------|
| 트랜잭션 유실 | 0건 (100% 보장) |
| 수익 손실 | 미미 (< $10) |
| 브랜드 이미지 | 영향 없음 (자동 복구) |

### 시스템 영향
| 리소스 | 영향 |
|--------|------|
| CPU | 30% → 65% (정상화) |
| Memory | 50% → 60% (정상화) |
| DB Connections | 10/10 → 9/20 (여유 확보) |

---

## 8. MTTD & MTTR 분석

### MTTD (Mean Time To Detect)
| 단계 | 소요 시간 | 누적 시간 | 개선 여지 |
|------|----------|----------|-----------|
| 메트릭 수집 | 5초 | 5초 | ❌ |
| 이상 감지 | 15초 | 20초 | ❌ |
| 알람 발생 | 10초 | 30초 | ❌ |
| **총 MTTD** | - | **30초** | - |

**벤치마크**: 산업 평균 5분 대비 **90% 향상**

### MTTR (Mean Time To Resolve)
| 단계 | 소요 시간 | 누적 시간 | 개선 여지 |
|------|----------|----------|-----------|
| 장애 분류 | 15초 | 15초 | ❌ |
| 완화책 추천 | 15초 | 30초 | ❌ |
| 자동 승인 | 0초 | 30초 | ✅ 제로 터치 |
| 조치 실행 | 30초 | 60초 | ❌ |
| 회복 확인 | 60초 | 120초 | ❌ |
| **총 MTTR** | - | **2분** | - |

**벤치마크**: 산업 평균 45분 대비 **96% 향상**

---

## 9. Action Items (개선 과제)

### 즉시 조치 (Immediate - 24시간 이내)
- [x] Connection Pool Size 영구 증설 (10 → 20)
- [x] Redis TTL 설정 수정 (1분 → 1시간)
- [x] Slow Query 로그 분석으로 인덱스 최적화
- [ ] Auto-Scaling 정책 수정 (CPU > 50% 시 Scale-out)

### 단기 조치 (Short-term - 1주 이내)
- [ ] Cache Warmer 구현으로 만료 전 미리 갱신
- [ ] Circuit Breaker 설정으로 DB 직접 호출 제한
- [ ] 자동 완화 시스템 A/B 테스트 (오탐 감소)
- [ ] 인시던트 대시보드 실시간 알람 강화

### 중기 조치 (Mid-term - 1달 이내)
- [ ] Multi-Region Active-Active 아키텍처 검토
- [ ] DB Read Replica 추가로 부하 분산
- [ ] 자동 완화 시스템 확장 (네트워크, 캐시 등)
- [ ] Post-Mortem 문서화 및 팀 교육

### 장기 조치 (Long-term - 3달 이내)
- [ ] Predictive Auto-Scaling (AI 기반 예측)
- [ ] Chaos Engineering 정기 챔피언십
- [ ] SLO/SLI 모니터링 시스템 구축
- [ ] 오토메이션 파이프라인 전체 검토

---

## 10. Lessons Learned (학습 내용)

### 잘한 점 (What Went Well)
1. **자동 감지**: 30초 만에 장애 감지 (MTTD 우수)
2. **자동 완화**: 수동 개입 없이 2분 만에 회복
3. **데이터 기반 의사결정**: 92% 확신도로 정확한 원인 분류
4. **Zero Touch**: 모든 과정이 자동화되어 SRE 개입 불필요

### 개선 필요 (What Needs Improvement)
1. **TTL 설정 테스트 부족**: 설정 오류가 테스트에서 발견되지 않음
2. **Auto-Scaling 미작동**: 급증 트래픽에 대응하지 못함
3. **사전 모니터링 부족**: Cache Miss Rate 증가를 미리 감지 못함

### 핵심 인사이트
- **자동화의 힘**: MTTD 30초, MTTR 2분은 수동으로는 불가능
- **설정 오류 치명적**: TTL 1분 vs 1시간 실수가 장애로 이어짐
- **계층적 방어**: Cache + Connection Pool + Circuit Breaker 다중 레이어 필요

---

## 11. Appendix (부록)

### A. 관련 로그
```text
2026-02-05 10:15:30.000 ERROR [metrics-exporter] P99ResponseTimeSpike - p99 exceeded threshold: 5000ms > 1000ms
2026-02-05 10:15:45.000 INFO  [anomaly-detector] RootCauseClassifier - Classified: Database Slowdown (confidence: 92%)
2026-02-05 10:16:00.000 INFO  [auto-mitigation] ExecutionEngine - Executing: Increase HikariCP pool size 10 → 20
2026-02-05 10:17:30.000 INFO  [health-check] RecoveryMonitor - Recovery confirmed: p99 50ms (baseline)
```

### B. 메트릭 그래프
```
[p99 Response Time]
5000ms |       ╭───╮
4000ms |       │   │
3000ms |       │   │
2000ms |       │   │
1000ms |   ╭───╯   ╰───╮
  50ms ────╯           ╰───────────
       10:15  10:16  10:17  10:18
            장애   완화   회복
```

### C. Auto-Mitigation 아키텍처
```mermaid
sequenceDiagram
    participant Prometheus as Monitoring
    participant AlertManager as Alert Manager
    participant Classifier as AI Classifier
    participant Recommender as Recommender
    participant Approver as Auto-Approval
    participant Executor as Execution Engine
    participant Service as Target Service

    Prometheus->>AlertManager: p99 > 1000ms (30s)
    AlertManager->>Classifier: Trigger classification
    Classifier->>Classifier: Analyze metrics
    Classifier-->>Recommender: Root cause: DB (92%)
    Recommender->>Recommender: Generate mitigation plan
    Recommender-->>Approver: Recommend: Pool size 10→20
    Approver->>Approver: Risk assessment: LOW
    Approver-->>Executor: AUTO-APPROVED
    Executor->>Service: Update config via Actuator
    Service-->>Executor: ACK (pool size: 20)
    Executor-->>Prometheus: Verify recovery
    Prometheus->>Prometheus: p99: 50ms ✓
```

---

## 12. Approval & Sign-off

| 역할 | 이름 | 승인 일시 | 서명 |
|------|------|-----------|------|
| 작성자 | 🔴 Red Agent | 2026-02-05 11:00 | [자동 생성] |
| 검토자 | 🟢 Green Agent | 2026-02-05 11:15 | [승인] |
| 승인자 | SRE 팀장 | 2026-02-05 11:30 | [승인] |

---

*Generated by 5-Agent Council*
