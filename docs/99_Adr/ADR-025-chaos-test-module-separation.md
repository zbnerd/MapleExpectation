# ADR-025: Chaos Test Module Separation

**Status**: Accepted (2026-02-11)
**Related ADRs**: ADR-014 (Multi-module Structure), ADR-017 (Test Separation)
**Related Issues**: N/A (Design Phase)

---

## Context

MapleExpectation currently houses 22 chaos engineering and nightmare scenario tests in `module-app/src/test-legacy/`. These tests are:

- **Long-running** (15-30 minutes total)
- **Resource-intensive** (OOM, disk full, GC pause simulations)
- **Failure-injection** tests that require isolation
- **Currently excluded** from regular test execution via `test-legacy` directory

### Current State

```
module-app/
└── src/
    ├── test/              # Regular unit/integration tests
    └── test-legacy/       # Chaos + Nightmare tests (22 files)
        └── java/maple/expectation/chaos/
            ├── network/   # ClockDriftChaosTest, ThunderingHerdLockChaosTest
            ├── core/      # OOMChaosTest
            ├── resource/  # DiskFullChaosTest, GcPauseChaosTest, PoolExhaustionChaosTest
            └── nightmare/ # 15 nightmare scenario tests
```

### Problems

1. **No Physical Separation**: Chaos tests are physically in `module-app` despite being excluded from builds
2. **Unclear Ownership**: `test-legacy` naming implies "deprecated" when these are critical resilience tests
3. **CI/CD Complexity**: Nightly workflow uses `test -PchaosTest` which relies on tag filtering
4. **Dependency Pollution**: Chaos tests require special JVM config (2GB heap) that doesn't apply to regular tests
5. **Module Confusion**: Chaos tests depend on `module-app` (test scope), creating circular concerns

---

## Decision

Create a dedicated `module-chaos-test` module with a custom `chaosTest` sourceSet, separate from regular test execution.

### Target Structure

```
module-chaos-test/
├── build.gradle                           # Custom chaosTest sourceSet
├── src/chaos-test/                        # Dedicated sourceSet (not test/)
│   ├── java/maple/expectation/chaos/
│   │   ├── network/                       # Network chaos (2 tests)
│   │   ├── core/                          # Core chaos (1 test)
│   │   ├── resource/                      # Resource chaos (3 tests)
│   │   └── nightmare/                     # Nightmare scenarios (15 tests)
│   └── resources/
│       ├── application-chaos.yml          # Chaos test profile
│       └── junit-platform.properties      # Test configuration
```

### Key Changes

1. **Dedicated Module**: `module-chaos-test` is included in `settings.gradle` but excluded from regular builds
2. **Custom SourceSet**: `chaosTest` sourceSet (independent from `test`)
3. **Gradle Tasks**: `chaosTest`, `chaosTestNetwork`, `chaosTestResource`, `nightmareTest`
4. **CI/CD Workflow**: New `.github/workflows/nightly-chaos.yml` for chaos tests
5. **No test-legacy**: Remove `module-app/src/test-legacy` after migration

---

## Alternatives Considered

### Alternative 1: Keep in test-legacy with Improved Tagging

**Approach**: Keep chaos tests in `module-app/src/test-legacy`, enhance tag filtering

**Pros**:
- No structural changes required
- Migration effort: 0 hours

**Cons**:
- `test-legacy` naming remains confusing (not really "legacy")
- No physical separation from regular tests
- JVM config pollution (chaos tests need 2GB heap vs 512MB for unit tests)
- CI/CD still relies on fragile tag filtering

**Rejected**: No improvement over current state

---

### Alternative 2: Move to Regular test/ with @Tag("chaos")

**Approach**: Move chaos tests to `module-app/src/test`, rely on JUnit tags

**Pros**:
- Removes `test-legacy` directory
- Standard test directory structure

**Cons**:
- Chaos tests would be included in regular test execution (accidental risk)
- CI/CD would still need explicit exclusion logic
- Test report pollution (chaos test failures mixed with unit test failures)
- No isolation for resource exhaustion tests (OOM could affect other tests)

**Rejected**: Risk of accidental execution in CI/CD

---

### Alternative 3: Separate Chaos Test Module (ACCEPTED)

**Approach**: Create `module-chaos-test` with custom `chaosTest` sourceSet

**Pros**:
- **Physical Isolation**: Impossible to accidentally run in PR CI
- **Clear Ownership**: Dedicated module for chaos engineering
- **Flexible Execution**: Category-specific tasks (network, resource, core)
- **JVM Config**: Separate heap/GC settings for chaos tests
- **CI/CD Clarity**: Explicit workflow (`nightly-chaos.yml`)
- **Dependency Management**: Clear dependencies on all app modules

**Cons**:
- Migration effort: ~8 hours (file moves, dependency resolution)
- Additional module to maintain
- Requires root `settings.gradle` update

**Accepted**: Benefits outweigh migration cost

---

## Consequences

### Positive

