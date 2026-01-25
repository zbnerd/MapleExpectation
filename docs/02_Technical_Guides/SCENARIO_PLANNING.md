# Scenario Planning (시나리오 플래닝)

> **Issue**: #255
> **Last Updated**: 2026-01-25

---

## 1. 핵심 불확실성 축 (Uncertainty Axes)

### 축 1: 트래픽/동시성 (Traffic & Concurrency)
- **Low**: RPS < 100, 동시 사용자 < 200
- **High**: RPS > 500, 동시 사용자 > 500

### 축 2: 외부 API 안정성 (External API Stability)
- **Stable**: p95 < 500ms, 실패율 < 1%
- **Unstable**: p95 > 1s 또는 실패율 > 5%

---

## 2. 4분면 시나리오 매트릭스

```
                    External API
                 Stable    Unstable
             ┌──────────┬──────────┐
     Low     │  Green   │  Orange  │
Traffic      │ (Normal) │ (Defend) │
             ├──────────┼──────────┤
     High    │  Yellow  │   Red    │
             │ (Scale)  │ (Crisis) │
             └──────────┴──────────┘
```

---

## 3. 시나리오별 대응 전략

### 🟢 Green: Normal Operations (Low Traffic + Stable API)

**상태**
- RPS < 100, 외부 API 정상
- 모든 시스템 정상 동작

**대응 전략**
- 기본 캐시/타임아웃 설정 유지
- 정기 모니터링만 수행

**활성 모듈**
| Module | Status | Configuration |
|--------|--------|---------------|
| TieredCache | L1 중심 | TTL 60min |
| Circuit Breaker | 기본 | threshold 50% |
| Singleflight | 대기 | 필요시 활성화 |

---

### 🟡 Yellow: Scale Mode (High Traffic + Stable API)

**상태**
- RPS > 500, 외부 API 정상
- 캐시 MISS 증가, 리소스 압박

**대응 전략**
- L2 캐시(Redis) 적극 활용
- Singleflight로 중복 요청 병합
- Backpressure 활성화

**활성 모듈**
| Module | Status | Configuration |
|--------|--------|---------------|
| TieredCache | L1 + L2 | TTL 확장 |
| Singleflight | **활성화** | 중복 요청 병합 |
| Admission Control | Queue 기반 | capacity 100 |
| Write-Behind Buffer | 활성화 | batch 5s |

**Switch Rule**
```yaml
trigger:
  - rps > 500 for 5min
  - OR cache_miss_ratio > 30%
action:
  - activate_singleflight: true
  - extend_l2_ttl: 600s
  - enable_write_behind: true
```

---

### 🟠 Orange: Defend Mode (Low Traffic + Unstable API)

**상태**
- RPS 정상, 외부 API 지연/실패
- Circuit Breaker 작동 가능성

**대응 전략**
- Circuit Breaker 민감도 조정
- Fallback 응답 활성화
- Stale Cache 허용 (stale-while-revalidate)

**활성 모듈**
| Module | Status | Configuration |
|--------|--------|---------------|
| Circuit Breaker | **민감** | threshold 30% |
| Fallback Handler | 활성화 | stale cache 허용 |
| Retry | 축소 | maxAttempts 1 |
| TimeLimiter | 단축 | timeout 5s |

**Switch Rule**
```yaml
trigger:
  - external_api_error_rate > 5% for 3min
  - OR external_api_p95 > 1000ms for 3min
action:
  - circuit_breaker_threshold: 30%
  - enable_stale_cache: true
  - reduce_retry_attempts: 1
```

---

### 🔴 Red: Crisis Mode (High Traffic + Unstable API)

**상태**
- RPS > 500, 외부 API 장애
- 최악의 시나리오

**대응 전략**
- Rate Limiting 강제 적용
- 비핵심 기능 비활성화 (Graceful Degradation)
- 캐시 TTL 대폭 연장
- Cache-Only Mode 전환

**활성 모듈**
| Module | Status | Configuration |
|--------|--------|---------------|
| Rate Limiter | **강제** | 500 RPS 제한 |
| Circuit Breaker | FORCED_OPEN | 외부 호출 차단 |
| Cache-Only | 활성화 | DB 캐시만 사용 |
| Admission Control | 거부 모드 | 503 응답 |

**Switch Rule**
```yaml
trigger:
  - circuit_open_ratio > 50%
  - AND rps > 500
action:
  - force_rate_limit: 500
  - extend_all_cache_ttl: 1800s
  - enable_cache_only_mode: true
  - send_alert: critical
```

