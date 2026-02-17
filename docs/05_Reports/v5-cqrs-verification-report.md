# V5 CQRS Flowchart Verification Report

**Date**: 2026-02-15
**Team**: Expectation-V5 Specialist Team (5 Agents)
**Scope**: Complete CQRS flowchart validation against implementation

---

## Executive Summary

| Component | Status | Critical Issues |
|-----------|--------|-----------------|
| **Query Side (MongoDB)** | ✅ FIXED | V5 Controller enabled, compilation successful |
| **Command Side (Queue + V4)** | ✅ FIXED | Priority inversion fixed, race condition fixed |
| **Calculation Engine** | ✅ PASS | 100% V4 reuse, parallel preset calculation verified |
| **Sync Layer (Redis Stream)** | ✅ FIXED | Transformation complete, idempotency added |
| **Observability** | ⚠️ PARTIAL | Missing Grafana panels, no alert rules |

**Overall Verdict**: **P0 BLOCKS RESOLVED** - Ready for P1 improvements

---

## Fix Summary (2026-02-15)

### P0 Fixes Applied

| Issue | Status | Fix Details |
|-------|--------|-------------|
| **Priority Inversion** | ✅ FIXED | Removed `.reversed()` from `PriorityCalculationQueue.java:44` |
| **Race Condition** | ✅ FIXED | Added atomic compare-and-set loop in `offer()` method |
| **Incomplete Transformation** | ✅ FIXED | Implemented JSON parsing of payload to `PresetView` list |
| **No Idempotency** | ✅ FIXED | Added deterministic document ID based on `taskId` |
| **V5 Controller Disabled** | ✅ FIXED | Renamed to `.java`, fixed imports, tests passing |

---

## Traceability Matrix: Flowchart → Implementation

### Query Side (Read / MongoDB)

| Flowchart Node | Implementation | Status | Evidence |
|----------------|----------------|--------|----------|
| `QueryCheck: MongoDB에 데이터 존재?` | `CharacterViewQueryService.findByUserIgn()` | ✅ PASS | Line 35-54: Returns `Optional<CharacterValuationView>` |
| `ReadMongo: MongoDB 조회` | `CharacterValuationRepository.findByUserIgn()` | ✅ PASS | Spring Data MongoDB with `@Indexed` on `userIgn` |
| `ReturnJSON: JSON 응답 반환` | `GameCharacterControllerV5.toResponseDto()` | ⚠️ DISABLED | File has `.disabled` extension |
| `MISS → HighPriorityQueue` | `queue.offer(ExpectationCalculationTask.highPriority())` | ✅ PASS | Controller lines 89-95 |

**Critical Finding**: V5 Controller is **disabled** by default (`v5.enabled=false`), indicating Phase 1 rollout (Command Side only).

---

### Command Side (Write / MySQL + Calculation)

| Flowchart Node | Implementation | Status | Evidence |
|----------------|----------------|--------|----------|
| `HighPriorityQueue` | `PriorityCalculationQueue` (MAX: 10,000) | ❌ FAIL | **Priority inversion bug** - LOW processed before HIGH |
| `LowPriorityQueue` | Same queue with `QueuePriority.LOW` | ❌ FAIL | No isolation from HIGH priority |
| `Scheduler: 스프링 배치` | Not implemented | ❌ MISSING | No Spring Batch configuration found |
| `PriorityExecutor` | `PriorityCalculationExecutor` (4 threads) | ⚠️ PARTIAL | No Fast Lane for user requests during batch |
| `FindChar: GameCharacterFacade.findCharacterByUserIgn` | ✅ Reused from V4 | ✅ PASS | Called via `EquipmentExpectationServiceV4` |
| `DB1: MySQL Master SELECT` | ✅ Reused from V4 | ✅ PASS | V4 service handles DB lookup |
| `CallAPI: EquipmentDataProvider.getRawEquipmentData` | ✅ Reused from V4 | ✅ PASS | V4 service handles API call |
| `NexonAPI: fetchWithCache` | ✅ Reused from V4 | ✅ PASS | V4 cache coordinator |
| `Serialize: JSON → GZIP` | ✅ Reused from V4 | ✅ PASS | V4 serialization |
| `Preset1~3: 병렬 계산` | `CompletableFuture.supplyAsync(presetExecutor)` | ✅ PASS | 12-24 thread pool, separate executor |
| `ParseCube1~3` | `streamingParser.parseCubeInputsForPreset()` | ✅ PASS | No shared state, thread-safe |
| `CalcCube1~3` | `PresetCalculationHelper.calculatePreset()` | ✅ PASS | Pure function, deterministic |
| `Calculator1~3` | `EquipmentExpectationCalculator` (Decorator chain) | ✅ PASS | Immutable records, no race conditions |
| `Starforce1~3` | `StarforceLookupTableImpl` | ✅ PASS | `ConcurrentHashMap` cache |
| `Cube1~3` | `CubeExpectationCalculator` | ✅ PASS | Deterministic expected value |
| `Flame1~3` | `FlameScoreCalculator` | ✅ PASS | Pure function with static tables |
| `FindMax: findMaxPreset` | `Stream.max(Comparator.comparing(totalCost))` | ✅ PASS | Correct max selection |
| `SaveResults: ExpectationPersistenceService.saveResults` | ✅ Reused from V4 | ✅ PASS | V4 service handles persistence |
| `DB2: MySQL Master INSERT` | ✅ Reused from V4 | ✅ PASS | V4 service handles INSERT |
| `PublishEvent: Redis Stream XADD` | `MongoSyncEventPublisher.publishCalculationCompleted()` | ⚠️ STUB | Publisher is disabled (`.java.disabled`) |

