# Nightmare Test Code Analysis Report

**Generated:** 2026-02-19
**Analysis Scope:** All Nightmare test files in `/module-chaos-test/src/chaos-test/java/maple/expectation/chaos/nightmare/`
**Total Files Analyzed:** 19

---

## Executive Summary

### FLUSHALL Usage Analysis

**Total Files Using FLUSHALL:** 0 / 19 (0%)

| File | FLUSHALL Usage | Pattern |
|------|----------------|---------|
| ThunderingHerdNightmareTest.java | ✅ **Removed** | Now uses `safeDeleteKey()` |
| CelebrityProblemNightmareTest.java | ✅ **Removed** | Now uses `safeDeleteKey()` |

### Key Findings

1. **FLUSHALL has been completely removed** - All tests now use selective key deletion (`safeDeleteKey()`)
2. **Fault injection is realistic** - All tests use actual failure scenarios (timeouts, deadlocks, connection exhaustion, selective key deletion)
3. **Verification criteria are appropriate** - Tests measure meaningful metrics (response time, deadlock count, data integrity)
4. **L1/L2 tiered cache tests** - N01 now includes separate tests for L1 invalidation, L2 invalidation, and combined cold start scenarios

---

## Detailed Analysis

### 1. FLUSHALL Usage Analysis (Updated 2026-02-19)

#### Files That Previously Used FLUSHALL (Now Removed):

**ThunderingHerdNightmareTest.java (N01)**
- **Previous Pattern:** `redisTemplate.getConnectionFactory().getConnection().flushAll()`
- **Current Pattern:** `safeDeleteKey(CACHE_KEY)` - Selective key deletion
- **Improvement:** More realistic scenario simulating TTL expiration or targeted cache invalidation
- **Additional Tests:** L1 invalidation, L2 invalidation, and combined cold start scenarios
- **Threshold:** Strengthened from 10% to 1% DB query ratio

**CelebrityProblemNightmareTest.java (N05)**
- **Previous Pattern:** `redisTemplate.getConnectionFactory().getConnection().flushAll()`
- **Current Pattern:** `safeDeleteKey(HOT_KEY)` - Selective key deletion
- **Improvement:** More realistic hot key scenario without affecting other cache entries
- **Verification:** Tests measure lock contention, DB query rate, response distribution

#### All Files Now Use Realistic Fault Injection (19 files):

All tests use realistic fault injection methods:
- **Selective key deletion** (ThunderingHerdNightmareTest, CelebrityProblemNightmareTest)
- **Connection timeouts** (ConnectionVampireNightmareTest)
- **Deadlock scenarios** (DeadlockTrapNightmareTest, CircularLockDeadlockNightmareTest)
- **Thread pool exhaustion** (ThreadPoolExhaustionNightmareTest, CallerRunsPolicyNightmareTest)
- **Metadata locks** (MetadataLockFreezeNightmareTest)
- **Outbox processing failures** (ZombieOutboxNightmareTest, PoisonPillNightmareTest, NexonApiOutboxNightmareTest)
- **AOP proxy bypass** (SelfInvocationNightmareTest, AopOrderNightmareTest)
- **Context propagation loss** (AsyncContextLossNightmareTest)
- **Pipeline exception handling** (PipelineExceptionNightmareTest)
- **Deep paging** (DeepPagingNightmareTest)
- **Timeout cascades** (N06TimeoutCascadeNightmareTest)
- **Network latency via Toxiproxy** (N06TimeoutCascadeNightmareTest)

---

## Complete Test File Analysis

