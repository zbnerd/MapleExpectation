# Metric Collection Evidence Report

> **Issue**: #254
> **Collection Date**: 2026-01-24
> **Method**: Grafana Dashboard Analysis + Prometheus Configuration Review

---

## 1. Prometheus Configuration

**Source**: `docker/prometheus/prometheus.yml`

| Configuration | Value | Notes |
|---------------|-------|-------|
| Scrape Interval | 15s | 5-Agent Council 합의 (Green) |
| Evaluation Interval | 15s | Alert rule 평가 주기 |
| Data Retention | 15 days | 장기 분석용 |
| Scrape Timeout | 10s | 연결 실패 조기 탐지 |
| Target | `host.docker.internal:8080/actuator/prometheus` | Spring Boot Actuator |

**Alert Rule Files**:
- `rules/*.yml` - P0 Issues (N02, N07, N09) 관련 알림 규칙

---

## 2. Grafana Dashboard Inventory

| Dashboard | UID | Purpose | Panels | Tags |
|-----------|-----|---------|--------|------|
| Spring Boot Prometheus Metrics | `spring-boot-metrics` | Core JVM/HTTP 메트릭 | 8 | prometheus, spring-boot, observability |
| Lock Health Monitoring (P0) | `lock-health-p0` | N02/N07/N09 Lock 모니터링 | 10 | p0, lock, nightmare, n02, n07, n09 |

---

## 3. Key PromQL Queries

### 3.1 Performance KPIs

| Metric | PromQL Query | Dashboard | Panel ID |
|--------|--------------|-----------|----------|
| **RPS (Request Rate)** | `rate(http_server_requests_seconds_count[1m])` | spring-boot-metrics | 3 |
| **p50 Latency** | `histogram_quantile(0.50, rate(http_server_requests_seconds_bucket[5m]))` | spring-boot-metrics | 4 |
| **p95 Latency** | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))` | spring-boot-metrics | 4 |
| **p99 Latency** | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))` | spring-boot-metrics | 4 |
| **Cache Hit Rate** | `rate(cache_gets_total{result="hit"}[5m]) / (rate(cache_gets_total{result="hit"}[5m]) + rate(cache_gets_total{result="miss"}[5m]))` | spring-boot-metrics | 5 |

### 3.2 Resilience KPIs

| Metric | PromQL Query | Dashboard | Panel ID |
|--------|--------------|-----------|----------|
| **Circuit Breaker State** | `resilience4j_circuitbreaker_state{state="closed"} * 0 + resilience4j_circuitbreaker_state{state="half_open"} * 1 + resilience4j_circuitbreaker_state{state="open"} * 2` | spring-boot-metrics | 6 |
| **Lock Order Violations** | `sum(lock_order_violation_total) or vector(0)` | lock-health-p0 | 1 |
| **Locks Currently Held** | `sum(lock_held_current) or vector(0)` | lock-health-p0 | 2 |
| **Lock Acquisition Rate** | `sum(rate(lock_acquisition_total[1m]))` | lock-health-p0 | 3 |
| **Lock Success Rate** | `sum(rate(lock_acquisition_total{status="success"}[1m]))` | lock-health-p0 | 5 |
| **Lock Failure Rate** | `sum(rate(lock_acquisition_total{status="failed"}[1m]))` | lock-health-p0 | 5 |
| **Redis Lock CB State** | `resilience4j_circuitbreaker_state{name="redisLock",state="open"} * 1 + resilience4j_circuitbreaker_state{name="redisLock",state="half_open"} * 0.5 or vector(0)` | lock-health-p0 | 4 |

### 3.3 Resource KPIs

| Metric | PromQL Query | Dashboard | Panel ID |
|--------|--------------|-----------|----------|
| **JVM Heap Used** | `jvm_memory_used_bytes{area="heap"}` | spring-boot-metrics | 1 |
| **JVM Non-Heap Used** | `jvm_memory_used_bytes{area="nonheap"}` | spring-boot-metrics | 2 |
| **HikariCP Active** | `hikaricp_connections_active` | spring-boot-metrics | 7 |
| **HikariCP Idle** | `hikaricp_connections_idle` | spring-boot-metrics | 7 |
| **HikariCP Pending** | `hikaricp_connections_pending` | spring-boot-metrics | 7 |
| **JVM Threads Live** | `jvm_threads_live_threads` | spring-boot-metrics | 8 |
| **JVM Threads Daemon** | `jvm_threads_daemon_threads` | spring-boot-metrics | 8 |
| **JVM Threads Peak** | `jvm_threads_peak_threads` | spring-boot-metrics | 8 |

### 3.4 Lock Pool & Fallback KPIs (P0 Issues)

| Metric | PromQL Query | Dashboard | Panel ID |
|--------|--------------|-----------|----------|
| **MySQL Lock Pool Active** | `hikaricp_connections_active{pool="MySQLLockPool"}` | lock-health-p0 | 7 |
| **MySQL Lock Pool Idle** | `hikaricp_connections_idle{pool="MySQLLockPool"}` | lock-health-p0 | 7 |
| **MySQL Lock Pool Max** | `hikaricp_connections_max{pool="MySQLLockPool"}` | lock-health-p0 | 7 |
| **Redis→MySQL Fallback Rate** | `rate(lock_fallback_to_mysql_total[1m])` | lock-health-p0 | 8 |
| **MySQL InnoDB Row Lock Waits** | `rate(mysql_global_status_innodb_row_lock_waits[1m])` | lock-health-p0 | 9 |
| **MySQL Avg Row Lock Time** | `mysql_global_status_innodb_row_lock_time_avg / 1000` | lock-health-p0 | 10 |

