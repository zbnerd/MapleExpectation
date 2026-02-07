# Risk Register - Refactor Hazards

> **Owner:** Yellow QA Master (5-Agent Council)
>
> **Last Updated:** 2026-02-07
>
> **Status:** Phase 0 - Test Strategy & Risk Assessment
>
> **Related:**
> - [Testing Guide](../02_Technical_Guides/testing-guide.md)
> - [Multi-Agent Protocol](../00_Start_Here/multi-agent-protocol.md)
> - [Chaos Engineering](../01_Chaos_Engineering/)

---

## Executive Summary

This Risk Register identifies potential hazards during the multi-module refactoring phase. Each risk is categorized by severity (P0-P3) with specific mitigation strategies and test coverage requirements.

### Current Test Baseline

| Metric | Count | Notes |
|--------|-------|-------|
| Total Test Files | 139 | All *Test.java files |
| Total @Test Methods | 926 | Including parameterized tests |
| Chaos/Nightmare Tests | 20 | N01-N18 scenarios covered |
| Integration Tests (Testcontainers) | 37 | Docker-based isolated tests |
| Concurrency Tests | 3 | LikeConcurrency, Provider, etc. |
| Main Source Files | 441 | Production code to refactor |
| Test:Source Ratio | 2.1:1 | Tests per source file |

### Test Categories

| Category | Count | Test Tag | CI Run Time |
|----------|-------|----------|-------------|
| Chaos/Nightmare | 20 | chaos, nightmare | 10-15 min |
| Chaos Core | 3 | chaos | 2-3 min |
| Chaos Network | 5 | chaos | 3-5 min |
| Chaos Resource | 4 | chaos | 3-5 min |
| Chaos Connection | 1 | chaos | 1-2 min |
| Integration Tests | 37 | integration | 3-5 min |
| Concurrency Tests | 3 | unit | 1-2 min |
| LogicExecutor Tests | 2 | unit | <1 min |
| Resilience4j Tests | 4 | unit/integration | 1-2 min |
| TieredCache Tests | 8 | integration | 2-3 min |
| Graceful Shutdown Tests | 7 | integration | 2-3 min |
| Outbox Tests | 3 | integration | 1-2 min |
| AOP Tests | 2 | unit | <1 min |
| Controller Tests | 5 | integration | 1-2 min |
| Service Tests | 32 | unit/integration | 5-10 min |
| Queue Tests | 8 | integration | 2-3 min |
| Lock Tests | 6 | integration | 2-3 min |
| Rate Limit Tests | 5 | integration | 1-2 min |

---

## Top 10 Critical Risks + Mitigation

| # | Risk | Severity | Impact | Mitigation Strategy | Test Coverage | Status |
|---|------|----------|--------|---------------------|---------------|--------|
| 1 | TieredCache race condition on refactor | P0 | Data inconsistency, cache stampede | ArchUnit rule + concurrency regression test | TieredCacheRaceConditionTest | NEEDS_UPDATE |
| 2 | Circuit Breaker misconfiguration | P0 | Outage on cascading failure | Integration test + state verification | CircuitBreakerMarkerP0Test | NEEDS_UPDATE |
| 3 | LogicExecutor regression | P0 | Error handling breaks, silent failures | Characterization tests for all 6 patterns | DefaultCheckedLogicExecutorTest | NEEDS_UPDATE |
| 4 | Outbox data loss on module split | P0 | Data integrity violation | Replay verification test + reconciliation query | OutboxProcessorTest | NEEDS_UPDATE |
| 5 | Performance regression (p99) | P1 | Degraded UX, SLA breach | Benchmark before/after with JMH | LoadTest (CI pipeline) | MISSING |
| 6 | Graceful Shutdown failure | P1 | Data loss on deploy | Shutdown phase ordering test | GracefulShutdownIntegrationTest | NEEDS_UPDATE |
| 7 | Async pipeline deadlock | P1 | System hang, OOM | Timeout test + deadlock detection | AsyncPipelineTest | MISSING |
| 8 | DP Calculator precision loss | P2 | Wrong calculations, user impact | Golden file test with known-good values | PotentialCalculatorTest | ADEQUATE |
| 9 | Flaky test introduction | P2 | CI instability, reduced velocity | CLAUDE.md Section 24 enforcement | FlakyTestExtension | NEEDS_UPDATE |
| 10 | Memory leak in new modules | P2 | OOM in production | Heap dump analysis + leak detection | MemoryLeakTest | MISSING |