| File | FLUSHALL | Fault Injection Method | Verification Criteria | Realism | Needs Improvement |
|------|----------|----------------------|---------------------|----------|-------------------|
| **ThunderingHerdNightmareTest.java** | ❌ No (Removed) | Selective key deletion + 1,000 concurrent requests | DB query ≤1%, P99 < 5s | ✅ HIGH | No |
| **DeadlockTrapNightmareTest.java** | ❌ No | Cross-table lock ordering (A→B, B→A) | Deadlock count, data integrity | ✅ HIGH | No |
| **ThreadPoolExhaustionNightmareTest.java** | ❌ No | Submit 250 tasks (pool capacity + 50) | CallerRuns=0, submit <500ms | ✅ HIGH | No |
| **ConnectionVampireNightmareTest.java** | ❌ No | Mock API 5s delay + 20 concurrent requests | Connection timeout=0 | ✅ HIGH | No |
| **CelebrityProblemNightmareTest.java** | ❌ No (Removed) | Selective key deletion + 1,000 concurrent hot key requests | DB query ≤10%, P99 < 5s | ✅ HIGH | No |
| **MetadataLockFreezeNightmareTest.java** | ❌ No | Long SELECT + ALTER TABLE + normal queries | Blocked queries ≤5 | ✅ HIGH | No |
| **CircularLockDeadlockNightmareTest.java** | ❌ No | Named lock reverse ordering (A→B, B→A) | Deadlock/timeout count | ✅ HIGH | No |
| **CallerRunsPolicyNightmareTest.java** | ❌ No | Saturate queue + verify caller thread execution | CallerRuns=0, fast fail | ✅ HIGH | No |
| **ZombieOutboxNightmareTest.java** | ❌ No | Force PROCESSING status + 10min old timestamp | Status ≠ PROCESSING | ✅ HIGH | No |
| **PipelineExceptionNightmareTest.java** | ❌ No | executeOrDefault with RuntimeException | Default returned, exception logged | ✅ HIGH | No |
| **AsyncContextLossNightmareTest.java** | ❌ No | MDC propagation across async boundaries | MDC preserved in async threads | ✅ HIGH | No |
| **SelfInvocationNightmareTest.java** | ❌ No | this.method() internal calls vs external proxy | Transaction propagation analysis | ✅ HIGH | No |
| **PoisonPillNightmareTest.java** | ❌ No | Native query payload corruption | verifyIntegrity() failure → DLQ | ✅ HIGH | No |
| **DeepPagingNightmareTest.java** | ❌ No | OFFSET 9990 (deep pagination) | Performance degradation ratio | ✅ HIGH | No |
| **NexonApiOutboxNightmareTest.java** | ❌ No | 100K entries + API 503 outage → recovery | 100% complete, 0 data loss, DLQ <0.1% | ✅ HIGH | No |
| **NexonApiOutboxMultiFailureNightmareTest.java** | ❌ No | Compound failures: Redis timeout, DB failover, process kill | Zero data loss, DLQ <0.1% | ✅ HIGH | No |
| **AopOrderNightmareTest.java** | ❌ No | Multiple AOP stack analysis | Metrics recording, execution order | ✅ HIGH | No |
| **N06TimeoutCascadeNightmareTest.java** | ❌ No | Redis 5s delay × 3 retries vs 3s client timeout | Zombie request rate, waste time | ✅ HIGH | No |

All other tests use realistic fault injection methods:
- **Connection timeouts** (ConnectionVampireNightmareTest)
- **Deadlock scenarios** (DeadlockTrapNightmareTest, CircularLockDeadlockNightmareTest)
- **Thread pool exhaustion** (ThreadPoolExhaustionNightmareTest, CallerRunsPolicyNightmareTest)
- **Metadata locks** (MetadataLockFreezeNightmareTest)
- **Outbox processing failures** (ZombieOutboxNightmareTest, PoisonPillNightmareTest, NexonApiOutboxNightmareTest)
- **AOP proxy bypass** (SelfInvocationNightmareTest, AopOrderNightmareTest)
- **Context propagation loss** (AsyncContextLossNightmareTest)
- **Pipeline exception handling** (PipelineExceptionNightmareTest)
- **Deep paging** (DeepPagingNightmareTest)
- **Timeout cascades** (N06TimeoutCascadeNightmareTest)

---

## Complete Test File Analysis