---

## 4. Performance Baseline (From Load Test)

**Source**: `docs/04_Reports/PERFORMANCE_260105.md`

| Metric | Value | Condition | Evidence |
|--------|-------|-----------|----------|
| **RPS (Mean)** | 235.7 | 100 users, 60s | Locust Load Test |
| **p50 Latency** | 160ms | Cold/Load Test | Locust Statistics |
| **p50 Latency** | 27ms | Warm Cache | Benchmark |
| **p95 Latency** | 360ms | Warm Cache | Benchmark |
| **Throughput** | 82.5 MB/s | 235 RPS × 350KB | Calculated |
| **Total Requests** | 48,183 | 60s duration | Locust |
| **Failure Rate** | 0% | All conditions | Verified |

### 4.1 Load Test Conditions

```yaml
# Locust Configuration (추정)
Users: 100
Spawn Rate: 10/s
Duration: 60s
Target: localhost:8080
```

### 4.2 CPU-Bound Processing Per Request

| Step | Data Size | Operation |
|------|-----------|-----------|
| 1. Decompression | 17KB (GZIP) | DB에서 압축 데이터 조회 |
| 2. Expansion | 350KB (JSON) | 메모리에서 압축 해제 |
| 3. Parsing | 350KB | JSON 트리 파싱 + 비즈니스 로직 |
| 4. Serialization | 4.3KB (DTO) | 응답 변환 |

**Equivalent Load Calculation**:
```
1 Request = 350KB / 2KB (일반 요청) = 150 Standard Requests
100 Users = 15,000 Equivalent Concurrent Users
```

---

## 5. Before/After Comparison (From Performance Report)

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| JSON Compression | 350KB | 17KB | 95% reduction |
| Concurrent Processing | 5.3s | 1.1s | 480% faster |
| DB Index Query | 0.98s | 0.02s | 50x faster |
| Memory Usage | 300MB | 30MB | 90% reduction |

---

## 6. Circuit Breaker State Mapping

**Dashboard**: spring-boot-metrics (Panel 6)

| Numeric Value | State | Color | Meaning |
|---------------|-------|-------|---------|
| 0 | CLOSED | Green | 정상 작동 |
| 1 | HALF_OPEN | Yellow | 복구 시도 중 |
| 2 | OPEN | Red | 장애 격리 |

---

## 7. Threshold Configurations (From lock-metrics.json)

### 7.1 Lock Order Violations (N09)

| Threshold | Value | Color | Action |
|-----------|-------|-------|--------|
| Normal | 0 | Green | 정상 |
| Warning | 1+ | Yellow | 모니터링 강화 |
| Critical | 5+ | Red | 즉시 조사 필요 |

### 7.2 Locks Currently Held (N02)

| Threshold | Value | Color | Action |
|-----------|-------|-------|--------|
| Normal | 0-9 | Green | 정상 |
| Warning | 10-24 | Yellow | 데드락 위험 증가 |
| Critical | 25+ | Red | 데드락 가능성 높음 |

### 7.3 MySQL Lock Pool Connections (N07/N11)

| Threshold | Value | Color | Action |
|-----------|-------|-------|--------|
| Normal | 0-23 | Green | 정상 |
| Warning | 24-27 | Yellow | 풀 포화 임박 |
| Critical | 28-30 | Red | 커넥션 고갈 위험 |

### 7.4 MySQL Avg Row Lock Time (N07)

| Threshold | Value | Color | Action |
|-----------|-------|-------|--------|
| Normal | <3s | Green | 정상 |
| Warning | 3-5s | Yellow | MDL 경합 증가 |
| Critical | 5s+ | Red | MDL 데드락 가능성 |

---

## 8. Prometheus API Query Examples

```bash
# RPS 조회
curl 'http://localhost:9090/api/v1/query?query=rate(http_server_requests_seconds_count[1m])'

# p95 Latency 조회
curl 'http://localhost:9090/api/v1/query?query=histogram_quantile(0.95,rate(http_server_requests_seconds_bucket[5m]))'

# Cache Hit Rate 조회
curl 'http://localhost:9090/api/v1/query?query=rate(cache_gets_total{result="hit"}[5m])/(rate(cache_gets_total{result="hit"}[5m])+rate(cache_gets_total{result="miss"}[5m]))'

# Circuit Breaker 상태 조회
curl 'http://localhost:9090/api/v1/query?query=resilience4j_circuitbreaker_state'

# Lock Order Violations 조회
curl 'http://localhost:9090/api/v1/query?query=sum(lock_order_violation_total)'
```

---

## 9. Collection Metadata

| Field | Value |
|-------|-------|
| Collection Date | 2026-01-24 |
| Collection Method | Grafana Dashboard JSON Analysis |
| Prometheus Config Version | Issue #209 |
| Dashboard Versions | spring-boot-metrics v1, lock-health-p0 v1 |
| Baseline Source | PERFORMANCE_260105.md |

---

## Related Documents

- [KPI-BSC Dashboard](./KPI_BSC_DASHBOARD.md) - KPI 계산 및 BSC 관점 분석
- [Performance Report](./PERFORMANCE_260105.md) - 부하 테스트 상세 결과
- [Architecture](../00_Start_Here/architecture.md) - 시스템 아키텍처

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-24*
