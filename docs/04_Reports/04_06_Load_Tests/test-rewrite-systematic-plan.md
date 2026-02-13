# Systematic Test Rewrite Execution Plan

**Date:** 2026-02-11
**Status:** IN PROGRESS
**Goal:** Rewrite ALL 132+ test classes following test pyramid principles

---

## Executive Summary

**Current State:**
- Infrastructure: ✅ 100% Complete (Testcontainers Singleton, integrationTest source set, jqwik PBT)
- Rewritten: 2 classes (CostFormatterTest, StatTypeTest)
- Remaining: 45 test-legacy files + 87 active test files
- Target: Rewrite ALL tests to proper pyramid structure

**Strategy:** Parallel execution with ULTRAWORK MODE agents

---

## Categorization Summary

| Category | Count | Percentage | Rewrite Strategy |
|----------|-------|------------|------------------|
| **P0 (Pure Logic)** | 6 | 4.5% | Pure JUnit5, NO Spring/DB |
| **P1 (@WebMvcTest)** | 2 | 1.5% | @WebMvcTest + Mock |
| **P2 (@DataJpaTest)** | 4 | 3.0% | @DataJpaTest + Testcontainers |
| **P3 (Keep Integration)** | 11 | 8.3% | Keep/Testcontainers optimize |
| **P4 (Chaos/Nightmare)** | 22 | 16.7% | Move to chaos test module |
| **Active Tests (Review)** | 87 | 66.0% | Analyze and categorize |
| **TOTAL** | **132** | **100%** | Systematic rewrite |

---

## Phase 1: P0 Pure Logic Tests (Quick Wins)

### Target Files (6 from test-legacy)

1. **DomainCharacterizationTest.java**
   - Current: @SpringBootTest (unnecessary)
   - Target: Pure JUnit5
   - Location: module-core/src/test/java/
   - Complexity: Low

2. **InMemoryBufferStrategyTest.java**
   - Current: Spring dependencies
   - Target: Pure JUnit5 with mocks
   - Location: module-app/src/test/java/
   - Complexity: Low

3. **EquipmentPersistenceTrackerTest.java**
   - Current: Mockito
   - Target: Pure JUnit5
   - Location: module-app/src/test/java/
   - Complexity: Low

4. **ExpectationWriteBackBufferTest.java**
   - Current: None (already pure)
   - Target: Extract from test-legacy
   - Location: module-app/src/test/java/
   - Complexity: Medium

5-6. Additional pure logic tests identified during execution

**Estimated Time:** 2-3 hours
**Expected Speedup:** 10-100x for these tests

---

## Phase 2: P1 Controller Tests (@WebMvcTest)

### Target Files (2 from test-legacy)

1. **AdminControllerTest.java**
   - Current: @SpringBootTest + @MockMvc
   - Target: @WebMvcTest(AdminController.class)
   - Mocks: AdminService, monitoring services
   - Complexity: Low

2. **GlobalExceptionHandlerTest.java**
   - Current: @SpringBootTest
   - Target: @WebMvcTest + exception simulation
   - Mocks: All services
   - Complexity: Low

**Estimated Time:** 1-2 hours
**Expected Speedup:** 5x for controller tests

---

## Phase 3: P2 Repository Tests (@DataJpaTest)

### Target Files (4 from test-legacy)

1. **RefreshTokenIntegrationTest.java**
   - Current: @SpringBootTest + Testcontainers
   - Target: @DataJpaTest + Testcontainers Singleton
   - Focus: JPA entity persistence, queries
   - Complexity: Medium

2-4. Additional JPA-focused tests

**Estimated Time:** 2-3 hours
**Expected Speedup:** 3x for repository tests

---

## Phase 4: Active Tests Analysis & Rewrite (87 files)

### High-Priority Categories

#### Controller Tests (5 files)
- GameCharacterControllerV1Test.java
- GameCharacterControllerV2Test.java
- GameCharacterControllerV3Test.java
- GameCharacterControllerV4Test.java
- AdminControllerUnitTest.java

