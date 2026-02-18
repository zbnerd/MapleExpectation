# Code Review Checklist: Flaky Test SOLID-Based Refactoring

> **Version**: 1.0.0
> **Last Updated**: 2026-02-10
> **Related ADR**: [ADR-020](../adr/ADR-020-flaky-test-fixing-solid-refactoring.md)
> **Related Issues**: #328, #329, #330

---

## Overview

This checklist is designed for multi-agent review of flaky test fixes following SOLID principles and CLAUDE.md guidelines. Use this checklist when reviewing PRs that address flaky tests identified in [flaky-test-management.md](../03_Technical_Guides/flaky-test-management.md).

---

## 1. SOLID Principles Compliance

### 1.1 Single Responsibility Principle (SRP)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 1.1.1 | Does each test method verify only one scenario? | [ ] | Each test should have a single @DisplayName with one clear assertion |
| 1.1.2 | Is test setup data isolated in @BeforeEach? | [ ] | No shared setup between test methods |
| 1.1.3 | Are helper methods properly extracted for complex assertions? | [ ] | Follow Section 15: Lambda Hell prevention (3-line rule) |
| 1.1.4 | Is Awaitility usage wrapped in dedicated helper class? | [ ] | Reference: TestAwaitilityHelper pattern from ADR-020 |
| 1.1.5 | Does each test class have a single, well-defined purpose? | [ ] | No "God Test" classes testing multiple unrelated features |

**Evidence Template:**
```java
// Good: Single responsibility
@Test
@DisplayName("동기화 성공 시 임시 키 삭제 확인")
void syncSuccess_TempKeyDeleted() {
    // Single scenario: verify temp key deletion after sync
}

// Bad: Multiple responsibilities
@Test
@DisplayName("동기화 및 삭제 및 복구 테스트") // ❌ Three scenarios
void syncAndDeleteAndRecover() { }
```

### 1.2 Open/Closed Principle (OCP)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 1.2.1 | Are wait strategies extensible without modification? | [ ] | Use WaitStrategy interface pattern from ADR-020 |
| 1.2.2 | Can new test scenarios be added without modifying existing tests? | [ ] | No hardcoded values that prevent extension |
| 1.2.3 | Is polling configuration externalized? | [ ] | Awaitility timeout/pollInterval as constants |

**Evidence Template:**
```java
// Good: Extensible wait strategy
public interface WaitStrategy {
    void waitFor(Runnable condition);
}

// Bad: Hardcoded polling
await().atMost(2000, TimeUnit.MILLISECONDS) // ❌ Magic number
```

### 1.3 Liskov Substitution Principle (LSP)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 1.3.1 | Can test implementations be substituted without breaking tests? | [ ] | Mock/stub behavior matches real implementation |
| 1.3.2 | Are inherited test methods still valid in subclasses? | [ ] | IntegrationTestSupport extension is appropriate |
| 1.3.3 | Do test doubles preserve contracts? | [ ] | @Mock behavior aligns with actual service behavior |

### 1.4 Interface Segregation Principle (ISP)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 1.4.1 | Are test-specific interfaces focused and cohesive? | [ ] | No fat interfaces with unused methods |
| 1.4.2 | Are test helper methods grouped by responsibility? | [ ] | Separate helpers for DB, Redis, Awaitility operations |

### 1.5 Dependency Inversion Principle (DIP)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 1.5.1 | Do tests depend on abstractions (interfaces), not concretions? | [ ] | @MockBean over direct instantiation |
| 1.5.2 | Are dependencies injected via constructor? | [ ] | Follow Section 6: Constructor injection mandatory |
| 1.5.3 | Are test doubles defined at appropriate boundaries? | [ ] | Mock external services, test internal logic |

---

## 2. CLAUDE.md Compliance

