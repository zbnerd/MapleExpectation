# Comprehensive Monitoring Query Guide

## Overview

This guide provides a complete library of Prometheus and Loki queries for monitoring MapleExpectation performance, resilience, and system health. It is designed for quantitative validation during refactoring phases and production operations.

**Related Documents:**
- [Chaos Engineering Test Strategy](docs/01_Chaos_Engineering/00_Overview/TEST_STRATEGY.md)
- [Baseline Metrics](docs/04_Reports/Baseline/BASELINE_20260210.md)
- [Infrastructure Guide](docs/02_Technical_Guides/infrastructure.md)

---

## 1. Prometheus Query Library

### 1.1 HTTP Performance Metrics

#### Request Rate (RPS)
```promql
# Total RPS across all endpoints
sum(rate(http_server_requests_seconds_count[5m]))

# RPS by endpoint
sum(rate(http_server_requests_seconds_count[5m])) by (uri)

# RPS for expectation APIs only
sum(rate(http_server_requests_seconds_count{uri=~".*expectation.*"}[5m]))
```

#### Latency Percentiles
```promql
# p50 Latency (Median)
histogram_quantile(0.50, rate(http_server_requests_seconds_bucket[5m]))

# p95 Latency
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# p99 Latency (Critical for SLA)
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

# p99.9 Latency (Tail latency)
histogram_quantile(0.999, rate(http_server_requests_seconds_bucket[5m]))

# Per-endpoint p99 latency
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))
```

#### Error Rates
```promql
# Total 5xx error rate (server errors)
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) /
sum(rate(http_server_requests_seconds_count[5m])) * 100

# Total 4xx error rate (client errors)
sum(rate(http_server_requests_seconds_count{status=~"4.."}[5m])) /
sum(rate(http_server_requests_seconds_count[5m])) * 100

# Error rate by endpoint
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (uri) /
sum(rate(http_server_requests_seconds_count[5m])) by (uri) * 100
```

---

### 1.2 Cache Performance Metrics

#### L1 (Caffeine) Cache Hit Rate
```promql
# L1 Hit Rate Percentage
sum(rate(cache_hit{layer="L1"}[5m])) /
(sum(rate(cache_hit{layer="L1"}[5m])) + sum(rate(cache_miss[5m]))) * 100

# Per-cache L1 hit rate
sum(rate(cache_hit{layer="L1"}[5m])) by (cache) /
(sum(rate(cache_hit{layer="L1"}[5m])) by (cache) + sum(rate(cache_miss[5m])) by (cache)) * 100
```

#### L2 (Redis) Cache Hit Rate
```promql
# L2 Hit Rate Percentage
sum(rate(cache_hit{layer="L2"}[5m])) /
(sum(rate(cache_hit{layer="L2"}[5m])) + sum(rate(cache_miss[5m]))) * 100

# Combined Hit Rate (L1 + L2)
sum(rate(cache_hit[5m])) /
(sum(rate(cache_hit[5m])) + sum(rate(cache_miss[5m]))) * 100
```

#### Cache Miss Penalty
```promql
# Average cache miss latency (milliseconds)
sum(rate(cache_miss_duration_seconds_sum[5m])) /
sum(rate(cache_miss_duration_seconds_count[5m])) * 1000

# Average cache hit latency (milliseconds)
sum(rate(cache_hit_duration_seconds_sum[5m])) /
sum(rate(cache_hit_duration_seconds_count[5m])) * 1000
```

---

### 1.3 Thread Pool Metrics

#### Pool Size and Capacity
```promql
# Current pool size (active threads)
executor_pool_size_threads{name="equipmentExecutor"}

# Queue remaining capacity
executor_queue_remaining_capacity{name="equipmentExecutor"}

# Pool utilization percentage
(executor_pool_size_threads{name="equipmentExecutor"} /
 executor_pool_max_threads{name="equipmentExecutor"}) * 100
```

#### Task Rejection and Execution
```promql
# Task rejection rate
rate(executor_rejected_total[5m])

# Completed tasks rate
rate(executor_completed_tasks_total[5m])
```

---

### 1.4 Database Connection Pool Metrics

