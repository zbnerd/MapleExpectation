# Test Classification and Migration Plan

> **Test Bankruptcy → Pyramid Rebuild**
> Comprehensive analysis and migration strategy for existing tests.

---

## Executive Summary

| Metric | Count | Status |
|--------|-------|--------|
| **Total Tests (module-app)** | 116 | 🔄 Needs migration |
| **Total Tests (module-core)** | 6 | ✅ Already compliant |
| **Total Tests (module-infra)** | 1 | ✅ Already compliant |
| **@SpringBootTest** | 4 | ⚠️ Reduce to 1-3 smoke tests |
| **@WebMvcTest** | 0 | ❌ Need to add |
| **Testcontainers tests** | ~15 | 🔄 Move to integrationTest |
| **Unit tests** | ~90 | ✅ Keep in test/ |
| **Chaos/Nightmare** | ~3 | 🔄 Move to chaos-test module |

---

## Module-by-Module Analysis

### 1. module-core: ✅ COMPLIANT

**Status**: Already following the new test pyramid principles.

| Test File | Type | Action |
|-----------|------|--------|
| `ProbabilityContractsProperties.java` | jqwik PBT | ✅ Keep |
| `DeterminismProperties.java` | jqwik PBT | ✅ Keep |
| `ExpectationValueProperties.java` | jqwik PBT | ✅ Keep |
| `BoundaryConditionsProperties.java` | jqwik PBT | ✅ Keep |
| `CostFormatterTest.java` | Unit | ✅ Keep |
| `GoldenMasterTests.java` | Regression | ✅ Keep |

**Actions Required**: None. This module is the reference implementation.

---

### 2. module-infra: ⚠️ PARTIAL

**Status**: Has infrastructure configured but minimal tests.

| Test File | Current Location | Should Be | Action |
|-----------|-----------------|-----------|--------|
| `RedisEventPublisherTest.java` | src/test/java/ | src/integrationTest/java/ | 🔄 Move |

**Actions Required**:
1. Move `RedisEventPublisherTest` to `integrationTest` source set
2. Add `@DataJpaTest` slice tests for JPA repositories
3. Use `SharedContainers` for Testcontainers

---

### 3. module-app: 🔄 HEAVY MIGRATION NEEDED

**Status**: 116 tests need classification and selective migration.

#### Test Categories

##### A. CONTROLLER TESTS (~4 files) - NEED REFACTOR

| Test File | Current | Should Be | Action |
|-----------|---------|-----------|--------|
| `GameCharacterControllerV1Test.java` | ❌ Unknown | `@WebMvcTest` | 🔄 Refactor |
| `GameCharacterControllerV2Test.java` | ❌ Unknown | `@WebMvcTest` | 🔄 Refactor |
| `GameCharacterControllerV3Test.java` | ❌ Unknown | `@WebMvcTest` | 🔄 Refactor |
| `GameCharacterControllerV4Test.java` | ❌ Unknown | `@WebMvcTest` | 🔄 Refactor |
| `AdminControllerUnitTest.java` | ✅ Unit | `@WebMvcTest` | ✅ Keep or refactor |

**Action**: Convert to `@WebMvcTest` with `@MockBean` for services.

---

##### B. SERVICE UNIT TESTS (~40 files) - KEEP AS-IS

**Location**: `src/test/java/maple/expectation/service/`

These are likely pure unit tests. Keep them in `test/` source set.

**Examples**:
- `CubeServiceTest.java`
- `DonationServiceTransactionTest.java`
- `LikeSyncServiceTest.java`
- `AuthServiceTest.java`
- `RefreshTokenServiceTest.java`
- `SessionServiceTest.java`
- `CubeCostPolicyTest.java`
- `OutboxProcessorTest.java`
- `DlqHandlerTest.java`
- `DlqAdminServiceTest.java`
- `ProbabilityConvolverTest.java`
- `TailProbabilityCalculatorTest.java`
- `DensePmfTest.java`
- `SparsePmfTest.java`
- `PotentialCalculatorTest.java`
- ... and more