### 2.1 Section 4: SOLID Principles

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 2.1.1 | Sequential Thinking applied before implementation? | [ ] | Dependencies, modern syntax, infrastructure impact analyzed |
| 2.1.2 | Code follows modern Java 21 patterns? | [ ] | Records, pattern matching, switch expressions used appropriately |
| 2.1.3 | Refactoring done before new features? | [ ] | Existing SOLID violations addressed first |

### 2.2 Section 12: Zero Try-Catch Policy & LogicExecutor

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 2.2.1 | **CRITICAL**: No try-catch blocks in test code? | [ ] | All execution through LogicExecutor patterns |
| 2.2.2 | Are test exceptions properly propagated? | [ ] | No swallowed exceptions in test logic |
| 2.2.3 | Is LogicExecutor.executeOrDefault used for safe fallbacks? | [ ] | Reference: CLAUDE.md Section 12 patterns |
| 2.2.4 | Are test resources managed via executeWithFinally? | [ ] | Proper cleanup in @AfterEach if needed |

**Anti-Pattern Detection:**
```java
// Bad: Direct try-catch (Section 12 violation)
try {
    service.process();
} catch (Exception e) {
    log.error("Error", e);
}

// Good: LogicExecutor pattern
executor.execute(() -> service.process(), context);
```

### 2.3 Section 15: Anti-Pattern: Lambda Hell

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 2.3.1 | **3-Line Rule**: No lambda exceeds 3 lines? | [ ] | Extract to private method if longer |
| 2.3.2 | Method references preferred over lambdas? | [ ] | `service::process` over `() -> service.process()` |
| 2.3.3 | No nested lambdas (executor in executor)? | [ ] | Flatten execution hierarchy |
| 2.3.4 | No branching (if/else) inside lambdas? | [ ] | Extract conditional logic to methods |

**Evidence Template:**
```java
// Bad: Lambda hell (Section 15 violation)
await().untilAsserted(() -> {
    if (condition) {
        assertThat(repo.findById(id)).isPresent();
    } else {
        assertThat(repo.findById(id)).isEmpty();
    }
});

// Good: Method extraction
await().untilAsserted(() -> this.verifyRepositoryState(id));
```

### 2.4 Section 24: Flaky Test Prevention

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 2.4.1 | **CRITICAL**: No Thread.sleep usage? | [ ] | Replace with Awaitility or CountDownLatch |
| 2.4.2 | Proper Awaitility usage with atMost timeout? | [ ] | Maximum 5 seconds, reasonable poll interval |
| 2.4.3 | Explicit transaction boundaries? | [ ] | No @Transactional on concurrent test classes |
| 2.4.4 | Proper cleanup in @AfterEach? | [ ] | Redis keys deleted, test data cleaned up |
| 2.4.5 | @Tag("flaky") removed after fix? | [ ] | Verify fix by removing the tag |

**Anti-Pattern Detection:**
```java
// Bad: Thread.sleep anti-pattern (Section 24 violation)
Thread.sleep(200); // ❌ Environment-dependent

// Good: Awaitility dynamic wait
await().atMost(5, TimeUnit.SECONDS)
    .untilAsserted(() -> assertThat(condition).isTrue());
```

---

## 3. Code Quality

### 3.1 Method Length & Complexity

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 3.1.1 | Are test methods under 20 lines? | [ ] | Follow Section 6 guidelines |
| 3.1.2 | Is indentation max 2 levels? | [ ] | Fail Fast (Early Return) pattern |
| 3.1.3 | Is cyclomatic complexity low (< 5)? | [ ] | Single assertion per test preferred |
| 3.1.4 | No magic numbers/hardcoded values? | [ ] | Use constants for timeouts, test data |

**Evidence Template:**
```java
// Good: Constants, not magic numbers
private static final Duration TIMEOUT = Duration.ofSeconds(5);
private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
private static final String TEST_FINGERPRINT = "test-fingerprint";

// Bad: Magic numbers
await().atMost(2000, TimeUnit.MILLISECONDS); // ❌
```

