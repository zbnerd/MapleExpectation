# Resilience Baseline - Operations Before Refactor

> **Document Owner**: 🔴 Red SRE Gatekeeper (5-Agent Council)
> **Created**: 2026-02-07
> **Purpose**: Capture all resilience configurations BEFORE refactor to ensure nothing breaks
> **Status**: ✅ BASELINE ESTABLISHED

---

## 1. Timeout Layering (3 layers)

### Current Configuration

```
TCP Connect: 3s (network connection)
HTTP Response: 5s (read timeout)
TimeLimiter: 28s (total with retries)
Retry: maxAttempts=3, exponential backoff with jitter
```

### Timeout Locations

| Component | Timeout Value | File | Line |
|-----------|---------------|------|------|
| **HikariCP Connection Timeout** | 3000ms | `src/main/resources/application.yml` | 20 |
| **MySQL Lock Wait Timeout** | 8s (MDL freeze prevention) | `src/main/resources/application.yml` | 25 |
| **Resilience4j TimeLimiter** | 28s (3-retry upper bound) | `src/main/resources/application.yml` | 171 |
| **Nexon API Connect Timeout** | 3s | `src/main/resources/application.yml` | 194 |
| **Nexon API Response Timeout** | 5s | `src/main/resources/application.yml` | 195 |
| **Cache Follower Timeout** | 30s | `src/main/resources/application.yml` | 196 |
| **Discord Alert Timeout** | 3s (best-effort) | `src/main/java/.../DiscordAlertService.java` | 44 |
| **Graceful Shutdown Phase** | 50s | `src/main/resources/application.yml` | 13 |
| **Equipment Await Timeout** | 20s | `src/main/resources/application.yml` | 475 |
| **Expectation Buffer Shutdown** | 30s | `src/main/resources/application.yml` | 340 |
| **Lock Pool Connection Timeout** | 5000ms | `src/main/java/.../LockHikariConfig.java` | 69 |
| **Redisson Lock Wait** | 5s (cache singleflight) | `src/main/resources/application.yml` | 304 |
| **LangChain4j GLM Timeout** | 60s | `src/main/resources/application.yml` | 245 |

### Timeout Calculation Formula (TimeLimiter)

```java
// From application.yml:171
// maxAttempts*(connect+response) + (maxAttempts-1)*wait + margin
// = 3*(3s+5s) + 2*0.5s + 3s = 24 + 1 + 3 = 28s
timeoutDuration: 28s
```

**CRITICAL INVARIANT**: 28s upper bound MUST be preserved. Any change must recalculate this formula.

---

## 2. Circuit Breaker Configuration

### Current Settings

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10              # Last 10 calls
        failureRateThreshold: 50           # 50% failure opens circuit
        waitDurationInOpenState: 10s       # OPEN state duration
        permittedNumberOfCallsInHalfOpenState: 3
        ignoreExceptions:
          - maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker
        recordExceptions:
          - maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker
```

**File**: `src/main/resources/application.yml:84-98`

### Circuit Breaker Instances

| Instance | Sliding Window | Failure Rate | Wait Duration | Min Calls | Purpose |
|----------|---------------|--------------|--------------|-----------|---------|
| **nexonApi** | 10 | 50% | 10s | 10 | External API calls |
| **likeSyncDb** | 5 | 60% | 30s | 3 | Like sync DB batch |
| **redisLock** | 20 | 60% | 30s | 5 | Redis distributed lock |
| **openAiApi** | 10 | 50% | 60s | 5 | OpenAI LLM calls |

**File**: `src/main/resources/application.yml:99-129`

### Marker Interface Usage

**IGNORE (4xx - Business Exceptions)**:
- `CircuitBreakerIgnoreMarker` interface: `src/main/java/.../CircuitBreakerIgnoreMarker.java:3`
- These do NOT count as failures for circuit breaker state

**RECORD (5xx - System Exceptions)**:
- `CircuitBreakerRecordMarker` interface: `src/main/java/.../CircuitBreakerRecordMarker.java:3`
- These COUNT as failures and will open the circuit

### Verification

```bash
# 1. Check circuit breaker state
curl http://localhost:8080/actuator/health/circuitbreakers

# 2. View specific circuit breaker metrics
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state