#### Pool Utilization
```promql
# Active connections
hikaricp_connections_active{pool="MySQLLockPool"}

# Pending connections (waiting for connection)
hikaricp_connections_pending{pool="MySQLLockPool"}

# Pool utilization percentage
(hikaricp_connections_active{pool="MySQLLockPool"} /
 hikaricp_connections_max{pool="MySQLLockPool"}) * 100
```

---

### 1.5 Circuit Breaker Metrics

#### Circuit State
```promql
# Circuit breaker state (1=CLOSED, 2=OPEN, 3=HALF_OPEN)
resilience4j_circuitbreaker_state{name="nexonApi"}

# Failure rate
resilience4j_circuitbreaker_failure_rate{name="nexonApi"}
```

---

### 1.6 JVM & Memory Metrics

#### Heap Memory
```promql
# Heap usage percentage
(jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100

# Heap used by generation
jvm_memory_used_bytes{area="heap", pool="eden"}
```

#### Garbage Collection
```promql
# GC pause time percentiles (milliseconds)
histogram_quantile(0.50, rate(jvm_gc_pause_seconds_bucket[5m])) * 1000
histogram_quantile(0.95, rate(jvm_gc_pause_seconds_bucket[5m])) * 1000
histogram_quantile(0.99, rate(jvm_gc_pause_seconds_bucket[5m])) * 1000

# GC frequency (collections per minute)
sum(rate(jvm_gc_collections_seconds_total[1m])) * 60
```

---

## 2. Loki Query Library

### Error Detection
```logql
# All errors
{level="error"} |= "error"

# Errors by component
{level="error", component="EquipmentExpectationServiceV4"} |= "error"

# 5xx server errors
{level="error"} |= "5" or |= "500" or |= "502" or |= "503"
```

### Performance Issues
```logql
# Timeout errors
{level="warn"} |= "timeout" or |= "timed out"

# Slow query warnings
{level="warn"} |= "slow" or |= "latency"

# Circuit breaker activations
{component="CircuitBreaker"} |= "OPEN" or |= "HALF_OPEN"
```

### Cache Performance
```logql
# Cache misses
{component="TieredCache"} |= "MISS"

# Cache hits
{component="TieredCache"} |= "HIT"

# Cache failures
{level="error", component="TieredCache"} |= "L2 failure" or |= "Redis"
```

---

## 3. Grafana Dashboard Reference

### Available Dashboards

| Dashboard | Purpose | Key Metrics |
|-----------|---------|-------------|
| **API Performance** | HTTP request metrics | RPS, p50/p95/p99 latency, error rate |
| **Cache Performance** | Tiered cache monitoring | L1/L2 hit rates, miss penalty |
| **JVM & GC** | JVM health monitoring | Heap usage, GC pause time, thread count |
| **Database Pool** | Connection pool metrics | Active/pending connections, pool usage |

### Dashboard Access

**Local Development:**
```
http://localhost:3000/d/maple-api-performance
http://localhost:3000/d/maple-cache-performance
http://localhost:3000/d/maple-jvm-dashboard
```

---

## 4. Comparison Methodology

### Baseline Capture Process

**Step 1: Pre-Refactoring Baseline**
```bash
# Capture baseline metrics
curl -s http://localhost:8080/actuator/metrics > baseline_before.json

# Run load test
wrk -t8 -c100 -d60s --latency \
  http://localhost:8080/api/v4/characters/test/expectation \
  > results/wrk_baseline_before.txt
```

**Step 2: Apply Refactoring**
```bash
# Apply your refactoring changes
./gradlew clean test -PfastTest
```

**Step 3: Post-Refactoring Metrics**
```bash
# Capture metrics after refactoring
curl -s http://localhost:8080/actuator/metrics > baseline_after.json

# Run load test again
wrk -t8 -c100 -d60s --latency \
  http://localhost:8080/api/v4/characters/test/expectation \
  > results/wrk_baseline_after.txt
```

**Step 4: Compare Deltas**
```bash
# Compare wrk results
diff results/wrk_baseline_before.txt results/wrk_baseline_after.txt

# Check for regression
# RPS should be within ±5%
# p99 should not increase more than +5ms
```

