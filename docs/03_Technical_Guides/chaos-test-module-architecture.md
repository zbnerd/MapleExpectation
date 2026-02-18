# Module-Chaos-Test: Architecture & Migration Guide

> **Status**: Design Complete - Ready for Implementation
> **Created**: 2026-02-11
> **Related ADR**: ADR-014 (Multi-module Structure), ADR-017 (Test Separation)

---

## 1. Overview

### 1.1 Purpose

The `module-chaos-test` module provides a dedicated, isolated environment for chaos engineering and nightmare scenario testing. It separates 22 critical tests from the main test suite to enable:

- **Independent Execution**: Run chaos tests on-demand without affecting regular CI/CD
- **Resource Isolation**: Separate JVM configuration for resource exhaustion tests
- **CI/CD Flexibility**: PR validation (fast) vs. nightly builds (comprehensive)
- **Maintainability**: Clear ownership and dependency management for chaos tests

### 1.2 Test Inventory (22 Total)

#### Chaos Tests (7)
| Category | Test Class | Scenario | Failure Injection |
|----------|-----------|----------|-------------------|
| **Network** | `ClockDriftChaosTest` | NTP sync failure | System clock manipulation |
| **Network** | `ThunderingHerdLockChaosTest` | Lock contention | 100 concurrent lock requests |
| **Core** | `OOMChaosTest` | Memory exhaustion | Heap allocation bomb |
| **Resource** | `DiskFullChaosTest` | Storage exhaustion | Docker volume limit |
| **Resource** | `GcPauseChaosTest` | GC latency spike | Explicit GC calls |
| **Resource** | `PoolExhaustionChaosTest` | Pool starvation | Thread/connection pool depletion |

#### Nightmare Tests (15)
| Test Class | Scenario | Agent | Priority |
|-----------|----------|-------|----------|
| `AopOrderNightmareTest` | Proxy chain corruption | Blue (Architect) | P0 |
| `AsyncContextLossNightmareTest` | MDC propagation loss | Yellow (QA) | P1 |
| `CallerRunsPolicyNightmareTest` | Rejection policy failure | Red (SRE) | P0 |
| `CelebrityProblemNightmareTest` | Hot spot contention | Green (Perf) | P1 |
| `CircularLockDeadlockNightmareTest` | Lock cycle deadlock | Red (SRE) | P0 |
| `ConnectionVampireNightmareTest` | Connection leak | Red (SRE) | P0 |
| `DeadlockTrapNightmareTest` | Distributed deadlock | Red (SRE) | P0 |
| `DeepPagingNightmareTest` | Large offset performance | Green (Perf) | P1 |
| `MetadataLockFreezeNightmareTest` | Metadata lock timeout | Red (SRE) | P0 |
| `NexonApiOutboxNightmareTest` | Outbox replay failure | Purple (Auditor) | P0 |
| `NexonApiOutboxMultiFailureNightmareTest` | Compound failure | Purple (Auditor) | P1 |
| `PipelineExceptionNightmareTest` | Pipeline flow break | Blue (Architect) | P0 |
| `PoisonPillNightmareTest` | Corrupted message handling | Purple (Auditor) | P1 |
| `SelfInvocationNightmareTest` | AOP proxy bypass | Blue (Architect) | P0 |
| `ThreadPoolExhaustionNightmareTest` | Thread starvation | Red (SRE) | P0 |
| `ThunderingHerdNightmareTest` | Cache stampede | Green (Perf) | P0 |
| `ZombieOutboxNightmareTest` | Outbox zombie entry | Purple (Auditor) | P1 |

---

## 2. Directory Structure

```
module-chaos-test/
├── build.gradle                           # Chaos test configuration
├── src/
│   └── chaos-test/                        # Custom sourceSet (separate from test/)
│       ├── java/
│       │   └── maple/
│       │       └── expectation/
│       │           └── chaos/
│       │               ├── network/       # Network chaos scenarios
│       │               │   ├── ClockDriftChaosTest.java
│       │               │   └── ThunderingHerdLockChaosTest.java
│       │               ├── core/          # Core infrastructure chaos
│       │               │   └── OOMChaosTest.java
│       │               ├── resource/      # Resource exhaustion chaos
│       │               │   ├── DiskFullChaosTest.java
│       │               │   ├── GcPauseChaosTest.java
│       │               │   └── PoolExhaustionChaosTest.java
│       │               ├── connection/    # Connection pool chaos
│       │               │   └── (moved from network/)
│       │               └── nightmare/     # Nightmare scenarios (15)
│       │                   ├── N01_ThunderingHerdNightmareTest.java
│       │                   ├── N02_DeadlockTrapNightmareTest.java
│       │                   ├── N03_ThreadPoolExhaustionNightmareTest.java
│       │                   └── ... (12 more)
│       └── resources/
│           ├── application-chaos.yml      # Chaos test profile
│           └── junit-platform.properties  # Test configuration
└── docs/
    └── chaos-migration-checklist.md       # Migration steps (Section 5)
```