### 3.2 Naming & Readability

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 3.2.1 | Are variable names descriptive? | [ ] | `activeSubscribers`, not `subs` |
| 3.2.2 | Do test methods describe behavior? | [ ] | `shouldReturnTrueWhen...` pattern |
| 3.2.3 | Are constants properly named? | [ ] | UPPER_SNAKE_CASE for constants |
| 3.2.4 | Is test intent clear from @DisplayName? | [ ] | Korean display names with clear scenarios |

### 3.3 Modern Java Patterns

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 3.3.1 | Java 21 features used appropriately? | [ ] | Records for test data, pattern matching |
| 3.3.2 | Optional chaining instead of null checks? | [ ] | Section 4: Optional Chaining Best Practice |
| 3.3.3 | No deprecated APIs used? | [ ] | Section 5: Deprecation Prohibition |
| 3.3.4 | RestClient over RestTemplate? | [ ] | Latest Best Practice APIs |

---

## 4. Test Reliability

### 4.1 Thread Safety & Concurrency

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 4.1.1 | **CRITICAL**: No race conditions in concurrent tests? | [ ] | Proper synchronization, CountDownLatch usage |
| 4.1.2 | Is ExecutorService properly shut down? | [ ] | awaitTermination with adequate timeout |
| 4.1.3 | Are distributed locks properly tested? | [ ] | Redisson lock acquisition/release |
| 4.1.4 | Is @Execution(ExecutionMode.SAME_THREAD) used? | [ ] | For tests requiring serial execution |

**DonationTest Specific:**
```java
// Good: Proper executor shutdown
executorService.shutdown();
boolean terminated = executorService.awaitTermination(10, TimeUnit.SECONDS);
assertThat(terminated).as("ExecutorService 정상 종료").isTrue();
```

### 4.2 Asynchronous Operations

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 4.2.1 | **CRITICAL**: No fixed wait times (Thread.sleep)? | [ ] | Use Awaitility or CountDownLatch |
| 4.2.2 | Proper Awaitility configuration? | [ ] | atMost(5, SECONDS), pollInterval(100, MILLISECONDS) |
| 4.2.3 | Explicit async completion verification? | [ ] | untilAsserted with meaningful assertion |
| 4.2.4 | Redis async operations properly awaited? | [ ] | RedisTemplate operations verified before assertion |

**RefreshTokenIntegrationTest Specific:**
```java
// Good: Awaitility for Redis save verification
refreshTokenService.createRefreshToken(sessionId, fingerprint);
awaitility.untilAsserted(() ->
    assertThat(refreshTokenRepository.findById(tokenId)).isPresent()
);
```

### 4.3 Transaction Boundaries

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 4.3.1 | **CRITICAL**: No @Transactional on test class? | [ ] | Prevents cross-thread visibility issues |
| 4.3.2 | Explicit flush for cross-thread visibility? | [ ] | saveAndFlush + flush() for concurrent access |
| 4.3.3 | Each test in independent transaction? | [ ] | @Transactional(propagation = REQUIRES_NEW) if needed |
| 4.3.4 | Proper cleanup of test data? | [ ] | Database state reset between tests |

### 4.4 Resource Cleanup

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 4.4.1 | Redis keys cleaned up in @AfterEach? | [ ] | No key leakage between tests |
| 4.4.2 | Test containers properly managed? | [ ] | Singleton container for performance |
| 4.4.3 | Mock resets between tests? | [ ] | @DirtiesContext if needed |
| 4.4.4 | Database rows properly deleted? | [ ] | Tracking and cleanup pattern |

**LikeSyncCompensationIntegrationTest Specific:**
```java
// Good: Awaitility for temp key deletion verification
awaitility.untilAsserted(() -> {
    assertThat(redisTemplate.hasKey(SOURCE_KEY)).isFalse();
    var tempKeys = redisTemplate.keys("{buffer:likes}:sync:*");
    assertThat(tempKeys).isEmpty();
});
```

---

## 5. Statelessness & Isolation