---

## 4. 조기 경고 지표 (Leading Indicators)

| 지표 | Green | Yellow | Orange | Red |
|------|-------|--------|--------|-----|
| **RPS** | < 100 | > 500 | < 100 | > 500 |
| **External API p95** | < 500ms | < 500ms | > 1s | > 1s |
| **External API Error** | < 1% | < 1% | > 5% | > 5% |
| **Cache Miss Ratio** | < 20% | > 30% | < 20% | > 30% |
| **Circuit Open Ratio** | 0% | 0% | > 20% | > 50% |
| **Thread Pool Active** | < 50% | > 70% | < 50% | > 70% |
| **DB Pool Utilization** | < 60% | > 80% | < 60% | > 80% |

---

## 5. Prometheus Alert Rules

```yaml
groups:
  - name: scenario-alerts
    rules:
      # Yellow Alert - High Traffic
      - alert: HighTrafficDetected
        expr: rate(http_server_requests_seconds_count[5m]) > 500
        for: 5m
        labels:
          severity: warning
          scenario: yellow
        annotations:
          summary: "High traffic detected - entering Scale Mode"

      # Orange Alert - External API Unstable
      - alert: ExternalAPIUnstable
        expr: |
          histogram_quantile(0.95, rate(external_api_duration_seconds_bucket[5m])) > 1
          OR rate(external_api_errors_total[5m]) / rate(external_api_requests_total[5m]) > 0.05
        for: 3m
        labels:
          severity: warning
          scenario: orange
        annotations:
          summary: "External API unstable - entering Defend Mode"

      # Red Alert - Crisis Mode
      - alert: CrisisMode
        expr: |
          sum(resilience4j_circuitbreaker_state{state="open"}) > 0
          AND rate(http_server_requests_seconds_count[5m]) > 500
        for: 1m
        labels:
          severity: critical
          scenario: red
        annotations:
          summary: "CRISIS MODE - Circuit open with high traffic"
```

---

## 6. 시나리오 전환 매트릭스

| From → To | Trigger | Auto/Manual | Cooldown |
|-----------|---------|-------------|----------|
| Green → Yellow | RPS > 500, 5min | Auto | - |
| Green → Orange | API Error > 5%, 3min | Auto | - |
| Yellow → Red | Circuit Open > 50% | Auto | - |
| Orange → Red | RPS > 500 | Auto | - |
| Red → Orange | RPS < 100, 5min | Auto | 10min |
| Red → Yellow | API Stable, 5min | Auto | 10min |
| Any → Green | All indicators normal, 10min | Auto | 15min |
| Any → Any | `/admin/scenario/{mode}` | Manual | - |

---

## 7. 복구 절차 (Recovery Path)

### 자동 복구 경로
```
Red → Orange → Green (외부 API 복구 시)
Red → Yellow → Green (트래픽 감소 시)
```

### 복구 조건
1. 조기 경고 지표가 5분간 안정
2. Circuit Breaker가 CLOSED로 전환
3. Error Rate < 1%
4. 10분 Cooldown 경과

### 수동 전환 API
```bash
# 강제 Degraded Mode 진입
curl -X POST http://localhost:8080/admin/scenario/red

# 강제 Normal Mode 복귀
curl -X POST http://localhost:8080/admin/scenario/green

# 현재 시나리오 확인
curl http://localhost:8080/admin/scenario
```

---

## 8. 시나리오별 SLA 조정

| Scenario | Availability | p95 Latency | Error Rate |
|----------|--------------|-------------|------------|
| Green | 99.9% | < 500ms | < 0.1% |
| Yellow | 99.5% | < 1s | < 1% |
| Orange | 99% | < 2s | < 2% |
| Red | 95% | < 5s | < 5% |

---

## 9. Grafana Dashboard Integration

### Scenario Status Panel
```promql
# Current Scenario (1=Green, 2=Yellow, 3=Orange, 4=Red)
scenario_current_mode
```

### Transition History
```promql
# Scenario transitions in last 24h
changes(scenario_current_mode[24h])
```

---

## Related Documents

- [KPI-BSC Dashboard](../04_Reports/KPI_BSC_DASHBOARD.md) - 성과 지표
- [Chaos Engineering](../01_Chaos_Engineering/06_Nightmare/) - Nightmare 시나리오
- [Infrastructure Guide](./infrastructure.md) - 인프라 설정

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-25*