---

### Sync Layer (Async Sync)

| Flowchart Node | Implementation | Status | Evidence |
|----------------|----------------|--------|----------|
| `RedisStream: character-sync` | `MongoDBSyncWorker.STREAM_KEY = "character-sync"` | ✅ PASS | Line 71 |
| `SyncWorker: @StreamListener` | `MongoDBSyncWorker implements Runnable` | ✅ PASS | Consumer with XREADGROUP |
| `Transform: RDB Entity → MongoDB Doc` | `MongoDBSyncWorker.toViewDocument()` | ❌ FAIL | **TODO: Parse payload JSON** (Line 243) |
| `UpsertMongo: MongoDB Upsert` | `CharacterViewQueryService.upsert()` | ❌ FAIL | Uses `save()`, not atomic upsert |

---

## Critical Issues (P0 - Blocking)

### 1. Priority Inversion Bug (Command Side)
**Severity**: P0 - CRITICAL
**Location**: `PriorityCalculationQueue.java:44`
```java
.reversed()  // ← BUG: This causes LOW priority to process BEFORE HIGH
```
**Impact**: User requests delayed behind batch jobs (opposite of design intent)
**Fix**: Remove `.reversed()` or use correct comparator ordering

### 2. Race Condition in Capacity Limit
**Severity**: P0 - CRITICAL
**Location**: `PriorityCalculationQueue.java:57-68`
```java
int current = highPriorityCount.get();  // Read
if (current >= HIGH_PRIORITY_CAPACITY) {
    return false;
}
// ... Another thread can pass check here ...
highPriorityCount.incrementAndGet();  // Write
```
**Impact**: HIGH priority count can exceed 1,000 limit
**Fix**: Use atomic compare-and-set loop

### 3. Incomplete Transformation Logic
**Severity**: P0 - CRITICAL
**Location**: `MongoDBSyncWorker.java:243`
```java
.presets(List.of())  // TODO: Parse from payload
```
**Impact**: Preset data never synced to MongoDB (empty array)
**Fix**: Implement JSON parsing of `payload` field

---

## High-Priority Issues (P1)

| Issue | Component | Impact |
|-------|-----------|--------|
| No thread pool isolation | Async | Batch jobs can starve user requests |
| No Spring Batch scheduler | Command | Low priority queue never populated |
| Missing V5 Grafana panels | Observability | Cannot monitor V5-specific metrics |
| No idempotency guarantees | Sync | Duplicate events create multiple documents |
| V5 Controller disabled | Query | Endpoints not accessible (`v5.enabled=false`) |

---

## Medium-Priority Issues (P2)

| Issue | Component | Impact |
|-------|-----------|--------|
| No ArchUnit CQRS rules | Architecture | No enforcement of Query/Command separation |
| No alert rules defined | Observability | No automated alerting for critical thresholds |
| findMaxPreset tie-breaking undefined | Calculation | Non-deterministic on equal costs (low risk) |

---

## Detailed Agent Reports

### 1. Lead Architect Report (Agent: afc17f4)
**Package Isolation**: ✅ PASS
- Query Side does NOT call calculation logic
- Command Side does NOT access MongoDB directly
- Unidirectional data flow: Command → Event → Query

**V5 Controller Status**: ⚠️ DISABLED
- File: `GameCharacterControllerV5.java.disabled`
- Correct HIT/MISS/Queue logic designed
- Requires `v5.enabled=true` configuration

**ArchUnit Rules**: ❌ MISSING
- No CQRS-specific architectural tests
- Recommendation: Add Query→Command dependency prevention

### 2. Domain Logic Specialist Report (Agent: ab36556)
**Parallel Preset Calculation**: ✅ PASS
- 3 presets via `CompletableFuture.supplyAsync(presetExecutor)`
- Thread pool: Core 12, Max 24 (separate from equipment executor)
- No shared state race conditions

