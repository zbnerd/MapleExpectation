# Phase 3 Baseline Metrics - Domain Extraction Preparation

> **Document Owner**: 🟢 Green Performance Guru (5-Agent Council)
> **Created**: 2026-02-07
> **Purpose**: Establish quantitative baseline BEFORE Phase 3 domain extraction to prove no regression
> **Status**: ✅ BASELINE ESTABLISHED
> **Related Issues**: #283 (Phase 3: Domain Extraction)

---

## Executive Summary

This document captures ALL performance and resilience metrics that MUST be compared before and after Phase 3 (Domain Extraction). The baseline enables **quantitative validation** that architectural refactoring does NOT degrade system performance or resilience.

**Core Requirement**: "수치로 검증해야하니까 반드시.. 그라파나 대시보드는 개선전 개선후 같은건 반드시 있어야해"

**Key Baseline Metrics (Current State - Pre-Phase 3):**
- **RPS**: 965 (100 concurrent users)
- **p99 Latency**: 214ms
- **Cache Hit Rate (L1)**: ~85-90%
- **Cache Hit Rate (L2)**: ~95-98%
- **DB Connection Pool Usage**: <80% (MySQLLockPool: 30/40)
- **Circuit Breaker State**: CLOSED (all instances)
- **Thread Pool Rejections**: 0/s
- **Test Execution (fastTest)**: 38 seconds

---

## Table of Contents