**Action:** Review current implementation, convert to @WebMvcTest if needed

#### Service Unit Tests (30+ files)
These should already be unit tests, verify:
- No @SpringBootTest
- Proper mocking
- Isolated logic

**Examples:**
- PotentialCalculatorTest.java
- CubeCostPolicyTest.java
- SessionServiceTest.java
- AuthServiceTest.java
- JwtTokenProviderTest.java

#### DTO/Utility Tests (20+ files)
These should be pure unit tests:
- SparsePmfTest.java
- DensePmfTest.java
- StatTypeTest.java ✅ (Already verified)
- StatParserTest.java
- Various DTO tests

#### Integration Tests (15+ files)
Review and categorize:
- GracefulShutdownIntegrationTest
- AclPipelineIntegrationTest
- LikeSyncAtomicityIntegrationTest
- Cache integration tests
- Queue tests

**Estimated Time:** 8-12 hours
**Strategy:** Parallel execution with multiple agents

---

## Phase 5: P3 Integration Tests Optimization (11 files)

### Files to Keep & Optimize

1. **CubeServiceTest.java**
   - Keep as integration test
   - Optimize: Testcontainers Singleton
   - Verify: Essential probability calculations

2. **CharacterEquipmentCharacterizationTest.java**
   - Keep as characterization
   - Optimize: @DataJpaTest instead of @SpringBootTest

3. **LikeSyncCompensationIntegrationTest.java**
   - Keep as integration
   - Optimize: Testcontainers Singleton
   - Verify: Transactional outbox behavior

4. **CacheInvalidationIntegrationTest.java**
   - Keep as integration
   - Optimize: Redis Testcontainers Singleton

5. **RedisLockConsistencyTest.java**
   - Keep as integration
   - Optimize: Shared Redis container

6. **MySQLResilienceIntegrationTest.java**
   - Keep as integration
   - Optimize: Shared MySQL container

7-11. Other essential integration tests

**Estimated Time:** 3-4 hours
**Action:** Review, optimize Testcontainers usage, document rationale

---

## Phase 6: P4 Chaos/Nightmare Tests (22 files)

### Action: Move to Separate Chaos Test Module

**Recommended Structure:**
```
module-chaos-test/
├── src/chaosTest/java/
│   ├── chaos/
│   │   ├── network/
│   │   ├── resource/
│   │   └── connection/
│   └── nightmare/
│       ├── deadlock/
│       ├── outbox/
│       └── threading/
└── build.gradle (chaosTest sourceSet)
```

**Chaos Tests (7 files):**
- ClockDriftChaosTest.java
- ThunderingHerdLockChaosTest.java
- OOMChaosTest.java
- DiskFullChaosTest.java
- GcPauseChaosTest.java
- PoolExhaustionChaosTest.java

**Nightmare Tests (15 files):**
- AopOrderNightmareTest.java
- AsyncContextLossNightmareTest.java
- CallerRunsPolicyNightmareTest.java
- CelebrityProblemNightmareTest.java
- CircularLockDeadlockNightmareTest.java
- ConnectionVampireNightmareTest.java
- DeadlockTrapNightmareTest.java
- DeepPagingNightmareTest.java
- MetadataLockFreezeNightmareTest.java
- NexonApiOutboxNightmareTest.java
- NexonApiOutboxMultiFailureNightmareTest.java
- PipelineExceptionNightmareTest.java
- PoisonPillNightmareTest.java
- SelfInvocationNightmareTest.java
- ThreadPoolExhaustionNightmareTest.java
- ThunderingHerdNightmareTest.java
- ZombieOutboxNightmareTest.java

**CI Strategy:**
- PR pipeline: NO chaos tests
- Main merge: Run chaos tests (manual trigger)
- Nightly: Full chaos test suite