---

## 3. Gradle Configuration

### 3.1 SourceSet Declaration

```gradle
sourceSets {
	chaosTest {
		java {
			compileClasspath += main.sourceSets.main.output
			compileClasspath += test.sourceSets.test.output
			runtimeClasspath += main.sourceSets.main.output
			runtimeClasspath += test.sourceSets.test.output
			srcDir file('src/chaos-test/java')
		}
		resources {
			srcDir file('src/chaos-test/resources')
		}
	}
}
```

**Key Points**:
- Separate from `test` sourceSet (no pollution of regular tests)
- Inherits classpath from both `main` and `test` (reuse test utilities)
- Dedicated resources directory for chaos-specific configs

### 3.2 Gradle Tasks

| Task | Description | Usage |
|------|-------------|-------|
| `chaosTest` | Run all 22 chaos/nightmare tests | `./gradlew :module-chaos-test:chaosTest` |
| `chaosTestNetwork` | Network chaos only (Clock Drift, Split Brain) | `./gradlew :module-chaos-test:chaosTestNetwork` |
| `chaosTestResource` | Resource chaos (OOM, Disk, GC) | `./gradlew :module-chaos-test:chaosTestResource` |
| `chaosTestCore` | Core infrastructure chaos | `./gradlew :module-chaos-test:chaosTestCore` |
| `nightmareTest` | 15 nightmare scenarios only | `./gradlew :module-chaos-test:nightmareTest` |

### 3.3 JVM Configuration

```gradle
jvmArgs = [
	'-Xms512m',
	'-Xmx2048m',
	'-XX:+UseG1GC',
	'-XX:MaxMetaspaceSize=512m',
	'-Djava.awt.headless=true'
]
```

**Rationale**:
- `-Xmx2048m`: Sufficient headroom for OOM simulation tests
- `-XX:+UseG1GC`: Predictable GC pause behavior
- Sequential execution (`maxParallelForks = 1`): Chaos tests require isolation

### 3.4 Tag Filtering

```gradle
useJUnitPlatform {
	includeTags 'chaos', 'nightmare'
	excludeTags 'quarantine', 'flaky'
}
```

---

## 4. CI/CD Integration Strategy

### 4.1 Current State (Before Migration)

**CI Pipeline** (`.github/workflows/ci.yml`):
- **PR to master/develop**: Fast tests only (`-PfastTest`)
  - Excludes: `sentinel`, `slow`, `quarantine`, `chaos`, `nightmare`, `integration`, `flaky`
  - Runtime: 3-5 minutes
- **Push to develop**: Same as PR

**Nightly Pipeline** (`.github/workflows/nightly.yml`):
- **Schedule**: Daily at KST 00:00 (UTC 15:00)
- **Manual**: `workflow_dispatch` with skip options
- **Steps**:
  1. Unit Tests (`-PfastTest`)
  2. Integration Tests (`-PintegrationTest`)
  3. Chaos Tests (`-PchaosTest`) → Uses `module-app/src/test-legacy`
  4. Nightmare Tests (`-PnightmareTest`) → Uses `module-app/src/test-legacy`

### 4.2 Target State (After Migration)

#### 4.2.1 CI Pipeline (PR Validation)

```yaml
# .github/workflows/ci.yml (NO CHANGES NEEDED)
# Already excludes chaos/nightmare tests via -PfastTest
```

**Behavior**: Unchanged - PR validation remains fast (3-5 minutes)

#### 4.2.2 Nightly Pipeline (v2 - Post-Migration)