**Action**: ✅ Keep in `test/`, ensure they don't use `@SpringBootTest`.

---

##### C. GLOBAL COMPONENT UNIT TESTS (~30 files) - KEEP AS-IS

**Location**: `src/test/java/maple/expectation/global/`

These test utility/global components. Keep as unit tests.

**Examples**:
- `TieredCacheTest.java`
- `DefaultCheckedLogicExecutorTest.java`
- `IdempotencyGuardTest.java`
- `RateLimitingServiceTest.java`
- `RateLimitingFacadeTest.java`
- `ConsumeResultTest.java`
- `RateLimitContextTest.java`
- `CircuitBreakerMarkerP0Test.java`
- `RetryBudgetManagerTest.java`
- `JwtTokenProviderTest.java`
- `CorsOriginValidatorTest.java`
- `PrometheusSecurityFilterTest.java`
- `MetricsCollectorTest.java`
- `AlertThrottlerTest.java`
- `PiiMaskingFilterTest.java`
- `FlushResultTest.java`
- `ShutdownDataTest.java`
- `StatParserTest.java`
- `StatTypeTest.java`
- `StatTypeTestNew.java`
- `GzipStringConverterTest.java`

**Action**: ✅ Keep in `test/`.

---

##### D. INTEGRATION TESTS (~15 files) - MOVE TO integrationTest

**Pattern**: Files ending with `*IntegrationTest.java`