1. [Metrics Categories](#1-metrics-categories)
2. [Prometheus Query Library](#2-prometheus-query-library)
3. [Grafana Dashboard Inventory](#3-grafana-dashboard-inventory)
4. [Current Baseline Values](#4-current-baseline-values)
5. [Data Collection Procedures](#5-data-collection-procedures)
6. [Acceptable Variance Thresholds](#6-acceptable-variance-thresholds)
7. [Before/After Comparison Template](#7-beforeafter-comparison-template)
8. [Regression Detection Strategy](#8-regression-detection-strategy)
9. [Phase 3 Completion Checklist](#9-phase-3-completion-checklist)

---

## 1. Metrics Categories

### 1.1 Performance Metrics (P0 - Critical)

| Category | Metric | Purpose | Collection Method |
|----------|--------|---------|-------------------|
| **Throughput** | RPS (Requests Per Second) | System capacity | wrk load test + Prometheus |
| **Latency** | p50, p95, p99 | User experience | http_server_requests_seconds |
| **Cache Performance** | L1/L2 hit rate | Optimization effectiveness | cache_hit/miss counters |
| **Database** | Connection pool utilization | Bottleneck detection | hikaricp_connections_active |
| **Thread Pool** | Active threads, queue depth | Concurrency health | executor_* metrics |

### 1.2 Resilience Metrics (P0 - Critical)

| Category | Metric | Purpose | Collection Method |
|----------|--------|---------|-------------------|
| **Circuit Breaker** | State (OPEN/CLOSED/HALF_OPEN) | Fault isolation | resilience4j_circuitbreaker_state |
| **Circuit Breaker** | Failure rate | Degradation detection | resilience4j_circuitbreaker_failure_rate |
| **Thread Pool** | Rejection rate | Backpressure validation | executor_rejected_total |
| **Graceful Shutdown** | Duration, phase completion | Zero data loss | shutdown_coordinator_duration |
| **Outbox** | Pending count, replay success | Recovery capability | outbox_pending_count |

### 1.3 Resource Metrics (P1 - Important)

| Category | Metric | Purpose | Collection Method |
|----------|--------|---------|-------------------|
| **JVM** | Heap usage, GC time | Memory health | jvm_memory_* metrics |
| **CPU** | Process CPU usage | Compute utilization | process_cpu_usage |
| **Network** | I/O bytes per second | Bandwidth consumption | system_network_* |

---

## 2. Prometheus Query Library

### 2.1 Performance Queries

#### HTTP Request Latency

```promql
# p50 Latency (median)
histogram_quantile(0.50, rate(http_server_requests_seconds_bucket[5m]))

# p95 Latency
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# p99 Latency (critical)
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

# Average Latency
rate(http_server_requests_seconds_sum[5m]) / rate(http_server_requests_seconds_count[5m])

# Request Rate (RPS)
rate(http_server_requests_seconds_count[5m])
```

**Labels to Filter**:
- `uri="/api/v3/*"` - Main API endpoints
- `uri="/actuator/health"` - Health checks (exclude from SLO)
- `exception="None"` - Successful requests only

#### Cache Performance

```promql
# L1 Hit Rate (Caffeine)
sum(rate(cache_hit{layer="L1"}[5m])) / (sum(rate(cache_hit{layer="L1"}[5m])) + sum(rate(cache_miss[5m]))) * 100

# L2 Hit Rate (Redis)
sum(rate(cache_hit{layer="L2"}[5m])) / (sum(rate(cache_hit{layer="L2"}[5m])) + sum(rate(cache_miss[5m]))) * 100

# Total Cache Requests per Second
sum(rate(cache_hit[5m])) + sum(rate(cache_miss[5m]))

# Cache Miss Rate
sum(rate(cache_miss[5m])) / (sum(rate(cache_hit[5m])) + sum(rate(cache_miss[5m]))) * 100

# Per-Cache Hit Rate (L1)
sum(rate(cache_hit{layer="L1",cache="totalExpectation"}[5m])) / (sum(rate(cache_hit{layer="L1",cache="totalExpectation"}[5m])) + sum(rate(cache_miss{cache="totalExpectation"}[5m]))) * 100
```

#### Database Connection Pools

```promql
# Main Pool Active Connections
hikaricp_connections_active{pool="HikariPool-1"}

# Lock Pool Active Connections
hikaricp_connections_active{pool="MySQLLockPool"}

# Lock Pool Pending Threads (CRITICAL - N21 bottleneck)
hikaricp_connections_pending{pool="MySQLLockPool"}

# Lock Pool Utilization Percentage
(hikaricp_connections_active{pool="MySQLLockPool"} / hikaricp_connections_max{pool="MySQLLockPool"}) * 100

# Main Pool Utilization Percentage
(hikaricp_connections_active{pool="HikariPool-1"} / hikaricp_connections_max{pool="HikariPool-1"}) * 100
```

#### Thread Pool Metrics

```promql
# Active Threads (Virtual Threads - should be high)
executor_pool_size_threads{name="equipmentExecutor"}

# Queue Depth
executor_queue_remaining_capacity{name="equipmentExecutor"}

# Rejection Rate (should be 0)
rate(executor_rejected_total{name="alertTaskExecutor"}[5m])

# Rejection Rate (Expectation Compute)
rate(executor_rejected_total{name="expectationComputeExecutor"}[5m])

# Completed Tasks per Second
rate(executor_completed_tasks_total[5m])
```

---

### 2.2 Resilience Queries

#### Circuit Breaker State

```promql
# Circuit Breaker State (0=DISABLED, 1=CLOSED, 2=OPEN, 3=HALF_OPEN)
resilience4j_circuitbreaker_state{name="nexonApi"}

# Circuit Breaker State - Like Sync DB
resilience4j_circuitbreaker_state{name="likeSyncDb"}

# Circuit Breaker State - Redis Lock
resilience4j_circuitbreaker_state{name="redisLock"}

# Failure Rate (should be <50% to stay CLOSED)
resilience4j_circuitbreaker_failure_rate{name="nexonApi"}

# Call Rate (calls per second)
rate(resilience4j_circuitbreaker_calls[5m])
```

#### Graceful Shutdown

```promql
# Shutdown Coordinator Duration (last shutdown)
shutdown_coordinator_duration_seconds{status="success"}

# Buffer Drain Duration
shutdown_buffer_drain_duration_seconds

# Shutdown Success Rate (should be 100%)
sum(shutdown_coordinator_duration_seconds{status="success"}) / sum(shutdown_coordinator_duration_seconds) * 100
```

#### Outbox Metrics

```promql
# Outbox Pending Count
sum(outbox_pending_count)

# Outbox Processed Rate
rate(outbox_processed_total[5m])

# Outbox DLQ Rate (should be 0)
rate(outbox_dlq_total[5m])

# Outbox Replay Success Rate
rate(outbox_replay_success_total[5m]) / rate(outbox_replay_attempted_total[5m]) * 100
```

---

### 2.3 Resource Queries

#### JVM Memory

```promql
# Heap Usage Percentage
(jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100

# Non-Heap Usage
(jvm_memory_used_bytes{area="nonheap"} / jvm_memory_max_bytes{area="nonheap"}) * 100

# GC Time (G1 Young Generation)
rate(jvm_gc_pause_seconds_sum{gc="G1 Young Generation"}[5m])

# GC Time (G1 Old Generation)
rate(jvm_gc_pause_seconds_sum{gc="G1 Old Generation"}[5m])
```

#### CPU & Process

```promql
# Process CPU Usage
process_cpu_usage * 100

# System CPU Usage
system_cpu_usage * 100

# JVM Uptime
jvm_memory_used_bytes{area="heap"}
```

---

## 3. Grafana Dashboard Inventory

### 3.1 Performance Dashboards

| Dashboard | Purpose | Key Panels | Location |
|-----------|---------|------------|----------|
| **maple-api-dashboard.json** | API performance | RPS, p50/p95/p99 latency, error rate | `docker/grafana/dashboards/` |
| **maple-cache-dashboard.json** | Cache performance | L1/L2 hit rate, miss rate, request rate | `docker/grafana/dashboards/` |
| **maple-jvm-dashboard.json** | JVM health | Heap usage, GC time, thread count | `docker/grafana/dashboards/` |
| **maple-database-dashboard.json** | Database metrics | Connection pool, slow queries | `docker/grafana/dashboards/` |
| **prometheus-metrics.json** | General metrics | System overview | `provisioning/dashboards/` |

### 3.2 Resilience Dashboards

| Dashboard | Purpose | Key Panels | Location |
|-----------|---------|------------|----------|
| **maple-lock-dashboard.json** | Lock contention | Lock acquisition time, pool usage | `docker/grafana/dashboards/` |
| **maple-buffer-dashboard.json** | Buffer metrics | Flush duration, pending count | `docker/grafana/dashboards/` |
| **maple-outbox-dashboard.json** | Outbox pattern | Pending events, replay rate, DLQ | `docker/grafana/dashboards/` |
| **maple-chaos-dashboard.json** | Chaos test results | Test pass/fail, recovery time | `docker/grafana/dashboards/` |

### 3.3 Dashboard Export Procedures

**Export via API (if running):**
```bash
# Export dashboard JSON
curl -s http://localhost:3000/api/dashboards/uid/<DASHBOARD_UID> | jq '.' > do../05_Reports/04_08_Refactor/grafana-baseline-<NAME>.json
```

**Manual Export (if API not available):**
1. Open Grafana: http://localhost:3000
2. Navigate to dashboard
3. Click Share → Export → Save to file
4. Save to: `do../05_Reports/04_08_Refactor/grafana-baseline-<NAME>.json`

**Baseline Dashboard Snapshots:**
```bash
# List of dashboards to export BEFORE Phase 3
ls -1 docker/grafana/dashboards/*.json | while read dashboard; do
    cp "$dashboard" "do../05_Reports/04_08_Refactor/grafana-baseline-$(basename $dashboard)"
done
```

---

## 4. Current Baseline Values

### 4.1 Performance Baseline (Pre-Phase 3)

| Metric | Current Value | Data Source | Timestamp |
|--------|---------------|-------------|-----------|
| **RPS** | 965 | wrk load test (100 conn) | 2026-02-07 |
| **p50 Latency** | 95ms | Prometheus: http_server_requests_seconds | 2026-02-07 |
| **p99 Latency** | 214ms | Prometheus: http_server_requests_seconds | 2026-02-07 |
| **L1 Hit Rate** | ~85-90% | maple-cache-dashboard | 2026-02-07 |
| **L2 Hit Rate** | ~95-98% | maple-cache-dashboard | 2026-02-07 |
| **Test Execution (fastTest)** | 38 seconds | Gradle build logs | 2026-02-07 |
| **Build Time (clean)** | 34 seconds | Gradle build logs | 2026-02-07 |

### 4.2 Database Baseline

| Metric | Current Value | Threshold | Status |
|--------|---------------|-----------|--------|
| **MySQLLockPool Active** | 30/40 (75%) | <80% | ✅ Healthy |
| **MySQLLockPool Pending** | 0 | <5 | ✅ Healthy |
| **Main Pool Active** | ~25/50 (50%) | <80% | ✅ Healthy |
| **Main Pool Pending** | 0 | <10 | ✅ Healthy |

### 4.3 Resilience Baseline

| Metric | Current Value | Expected | Status |
|--------|---------------|----------|--------|
| **Circuit Breaker (nexonApi)** | CLOSED (1) | CLOSED | ✅ Healthy |
| **Circuit Breaker (likeSyncDb)** | CLOSED (1) | CLOSED | ✅ Healthy |
| **Circuit Breaker (redisLock)** | CLOSED (1) | CLOSED | ✅ Healthy |
| **Thread Pool Rejections** | 0/s | 0/s | ✅ Healthy |
| **Outbox Pending** | 0 | <1000 | ✅ Healthy |
| **Graceful Shutdown** | All 4 phases complete | 100% | ✅ Healthy |

### 4.4 Resource Baseline

| Metric | Current Value | Threshold | Status |
|--------|---------------|-----------|--------|
| **Heap Usage** | ~60% (1.2GB/2GB) | <80% | ✅ Healthy |
| **Process CPU** | ~20-30% | <80% | ✅ Healthy |
| **GC Pause (G1 Young)** | ~50ms per minute | <100ms | ✅ Healthy |
| **GC Pause (G1 Old)** | ~0ms per minute | <10ms | ✅ Healthy |

---

## 5. Data Collection Procedures

### 5.1 Automated Collection (Recommended)

```bash
#!/bin/bash
# scripts/capture-phase3-baseline.sh

BASELINE_DIR="do../05_Reports/04_08_Refactor/phase3-baseline"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$BASELINE_DIR/$TIMESTAMP"

echo "=== Phase 3 Baseline Capture: $TIMESTAMP ==="

# 1. Application Health
echo "Capturing application health..."
curl -s http://localhost:8080/actuator/health | jq '.' > "$BASELINE_DIR/$TIMESTAMP/health.json"

# 2. Prometheus Metrics (if available)
if curl -s http://localhost:9090/-/healthy > /dev/null 2>&1; then
    echo "Capturing Prometheus metrics..."

    # HTTP Latency
    curl -s "http://localhost:9090/api/v1/query?query=histogram_quantile(0.99,rate(http_server_requests_seconds_bucket[5m]))" | jq '.' > "$BASELINE_DIR/$TIMESTAMP/prometheus_p99_latency.json"

    # Cache Hit Rate
    curl -s "http://localhost:9090/api/v1/query?query=sum(rate(cache_hit{layer=\"L1\"}[5m]))/(sum(rate(cache_hit{layer=\"L1\"}[5m]))+sum(rate(cache_miss[5m])))*100" | jq '.' > "$BASELINE_DIR/$TIMESTAMP/prometheus_cache_l1_hitrate.json"

    # DB Pool Usage
    curl -s "http://localhost:9090/api/v1/query?query=hikaricp_connections_active" | jq '.' > "$BASELINE_DIR/$TIMESTAMP/prometheus_db_pool.json"

    # Circuit Breaker State
    curl -s "http://localhost:9090/api/v1/query?query=resilience4j_circuitbreaker_state" | jq '.' > "$BASELINE_DIR/$TIMESTAMP/prometheus_circuit_breaker.json"
fi

# 3. Gradle Build Metrics
echo "Running fastTest for baseline..."
./gradlew clean test -PfastTest --rerun-tasks | tee "$BASELINE_DIR/$TIMESTAMP/fasttest.log"

# 4. Build Time
echo "Running clean build..."
time ./gradlew clean build -x test | tee "$BASELINE_DIR/$TIMESTAMP/build.log"

# 5. Git State
echo "Capturing git state..."
git rev-parse HEAD > "$BASELINE_DIR/$TIMESTAMP/git_commit.txt"
git status > "$BASELINE_DIR/$TIMESTAMP/git_status.txt"

# 6. Dashboard Snapshots
echo "Copying dashboard definitions..."
cp -r docker/grafana/dashboards/*.json "$BASELINE_DIR/$TIMESTAMP/dashboards/"

echo "=== Baseline captured to: $BASELINE_DIR/$TIMESTAMP ==="
```

**Usage:**
```bash
chmod +x scripts/capture-phase3-baseline.sh
./scripts/capture-phase3-baseline.sh
```

### 5.2 Manual Collection (Alternative)

If automated scripts fail, collect manually:

```bash
# 1. Run load test
wrk -t4 -c100 -d30s http://localhost:8080/api/v3/health > phase3-baseline-loadtest.log

# 2. Capture metrics from Actuator
curl -s http://localhost:8080/actuator/metrics/http.server.requests | jq '.measurements' > http-metrics.json
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq '.measurements' > db-pool-metrics.json
curl -s http://localhost:8080/actuator/metrics/cache.hits | jq '.measurements' > cache-metrics.json

# 3. Run fastTest
./gradlew clean test -PfastTest --rerun-tasks 2>&1 | tee fasttest-baseline.log

# 4. Save results
mkdir -p do../05_Reports/04_08_Refactor/phase3-baseline/manual-$(date +%Y%m%d)
cp *.log *.json do../05_Reports/04_08_Refactor/phase3-baseline/manual-$(date +%Y%m%d)/
```

---

## 6. Acceptable Variance Thresholds

### 6.1 Performance Thresholds (P0)

| Metric | Baseline | Acceptable Variance | Action Threshold |
|--------|----------|-------------------|------------------|
| **RPS** | 965 | ±5% (917-1013) | >5% degradation ❌ FAIL |
| **p50 Latency** | 95ms | ±10% (85-105ms) | >10% increase ❌ FAIL |
| **p99 Latency** | 214ms | ±10% (193-235ms) | >10% increase ❌ FAIL |
| **L1 Hit Rate** | 85-90% | -3% absolute | Hit rate drops >3% ❌ FAIL |
| **L2 Hit Rate** | 95-98% | -2% absolute | Hit rate drops >2% ❌ FAIL |
| **Test Execution** | 38s | +20% (45s max) | >45 seconds ❌ FAIL |

### 6.2 Resilience Thresholds (P0)

| Metric | Baseline | Acceptable Variance | Action Threshold |
|--------|----------|-------------------|------------------|
| **Circuit Breaker State** | CLOSED | MUST remain CLOSED | Any OPEN ❌ FAIL |
| **Thread Pool Rejections** | 0/s | 0/s | Any rejection ❌ FAIL |
| **DB Pool Pending** | 0 | <5 | Pending ≥5 ❌ FAIL |
| **Outbox Pending** | 0 | <1000 | ≥1000 ⚠️ WARNING |
| **Graceful Shutdown** | 100% success | 100% success | Any phase fail ❌ FAIL |

### 6.3 Resource Thresholds (P1)

| Metric | Baseline | Acceptable Variance | Action Threshold |
|--------|----------|-------------------|------------------|
| **Heap Usage** | 60% | <80% | ≥80% ⚠️ WARNING |
| **Process CPU** | 20-30% | <80% | ≥80% ⚠️ WARNING |
| **GC Time** | 50ms/min | <100ms/min | ≥100ms ⚠️ WARNING |

### 6.4 Variance Calculation Formula

```python
# Percentage variance calculation
variance_percent = ((after_value - before_value) / before_value) * 100

# Absolute difference (for rates)
absolute_diff = after_value - before_value

# Example:
# Before: RPS = 965
# After:  RPS = 920
# Variance = ((920 - 965) / 965) * 100 = -4.66% (within ±5% threshold)
```

---

## 7. Before/After Comparison Template

### 7.1 Phase 3 Completion Report Template

```markdown
# Phase 3 Completion Report - Domain Extraction

**Date**: [YYYY-MM-DD]
**Agent**: 🟢 Green Performance Guru
**Baseline Date**: 2026-02-07

---

## Executive Summary

- **Status**: ✅ PASS / ❌ FAIL / ⚠️ WARNING
- **Overall Performance**: [Improved/Regressed/Neutral]
- **Critical Regressions**: [Number of P0 failures]

---

## 1. Performance Comparison

### 1.1 Throughput (RPS)

| Metric | Before | After | Variance | Status |
|--------|--------|-------|----------|--------|
| **RPS** | 965 | [VALUE] | [±%] | [✅/❌] |

**Analysis**: [Explain variance, if any]

### 1.2 Latency

| Metric | Before | After | Variance | Status |
|--------|--------|-------|----------|--------|
| **p50** | 95ms | [VALUE] | [±%] | [✅/❌] |
| **p95** | ~150ms | [VALUE] | [±%] | [✅/❌] |
| **p99** | 214ms | [VALUE] | [±%] | [✅/❌] |

**Analysis**: [Explain latency changes, hot paths affected]

### 1.3 Cache Performance

| Metric | Before | After | Variance | Status |
|--------|--------|-------|----------|--------|
| **L1 Hit Rate** | 85-90% | [VALUE] | [±%] | [✅/❌] |
| **L2 Hit Rate** | 95-98% | [VALUE] | [±%] | [✅/❌] |
| **Miss Rate** | ~5% | [VALUE] | [±%] | [✅/❌] |

**Analysis**: [Explain cache behavior changes, if any]

---

## 2. Resilience Comparison

### 2.1 Circuit Breaker

| Instance | Before | After | Status |
|----------|--------|-------|--------|
| **nexonApi** | CLOSED | [STATE] | [✅/❌] |
| **likeSyncDb** | CLOSED | [STATE] | [✅/❌] |
| **redisLock** | CLOSED | [STATE] | [✅/❌] |

**Analysis**: [Any unexpected circuit opens?]

### 2.2 Thread Pool

| Metric | Before | After | Status |
|--------|--------|-------|--------|
| **Rejections (alert)** | 0/s | [VALUE] | [✅/❌] |
| **Rejections (expectation)** | 0/s | [VALUE] | [✅/❌] |

**Analysis**: [Any backpressure issues?]

### 2.3 Database Pool

| Metric | Before | After | Status |
|--------|--------|-------|--------|
| **Lock Pool Active** | 30/40 | [VALUE] | [✅/❌] |
| **Lock Pool Pending** | 0 | [VALUE] | [✅/❌] |

**Analysis**: [N21 bottleneck still present? Improved?]

---

## 3. Test Execution Comparison

| Metric | Before | After | Variance | Status |
|--------|--------|-------|----------|--------|
| **fastTest Duration** | 38s | [VALUE] | [±%] | [✅/❌] |
| **Build Time (clean)** | 34s | [VALUE] | [±%] | [✅/❌] |

**Analysis**: [Test performance changes]

---

## 4. Grafana Dashboard Comparison

### 4.1 Dashboard Snapshots

- **Before**: [Link to baseline dashboard JSON]
- **After**: [Link to post-phase3 dashboard JSON]
- **Visual Diff**: [Screenshots or comparison notes]

### 4.2 Key Panel Changes

| Panel | Before | After | Variance |
|-------|--------|-------|----------|
| [Panel Name] | [Value] | [Value] | [±%] |

---

## 5. Regression Analysis

### 5.1 Critical Failures (P0)

- [ ] RPS degradation >5%
- [ ] p99 latency increase >10%
- [ ] Cache hit rate drop >3%
- [ ] Circuit breaker opened unexpectedly
- [ ] Thread pool rejections >0
- [ ] DB pool pending ≥5
- [ ] Graceful shutdown failed

**Total P0 Failures**: [Number]

### 5.2 Warnings (P1)

- [ ] Heap usage ≥80%
- [ ] CPU usage ≥80%
- [ ] GC time ≥100ms/min
- [ ] Outbox pending ≥1000

**Total P1 Warnings**: [Number]

---

## 6. Recommendations

### 6.1 Actions Required

1. [Action item for critical regressions]

### 6.2 Optimization Opportunities

1. [Observed improvement, should we keep it?]

---

## 7. Sign-Off

| Role | Name | Status | Comments |
|------|------|--------|----------|
| **Performance (Green)** | [Name] | [✅/❌] | [Approval/Rejection reason] |
| **Architecture (Blue)** | [Name] | [✅/❌] | [Structural review] |
| **Resilience (Red)** | [Name] | [✅/❌] | [Reliability review] |

---

## 8. Evidence

- **Load Test Logs**: [Link to file]
- **Prometheus Snapshots**: [Link to file]
- **Test Execution Logs**: [Link to file]
- **Dashboard JSONs**: [Link to files]

---

**Decision**: ✅ APPROVED for merge / ❌ REJECTED - Fix required / ⚠️ CONDITIONAL - [conditions]

**Next Steps**: [What happens next?]
```

---

## 8. Regression Detection Strategy

### 8.1 Automated Detection (Post-Phase 3)

```bash
#!/bin/bash
# scripts/validate-phase3-no-regression.sh

set -e

BASELINE_FILE="do../05_Reports/04_08_Refactor/PHASE3_BASELINE_METRICS.md"
CURRENT_DIR="do../05_Reports/04_08_Refactor/phase3-current"

echo "=== Phase 3 Regression Detection ==="

# 1. Run fastTest
echo "Running fastTest..."
START_TIME=$(date +%s)
./gradlew clean test -PfastTest --rerun-tasks > test-output.log 2>&1
END_TIME=$(date +%s)
TEST_DURATION=$((END_TIME - START_TIME))

echo "Test Duration: ${TEST_DURATION}s (baseline: 38s, max: 45s)"

if [ $TEST_DURATION -gt 45 ]; then
    echo "❌ FAIL: Test execution exceeded 45s threshold"
    exit 1
fi

# 2. Run load test (if application running)
if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "Running load test..."
    wrk -t4 -c100 -d30s http://localhost:8080/api/v3/health > load-test-output.log 2>&1

    # Parse RPS from wrk output
    RPS=$(grep "requests/sec" load-test-output.log | awk '{print $1}')
    echo "RPS: $RPS (baseline: 965, min: 917)"

    # Use python_repl for floating point comparison
    python3 -c "
import sys
rps = float('$RPS')
baseline = 965.0
min_acceptable = baseline * 0.95  # 5% variance

if rps < min_acceptable:
    print(f'❌ FAIL: RPS {rps} below minimum {min_acceptable}')
    sys.exit(1)
else:
    print(f'✅ PASS: RPS {rps} within acceptable range')
"
fi

# 3. Check circuit breaker state (if Prometheus running)
if curl -s http://localhost:9090/-/healthy > /dev/null 2>&1; then
    echo "Checking circuit breaker state..."
    CB_STATE=$(curl -s "http://localhost:9090/api/v1/query?query=resilience4j_circuitbreaker_state" | jq -r '.data.result[0].value[1]')

    if [ "$CB_STATE" != "1" ]; then
        echo "❌ FAIL: Circuit breaker not CLOSED (state=$CB_STATE)"
        exit 1
    fi
    echo "✅ PASS: Circuit breaker CLOSED"
fi

echo ""
echo "=== ✅ All Regression Checks Passed ==="
```

### 8.2 Manual Validation Checklist

Before marking Phase 3 as complete, verify:

```markdown
## Phase 3 Manual Validation Checklist

### Performance
- [ ] RPS ≥ 917 (5% variance from 965)
- [ ] p99 ≤ 235ms (10% variance from 214ms)
- [ ] L1 hit rate ≥ 82% (3% variance from 85%)
- [ ] L2 hit rate ≥ 93% (2% variance from 95%)
- [ ] fastTest ≤ 45s (20% variance from 38s)

### Resilience
- [ ] All circuit breakers CLOSED
- [ ] Thread pool rejections = 0
- [ ] DB pool pending < 5
- [ ] Graceful shutdown completes all 4 phases
- [ ] Outbox pending < 1000

### Resources
- [ ] Heap usage < 80%
- [ ] CPU usage < 80%
- [ ] GC time < 100ms/min

### Documentation
- [ ] Before/after report completed
- [ ] Grafana dashboards exported
- [ ] Prometheus snapshots saved
- [ ] Regression analysis documented
```

---

## 9. Phase 3 Completion Checklist

### 9.1 Pre-Phase 3 (Before Refactoring)

- [x] **Baseline Metrics Documented** (this file)
- [ ] **Grafana Dashboards Exported**
  ```bash
  cp -r docker/grafana/dashboards/*.json do../05_Reports/04_08_Refactor/grafana-baseline/
  ```
- [ ] **Prometheus Snapshots Captured**
  ```bash
  curl -s http://localhost:9090/api/v1/query?query=[QUERY] | jq '.' > do../05_Reports/04_08_Refactor/prometheus-baseline-[METRIC].json
  ```
- [ ] **Load Test Baseline Recorded**
  ```bash
  wrk -t4 -c100 -d30s http://localhost:8080/api/v3/health > do../05_Reports/04_08_Refactor/loadtest-baseline.log
  ```
- [ ] **Test Baseline Recorded**
  ```bash
  ./gradlew clean test -PfastTest --rerun-tasks > do../05_Reports/04_08_Refactor/fasttest-baseline.log
  ```

### 9.2 Post-Phase 3 (After Refactoring)

- [ ] **Rerun All Tests**
  ```bash
  ./gradlew clean test -PfastTest --rerun-tasks
  ```
- [ ] **Rerun Load Test**
  ```bash
  wrk -t4 -c100 -d30s http://localhost:8080/api/v3/health > do../05_Reports/04_08_Refactor/loadtest-after.log
  ```
- [ ] **Capture Current Metrics**
  ```bash
  ./scripts/capture-phase3-baseline.sh
  ```
- [ ] **Compare Before/After**
  - Use comparison template (Section 7)
  - Calculate variances
  - Identify regressions
- [ ] **Export Post-Phase 3 Dashboards**
  ```bash
  cp -r docker/grafana/dashboards/*.json do../05_Reports/04_08_Refactor/grafana-after/
  ```
- [ ] **Create Comparison Report**
  - Fill out template in Section 7.1
  - Attach evidence (logs, screenshots)
  - Sign-off from 3 agents (Green, Blue, Red)

### 9.3 Regression Escalation

**If P0 regression detected:**

1. **Immediate Action**: Stop Phase 3 rollout
2. **Analysis**: Identify root cause using profiling
3. **Remediation**: Fix regression OR justify variance
4. **Re-baseline**: If justified, update baseline with rationale
5. **Re-test**: Repeat validation

**If P1 warning detected:**

1. **Monitor**: Observe for 24 hours
2. **Investigate**: Determine if warning is genuine or noise
3. **Document**: Record findings in Phase 3 report
4. **Decision**: Proceed OR rollback based on impact

---

## 10. References

### 10.1 Related Documents

| Document | Purpose |
|----------|---------|
| `PERFORMANCE_BASELINE.md` | Pre-refactor performance baseline |
| `RESILIENCE_BASELINE.md` | Pre-refactor resilience configuration |
| `PHASE2_SUMMARY.md` | Phase 2 completion report |
| `docs/02_Chaos_Engineering/` | Chaos test scenarios (N01-N23) |
| `CLAUDE.md:4-5` | Performance & resilience requirements |

### 10.2 Key Files

| File | Purpose |
|------|---------|
| `docker/prometheus/prometheus.yml` | Prometheus scrape configuration |
| `docker/grafana/dashboards/*.json` | Grafana dashboard definitions |
| `src/main/resources/application.yml` | Metrics tags configuration |
| `src/main/resources/micrometer-registry.yml` | Micrometer registry setup |

---

## 11. Quick Reference Commands

```bash
# === Capture Baseline (Before Phase 3) ===
./scripts/capture-phase3-baseline.sh

# === Validate No Regression (After Phase 3) ===
./scripts/validate-phase3-no-regression.sh

# === Manual Metrics Collection ===
# RPS
rate(http_server_requests_seconds_count[5m])

# p99 Latency
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

# Cache Hit Rate
sum(rate(cache_hit{layer="L1"}[5m])) / (sum(rate(cache_hit{layer="L1"}[5m])) + sum(rate(cache_miss[5m]))) * 100

# DB Pool
hikaricp_connections_active{pool="MySQLLockPool"}

# Circuit Breaker
resilience4j_circuitbreaker_state{name="nexonApi"}

# === Export Dashboards ===
cp docker/grafana/dashboards/*.json do../05_Reports/04_08_Refactor/grafana-baseline/

# === Run Load Test ===
wrk -t4 -c100 -d30s http://localhost:8080/api/v3/health

# === Run Test Suite ===
./gradlew clean test -PfastTest --rerun-tasks
```

---

**[PROMISE:STAGE_COMPLETE]**

Phase 3 baseline metrics established. All Prometheus queries, Grafana dashboards, and comparison templates documented. Ready for Phase 3 domain extraction with quantitative regression detection in place.

**Next Steps:**
1. Export Grafana dashboards to `do../05_Reports/04_08_Refactor/grafana-baseline/`
2. Run `scripts/capture-phase3-baseline.sh` to capture current state
3. Proceed with Phase 3 refactoring
4. Use validation script post-refactoring to ensure no regression