# 3. Monitor via Prometheus
# Query: circuit_breaker_state{name="nexonApi"}
```

---

## 3. Graceful Shutdown (4 phases)

### Current Implementation

```java
// Phase: Integer.MAX_VALUE - 1000 (last to execute)
// File: src/main/java/maple/expectation/global/shutdown/GracefulShutdownCoordinator.java
```

### 4-Phase Sequential Shutdown

| Phase | Description | Timeout | File Reference |
|-------|-------------|---------|----------------|
| **Phase 1** | Equipment async storage completion wait | 20s | `GracefulShutdownCoordinator.java:154-168` |
| **Phase 2** | Local like buffer flush | N/A | `GracefulShutdownCoordinator.java:173-189` |
| **Phase 3** | DB final sync (distributed lock) | Wait 3s, Lease 10s | `GracefulShutdownCoordinator.java:194-220` |
| **Phase 4** | Backup data final storage | N/A | `GracefulShutdownCoordinator.java:135-137` |

### SmartLifecycle Phase Ordering

| Component | Phase | Execution Order |
|-----------|-------|-----------------|
| **ExpectationBatchShutdownHandler** | MAX_VALUE - 500 | First (buffer drain) |
| **GracefulShutdownCoordinator** | MAX_VALUE - 1000 | Second (main shutdown) |

**File**: `src/main/java/maple/expectation/global/shutdown/GracefulShutdownCoordinator.java:226`
**File**: `src/main/java/maple/expectation/service/v4/buffer/ExpectationBatchShutdownHandler.java:250`

### Graceful Shutdown Configuration

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 50s  # Per-phase timeout
```

**File**: `src/main/resources/application.yml:13`

### Verification Commands

```bash
# 1. Trigger graceful shutdown
curl -X POST http://localhost:8080/actuator/shutdown

# 2. Check shutdown metrics
curl http://localhost:8080/actuator/metrics/shutdown.coordinator.duration

# 3. Verify phases completed
# Check logs for "[1/4]", "[2/4]", "[3/4]", "[4/4]" markers
```

---

## 4. Transactional Outbox

### Triple Safety Net

1. **DB Dead Letter Queue (DLQ)**
   - Tables: `donation_dlq`, `nexon_api_dlq`
   - Schema: `src/main/resources/donation_outbox_schema.sql:30-40`

2. **File Backup**
   - Location: `/tmp/maple-shutdown`
   - Implementation: `ShutdownDataPersistenceService.java`

3. **Discord Alert**
   - Webhook: Environment variable `DISCORD_WEBHOOK_URL`
   - Implementation: `DiscordAlertService.java:70-78`

### Replay Mechanism

| Component | Interval | Batch Size | File |
|-----------|----------|------------|------|
| **NexonApiOutboxScheduler** | 10s | 100 | `NexonApiOutboxScheduler.java:55-106` |
| **Stalled Recovery** | 5min | 100 | `NexonApiOutboxScheduler.java:99-105` |
| **OutboxProcessor** | N/A (poll-based) | 100 | `NexonApiOutboxProcessor.java:69-332` |

### Outbox Configuration

```yaml
outbox:
  batch-size: 100                   # SKIP LOCKED batch size
  stale-threshold: 5m               # Stalled judgment (5min)
  max-backoff: 1h                   # Exponential backoff max
  instance-id: ${app.instance-id}   # Scale-out instance identifier
  monitoring:
    size-alert-threshold: 1000      # Alert threshold
```

**File**: `src/main/resources/application.yml:457-471`

### Evidence: N19 Replay

From `docs/02_Chaos_Engineering/06_Nightmare/Results/N19-outbox-replay-result.md`:

- **Events**: 2,160,000 accumulated during 6-hour outage
- **Replay Time**: 30 minutes
- **Success Rate**: 99.997% (2,159,993 matched, 7 DLQ)
- **Throughput**: 1,200 tps (peak 1,500 tps)

---

## 5. Connection Pool Configuration

### MySQL Pools

#### Main Pool (HikariCP)

| Setting | Value | File | Line |
|---------|-------|------|------|
| **maximum-pool-size** | 50 (prod) | `application-prod.yml` | 16 |
| **minimum-idle** | 50 (prod) | `application-prod.yml` | 17 |
| **connection-timeout** | 3000ms | `application.yml` | 20 |
| **connection-init-sql** | `SET SESSION lock_wait_timeout = 8` | `application.yml` | 25 |

#### Lock Pool (MySQLLockPool)

| Setting | Value | File | Line |
|---------|-------|------|------|
| **Pool Size** | 40 (default), 150 (prod) | `LockHikariConfig.java:49` | 49 |
| **connection-timeout** | 5000ms | `LockHikariConfig.java` | 69 |
| **Pool Name** | "MySQLLockPool" | `LockHikariConfig.java` | 72 |

