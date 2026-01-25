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

**현재 구현 상태**
| Module | Status | Configuration | 참조 |
|--------|--------|---------------|------|
| TieredCache | L1 중심 | Caffeine 기반 | `CacheConfig.java` |
| Circuit Breaker | 기본 | failureRateThreshold 50% | `application.yml:57` |
| Singleflight | 활성 | V4 API 적용 | `EquipmentExpectationServiceV4.java` |

---

### 🟡 Yellow: Scale Mode (High Traffic + Stable API)

**상태**
- RPS > 500, 외부 API 정상
- 캐시 MISS 증가, 리소스 압박

**현재 구현 상태**
| Module | Status | Configuration | 참조 |
|--------|--------|---------------|------|
| TieredCache | L1 + L2 | Caffeine + Redis | `TieredCacheManager.java` |
| Singleflight | 활성화 | 중복 요청 병합 | `EquipmentExpectationServiceV4.java` |
| Write-Behind Buffer | 활성화 | batch 5s | `ExpectationBatchWriteScheduler.java` |
| Graceful Shutdown | 활성화 | 50s 대기 | `application.yml:10` |

---

### 🟠 Orange: Defend Mode (Low Traffic + Unstable API)

**상태**
- RPS 정상, 외부 API 지연/실패
- Circuit Breaker 작동

**현재 구현 상태**
| Module | Status | Configuration | 참조 |
|--------|--------|---------------|------|
| Circuit Breaker | 활성화 | 다중 인스턴스 | `application.yml:66-82` |
| Retry | 활성화 | maxAttempts 3 | `application.yml:92-94` |
| TimeLimiter | 활성화 | timeout 28s | `application.yml:113` |
| Fallback | Redis Lock 폴백 | MySQL 폴백 | `ResilientLockStrategy.java` |

**실제 Resilience4j 설정 (application.yml)**
```yaml
resilience4j.circuitbreaker.instances:
  nexonApi:
    slidingWindowSize: 10
    failureRateThreshold: 50
    waitDurationInOpenState: 10s
    minimumNumberOfCalls: 10

  redisLock:
    slidingWindowSize: 20
    failureRateThreshold: 60
    waitDurationInOpenState: 30s
```

---

### 🔴 Red: Crisis Mode (High Traffic + Unstable API)

**상태**
- RPS > 500, 외부 API 장애
- 최악의 시나리오

**현재 구현 상태**
| Module | Status | Configuration | 참조 |
|--------|--------|---------------|------|
| RateLimiter | 구현됨 | IP/User 기반 | `RateLimitingService.java` |
| Circuit Breaker | OPEN 상태 | 자동 전환 | `resilience4j` |
| Graceful Shutdown | 활성화 | 버퍼 드레인 | `ExpectationBatchShutdownHandler.java` |

---

## 4. 조기 경고 지표 (Leading Indicators)

### 실제 메트릭 (Actuator/Prometheus 노출)

| 지표 | 메트릭 이름 | 참조 |
|------|------------|------|
| **Circuit Breaker 상태** | `resilience4j_circuitbreaker_state` | `application.yml:55` |
| **HikariCP 연결** | `hikaricp_connections_active` | `application.yml:16` |
| **Lock 획득 실패** | `lock_acquisition_total{status="failed"}` | `lock-alerts.yml:24` |
| **Lock 순서 위반** | `lock_order_violation_total` | `lock-alerts.yml:12` |
| **Buffer 대기 수** | `expectation.buffer.pending` | `ExpectationWriteBackBuffer.java` |

---

## 5. 현재 Prometheus Alert Rules

### 실제 구현된 알림 (lock-alerts.yml)