---

## Module-Specific Test Strategy

### 1. LogicExecutor Pipeline

**Current Tests:**
- `DefaultCheckedLogicExecutorTest.java` - Basic execution patterns
- `FinallyPolicyTest.java` - Finally block policy

**Gaps:**
- No test for `executeWithTranslation` pattern with IOException
- No test for `executeWithRecovery` with complex recovery logic
- No test for TaskContext propagation across thread boundaries
- No test for MDC context preservation in async scenarios

**Flaky Prevention:**
- All async tests use `CountDownLatch` + `awaitTermination()`
- No `Thread.sleep()` - use Awaitility library
- Clock injection for time-based logic

**Required Characterization Tests:**
```java
// Pattern 1: execute() - Exception propagation
@Test
void execute_shouldPropagateException_withoutLoggingSuppression() { }

// Pattern 3: executeOrDefault() - Returns value on exception
@Test
void executeOrDefault_shouldReturnDefault_onException() { }

// Pattern 4: executeWithRecovery() - Recovery function called
@Test
void executeWithRecovery_shouldCallRecoveryFunction_onException() { }

// Pattern 5: executeWithFinally() - Finally always executed
@Test
void executeWithFinally_shouldExecuteFinally_evenOnException() { }

// Pattern 6: executeWithTranslation() - Exception type conversion
@Test
void executeWithTranslation_shouldConvertIOExceptionToServerException() { }
```

**ArchUnit Rules:**
- No `try-catch` in service/infrastructure modules
- LogicExecutor must be used for exception handling
- TaskContext must be provided for all executor calls

---

### 2. Resilience4j Circuit Breaker

**Current Tests:**
- `CircuitBreakerMarkerP0Test.java` - Marker interface verification
- `DistributedCircuitBreakerTest.java` - Redis-based circuit state
- `MySQLResilienceIntegrationTest.java` - DB resilience patterns
- `RetryBudgetManagerTest.java` - Retry budget logic

**Gaps:**
- No test for state transition (CLOSED -> OPEN -> HALF_OPEN)
- No test for `CircuitBreakerIgnoreMarker` in business exceptions
- No test for circuit breaker configuration drift
- No test for circuit breaker metrics export

**Circuit State Verification:**
```java
@Test
void circuitBreaker_shouldTransitionToOpen_whenFailureThresholdExceeded() {
    // Act: Call failing service repeatedly
    for (int i = 0; i < threshold + 1; i++) {
        assertThrows(Exception.class, () -> service.call());
    }
    // Assert: Circuit is OPEN
    assertTrue(circuitBreaker.getState() == CircuitBreaker.State.OPEN);
}

@Test
void circuitBreaker_shouldIgnoreBusinessExceptions() {
    // Business exceptions should NOT open circuit
    assertThat(exception.getClass())
        .isInstanceOf(ClientBaseException.class);
    assertFalse(circuitBreaker.getState() == CircuitBreaker.State.OPEN);
}
```

---

### 3. TieredCache + Singleflight

**Current Tests:**
- `TieredCacheTest.java` - Basic L1/L2 cache operations
- `TieredCacheRaceConditionTest.java` - Concurrent write scenarios
- `TieredCacheWriteOrderP0Test.java` - Write ordering verification
- `CacheInvalidationIntegrationTest.java` - Redis pub/sub invalidation
- `EquipmentExpectationServiceV4SingleflightTest.java` - Singleflight deduplication

**Gaps:**
- No test for cache stampede prevention under high load
- No test for Singleflight timeout/cancellation handling
- No test for L1/L2 consistency during Redis failover
- No test for cache warmup correctness

