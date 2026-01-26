# Observability Guide

> MapleExpectation 모니터링 및 관측성 가이드
> **Last Updated**: 2026-01-26

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              /actuator/prometheus                    │   │
│  │  - JVM Metrics (Heap, GC, Threads)                  │   │
│  │  - HTTP Metrics (RPS, Latency, Errors)              │   │
│  │  - Cache Metrics (L1/L2 Hit Rate)                   │   │
│  │  - Circuit Breaker State                            │   │
│  │  - HikariCP Connection Pool                         │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────────┬────────────────────────────────┘
                             │ Scrape (15s)
                             ▼
                    ┌─────────────────┐
                    │   Prometheus    │
                    │   :9090         │
                    └────────┬────────┘
                             │ Query
                             ▼
                    ┌─────────────────┐
                    │    Grafana      │
                    │   :3000         │
                    │  admin/admin    │
                    └─────────────────┘
```

---

## 2. Quick Start

```bash
# 1. 인프라 실행
docker-compose up -d

# 2. 메트릭 확인
curl http://localhost:8080/actuator/prometheus

# 3. Grafana 접속
open http://localhost:3000  # admin/admin
```

---

## 3. Prometheus Configuration

### 3.1 Scrape Config (`docker/prometheus/prometheus.yml`)

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'spring-boot'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
    scrape_interval: 15s
    scrape_timeout: 10s
```

### 3.2 Alert Rules (`docker/prometheus/rules/lock-alerts.yml`)

| Alert | Condition | Severity | Description |
|-------|-----------|----------|-------------|
| `LockOrderViolationDetected` | `rate(lock_order_violation_total[5m]) > 0` | warning | N09 Deadlock 위험 |
| `DistributedLockFailureHigh` | `rate(lock_acquisition_total{status="failed"}[5m]) > 10` | warning | Redis/MySQL 락 실패 |
| `CircuitBreakerOpen` | `resilience4j_circuitbreaker_state{state="open"} == 1` | critical | Circuit Breaker Open |
| `LockPoolExhaustionRisk` | HikariCP active/max > 0.8 | warning | Connection Pool 고갈 위험 |

---

## 4. Key Metrics

### 4.1 HTTP Performance

```promql
# RPS (Requests Per Second)
rate(http_server_requests_seconds_count{uri="/api/v4/characters/{userIgn}/expectation"}[1m])

# p50 Latency
histogram_quantile(0.50, rate(http_server_requests_seconds_bucket{uri="/api/v4/characters/{userIgn}/expectation"}[5m]))

# p99 Latency
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{uri="/api/v4/characters/{userIgn}/expectation"}[5m]))

# Error Rate
rate(http_server_requests_seconds_count{status=~"5.."}[1m]) / rate(http_server_requests_seconds_count[1m])
```

### 4.2 Cache Performance

```promql
# L1 Fast Path Hit Rate (Target: >99%)
sum(rate(cache_l1_fast_path_total{result="hit"}[5m])) / sum(rate(cache_l1_fast_path_total[5m]))

# L1 Cache Hit Rate
sum(rate(cache_hit_total{layer="L1"}[5m])) / sum(rate(cache_gets_total{layer="L1"}[5m]))

# L2 Cache Hit Rate (Redis)
sum(rate(cache_hit_total{layer="L2"}[5m])) / sum(rate(cache_gets_total{layer="L2"}[5m]))
```

### 4.3 Circuit Breaker

```promql
# Circuit Breaker State (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
resilience4j_circuitbreaker_state{name="nexonApi"}

# Circuit Breaker Call Rate
rate(resilience4j_circuitbreaker_calls_seconds_count{name="nexonApi"}[1m])

# Circuit Breaker Failure Rate
rate(resilience4j_circuitbreaker_calls_seconds_count{name="nexonApi", kind="failed"}[1m])
  / rate(resilience4j_circuitbreaker_calls_seconds_count{name="nexonApi"}[1m])
```

### 4.4 Connection Pool

```promql
# Active Connections
hikaricp_connections_active{pool="MySQLLockPool"}

# Connection Usage Rate
hikaricp_connections_active{pool="MySQLLockPool"} / hikaricp_connections_max{pool="MySQLLockPool"}

# Connection Wait Time
rate(hikaricp_connections_acquire_seconds_sum[5m]) / rate(hikaricp_connections_acquire_seconds_count[5m])
```