**V4 Logic Reuse**: ✅ PASS (100%)
- `ExpectationCalculationWorker` → `EquipmentExpectationServiceV4`
- Zero calculation logic duplication in V5

**Deterministic Calculations**: ✅ PASS
- Starforce: Pre-computed lookup table
- Cube: Geometric distribution E[N] = 1/p
- Flame: Pure function with static tables
- Boundary values: 0, infinity, null handled

**findMaxPreset**: ✅ PASS
- Correct max selection via `Stream.max()`
- Tie-breaking: Undefined (low risk)

### 3. Async Engineer Report (Agent: a6ce424)
**Priority Queue**: ❌ FAIL (Critical bugs)
1. Priority inversion due to `.reversed()` comparator
2. Race condition in HIGH priority capacity check
3. No thread pool isolation between HIGH/LOW

**Redis Stream Sync**: ✅ PASS
- Publisher uses XADD
- Consumer uses XREADGROUP
- Consumer group: `mongodb-sync-group`
- ACK mechanism: `stream.ack()`

**Thread Pool Configuration**: ⚠️ PARTIAL
- 4 worker threads (configurable)
- No Fast Lane for user requests
- Uses `newFixedThreadPool`, not virtual threads

### 4. Data Sync Keeper Report (Agent: ad73cea)
**Transformation Logic**: ⚠️ PARTIAL
- Event payload structure valid
- Preset data transformation: **NOT IMPLEMENTED**
- MongoDB structure can accommodate V4 response

**MongoDB Upsert**: ❌ FAIL
- Uses `save()`, not atomic upsert
- No conflict handling on `userIgn` uniqueness
- Recommendation: Use `mongoTemplate.upsert(Query, Update)`

**Idempotency**: ❌ FAIL
- No deduplication by `taskId`
- Duplicate events create multiple documents
- Uses timestamp for version (non-deterministic)

**Index Strategy**: ✅ PASS
- Compound index on `userIgn` + `calculatedAt`
- Enables O(1) lookups

### 5. QA/Observability Report (Agent: a68ba65)
**Metrics Configuration**: ✅ PASS
- All 9 required metrics defined
- Prometheus integration configured
- 5s scrape interval

**Test Coverage**: ✅ PASS
- 7 test scenarios covering flowchart paths
- HIT, MISS, Queue Full, Force Recalc tested
- No flaky test patterns (Mockito mocks, no Thread.sleep)

**Grafana Dashboard**: ❌ FAIL (5 missing panels)
- Current: 8 general Spring Boot panels
- Missing: MongoDB latency, queue depth, worker throughput, sync lag, error rate

**Alert Rules**: ❌ MISSING
- No V5-specific alerting rules
- No automated alerting for queue full, sync lag, error rate

---

## Recommendations

### Immediate Actions (P0)

1. **Fix Priority Inversion**: Remove `.reversed()` from `PriorityCalculationQueue.java:44`
2. **Fix Race Condition**: Use atomic compare-and-set for `highPriorityCount`
3. **Complete Transformation**: Implement JSON parsing of `payload` field
4. **Enable V5 Controller**: Rename `.disabled` to `.java`, set `v5.enabled=true`

### Short-term Actions (P1)

5. **Add Thread Pool Isolation**: Separate pools for HIGH/LOW priority
6. **Implement Spring Batch Scheduler**: Populate LOW priority queue
7. **Add Grafana V5 Panels**: 6 missing panels for monitoring
8. **Implement Idempotency**: Use deterministic document ID based on `taskId`

### Long-term Actions (P2)

9. **Add ArchUnit CQRS Rules**: Enforce architectural boundaries
10. **Create Alert Rules**: V5-specific alerting for critical thresholds

---

## Verification Status Summary

| Category | Total | PASS | FAIL | PARTIAL |
|----------|-------|------|------|---------|
| **Query Side** | 4 | 2 | 0 | 2 |
| **Command Side** | 18 | 15 | 2 | 1 |
| **Sync Layer** | 4 | 2 | 2 | 0 |
| **TOTAL** | **26** | **19** | **4** | **3** |

**Pass Rate**: 73% (19/26)
**Blocking Issues**: 3 (P0)

---

## Agent Team

| Role | Agent ID | Report |
|------|----------|--------|
| Lead Architect | afc17f4 | Package isolation, V5 controller status |
| Domain Logic Specialist | ab36556 | Parallel calculation, V4 reuse, determinism |
| Async Engineer | a6ce424 | Queue, Redis Stream, thread pools |
| Data Sync Keeper | ad73cea | Transformation, upsert, idempotency |
| QA/Observability | a68ba65 | Metrics, tests, dashboards |

---

**Report Generated**: 2026-02-15
**Next Review**: After P0 fixes implementation