**Race Condition Tests:**
```java
@Test
void tieredCache_shouldPreventStampede_underHighConcurrency() {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(threadCount);

    // Simultaneous cache miss for same key
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            startLatch.await();
            try {
                cache.get("expensive-key", () -> loadFromSource());
            } finally {
                endLatch.countDown();
            }
        });
    }

    startLatch.countDown();
    endLatch.await(10, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    // Verify: Single call to loadFromSource despite 100 threads
    verify(sourceLoader, times(1)).load();
}
```

---

### 4. AOP + Async

**Current Tests:**
- `NexonDataCacheAspectExceptionTest.java` - AOP exception handling
- `SkipEquipmentL2CacheContextTest.java` - Context-based skipping
- `ConcurrencyStatsExtension.java` - Concurrency metrics

**Gaps:**
- No test for AOP proxy ordering (critical for @Transactional + @Cacheable)
- No test for async context propagation (MDC, SecurityContext)
- No test for self-invocation bypass (Spring AOP limitation)
- No test for AOP advisor chain conflicts

**AOP Order Test:**
```java
@Test
void aspectOrder_shouldExecuteTransactionBeforeCache() {
    // Verify @Transactional commits before cache update
    // This prevents caching uncommitted data
}

@Test
void asyncMethod_shouldPreserveMdcContext() {
    MDC.put("traceId", "test-123");
    CompletableFuture<Result> future = service.asyncMethod();
    Result result = future.join();

    assertEquals("test-123", result.getMdc("traceId"));
}
```

---

### 5. Transactional Outbox

**Current Tests:**
- `OutboxProcessorTest.java` - Outbox processing logic
- `DlqHandlerTest.java` - Dead Letter Queue handling
- `DlqAdminServiceTest.java` - DLQ admin operations
- `LikeSyncAtomicityIntegrationTest.java` - Atomicity verification
- `LikeSyncCompensationIntegrationTest.java` - Compensation patterns

**Gaps:**
- No test for outbox replay after module migration
- No test for triple safety net (DB + File + Discord)
- No test for outbox message ordering guarantee
- No test for concurrent outbox processor instances

**Replay Verification Test:**
```java
@Test
void outboxReplay_shouldProcessAllMessages_afterModuleMigration() {
    // Given: Outbox with pending messages
    outbox.save(new OutboxMessage(...));

    // When: Simulate module migration (pause processor)
    processor.pause();
    migrateModule();
    processor.resume();

    // Then: All messages processed
    await().atMost(30, TimeUnit.SECONDS)
        .untilAsserted(() -> assertEquals(0, outbox.countPending()));
}
```

---

### 6. Graceful Shutdown

**Current Tests:**
- `GracefulShutdownIntegrationTest.java` - Shutdown coordination
- `GracefulShutdownRedisFailureTest.java` - Redis unavailability during shutdown
- `ShutdownDataPersistenceServiceTest.java` - Data persistence logic
- `EquipmentPersistenceTrackerTest.java` - Persistence tracking
- `ShutdownDataRecoveryIntegrationTest.java` - Recovery on restart

**Gaps:**
- No test for shutdown phase ordering validation
- No test for buffer flush verification
- No test for in-flight request completion
- No test for shutdown timeout handling

**Shutdown Phase Test:**
```java
@Test
void gracefulShutdown_shouldExecutePhasesInOrder() {
    List<String> phaseLog = new ConcurrentLinkedQueue<>();

    // Register phase callbacks
    shutdownManager.registerPhase(PHASE_1, () -> phaseLog.add("PHASE_1"));
    shutdownManager.registerPhase(PHASE_2, () -> phaseLog.add("PHASE_2"));
    shutdownManager.registerPhase(PHASE_3, () -> phaseLog.add("PHASE_3"));

    shutdown.trigger();

    assertEquals(List.of("PHASE_1", "PHASE_2", "PHASE_3"), phaseLog);
}

@Test
void gracefulShutdown_shouldFlushAllBuffers_beforeExit() {
    // Given: Buffer with pending data
    buffer.write("key", "value");

    // When: Shutdown triggered
    shutdown.trigger();

    // Then: Buffer flushed to persistence
    verify(persistence).writeAll(eq(buffer.getPendingData()));
}
```

---

### 7. DP Calculator (Probabilistic)