### 5.1 Test Isolation

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 5.1.1 | **CRITICAL**: No shared state between tests? | [ ] | Each test independent |
| 5.1.2 | No dependency on execution order? | [ ] | @Order annotation should be unnecessary |
| 5.1.3 | Static state properly reset? | [ ] | @BeforeAll/@AfterAll for shared resources |
| 5.1.4 | Thread-locals properly cleaned? | [ ] | MDC cleared in @AfterEach |

### 5.2 Data Isolation

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 5.2.1 | Unique test data per test? | [ ] | UUID-based names, random IDs |
| 5.2.2 | Database properly reset? | [ ] | @Sql or @Transactional for rollback |
| 5.2.3 | Redis keys use hash tags? | [ ] | {key:pattern} for cluster compatibility |
| 5.2.4 | No leakage to production data? | [ ] | Test profiles properly isolated |

**Evidence Template:**
```java
// Good: Unique test data
private String generateUniqueKey() {
    return "{test:keys}:" + UUID.randomUUID();
}

// Bad: Shared state
private static final String SHARED_KEY = "shared-key"; // ❌
```

### 5.3 Environment Independence

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 5.3.1 | Tests pass on local and CI? | [ ] | No environment-specific timing |
| 5.3.2 | No dependency on external services? | [ ] | Testcontainers or mocks for external deps |
| 5.3.3 | Timezone-independent? | [ ] | Fixed clock or timezone in tests |
| 5.3.4 | Locale-independent assertions? | [ ] | No locale-dependent string comparisons |

---

## 6. ADR-020 Specific Requirements

### 6.1 RefreshTokenIntegrationTest (#329)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 6.1.1 | Thread.sleep(200) replaced with Awaitility? | [ ] | Evidence ID: [C1] |
| 6.1.2 | build.gradle includes Awaitility dependency? | [ ] | `testImplementation 'org.awaitility:awaitility:4.2.0'` |
| 6.1.3 | @Tag("flaky") removed after fix? | [ ] | Verify all flaky tags removed |
| 6.1.4 | Timeout set to 5 seconds minimum? | [ ] | `.atMost(5, TimeUnit.SECONDS)` |
| 6.1.5 | Poll interval reasonable (50-100ms)? | [ ] | `.pollInterval(100, TimeUnit.MILLISECONDS)` |

### 6.2 DonationTest (#328)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 6.2.1 | @Transactional removed from test class? | [ ] | Evidence ID: [C2] |
| 6.2.2 | saveAndFlush used for cross-thread visibility? | [ ] | Explicit flush() after save |
| 6.2.3 | ExecutorService timeout increased to 10s? | [ ] | From 5s to 10s for CI stability |
| 6.2.4 | Proper termination assertion added? | [ ] | `assertThat(terminated).isTrue()` |
| 6.2.5 | @Tag("flaky") removed after fix? | [ ] | Both concurrencyTest and hotspotTest |

### 6.3 LikeSyncCompensationIntegrationTest (#330)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 6.3.1 | Awaitility used for temp key deletion? | [ ] | Evidence ID: [C3] |
| 6.3.2 | Redis key assertions inside untilAsserted? | [ ] | Both hasKey and keys() assertions |
| 6.3.3 | Consecutive failure test properly awaited? | [ ] | Data recovery verified with awaitility |
| 6.3.4 | @Tag("flaky") removed after fix? | [ ] | Both test methods |
| 6.3.5 | Poll interval 50ms for faster feedback? | [ ] | Optimized for sync operations |

### 6.4 TestAwaitilityHelper (New Component)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 6.4.1 | Helper class created under support package? | [ ] | `src/test/java/maple/expectation/support/` |
| 6.4.2 | DEFAULT_AWAIT properly configured? | [ ] | 5s timeout, 100ms poll interval |
| 6.4.3 | SRP compliance for helper methods? | [ ] | untilRedisKeyPresent, untilRedisKeyAbsent |
| 6.4.4 | Reusable across test classes? | [ ] | No test-specific logic in helper |