**Estimated Time:** 4-5 hours
**Action:** Create module-chaos-test, move files, configure Gradle

---

## Execution Strategy

### Parallel Execution Plan

```
┌─────────────────────────────────────────────────────────────┐
│                  ULTRAWORK MODE - Parallel Agents           │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Agent 1 (P0): Pure Logic Tests (6 files)                    │
│  Agent 2 (P1): Controller Tests (2 files)                    │
│  Agent 3 (P2): Repository Tests (4 files)                    │
│  Agent 4 (Active): Review Active Tests (87 files)            │
│  Agent 5 (Integration): Optimize P3 Tests (11 files)         │
│                                                               │
│  After P0-P3:                                                │
│  Agent 6 (Chaos): Move P4 Tests (22 files)                   │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### Rollout Strategy

1. **Incremental Migration**: One category at a time
2. **Continuous Verification**: Each phase runs `./gradlew test`
3. **Metrics Tracking**: Document speedup, test counts, flaky reduction
4. **Git Commits**: Logical grouping by category

---

## Definition of Done

### Per Test Class
- [ ] No @SpringBootTest (unless P3 integration)
- [ ] Proper test slice (@WebMvcTest, @DataJpaTest) or pure JUnit5
- [ ] No Thread.sleep() (use Awaitility)
- [ ] Seed fixed for randomness
- [ ] Clock injected for time dependencies
- [ ] Tests pass: `./gradlew test`
- [ ] SOLID principles followed

### Per Category
- [ ] All files in category rewritten
- [ ] Build successful
- [ ] Tests faster than before (measured)
- [ ] No test failures
- [ ] Code reviewed (self-review)

### Overall Project
- [ ] All 132+ test classes reviewed
- [ ] P0-P3 tests rewritten (110+ files)
- [ ] P4 chaos tests moved (22 files)
- [ ] Test suite runs in <30 seconds for PR
- [ ] Integration tests run separately
- [ ] Documentation updated
- [ ] Final report generated

---

## Progress Tracking

### Phase 1: P0 Pure Logic (0/6 complete)
- [ ] DomainCharacterizationTest.java
- [ ] InMemoryBufferStrategyTest.java
- [ ] EquipmentPersistenceTrackerTest.java
- [ ] ExpectationWriteBackBufferTest.java
- [ ] Additional pure logic tests (2)

### Phase 2: P1 Controller (0/2 complete)
- [ ] AdminControllerTest.java
- [ ] GlobalExceptionHandlerTest.java

### Phase 3: P2 Repository (0/4 complete)
- [ ] RefreshTokenIntegrationTest.java
- [ ] Additional JPA tests (3)

### Phase 4: Active Tests (0/87 reviewed)
- [ ] Controller tests (5)
- [ ] Service tests (30)
- [ ] DTO/Utility tests (20)
- [ ] Integration tests (15)
- [ ] Other tests (17)

### Phase 5: P3 Integration (0/11 optimized)
- [ ] Review all P3 tests
- [ ] Optimize Testcontainers usage
- [ ] Document rationale

### Phase 6: P4 Chaos (0/22 moved)
- [ ] Create module-chaos-test
- [ ] Move chaos tests (7)
- [ ] Move nightmare tests (15)
- [ ] Configure Gradle

---

## Success Metrics

### Performance Goals
- **Before:** ~300 seconds (5 minutes) full test suite
- **After PR Tests:** ~30 seconds (90% reduction)
- **After Integration:** ~120 seconds (60% reduction)

### Quality Goals
- **Flaky Tests:** 0 (deterministic)
- **Test Coverage:** Maintain or increase
- **Build Success:** 100% on CI

### Organization Goals
- **Clear Separation:** Unit / Integration / Chaos
- **Fast Feedback:** PR builds <1 minute
- **Developer Experience:** Easy to write new tests

---

**Last Updated:** 2026-02-11
**Next Action:** Begin Phase 1 - P0 Pure Logic Tests