### 4.5 JVM Metrics

```promql
# Heap Usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# GC Pause Time
rate(jvm_gc_pause_seconds_sum[5m])

# Thread Count
jvm_threads_live_threads
```

---

## 5. Grafana Dashboards

### 5.1 Available Dashboards

| Dashboard | File | Description |
|-----------|------|-------------|
| **Application** | `application.json` | HTTP, Cache, JVM 종합 |
| **Prometheus Metrics** | `prometheus-metrics.json` | 시스템 메트릭 |
| **Lock Health (P0)** | `lock-metrics.json` | N02/N07/N09 모니터링 |
| **Slow Query** | `slow-query.json` | 쿼리 성능 분석 |

### 5.2 Lock Health Dashboard Panels

| Panel | Metric | Threshold |
|-------|--------|-----------|
| Lock Order Violations | `lock_order_violation_total` | 0 (Green) / 1+ (Yellow) / 5+ (Red) |
| Active Locks | `lock_held_current` | - |
| Lock Acquisition Rate | `rate(lock_acquisition_total[5m])` | - |
| Lock Wait Time (p99) | `histogram_quantile(0.99, lock_wait_duration_bucket)` | <100ms |
| MySQL Lock Pool Usage | `hikaricp_connections_active / max` | <80% |
| Redis Lock Fallback | `lock_fallback_to_mysql_total` | 0 |

---

## 6. Access URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Application | http://localhost:8080 | - |
| Actuator Health | http://localhost:8080/actuator/health | - |
| Actuator Prometheus | http://localhost:8080/actuator/prometheus | - |
| Prometheus | http://localhost:9090 | - |
| Grafana | http://localhost:3000 | admin/admin |

---

## 7. Troubleshooting Queries

### 7.1 High Latency Investigation

```promql
# 느린 엔드포인트 찾기
topk(5, histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])))

# 외부 API 지연 확인
histogram_quantile(0.99, rate(resilience4j_circuitbreaker_calls_seconds_bucket{name="nexonApi"}[5m]))
```

### 7.2 Cache Miss Spike

```promql
# 캐시 미스율 급증 확인
rate(cache_l1_fast_path_total{result="miss"}[5m]) / rate(cache_l1_fast_path_total[5m])

# Singleflight 동작 확인
rate(singleflight_inflight[5m])
```

### 7.3 Connection Pool Issue

```promql
# Connection Wait 시간 급증
rate(hikaricp_connections_acquire_seconds_sum[5m]) / rate(hikaricp_connections_acquire_seconds_count[5m]) > 0.1

# Pending Connection 확인
hikaricp_connections_pending
```

---

## 8. SLA Monitoring

### 8.1 KPI Targets

| Metric | Target | Query |
|--------|--------|-------|
| RPS | >250 | `rate(http_server_requests_seconds_count{uri=~"/api/v.*"}[1m])` |
| p50 Latency | <100ms | `histogram_quantile(0.50, ...)` |
| p99 Latency | <500ms | `histogram_quantile(0.99, ...)` |
| Error Rate | <1% | `rate(http_server_requests_seconds_count{status=~"5.."}[1m]) / rate(...)` |
| Cache Hit Rate | >95% | `sum(rate(cache_hit_total[5m])) / sum(rate(cache_gets_total[5m]))` |

### 8.2 Alerting Thresholds

| Metric | Warning | Critical |
|--------|---------|----------|
| RPS | <200 | <100 |
| p50 Latency | >50ms | >100ms |
| Error Rate | >0.1% | >1% |
| Circuit Breaker | HALF_OPEN | OPEN |
| Connection Pool | >80% | >95% |

---

## 9. Related Documents

- [KPI Dashboard](../04_Reports/KPI_BSC_DASHBOARD.md) - KPI 현황
- [Load Test Report](../04_Reports/Load_Tests/) - 부하 테스트 결과
- [Demo Guide](../demo/DEMO_GUIDE.md) - 시연 가이드
- [Chaos Engineering](../01_Chaos_Engineering/) - Nightmare 시나리오

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-26*