| Test File | Current | Should Be | Action |
|-----------|---------|-----------|--------|
| `ExpectationCacheIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `CacheInvalidationIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `RateLimitingFilterIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `MySQLResilienceIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `AiSreServiceIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `GracefulShutdownIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `AclPipelineIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `RefreshTokenIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `LikeSyncAtomicityIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `LikeSyncCompensationIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `LikeRealtimeSyncIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `EnvironmentIntegrationTest.java` | test/ | integrationTest/ | 🔄 Move |
| `NexonDataCollectorE2ETest.java` | test/ | integrationTest/ or chaos-test/ | 🔄 Move |

**Action**: Move to `src/integrationTest/java/` and ensure they use `IntegrationTestSupport` base class.

---

##### E. CHAOS/NIGHTMARE TESTS (~3 files) - MOVE TO module-chaos-test

**Pattern**: Files with "Nightmare", "Deadlock", "Freeze"

| Test File | Current | Should Be | Action |
|-----------|---------|-----------|--------|
| `CircularLockDeadlockNightmareTest.java` | test/ | module-chaos-test/ | 🔄 Move |
| `MetadataLockFreezeNightmareTest.java` | test/ | module-chaos-test/ | 🔄 Move |
| `RedisLockConsistencyTest.java` | test/ | module-chaos-test/ | 🔄 Move |
| `DualRunLockTest.java` | test/ | module-chaos-test/ | 🔄 Move |

**Action**: Move to `module-chaos-test/src/chaos-test/java/`.

---

##### F. SMOKE TESTS (~2 files) - KEEP BUT REDUCE

| Test File | Current | Action |
|-----------|---------|--------|
| `ExpectationApplicationTests.java` | test/ | ✅ Keep (smoke) |
| `EnvironmentIntegrationTest.java` | test/ | ✅ Keep (smoke) |

**Action**: Keep 1-2 smoke tests maximum with `@SpringBootTest`.

---

##### G. CHARACTERIZATION TESTS (~2 files) - MOVE TO test-legacy OR REMOVE

| Test File | Current | Action |
|-----------|---------|--------|
| `CalculatorCharacterizationTest.java` | test/ | 🔄 test-legacy/ |
| `DomainCharacterizationTest.java` | test/ | 🔄 test-legacy/ |

**Note**: These are characterization tests for legacy behavior. Move to `test-legacy/` for reference.

---

##### H. SUPPORT/UTILITY CLASSES (~10 files) - KEEP

| Test File | Action |
|-----------|--------|
| `IntegrationTestSupport.java` | ✅ Keep (base class) |
| `SharedContainers.java` | ✅ Keep (Testcontainers) |
| `ChaosTestSupport.java` | 🔄 Move to module-chaos-test |
| `SentinelContainerBase.java` | 🔄 Move to module-chaos-test |
| `TestAwaitilityHelper.java` | ✅ Keep |

---

##### I. ARCHITECTURE TESTS (~2 files) - KEEP

| Test File | Action |
|-----------|--------|
| `CleanArchitectureTest.java` | ✅ Keep (static analysis) |
| `ArchitectureTest.java` | ✅ Keep (ArchUnit) |

---

##### J. SCHEDULER TESTS (~4 files) - KEEP AS UNIT

| Test File | Action |
|-----------|--------|
| `ExpectationBatchWriteSchedulerTest.java` | ✅ Keep (unit) |
| `LikeSyncSchedulerTest.java` | ✅ Keep (unit) |
| `OutboxSchedulerTest.java` | ✅ Keep (unit) |
| `PopularCharacterWarmupSchedulerTest.java` | ✅ Keep (unit) |

---

##### K. E2E/EXTERNAL API TESTS (~3 files) - MOVE TO integrationTest OR REMOVE

| Test File | Current | Should Be | Action |
|-----------|---------|-----------|--------|
| `NexonDataCollectorE2ETest.java` | test/ | integrationTest/ or chaos-test/ | 🔄 Move |
| `NexonDataCollectorTest.java` | test/ | test/ (unit) | ✅ Keep |
| `ResilientNexonApiClientTest.java` | test/ | test/ (unit with mocks) | ✅ Keep |

---

## Migration Priority

### Phase 1: High Priority (Week 1)

1. ✅ **Configure integrationTest source set** - Already done in module-infra
2. ✅ **Configure jqwik PBT** - Already done in module-core
3. 🔄 **Create base test classes** - Partially done (`IntegrationTestSupport`)
4. 🔄 **Move integration tests to integrationTest/**
   - Move ~15 `*IntegrationTest.java` files
   - Ensure they use `SharedContainers`

### Phase 2: Medium Priority (Week 2)

5. 🔄 **Refactor controller tests to @WebMvcTest**
   - Convert ~4 controller tests
   - Add `@MockBean` for dependencies

6. 🔄 **Move chaos tests to module-chaos-test**
   - Move ~3 nightmare tests
   - Update build configuration

7. 🔄 **Consolidate smoke tests**
   - Keep only 1-2 `@SpringBootTest`
   - Document smoke test criteria

### Phase 3: Low Priority (Week 3+)

8. 🔄 **Add missing @DataJpaTest** for repositories
   - Create slice tests for JPA repositories
   - Use Testcontainers with singleton pattern

9. 🔄 **Move legacy characterization tests**
   - Move to `test-legacy/` for reference
   - Add `@Disabled` annotations

10. 🔄 **Documentation and training**
    - Update team on new test structure
    - Create runbooks for CI/CD

---

## Detailed Migration Commands

### Step 1: Create integrationTest directories

```bash
# module-infra (already exists)
mkdir -p module-infra/src/integrationTest/java/maple/expectation/infrastructure
mkdir -p module-infra/src/integrationTest/resources

# module-app
mkdir -p module-app/src/integrationTest/java/maple/expectation
mkdir -p module-app/src/integrationTest/resources
```

### Step 2: Move integration tests

```bash
cd /home/maple/MapleExpectation/module-app/src/test/java/maple/expectation