---

## 7. Verification & Evidence

### 7.1 Manual Testing

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 7.1.1 | Individual test passes 10 consecutive times? | [ ] | Run: `for i in {1..10}; do ./gradlew test --tests "*TestName"; done` |
| 7.1.2 | All flaky tests removed from flaky list? | [ ] | Verify flaky-test-management.md updated |
| 7.1.3 | No regression in other tests? | [ ] | Full test suite passes |
| 7.1.4 | CI pipeline passes on first run? | [ ] | No re-run needed |

### 7.2 Code Review Evidence

| # | Evidence Type | Location | Status |
|---|-------|----------|--------|
| E1 | Code changes | `/src/test/java/.../DonationTest.java` | [ ] |
| E2 | Code changes | `/src/test/java/.../RefreshTokenIntegrationTest.java` | [ ] |
| E3 | Code changes | `/src/test/java/.../LikeSyncCompensationIntegrationTest.java` | [ ] |
| E4 | New component | `/src/test/java/maple/expectation/support/TestAwaitilityHelper.java` | [ ] |
| E5 | Build config | `/build.gradle` (Awaitility dependency) | [ ] |

### 7.3 Metrics

| Metric | Before | After | Target |
|--------|--------|-------|--------|
| Flaky Test Count | 5 | ? | 0 |
| Test Success Rate | ~95% | ? | 100% |
| CI First-Run Success | Variable | ? | 100% |
| Avg Test Execution Time | ? | ? | No regression |

---

## 8. Final Approval Checklist

### 8.1 Critical Blockers (Must Pass)

| # | Check | Status | Blocker |
|---|-------|--------|---------|
| 8.1.1 | No Thread.sleep in any test code | [ ] | YES |
| 8.1.2 | All @Tag("flaky") removed | [ ] | YES |
| 8.1.3 | No try-catch in test code (Section 12) | [ ] | YES |
| 8.1.4 | Test isolation verified (10 runs) | [ ] | YES |
| 8.1.5 | ADR-020 evidence requirements met | [ ] | YES |

### 8.2 Code Quality (Must Pass)

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 8.2.1 | SOLID principles compliance | [ ] | Section 1 all passed |
| 8.2.2 | CLAUDE.md compliance | [ ] | Section 2 all passed |
| 8.2.3 | Code quality standards met | [ ] | Section 3 all passed |
| 8.2.4 | Test reliability verified | [ ] | Section 4 all passed |
| 8.2.5 | Statelessness confirmed | [ ] | Section 5 all passed |

### 8.3 Documentation

| # | Check | Status | Notes |
|---|-------|--------|-------|
| 8.3.1 | flaky-test-management.md updated | [ ] | Remove fixed tests from list |
| 8.3.2 | ADR-020 status updated | [ ] | Proposed → Accepted |
| 8.3.3 | PR includes checklist summary | [ ] | Reference to this document |

---

## Appendix: Quick Reference Commands

```bash
# Verify individual test stability (10 consecutive runs)
test_stability() {
    local test_pattern=$1
    for i in {1..10}; do
        echo "Run $i:"
        ./gradlew test --tests "$test_pattern"
        if [ $? -ne 0 ]; then
            echo "FAILED on run $i"
            return 1
        fi
    done
    echo "All 10 runs passed!"
}

# Run specific flaky test fixes
test_stability "*DonationTest.concurrencyTest"
test_stability "*RefreshTokenIntegrationTest.shouldRefreshTokensSuccessfully"
test_stability "*LikeSyncCompensationIntegrationTest.syncSuccess_TempKeyDeleted"

# Full test suite
./gradlew clean test

# With coverage
./gradlew test jacocoTestReport
```

---

**Document Status**: Active
**Template Version**: 1.0.0
**Last Reviewed**: 2026-02-10
**Next Review**: After all flaky tests resolved