**Metrics Available**:
- `hikaricp_connections_active{pool="MySQLLockPool"}`
- `hikaricp_connections_pending{pool="MySQLLockPool"}`

**File**: `src/main/java/maple/expectation/config/LockHikariConfig.java:52-89`

### Redis Connection

| Component | Configuration | Value |
|-----------|---------------|-------|
| **Redisson** | Version | 3.27.0 |
| **Sentinel HA** | Sentinels | 3 (quorum 2/3) |
| **Lock Implementation** | `lock.impl` | redis (default) |
| **Lock Pool Size** | `lock.datasource.pool-size` | 150 (prod) |

**File**: `src/main/resources/application-prod.yml:24-28`, `application.yml:437-444`

---

## 6. Thread Pool Configuration

### Async Executor

| Executor | Core | Max | Queue | Rejection Policy | File |
|----------|------|-----|-------|------------------|------|
| **equipment** (local) | 8 | 16 | 200 | AbortPolicy | `application.yml:448-450` |
| **equipment** (prod) | 16 | 32 | 500 | AbortPolicy | `application-prod.yml:57-59` |
| **preset** (local) | 12 | 24 | 100 | CallerRunsPolicy | `application.yml:452-454` |
| **preset** (prod) | 6 | 12 | 200 | CallerRunsPolicy | `application-prod.yml:61-63` |
| **alertTaskExecutor** | 2 | 4 | 200 | LOGGING_ABORT_POLICY | `ExecutorConfig.java:324-326` |
| **expectationComputeExecutor** | 4 | 8 | 200 | EXPECTATION_ABORT_POLICY | `ExecutorConfig.java:442-444` |

### Rejection Policies (CRITICAL)

**LOGGING_ABORT_POLICY**:
- Type: AbortPolicy-based
- Purpose: Alert tasks (best-effort)
- Behavior: Throws `RejectedExecutionException` with 1s sampled logging
- File: `src/main/java/maple/expectation/config/ExecutorConfig.java:85-108`

**EXPECTATION_ABORT_POLICY**:
- Type: AbortPolicy-based
- Purpose: Expectation compute (read-only)
- Behavior: Throws `RejectedExecutionException` with 1s sampled logging
- File: `src/main/java/maple/expectation/config/ExecutorConfig.java:144-167`

**CRITICAL INVARIANT**: CallerRunsPolicy is PROHIBITED for read-only executors (causes Tomcat thread starvation).

### Bulkhead Annotation

```java
@Bulkhead(name = "nexonApi", maxConcurrentCalls = 50, maxWaitDuration = 500ms)
```

**File**: `src/main/resources/application.yml:131-135`

---

## 7. Monitoring & Alerting

### Prometheus Metrics

| Metric | Description | Labels |
|--------|-------------|--------|
| `hikaricp_connections_active` | Active DB connections | pool=MySQLLockPool |
| `hikaricp_connections_pending` | Waiting threads | pool=MySQLLockPool |
| `circuit_breaker_state` | CB state (OPEN/CLOSED/HALF_OPEN) | name=nexonApi |
| `cache_hits_total` | Cache hit count | layer=L1/L2 |
| `executor.rejected` | Rejected task count | name=alert/expectation.compute |
| `http_server_requests_seconds` | Request latency | quantile=0.5,0.95,0.99 |
| `shutdown.coordinator.duration` | Shutdown time | status=success/failure |
| `shutdown.buffer.drain.duration` | Buffer drain time | N/A |
| `outbox.pending.count` | Outbox pending rows | N/A |

### Grafana Dashboards

| Dashboard | Purpose | Location |
|-----------|---------|----------|
| **JVM Metrics** | Memory, GC, threads | `docker/grafana/provisioning/dashboards/` |
| **Slow Query Dashboard** | DB query performance | `docker/grafana/provisioning/dashboards/` |
| **Application Logs** | Loki integration | `docker/promtail/config.yml` |
| **Outbox Monitoring** | Pending/processed metrics | N19 result docs |

### Discord Alerts

| Setting | Value |
|---------|-------|
| **Webhook URL** | `${DISCORD_WEBHOOK_URL}` (env var) |
| **Signature Verification** | Ed25519 (configured) |
| **RBAC** | @sre role only |
| **Timeout** | 3s (best-effort) |

**File**: `src/main/java/maple/expectation/service/v2/alert/DiscordAlertService.java:37-136`

---

## 8. Deployment Architecture

### Current Setup