```yaml
# .github/workflows/nightly-chaos.yml (NEW FILE)
name: Chaos & Nightmare Tests

on:
  schedule:
    - cron: '0 15 * * *'  # Daily KST 00:00
  workflow_dispatch:
    inputs:
      chaos_category:
        description: 'Chaos Category (all, network, resource, core)'
        required: false
        default: 'all'
      skip_nightmare:
        description: 'Skip Nightmare Tests'
        type: boolean
        default: false

jobs:
  chaos-tests:
    name: 'Chaos Engineering Tests'
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Run Chaos Tests
        run: |
          if [ "${{ github.event.inputs.chaos_category }}" = "network" ]; then
            ./gradlew :module-chaos-test:chaosTestNetwork
          elif [ "${{ github.event.inputs.chaos_category }}" = "resource" ]; then
            ./gradlew :module-chaos-test:chaosTestResource
          elif [ "${{ github.event.inputs.chaos_category }}" = "core" ]; then
            ./gradlew :module-chaos-test:chaosTestCore
          else
            ./gradlew :module-chaos-test:chaosTest
          fi
        env:
          SPRING_PROFILES_ACTIVE: chaos
          DOCKER_HOST: unix:///var/run/docker.sock
          TESTCONTAINERS_RYUK_DISABLED: false

      - name: Upload Chaos Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: chaos-test-reports
          path: |
            module-chaos-test/build/reports/tests/
            module-chaos-test/build/test-results/

  nightmare-tests:
    name: 'Nightmare Scenario Tests'
    runs-on: ubuntu-latest
    timeout-minutes: 45
    needs: chaos-tests
    if: ${{ github.event.inputs.skip_nightmare != 'true' }}

    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          cache: 'gradle'

      - name: Run Nightmare Tests
        run: ./gradlew :module-chaos-test:nightmareTest
        env:
          SPRING_PROFILES_ACTIVE: nightmare
          DOCKER_HOST: unix:///var/run/docker.sock

      - name: Upload Nightmare Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: nightmare-test-reports
          path: |
            module-chaos-test/build/reports/tests/
            module-chaos-test/build/test-results/
```

#### 4.2.3 Execution Matrix

| Trigger | Tests Executed | Duration | Module |
|---------|---------------|----------|--------|
| **PR (CI)** | Unit + Integration (fastTest) | 3-5 min | `module-*/src/test` |
| **Nightly** | All 22 chaos/nightmare | 30-45 min | `module-chaos-test/src/chaos-test` |
| **Manual** | Selectable by category | 10-45 min | `module-chaos-test/src/chaos-test` |

---

## 5. Migration Checklist

> **IMPORTANT**: DO NOT execute this checklist yet - this is design documentation only

### Phase 1: Preparation (Pre-Migration)

- [ ] **Step 1.1**: Create `module-chaos-test/build.gradle` (COMPLETED)
- [ ] **Step 1.2**: Create directory structure:
  ```bash
  mkdir -p module-chaos-test/src/chaos-test/java/maple/expectation/chaos/{network,core,resource,nightmare}
  mkdir -p module-chaos-test/src/chaos-test/resources
  ```
- [ ] **Step 1.3**: Update root `settings.gradle`:
  ```gradle
  include 'module-chaos-test'
  ```
- [ ] **Step 1.4**: Create `application-chaos.yml` profile

### Phase 2: File Migration (Test Classes)

- [ ] **Step 2.1**: Move Chaos Tests (7 files):
  ```bash
  # Network Chaos
  mv module-app/src/test-legacy/java/maple/expectation/chaos/network/ClockDriftChaosTest.java \
     module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/

  # Connection Chaos
  mv module-app/src/test-legacy/java/maple/expectation/chaos/connection/ThunderingHerdLockChaosTest.java \
     module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/

  # Core Chaos
  mv module-app/src/test-legacy/java/maple/expectation/chaos/core/OOMChaosTest.java \
     module-chaos-test/src/chaos-test/java/maple/expectation/chaos/core/

  # Resource Chaos
  mv module-app/src/test-legacy/java/maple/expectation/chaos/resource/DiskFullChaosTest.java \
     module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/
  mv module-app/src/test-legacy/java/maple/expectation/chaos/resource/GcPauseChaosTest.java \
     module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/
  mv module-app/src/test-legacy/java/maple/expectation/chaos/resource/PoolExhaustionChaosTest.java \
     module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/
  ```

- [ ] **Step 2.2**: Move Nightmare Tests (17 files):
  ```bash
  mv module-app/src/test-legacy/java/maple/expectation/chaos/nightmare/*.java \
     module-chaos-test/src/chaos-test/java/maple/expectation/chaos/nightmare/
  ```

- [ ] **Step 2.3**: Verify package imports:
  - Ensure all moved files have correct package declarations
  - Update import statements if any cross-package dependencies exist

### Phase 3: Support Classes Migration

- [ ] **Step 3.1**: Identify shared test support classes in `module-app/src/test-legacy`
  ```bash
  # Find all support classes
  find module-app/src/test-legacy -name "*Support.java" -o -name "*Base*.java" -o -name "Abstract*.java"
  ```

- [ ] **Step 3.2**: Create shared test utilities module OR duplicate required classes
  - **Option A**: Create `module-test-utils` for shared support classes
  - **Option B**: Duplicate critical support classes in `module-chaos-test/src/chaos-test/java/maple/expectation/chaos/support/`

- [ ] **Step 3.3**: Move `AbstractContainerBaseTest` and chaos-specific support classes

### Phase 4: Dependency Resolution

- [ ] **Step 4.1**: Update `module-chaos-test/build.gradle` dependencies
  - Verify all Spring Boot test dependencies are included
  - Add Testcontainers ToxiProxy for network chaos
  - Ensure Resilience4j is available for circuit breaker tests