| File | FLUSHALL | Fault Injection Method | Verification Criteria | Realism | Needs Improvement |
|------|----------|----------------------|---------------------|----------|-------------------|
| **ThunderingHerdNightmareTest.java** | ✅ Yes | Redis FLUSHALL → 1,000 concurrent requests | DB query ≤10%, P99 < 5s | ✅ HIGH | No |
| **DeadlockTrapNightmareTest.java** | ❌ No | Cross-table lock ordering (A→B, B→A) | Deadlock count, data integrity | ✅ HIGH | No |
| **ThreadPoolExhaustionNightmareTest.java** | ❌ No | Submit 250 tasks (pool capacity + 50) | CallerRuns=0, submit <500ms | ✅ HIGH | No |
| **ConnectionVampireNightmareTest.java** | ❌ No | Mock API 5s delay + 20 concurrent requests | Connection timeout=0 | ✅ HIGH | No |
| **CelebrityProblemNightmareTest.java** | ✅ Yes | FLUSHALL + 1,000 concurrent hot key requests | DB query ≤10%, P99 < 5s | ✅ HIGH | No |
| **MetadataLockFreezeNightmareTest.java** | ❌ No | Long SELECT + ALTER TABLE + normal queries | Blocked queries ≤5 | ✅ HIGH | No |
| **CircularLockDeadlockNightmareTest.java** | ❌ No | Named lock reverse ordering (A→B, B→A) | Deadlock/timeout count | ✅ HIGH | No |
| **CallerRunsPolicyNightmareTest.java** | ❌ No | Saturate queue + verify caller thread execution | CallerRuns=0, fast fail | ✅ HIGH | No |
| **ZombieOutboxNightmareTest.java** | ❌ No | Force PROCESSING status + 10min old timestamp | Status ≠ PROCESSING | ✅ HIGH | No |
| **PipelineExceptionNightmareTest.java** | ❌ No | executeOrDefault with RuntimeException | Default returned, exception logged | ✅ HIGH | No |
| **AsyncContextLossNightmareTest.java** | ❌ No | MDC propagation across async boundaries | MDC preserved in async threads | ✅ HIGH | No |
| **SelfInvocationNightmareTest.java** | ❌ No | this.method() internal calls vs external proxy | Transaction propagation analysis | ✅ HIGH | No |
| **PoisonPillNightmareTest.java** | ❌ No | Native query payload corruption | verifyIntegrity() failure → DLQ | ✅ HIGH | No |
| **DeepPagingNightmareTest.java** | ❌ No | OFFSET 9990 (deep pagination) | Performance degradation ratio | ✅ HIGH | No |
| **NexonApiOutboxNightmareTest.java** | ❌ No | 100K entries + API 503 outage → recovery | 100% complete, 0 data loss, DLQ <0.1% | ✅ HIGH | No |
| **NexonApiOutboxMultiFailureNightmareTest.java** | ❌ No | Compound failures: Redis timeout, DB failover, process kill | Zero data loss, DLQ <0.1% | ✅ HIGH | No |
| **AopOrderNightmareTest.java** | ❌ No | Multiple AOP stack analysis | Metrics recording, execution order | ✅ HIGH | No |
| **N06TimeoutCascadeNightmareTest.java** | ❌ No | Redis 5s delay × 3 retries vs 3s client timeout | Zombie request rate, waste time | ✅ HIGH | No |

---

## Realism Assessment

### Highly Realistic Fault Injection (Excellent)

Most tests use production-like failure scenarios:

1. **Database Deadlocks** (DeadlockTrapNightmareTest)
   - Uses actual InnoDB row-level locks
   - Verifies Coffman conditions (circular wait)
   - Measures deadlock detection time

2. **Connection Pool Exhaustion** (ConnectionVampireNightmareTest, ThreadPoolExhaustionNightmareTest)
   - Real HikariCP pool saturation
   - Measures connection acquire time
   - Verifies fail-fast behavior

3. **Distributed Lock Deadlocks** (CircularLockDeadlockNightmareTest)
   - MySQL GET_LOCK with reverse ordering
   - Tests actual lock strategy implementation
   - Validates timeout behavior

4. **Outbox Pattern Scenarios** (ZombieOutboxNightmareTest, PoisonPillNightmareTest, NexonApiOutboxNightmareTest)
   - Real donation service calls
   - Content hash verification
   - DLQ triple safety net

### Moderate Realism (Acceptable)

5. **AOP/Proxy Tests** (SelfInvocationNightmareTest, AopOrderNightmareTest)
   - Analytical rather than injection
   - Documents architecture patterns
   - Useful for design validation

6. **Async Context Loss** (AsyncContextLossNightmareTest)
   - Uses actual executors with TaskDecorator
   - Verifies MDC propagation
   - Good integration test

### FLUSHALL Usage (Context-Appropriate)