### Acceptance Thresholds

| Metric | Baseline | Acceptance Threshold | Rationale |
|--------|----------|---------------------|-----------|
| **RPS** | 965 req/s | ≥ 917 req/s (±5%) | Throughput variance within statistical noise |
| **p99 Latency** | 214 ms | ≤ 219 ms (+5ms) | 5ms allowance for mapping overhead |
| **Test Duration** | 38 s | ≤ 45 s (+20%) | 20% variance accounts for system load |
| **L1 Hit Rate** | 85-90% | ≥ 82% (-3%) | Minor cache miss rate increase acceptable |
| **L2 Hit Rate** | 95-98% | ≥ 93% (-2%) | Redis cache stability critical |
| **Error Rate** | 0% | 0% tolerance | No regression in error handling |

---

## 5. Enhanced wrk Script

### Script Location
Create: `scripts/wrk_load_test.sh`

```bash
#!/bin/bash
CHARACTER_ID="${1:-test_character}"
DURATION="${2:-60s}"
THREADS="${3:-8}"
CONNECTIONS="${4:-100}"

mkdir -p results

wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency \
  -s load-test/wrk-v4-expectation.lua \
  http://localhost:8080/api/v4/characters/${CHARACTER_ID}/expectation \
  > results/wrk_$(date +%Y%m%d_%H%M%S).txt

echo "Results saved to results/wrk_*.txt"
```

### Usage

```bash
# Basic load test
./scripts/wrk_load_test.sh "test_character" "60s" "8" "100"

# Extended duration
./scripts/wrk_load_test.sh "test_character" "120s" "16" "200"
```

---

## 6. Alert Rules

### High Error Rate
```yaml
- alert: HighErrorRate
  expr: sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) * 100 > 1
  for: 5m
  annotations:
    summary: "Error rate is above 1%"
```

### High p99 Latency
```yaml
- alert: HighLatency
  expr: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 0.5
  for: 5m
  annotations:
    summary: "p99 latency exceeds 500ms"
```

### Low Cache Hit Rate
```yaml
- alert: LowCacheHitRate
  expr: sum(rate(cache_hit{layer="L1"}[5m])) / (sum(rate(cache_hit{layer="L1"}[5m])) + sum(rate(cache_miss[5m]))) * 100 < 70
  for: 5m
  annotations:
    summary: "L1 cache hit rate dropped below 70%"
```

---

## 7. Integration with CI/CD

### Pre-Merge Validation

Add to `.github/workflows/pr-check.yml`:

```yaml
name: PR Performance Check

on: [pull_request]

jobs:
  performance-validation:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Start Services
        run: docker-compose up -d
      - name: Wait for Application
        run: curl --retry 10 --retry-delay 5 http://localhost:8080/actuator/health
      - name: Run Load Test
        run: |
          wrk -t4 -c50 -d30s http://localhost:8080/api/v3/health > wrk_results.txt
      - name: Validate Metrics
        run: ./scripts/validate-phase3-no-regression.sh
```

---

## 8. Troubleshooting Guide

### No Metrics Appearing

**Check Prometheus Targets:**
```bash
curl http://localhost:9090/api/v1/targets
```

**Check Actuator Endpoints:**
```bash
curl http://localhost:8080/actuator/prometheus
```

**Check Application Health:**
```bash
curl http://localhost:8080/actuator/health
```

### High Cache Miss Rate

**Check Redis Connection:**
```bash
docker exec -it redis_container redis-cli ping
```

**Verify Cache Configuration:**
Review `application.yml` for cache TTL settings

---

## 9. References

- [Prometheus Query Language](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Loki LogQL](https://grafana.com/docs/loki/latest/logql/)
- [wrk HTTP Benchmark](https://github.com/wg/wrk)
- [Phase 3 Baseline Metrics](docs/04_Reports/Baseline/BASELINE_20260210.md)

---

**Document Version:** 1.0
**Last Updated:** 2026-02-10
**Maintained By:** 5-Agent Council (Red Agent - Performance Specialist)