- [ ] **Step 4.2**: Resolve circular dependency issues
  - `module-chaos-test` depends on `module-app` (for @SpringBootApplication)
  - `module-app` should NOT depend on `module-chaos-test`

### Phase 5: Configuration Migration

- [ ] **Step 5.1**: Create `application-chaos.yml`:
  ```yaml
  spring:
    profiles:
      active: chaos
    datasource:
      url: jdbc:h2:mem:chaos_testdb
    redis:
      host: localhost
      port: 6379

  testcontainers:
    reuse: false  # Always fresh containers for chaos
  ```

- [ ] **Step 5.2**: Create `junit-platform.properties`:
  ```properties
  # Chaos test configuration
  junit.platform.execution.parallel.enabled = false
  junit.testexecution.lifecycle.default = always
  ```

### Phase 6: Validation

- [ ] **Step 6.1**: Local validation:
  ```bash
  # Test module builds
  ./gradlew :module-chaos-test:build

  # Run chaos tests only
  ./gradlew :module-chaos-test:chaosTest

  # Run nightmare tests only
  ./gradlew :module-chaos-test:nightmareTest

  # Run all chaos tests
  ./gradlew :module-chaos-test:chaosTest --tests "*Chaos*"
  ```

- [ ] **Step 6.2**: Verify regular tests still pass:
  ```bash
  ./gradlew test
  ./gradlew test -PfastTest
  ```

- [ ] **Step 6.3**: Verify no test-legacy tests remain:
  ```bash
  # Should return empty
  find module-app/src/test-legacy -name "*.java"
  ```

### Phase 7: CI/CD Update

- [ ] **Step 7.1**: Create `.github/workflows/nightly-chaos.yml` (Section 4.2.2)
- [ ] **Step 7.2**: Update existing `.github/workflows/nightly.yml`:
  - Remove Step 3 (Chaos Tests) and Step 4 (Nightmare Tests)
  - Add reference to new workflow

- [ ] **Step 7.3**: Test CI/CD workflow:
  - Push to feature branch
  - Trigger workflow_dispatch manually
  - Verify artifacts are uploaded correctly

### Phase 8: Documentation

- [ ] **Step 8.1**: Update `docs/02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md`
- [ ] **Step 8.2**: Update CLAUDE.md Section 10 (Testing)
- [ ] **Step 8.3**: Create ADR documenting migration rationale
- [ ] **Step 8.4**: Update README with chaos test execution instructions

### Phase 9: Cleanup

- [ ] **Step 9.1**: Remove `module-app/src/test-legacy` directory if empty
- [ ] **Step 9.2**: Update `module-app/build.gradle`:
  ```gradle
  // Remove test-legacy exclusion (no longer needed)
  tasks.named('test') {
    // exclude '**/test-legacy/**'  // DELETE THIS LINE
  }
  ```
- [ ] **Step 9.3**: Archive test-legacy in `docs/99_Archive/` if needed for reference

---

## 6. Rollback Plan

If migration fails, execute rollback:

1. **Revert files**:
   ```bash
   git checkout HEAD -- module-chaos-test/
   git checkout HEAD -- module-app/src/test-legacy/
   ```

2. **Remove module from settings.gradle**:
   ```gradle
   // Remove this line
   include 'module-chaos-test'
   ```

3. **Restore CI/CD workflows**:
   ```bash
   git checkout HEAD -- .github/workflows/nightly.yml
   rm .github/workflows/nightly-chaos.yml
   ```

---

## 7. Success Criteria

Migration is successful when ALL of the following are true:

- [x] **Criterion 1**: All 22 chaos/nightmare tests execute in `module-chaos-test`
- [ ] **Criterion 2**: Regular test suite (`./gradlew test`) completes without chaos tests
- [ ] **Criterion 3**: PR CI time remains ≤ 5 minutes
- [ ] **Criterion 4**: Nightly chaos workflow executes all 22 tests successfully
- [ ] **Criterion 5**: No `test-legacy` references remain in codebase
- [ ] **Criterion 6**: Test coverage reports include chaos test module separately

---

## 8. References

- [ADR-014: Multi-module Cross-cutting Concerns](/home/maple/MapleExpectation/docs/01_Adr/ADR-014-multi-module-cross-cutting-concerns.md)
- [Test Strategy](/home/maple/MapleExpectation/docs/02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md)
- [Flaky Test Management Guide](/home/maple/MapleExpectation/docs/03_Technical_Guides/flaky-test-management.md)
- [Nightmare Scenarios](/home/maple/MapleExpectation/docs/02_Chaos_Engineering/06_Nightmare/Scenarios/)

---

**Document Status**: Design Complete - Awaiting Implementation Approval
**Next Step**: Review and approve this design before executing Migration Checklist