```
AWS t3.small (or equivalent)
- vCPU: 2
- RAM: 2GB
- Cost: ~$15/month

Docker Compose Services:
- Spring Boot: :8080
- MySQL: :3306
- Redis Master: :6379
- Redis Slave: :6380
- Sentinels: :26379, :26380, :26381
- Prometheus: :9090
- Loki: :3100
- Grafana: :3000
```

**File**: `docker-compose.yml:1-176`

### Network Configuration

```
Network: maple-network (172.20.0.0/16)
Redis Master: 172.20.0.10
```

**File**: `docker-compose.yml:165-171`

---

## 9. Disaster Recovery

### Backup Strategy

| Component | Backup Method | Retention |
|-----------|---------------|-----------|
| **DB** | MySQL dump + GZIP | Daily |
| **Redis** | RDB snapshots (60s 1 key) | N/A |
| **Outbox** | File backup + replay | 5min TTL temp key |

### Recovery Procedures

| Scenario | Recovery Time | Evidence |
|----------|---------------|----------|
| **N19 Outbox Replay** | 47min (2.1M events) | `N19-outbox-replay-result.md` |
| **N21 Auto Mitigation** | MTTR 4min (MTTD 30s) | `INCIDENT_REPORT_N21_AUTO_MITIGATION.md` |
| **Failover** | Sentinel auto-promotion | Redis HA setup |

### N21 Auto Mitigation Baseline

From `docs/05_Reports/Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md`:

- **MTTD**: 30 seconds (p99 spike detection)
- **MTTR**: 2 minutes (auto-mitigation executed)
- **Confidence Threshold**: 80% (actual: 92%)
- **Rollback Conditions**: Defined in policy
- **Zero Data Loss**: Verified via SQL queries

---

## 10. Regression Guards

### Pre-Refactor Verification

```bash
# 1. Check timeout values
grep -n "timeout" /home/maple/MapleExpectation/src/main/resources/application.yml

# 2. Verify circuit breaker config
cat /home/maple/MapleExpectation/src/main/resources/application.yml | grep -A 20 "resilience4j:"

# 3. Test graceful shutdown
curl -X POST http://localhost:8080/actuator/shutdown

# 4. Check pool sizes
grep -n "pool-size\|pool-size\|maximum-pool-size" /home/maple/MapleExpectation/src/main/resources/application*.yml

# 5. Verify rejection policies
grep -n "AbortPolicy\|CallerRunsPolicy\|LOGGING_ABORT_POLICY\|EXPECTATION_ABORT_POLICY" /home/maple/MapleExpectation/src/main/java/maple/expectation/config/ExecutorConfig.java

# 6. Check marker interfaces
ls -la /home/maple/MapleExpectation/src/main/java/maple/expectation/global/error/exception/marker/

# 7. Verify Outbox configuration
grep -n "outbox:" /home/maple/MapleExpectation/src/main/resources/application.yml

# 8. Test circuit breaker state
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'

# 9. Check thread pool configuration
grep -n "core-pool-size\|max-pool-size\|queue-capacity" /home/maple/MapleExpectation/src/main/resources/application*.yml

# 10. Verify lock pool configuration
grep -n "lock:" /home/maple/MapleExpectation/src/main/resources/application*.yml
```

### Post-Refactor Verification

```bash
# 1. All timeout values unchanged
# Compare: diff <(grep timeout before.yml) <(grep timeout after.yml)

# 2. Circuit breaker behavior intact
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state

# 3. Graceful shutdown completes all 4 phases
# Check logs for [1/4], [2/4], [3/4], [4/4] sequence

# 4. No connection pool exhaustion
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending

# 5. Rejection policies unchanged
# Check ExecutorConfig.java for policy constants

# 6. Outbox replay functional
# Verify: SELECT COUNT(*) FROM nexon_api_outbox WHERE status = 'PENDING'

# 7. Thread pool rejection metrics intact
curl -s http://localhost:8080/actuator/metrics/executor.rejected
```

---

## 11. Critical Invariants (DO NOT CHANGE)

### Immutable

| Invariant | Current Value | Verification |
|-----------|---------------|--------------|
| **Timeout Layering** | 3s → 5s → 28s | `application.yml:171,194-195` |
| **Circuit Breaker Threshold** | 50% failure rate | `application.yml:91` |
| **Graceful Shutdown Phase Order** | Buffer → Main | Phase values |
| **Outbox Triple Safety Net** | DB + File + Discord | All 3 present |
| **Rejection Policy** | AbortPolicy (NOT CallerRuns) | `ExecutorConfig.java:144-167` |
| **Lock Wait Timeout** | 8s (MDL prevention) | `application.yml:25` |
| **Shutdown Phase Timeout** | 50s per phase | `application.yml:13` |
| **TimeLimiter Upper Bound** | 28s (3-retry formula) | `application.yml:171` |