**Current Tests:**
- `PotentialCalculatorTest.java` - Potential calculation
- `ProbabilityConvolverTest.java` - Probability convolution
- `TailProbabilityCalculatorTest.java` - Tail probability
- `SparsePmfTest.java` / `DensePmfTest.java` - PMF data structures

**Gaps:**
- No golden file regression test
- No precision verification at boundary values
- No test for Kahan summation correctness

**Golden File Test:**
```java
@Test
void calculator_shouldProduceKnownResults_forGoldenInputs() {
    String goldenFile = "src/test/resources/golden/calculator-results.json";

    Map<String, Double> expected = readGoldenFile(goldenFile);
    Map<String, Double> actual = calculator.calculateAll(expected.keySet());

    assertThat(actual)
        .usingComparatorWithPrecision(1e-10)
        .containsAllEntriesOf(expected);
}
```

---

## Flaky Test Prevention (CLAUDE.md Section 24)

### Rules to Enforce

| Rule | Violation Detection | Fix |
|------|---------------------|-----|
| No Thread.sleep() | `grep -r "Thread\.sleep" src/test` | Use Awaitility |
| Fixed testcontainers ports | Port conflicts in CI | `@Testcontainers` with random ports |
| Deterministic time | `LocalDate.now()` in tests | `Clock.fixed()` injection |
| Isolated test data | Shared IDs between tests | UUID per test + cleanup |
| No external service calls | Real API in tests | `@MockBean` or Testcontainers |

### Pre-commit Test Fast-Path

```bash
# Fast smoke test (< 30 seconds)
./gradlew test -PfastTest --tests "*LogicExecutor*"
./gradlew test -PfastTest --tests "*TieredCache*"
./gradlew test -PfastTest --tests "*CircuitBreaker*"

# Target: < 30 seconds for PR gate validation
```

### Flaky Test Detection

```bash
# Run test 10 times to detect flakiness
for i in {1..10}; do
    ./gradlew test --tests "TargetTest" || echo "FAILED at run $i"
done

# CI-based flaky detection
./gradlew test --rerun-tasks 2>&1 | grep -c "Flaky"
```

---

## Regression Guard Commands

### Before Each Refactor Stage

```bash
#!/bin/bash
# pre-refactor-check.sh

echo "=== Pre-Refactor Validation ==="

# 1. Unit tests (fast)
echo "[1/4] Running unit tests..."
./gradlew test -PfastTest || exit 1

# 2. Quick smoke test for critical modules
echo "[2/4] Running smoke tests..."
./gradlew test \
    --tests "*TieredCacheTest" \
    --tests "*LogicExecutorTest" \
    --tests "*CircuitBreaker*" \
    --tests "*OutboxProcessor*" || exit 1

# 3. Build verification
echo "[3/4] Building..."
./gradlew clean build -x test || exit 1

# 4. Characterization test baseline
echo "[4/4] Capturing characterization baseline..."
./gradlew test --tests "*Characterization*" -PoutputBaseline=refactor-baseline.json

echo "=== Pre-Refactor Validation: PASSED ==="
```

### After Each Refactor Stage

```bash
#!/bin/bash
# post-refactor-check.sh

echo "=== Post-Refactor Validation ==="

# 1. All unit tests
echo "[1/5] Running all unit tests..."
./gradlew test -PfastTest || exit 1

# 2. Compare with baseline
echo "[2/5] Comparing with baseline..."
./gradlew test --tests "*Characterization*" -PcompareBaseline=refactor-baseline.json || exit 1

# 3. Concurrency tests (critical for race conditions)
echo "[3/5] Running concurrency tests..."
./gradlew test --tests "*ConcurrencyTest" --tests "*RaceCondition*" || exit 1

# 4. Chaos tests (sample)
echo "[4/5] Running sample chaos tests..."
./gradlew test -PchaosTest --tests "*ThunderingHerd*" --tests "*CircularLock*" || exit 1

# 5. Integration tests
echo "[5/5] Running integration tests..."
./gradlew test -PintegrationTest || exit 1

echo "=== Post-Refactor Validation: PASSED ==="
```

---

## Test Gap Analysis