# Move integration tests
mv ExpectationCacheIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv CacheInvalidationIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv RateLimitingFilterIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv MySQLResilienceIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv AiSreServiceIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv GracefulShutdownIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv AclPipelineIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv RefreshTokenIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv LikeSyncAtomicityIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv LikeSyncCompensationIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv LikeRealtimeSyncIntegrationTest.java ../../integrationTest/java/maple/expectation/
mv EnvironmentIntegrationTest.java ../../integrationTest/java/maple/expectation/

# Move E2E test
mv NexonDataCollectorE2ETest.java ../../integrationTest/java/maple/expectation/ingestion/
```

### Step 3: Move chaos tests

```bash
# Create chaos-test directories if not exists
mkdir -p /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/global/lock
mkdir -p /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/support

# Move chaos tests
mv global/lock/CircularLockDeadlockNightmareTest.java /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/
mv global/lock/MetadataLockFreezeNightmareTest.java /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/
mv global/lock/RedisLockConsistencyTest.java /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/
mv global/lock/DualRunLockTest.java /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/

# Move support classes
mv support/ChaosTestSupport.java /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/support/
mv support/SentinelContainerBase.java /home/maple/MapleExpectation/module-chaos-test/src/chaos-test/java/maple/expectation/support/
```

### Step 4: Create module-app integrationTest configuration

Add to `module-app/build.gradle`:

```gradle
sourceSets {
    integrationTest {
        java.srcDir file('src/integrationTest/java')
        resources.srcDir file('src/integrationTest/resources')
        compileClasspath += sourceSets.main.output + configurations.testRuntimeClasspath
        runtimeClasspath += output + compileClasspath
    }
}

configurations {
    integrationTestImplementation.extendsFrom testImplementation
    integrationTestRuntimeOnly.extendsFrom testRuntimeOnly
}

tasks.register('integrationTest', Test) {
    description = 'Runs integration tests (Testcontainers, DB, Redis).'
    group = 'verification'
    testClassesDirs = sourceSets.integrationTest.output.classesDirs
    classpath = sourceSets.integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter tasks.test

    testLogging {
        events "passed", "skipped", "failed"
        exceptionFormat "full"
    }
}
```

---

## Test Classification Matrix

| Test Type | Count | Keep In | PR Pipeline | Nightly |
|-----------|-------|---------|-------------|---------|
| **Unit Tests** | ~90 | `test/` | ✅ | ✅ |
| **Integration Tests** | ~15 | `integrationTest/` | ❌ | ✅ |
| **Controller Tests** | ~4 | `test/` (@WebMvcTest) | ✅ | ✅ |
| **Smoke Tests** | 1-2 | `test/` (@SpringBootTest) | ✅ | ✅ |
| **Chaos Tests** | ~3 | `module-chaos-test/` | ❌ | ✅ |
| **Characterization** | ~2 | `test-legacy/` | ❌ | ❌ |

---

## Success Metrics

### Before Migration

- PR tests: ~116 tests, ~2-5 minutes (many unnecessary integration tests)
- Flaky test rate: Unknown
- Test clarity: Mixed (unit/integration/chaos all together)

### After Migration

- PR tests: ~90 unit tests, ~30 seconds
- Integration tests: ~15 tests, separate execution
- Chaos tests: ~3 tests, separate execution
- Test clarity: Clear separation by type and purpose

---

## Next Steps

1. ✅ **Review this plan** with the team
2. 🔄 **Execute Phase 1** (Integration test migration)
3. 🔄 **Execute Phase 2** (Controller and chaos test migration)
4. 🔄 **Execute Phase 3** (Documentation and training)
5. 🔄 **Update CI/CD** pipelines to reflect new test structure

---

## References

- [Module-wise Test Guide](../02_Technical_Guides/module-wise-test-guide.md)
- [ADR-015: Test Rebuild Pyramid](../adr/ADR-015-test-reboot-pyramid.md)
- [ADR-025: Chaos Test Module Separation](../adr/ADR-025-chaos-test-module-separation.md)
- [jqwik Configuration](../../module-core/src/test/resources/junit-platform.properties)