7. **Cache Stampede Tests** (ThunderingHerdNightmareTest, CelebrityProblemNightmareTest)
   - FLUSHALL simulates Redis restart
   - Tests singleflight pattern effectiveness
   - Measures actual DB load
   - **Justification:** Realistic cold start scenario

---

## Verification Criteria Assessment

### Excellent Metrics (All Tests)

| Category | Metric Examples | Files |
|----------|-----------------|-------|
| **Performance** | Response time, throughput, degradation ratio | All tests |
| **Reliability** | Deadlock count, timeout count, failure rate | N02, N03, N06, N09 |
| **Data Integrity** | Zero data loss, DLQ rate, consistency checks | N13, N17, N19, N19+ |
| **Resource Usage** | Connection pool status, thread pool metrics | N03, N04, N10 |
| **Correctness** | Transaction propagation, AOP order, context preservation | N12, N15, N16 |

### PASS/FAIL Criteria Appropriateness

All tests use **conditional PASS** or **documentation-focused** approaches:

- **Expected FAIL** scenarios test vulnerable system states
- Tests document findings even when failure is expected
- Clear improvement suggestions provided
- Metrics-based thresholds (e.g., DB query ≤10%, P99 < 5s)

---

## Improvement Recommendations

### Overall Assessment: ✅ NO CRITICAL ISSUES

The Nightmare test suite demonstrates:
1. **High-quality fault injection** - Most scenarios are realistic
2. **Meaningful verification** - All tests measure actionable metrics
3. **Good documentation** - Each test explains the CS principle
4. **Appropriate FLUSHALL use** - Only 2 files, both justified for cold start simulation

### Minor Suggestions (Optional)

1. **ThunderingHerdNightmareTest.java**
   - ✅ Already excellent - FLUSHALL is appropriate for cold start simulation
   - Consider adding TieredCache singleflight validation (mentioned in docs)

2. **CelebrityProblemNightmareTest.java**
   - ✅ FLUSHALL is appropriate for L1/L2 TTL expiration simulation
   - Current implementation is solid

3. **SelfInvocationNightmareTest.java**
   - Currently analytical (no runtime injection)
   - Consider adding runtime test with actual proxy behavior verification

4. **AopOrderNightmareTest.java**
   - Good documentation focus
   - Could add runtime AOP execution order verification if needed

---

## Conclusion

### FLUSHALL Usage: ✅ COMPLETELY REMOVED

**All 19 files now use selective key deletion or other realistic failure scenarios:**
- Selective key deletion (`safeDeleteKey()`) for cache tests
- Realistic TTL expiration simulation
- No impact on other cache entries

### Fault Injection: ✅ EXCELLENT

**All 19 files use realistic, production-like failures:**
- Selective key deletion (replaces FLUSHALL)
- Database deadlocks
- Connection pool exhaustion
- Distributed lock issues
- Outbox pattern edge cases
- AOP proxy bypass
- Timeout cascades
- Network latency via Toxiproxy

### Verification Criteria: ✅ APPROPRIATE

**All tests measure meaningful metrics:**
- Performance (response time, throughput)
- Reliability (deadlock/timeout counts)
- Data integrity (zero loss, DLQ rates)
- Resource usage (pool status, thread counts)
- Correctness (transaction propagation, context preservation)

---

**Recommendation:** The Nightmare test suite is now fully aligned with production-like scenarios. All FLUSHALL usage has been removed and replaced with selective key deletion patterns.

---

## Appendix: File-by-File Details

### Files That Previously Used FLUSHALL (Now Removed)

1. **ThunderingHerdNightmareTest.java**
   - **Previous:** `redisTemplate.getConnectionFactory().getConnection().flushAll();`
   - **Current:** `safeDeleteKey(CACHE_KEY)`
   - **Purpose:** Selective cache invalidation for cache stampede test
   - **Verification:** DB query ratio ≤1%, P99 response time <5s

2. **CelebrityProblemNightmareTest.java**
   - **Previous:** `redisTemplate.getConnectionFactory().getConnection().flushAll();`
   - **Current:** `safeDeleteKey(HOT_KEY)`
   - **Purpose:** Selective hot key invalidation for celebrity problem test
   - **Verification:** DB query ratio ≤10%, lock failure rate, P99 <5s

### Files Without FLUSHALL (17)

All use realistic fault injection - see detailed table above.

---

**Report End**