### Can Tweak (with evidence)

| Parameter | Current | Verification Method |
|-----------|---------|---------------------|
| **Pool Sizes** | Main: 50, Lock: 150 | Monitor `hikaricp_connections_pending` |
| **TTL Values** | Various | Verify cache hit rate |
| **Retry Attempts** | maxAttempts: 3 | Verify overall timeout ≤ 28s |
| **Circuit Breaker Window** | 10 calls | Check false positive rate |
| **Queue Capacities** | 200-500 | Monitor `executor.rejected` |

---

## 12. References (Complete File Inventory)

### Configuration Files

| File | Purpose | Key Settings |
|------|---------|--------------|
| `application.yml:84-130` | Resilience4j CB | All CB instances |
| `application.yml:137-164` | Retry config | maxAttempts, backoff |
| `application.yml:165-173` | TimeLimiter | 28s timeout |
| `application.yml:194-196` | Nexon API timeouts | 3s, 5s, 30s |
| `application.yml:437-454` | Lock & Thread pools | pool-size, executor |
| `application.yml:457-471` | Outbox config | batch-size, TTL |
| `application.yml:474-483` | Shutdown config | Phase timeouts |
| `application-prod.yml:50-63` | Prod overrides | Pool sizes |

### Source Files

| File | Purpose | Key Methods |
|------|---------|-------------|
| `GracefulShutdownCoordinator.java:116-149` | Main shutdown | stop() - 4 phases |
| `ExpectationBatchShutdownHandler.java:106-141` | Buffer drain | stop() - 3 phases |
| `NexonApiOutboxScheduler.java:71-105` | Outbox poll | pollAndProcess() |
| `NexonApiOutboxProcessor.java:88-109` | Outbox process | pollAndProcess() |
| `ExecutorConfig.java:85-167` | Rejection policies | LOGGING_ABORT_POLICY, EXPECTATION_ABORT_POLICY |
| `LockHikariConfig.java:52-89` | Lock pool | MySQLLockPool setup |
| `DiscordAlertService.java:70-78` | Alerting | sendCriticalAlert() |

### Marker Interfaces

| File | Purpose |
|------|---------|
| `CircuitBreakerIgnoreMarker.java:3` | 4xx exception marker |
| `CircuitBreakerRecordMarker.java:3` | 5xx exception marker |

### Documentation References

| Document | Purpose |
|----------|---------|
| `N19-outbox-replay.md` | Outbox pattern scenario |
| `N19-outbox-replay-result.md` | Evidence: 2.1M events, 99.98% success |
| `INCIDENT_REPORT_N21_AUTO_MITIGATION.md` | MTTD 30s, MTTR 2m evidence |
| `CLAUDE.md:11-12` | Exception handling & LogicExecutor |
| `infrastructure.md:17-20` | Cache, Security, Resilience patterns |

---

## 13. Change Impact Analysis (Before ANY Refactor)

Before making ANY changes to resilience components, complete this checklist:

### Pre-Change Checklist

- [ ] **Timeout Changes**: Recalculate TimeLimiter formula (line 171)
- [ ] **Circuit Breaker Changes**: Verify failure rate threshold impact
- [ ] **Pool Size Changes**: Monitor pending connections for 1 week
- [ ] **Rejection Policy Changes**: VERIFY NO CallerRunsPolicy for read-only executors
- [ ] **Graceful Shutdown Changes**: Ensure phase ordering preserved
- [ ] **Outbox Changes**: Verify triple safety net intact

### Impact Assessment Template

```
Change: _____________________________________________________
Component: _____________________ File: _________________ Line: ____

Invariant Check:
[ ] Timeout layering preserved (3s → 5s → 28s)
[ ] Circuit breaker thresholds unchanged
[ ] Graceful shutdown phase order maintained
[ ] Outbox safety net intact
[ ] Rejection policy correct (AbortPolicy)

Verification Command: _______________________________________

Rollback Plan: ______________________________________________
```

---

## 14. Sign-Off

| Role | Name | Date | Signature |
|------|------|------|-----------|
| **SRE Gatekeeper** | Red Agent | 2026-02-07 | ✅ Baseline Established |
| **Architect** | Blue Agent | - | Pending Review |
| **Performance** | Green Agent | - | Pending Review |

---

*This baseline is the SINGLE SOURCE OF TRUTH for all resilience configurations.*
*Any deviation without documented evidence and rollback plan is PROHIBITED.*