1. **Clarity**: Chaos tests are clearly separated and cannot accidentally run in PR CI
2. **CI/CD Speed**: PR validation remains fast (3-5 min) without any chaos test risk
3. **Flexible Execution**: Developers can run specific chaos categories locally
4. **Better Reports**: Separate test reports for chaos vs. regular tests
5. **Onboarding**: New developers immediately understand chaos test structure
6. **Documentation**: Clear home for chaos test documentation (this module)

### Negative

1. **Migration Effort**: 22 test files must be moved with package updates
2. **Support Classes**: May need to duplicate or extract common test support classes
3. **Module Complexity**: Additional module in multi-module structure
4. **Dependency Chain**: `module-chaos-test` depends on all app modules (test scope)

### Mitigation

- **Migration Checklist**: Documented step-by-step migration (Section 5 of architecture doc)
- **Rollback Plan**: Git revert strategy if issues arise
- **Support Classes**: Create `module-test-utils` if common support classes needed
- **CI/CD Validation**: Test nightly-chaos workflow in feature branch before merge

---

## Implementation Plan

### Phase 1: Structure Creation (COMPLETED)
- [x] Create `module-chaos-test/build.gradle`
- [x] Create directory structure
- [x] Create `application-chaos.yml` profile
- [x] Create `junit-platform.properties`

### Phase 2: File Migration (PENDING)
- [ ] Move 22 chaos/nightmare test files
- [ ] Update package declarations
- [ ] Resolve support class dependencies
- [ ] Validate imports

### Phase 3: Build Configuration (PENDING)
- [ ] Update root `settings.gradle`:
  ```gradle
  include 'module-chaos-test'
  ```
- [ ] Update `module-app/build.gradle`: Remove `test-legacy` exclusion
- [ ] Verify no circular dependencies

### Phase 4: CI/CD Update (PENDING)
- [ ] Create `.github/workflows/nightly-chaos.yml`
- [ ] Update `.github/workflows/nightly.yml`: Remove chaos steps
- [ ] Test workflows in feature branch
- [ ] Verify artifact uploads

### Phase 5: Validation (PENDING)
- [ ] Local execution: `./gradlew :module-chaos-test:chaosTest`
- [ ] Category execution: `./gradlew :module-chaos-test:chaosTestNetwork`
- [ ] Regular tests: `./gradlew test` (should exclude chaos)
- [ ] CI/CD manual trigger: Test workflow_dispatch

### Phase 6: Cleanup (PENDING)
- [ ] Remove empty `module-app/src/test-legacy` directory
- [ ] Update documentation (CLAUDE.md, README)
- [ ] Archive old structure in `docs/99_Archive/`

---

## Gradle Task Reference

### New Tasks

```bash
# Run all 22 chaos/nightmare tests
./gradlew :module-chaos-test:chaosTest

# Run network chaos only (2 tests)
./gradlew :module-chaos-test:chaosTestNetwork

# Run resource chaos only (3 tests)
./gradlew :module-chaos-test:chaosTestResource

# Run core chaos only (1 test)
./gradlew :module-chaos-test:chaosTestCore

# Run nightmare scenarios only (15 tests)
./gradlew :module-chaos-test:nightmareTest
```

### Existing Tasks (Unchanged)

```bash
# Regular unit + integration tests (excludes chaos)
./gradlew test

# Fast tests for PR CI
./gradlew test -PfastTest

# Integration tests only
./gradlew test -PintegrationTest
```

---

## CI/CD Integration

### PR Validation (Unchanged)
- **Workflow**: `.github/workflows/ci.yml`
- **Tests**: Unit + Integration (`-PfastTest`)
- **Duration**: 3-5 minutes
- **Chaos Tests**: Excluded (no change required)

### Nightly Chaos Tests (New)
- **Workflow**: `.github/workflows/nightly-chaos.yml` (NEW)
- **Tests**: All 22 chaos/nightmare tests
- **Duration**: 30-45 minutes
- **Trigger**: Schedule (KST 00:00) OR manual with category selection
- **Gate**: Non-blocking (report failure, don't block deployment)

### Legacy Nightly (Updated)
- **Workflow**: `.github/workflows/nightly.yml`
- **Change**: Remove chaos/nightmare steps (moved to new workflow)
- **Tests**: Unit → Integration only
- **Duration**: 15-20 minutes (reduced from 45 min)

---

## References

- [Architecture Documentation](/home/maple/MapleExpectation/docs/02_Technical_Guides/chaos-test-module-architecture.md)
- [Quick Start Guide](/home/maple/MapleExpectation/docs/02_Technical_Guides/chaos-test-quick-start.md)
- [CI/CD Patterns](/home/maple/MapleExpectation/docs/02_Technical_Guides/chaos-test-cicd-patterns.md)
- [Test Strategy](/home/maple/MapleExpectation/docs/01_Chaos_Engineering/00_Overview/TEST_STRATEGY.md)
- [ADR-014: Multi-module Cross-cutting Concerns](/home/maple/MapleExpectation/docs/adr/ADR-014-multi-module-cross-cutting-concerns.md)

---

**Decision Record Owner**: SRE-Gatekeeper (Red Agent)
**Reviewers**: 5-Agent Council (Blue, Green, Yellow, Purple, Red)
**Approval Status**: Pending Implementation Review