```yaml
groups:
  - name: lock-health
    rules:
      # N09: Lock Order Violation Detection
      - alert: LockOrderViolationDetected
        expr: rate(lock_order_violation_total[5m]) > 0
        labels:
          severity: warning
          nightmare: N09

      # N02/N09: Distributed Lock Failure
      - alert: DistributedLockFailureHigh
        expr: rate(lock_acquisition_total{status="failed"}[5m]) > 10
        labels:
          severity: warning
          nightmare: N02

      # Lock Pool Exhaustion Risk
      - alert: LockPoolExhaustionRisk
        expr: hikaricp_connections_active{pool="MySQLLockPool"} / hikaricp_connections_max{pool="MySQLLockPool"} > 0.8
        labels:
          severity: warning

  - name: circuit-breaker
    rules:
      # Circuit Breaker State Monitoring
      - alert: CircuitBreakerOpen
        expr: resilience4j_circuitbreaker_state{name="redisLock", state="open"} == 1
        labels:
          severity: critical

      - alert: CircuitBreakerHalfOpen
        expr: resilience4j_circuitbreaker_state{name="redisLock", state="half_open"} == 1
        for: 5m
        labels:
          severity: warning
```

---

## 6. 시나리오 전환 (자동)

### 현재 구현된 자동 전환

| 전환 | 트리거 | 메커니즘 | 참조 |
|------|--------|----------|------|
| Normal → CB Open | failureRate > 50% | Resilience4j | `application.yml:57` |
| CB Open → Half-Open | 10s 경과 | Resilience4j | `application.yml:58` |
| Redis Lock 실패 → MySQL 폴백 | CB Open 시 | 자동 폴백 | `ResilientLockStrategy.java` |

### 복구 조건
1. Circuit Breaker가 CLOSED로 전환 (Half-Open에서 성공 호출)
2. Error Rate < 50% (slidingWindowSize 기준)
3. waitDurationInOpenState 경과 (10s ~ 30s)

---

## 7. 시나리오별 SLA 조정

| Scenario | Availability | p95 Latency | Error Rate |
|----------|--------------|-------------|------------|
| Green | 99.9% | < 500ms | < 0.1% |
| Yellow | 99.5% | < 1s | < 1% |
| Orange | 99% | < 2s | < 2% |
| Red | 95% | < 5s | < 5% |

---

## 8. Grafana Dashboard 연동

### 현재 지원 메트릭 (Actuator/Prometheus)

```promql
# Circuit Breaker 상태
resilience4j_circuitbreaker_state{name="nexonApi"}
resilience4j_circuitbreaker_state{name="redisLock"}

# HikariCP Pool 상태
hikaricp_connections_active
hikaricp_connections_pending

# Buffer 상태
expectation_buffer_pending
expectation_buffer_flushed_total
```

### 대시보드 URL

| Dashboard | URL | Purpose |
|-----------|-----|---------|
| Spring Boot Metrics | `http://localhost:3000/d/spring-boot-metrics` | JVM/HTTP/Cache |
| Lock Health (P0) | `http://localhost:3000/d/lock-health-p0` | N02/N07/N09 모니터링 |
| Prometheus | `http://localhost:9090` | 메트릭 쿼리 |

---

## 9. 향후 구현 예정 (Proposed)

> ⚠️ 아래 기능은 아직 구현되지 않은 제안 사항입니다.

### 9.1 수동 시나리오 전환 API (미구현)
```bash
# Proposed: 강제 Degraded Mode 진입
POST /admin/scenario/red

# Proposed: 강제 Normal Mode 복귀
POST /admin/scenario/green
```

### 9.2 시나리오 상태 메트릭 (미구현)
```promql
# Proposed: 현재 시나리오 (1=Green, 2=Yellow, 3=Orange, 4=Red)
scenario_current_mode

# Proposed: 시나리오 전환 이력
changes(scenario_current_mode[24h])
```

### 9.3 트래픽 기반 알림 (미구현)
```yaml
# Proposed Alert Rules
- alert: HighTrafficDetected
  expr: rate(http_server_requests_seconds_count[5m]) > 500

- alert: ExternalAPIUnstable
  expr: histogram_quantile(0.95, rate(external_api_duration_seconds_bucket[5m])) > 1
```

---

## Related Documents

- [KPI-BSC Dashboard](../04_Reports/KPI_BSC_DASHBOARD.md) - 성과 지표
- [Chaos Engineering](../01_Chaos_Engineering/06_Nightmare/) - Nightmare 시나리오
- [Infrastructure Guide](./infrastructure.md) - 인프라 설정
- [Resilience Guide](./resilience.md) - 회복 탄력성 패턴

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-25*