### Missing Tests by Priority

| Priority | Module | Missing Test | Risk Level |
|----------|--------|--------------|------------|
| P0 | LogicExecutor | `executeWithTranslation` characterization | High |
| P0 | CircuitBreaker | State transition verification | High |
| P0 | TieredCache | Stampede prevention under load | High |
| P1 | Async | Context propagation across modules | Medium |
| P1 | Shutdown | Phase ordering validation | Medium |
| P1 | Outbox | Replay verification post-migration | Medium |
| P2 | Calculator | Golden file regression test | Low |
| P2 | Performance | JMH benchmark baseline | Low |
| P2 | Memory | Heap leak detection | Low |

### Characterization Test Requirements

Characterization tests capture current behavior to detect unintended changes during refactoring.

```java
// Template for characterization tests
@Test
@Tag("characterization")
void characterization_currentBehavior() {
    // Capture current behavior
    Result actual = systemUnderTest.input(testInput);

    // Save as baseline (first run)
    BaselineRecorder.record("test-name", actual);

    // Compare with baseline (subsequent runs)
    assertThat(actual).isEqualTo(BaselineLoader.load("test-name"));
}
```

---

## ArchUnit Rules for Refactor Safety

Add to build.gradle:

```gradle
test {
    // ArchUnit for architecture verification
    testClassesDirs += files("${buildDir}/classes/java/archUnit")
}
```

Rules to enforce:

```java
// No try-catch in business logic
@ArchTest
static final ArchRule NO_TRY_CATCH_IN_SERVICE =
    noClasses()
        .that().resideInAPackage("..service..")
        .and().areNotAssignableTo(LoggingAdvice.class)
        .should().containMethodsWithNameMatching(".*try.*catch.*");

// LogicExecutor usage
@ArchTest
static final ArchRule LOGIC_EXECUTOR_USAGE =
    classes()
        .that().resideInAPackage("..service..")
        .should().dependOnClassesThat().areAssignableTo(LogicExecutor.class);

// No cross-module dependencies
@ArchTest
static final ArchRule NO_CROSS_MODULE_DEPS =
    classes()
        .that().resideInAPackage("..service.v2..")
        .should().onlyDependOnClassesThat()
        .resideInAnyPackage("..service.v2..", "..global..", "java..", "org.springframework..");
```

---

## CI Pipeline Integration

### Test Execution Strategy

```yaml
# .github/workflows/pr.yml
name: PR Gate

on: [pull_request]

jobs:
  fast-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Run fast tests
        run: ./gradlew test -PfastTest
    timeout-minutes: 5

  smoke-test:
    runs-on: ubuntu-latest
    needs: fast-test
    steps:
      - uses: actions/checkout@v3
      - name: Run smoke tests
        run: |
          ./gradlew test \
            --tests "*LogicExecutor*" \
            --tests "*TieredCache*" \
            --tests "*CircuitBreaker*"
    timeout-minutes: 3

  characterization:
    runs-on: ubuntu-latest
    needs: smoke-test
    steps:
      - uses: actions/checkout@v3
      - name: Run characterization tests
        run: ./gradlew test --tests "*Characterization*"
    timeout-minutes: 2
```

---

## Success Criteria

A refactor stage is considered complete when:

1. [ ] All unit tests pass (`./gradlew test -PfastTest`)
2. [ ] All characterization tests match baseline
3. [ ] No new flaky tests detected (10 runs)
4. [ ] Performance within 10% of baseline (p99 latency)
5. [ ] ArchUnit rules pass
6. [ ] Code coverage unchanged or improved
7. [ ] No new SonarQube violations
8. [ ] Chaos tests (sample) pass

---

## Contact & Escalation

| Role | Name | Responsibility |
|------|------|----------------|
| Yellow QA | System | Test strategy, flaky prevention |
| Blue Architect | System | Architecture validation |
| Purple Auditor | System | Code review, compliance |

**Blocked?** Create issue with label `risk-register` and assign to Yellow QA.

---

## Change Log

| Date | Change | Author |
|------|--------|--------|
| 2026-02-07 | Initial creation | Yellow QA Master (Phase 0) |
