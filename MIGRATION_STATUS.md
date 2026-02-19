# Chaos Test Module Migration Status

> **Migration**: Chaos/Nightmare Tests → `module-chaos-test`
> **Status**: Design Complete, Awaiting Implementation Approval
> **Created**: 2026-02-11

---

## Progress Overview

| Phase | Status | Completion |
|-------|--------|------------|
| **Phase 1: Design & Structure** | ✅ Complete | 100% |
| **Phase 2: File Migration** | ⏳ Pending | 0% |
| **Phase 3: Build Configuration** | ⏳ Pending | 0% |
| **Phase 4: CI/CD Integration** | ⏳ Pending | 0% |
| **Phase 5: Validation** | ⏳ Pending | 0% |
| **Phase 6: Cleanup** | ⏳ Pending | 0% |

**Overall Progress**: 15% (Design Complete)

---

## Phase 1: Design & Structure (✅ Complete)

### Deliverables Created

- [x] **ADR-025**: Chaos Test Module Separation decision record
- [x] **Module Architecture**: Full design document with directory structure
- [x] **Build Configuration**: `module-chaos-test/build.gradle`
- [x] **SourceSet Setup**: Custom `chaosTest` sourceSet configuration
- [x] **Profile Configuration**: `application-chaos.yml`
- [x] **Test Configuration**: `junit-platform.properties`
- [x] **Quick Start Guide**: Developer execution reference
- [x] **CI/CD Patterns**: Workflow integration guide
- [x] **Directory Structure**: Created subdirectories for test organization

### Files Created

```
module-chaos-test/
├── build.gradle                                    ✅ Created
└── src/chaos-test/
    ├── java/maple/expectation/chaos/
    │   ├── network/                                ✅ Directory created
    │   ├── core/                                   ✅ Directory created
    │   ├── resource/                               ✅ Directory created
    │   └── nightmare/                              ✅ Directory created
    └── resources/
        ├── application-chaos.yml                   ✅ Created
        └── junit-platform.properties               ✅ Created

docs/02_Technical_Guides/
├── chaos-test-module-architecture.md              ✅ Created (500+ lines)
├── chaos-test-quick-start.md                      ✅ Created
└── chaos-test-cicd-patterns.md                    ✅ Created

docs/01_ADR/
└── ADR-025-chaos-test-module-separation.md        ✅ Created
```

---

## Phase 2: File Migration (⏳ Pending)

### Tests to Move (22 Total)

#### Chaos Tests (7 files)
- [ ] `module-app/src/test-legacy/.../network/ClockDriftChaosTest.java`
- [ ] `module-app/src/test-legacy/.../connection/ThunderingHerdLockChaosTest.java`
- [ ] `module-app/src/test-legacy/.../core/OOMChaosTest.java`
- [ ] `module-app/src/test-legacy/.../resource/DiskFullChaosTest.java`
- [ ] `module-app/src/test-legacy/.../resource/GcPauseChaosTest.java`
- [ ] `module-app/src/test-legacy/.../resource/PoolExhaustionChaosTest.java`

#### Nightmare Tests (15 files)
- [ ] `AopOrderNightmareTest.java`
- [ ] `AsyncContextLossNightmareTest.java`
- [ ] `CallerRunsPolicyNightmareTest.java`
- [ ] `CelebrityProblemNightmareTest.java`
- [ ] `CircularLockDeadlockNightmareTest.java`
- [ ] `ConnectionVampireNightmareTest.java`
- [ ] `DeadlockTrapNightmareTest.java`
- [ ] `DeepPagingNightmareTest.java`
- [ ] `MetadataLockFreezeNightmareTest.java`
- [ ] `NexonApiOutboxNightmareTest.java`
- [ ] `NexonApiOutboxMultiFailureNightmareTest.java`
- [ ] `PipelineExceptionNightmareTest.java`
- [ ] `PoisonPillNightmareTest.java`
- [ ] `SelfInvocationNightmareTest.java`
- [ ] `ThreadPoolExhaustionNightmareTest.java`
- [ ] `ThunderingHerdNightmareTest.java`
- [ ] `ZombieOutboxNightmareTest.java`

### Migration Commands (For Reference)

```bash
# Network Chaos
mv module-app/src/test-legacy/java/maple/expectation/chaos/network/ClockDriftChaosTest.java \
   module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/

mv module-app/src/test-legacy/java/maple/expectation/chaos/connection/ThunderingHerdLockChaosTest.java \
   module-chaos-test/src/chaos-test/java/maple/expectation/chaos/network/

# Core Chaos
mv module-app/src/test-legacy/java/maple/expectation/chaos/core/OOMChaosTest.java \
   module-chaos-test/src/chaos-test/java/maple/expectation/chaos/core/

# Resource Chaos
mv module-app/src/test-legacy/java/maple/expectation/chaos/resource/*.java \
   module-chaos-test/src/chaos-test/java/maple/expectation/chaos/resource/

# Nightmare
mv module-app/src/test-legacy/java/maple/expectation/chaos/nightmare/*.java \
   module-chaos-test/src/chaos-test/java/maple/expectation/chaos/nightmare/
```

---

## Phase 3: Build Configuration (⏳ Pending)

### Tasks

- [ ] Update `settings.gradle`:
  ```gradle
  include 'module-chaos-test'
  ```

- [ ] Update `module-app/build.gradle`:
  ```gradle
  # REMOVE THIS LINE (no longer needed)
  tasks.named('test') {
    exclude '**/test-legacy/**'
  }
  ```

- [ ] Verify dependencies in `module-chaos-test/build.gradle`

- [ ] Resolve support class dependencies:
  - [ ] Identify shared test utilities
  - [ ] Create `module-test-utils` OR duplicate required classes

---

## Phase 4: CI/CD Integration (⏳ Pending)

### Tasks

- [ ] Create `.github/workflows/nightly-chaos.yml`:
  - [ ] Chaos Tests job (with category selection)
  - [ ] Nightmare Tests job
  - [ ] Summary job

- [ ] Update `.github/workflows/nightly.yml`:
  - [ ] Remove Step 3 (Chaos Tests)
  - [ ] Remove Step 4 (Nightmare Tests)

- [ ] Update `.github/workflows/ci.yml`:
  - [ ] NO CHANGES NEEDED (already excludes chaos tests)

- [ ] Test workflows in feature branch

---

## Phase 5: Validation (⏳ Pending)

### Local Validation

- [ ] Module builds successfully:
  ```bash
  ./gradlew :module-chaos-test:build
  ```

- [ ] Chaos tests execute:
  ```bash
  ./gradlew :module-chaos-test:chaosTest
  ```

- [ ] Category tasks work:
  ```bash
  ./gradlew :module-chaos-test:chaosTestNetwork
  ./gradlew :module-chaos-test:chaosTestResource
  ./gradlew :module-chaos-test:nightmareTest
  ```

- [ ] Regular tests unaffected:
  ```bash
  ./gradlew test
  ./gradlew test -PfastTest
  ```

### CI/CD Validation

- [ ] Manual workflow_dispatch succeeds
- [ ] Scheduled execution (when triggered)
- [ ] Artifacts uploaded correctly
- [ ] Test reports published

---

## Phase 6: Cleanup (⏳ Pending)

### Tasks

- [ ] Remove `module-app/src/test-legacy` (if empty)
- [ ] Update CLAUDE.md with chaos test module reference
- [ ] Update README with chaos test execution guide
- [ ] Archive old structure in `docs/99_Archive/` (optional)
- [ ] Update related documentation references

---

## Blockers & Risks

### Current Blockers

None identified. Design is complete and ready for implementation.

### Potential Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Support class dependencies | Medium | Create module-test-utils OR duplicate classes |
| Circular dependencies | Medium | Careful dependency review in build.gradle |
| CI/CD workflow errors | High | Test in feature branch before merge |
| Package import issues | Low | Automated find-replace for package updates |
| Test execution failures | Medium | Gradual migration with validation at each step |

---

## Next Steps

1. **Review & Approve**: 5-Agent Council reviews ADR-025 and architecture doc
2. **Feature Branch**: Create `feature/chaos-test-module` branch
3. **Execute Migration**: Follow Phase 2-6 checklist
4. **Validation**: Test locally and in CI/CD
5. **PR Submission**: Submit PR to `develop` with comprehensive testing

---

## References

- **ADR-025**: [Chaos Test Module Separation](/home/maple/MapleExpectation/docs/01_ADR/ADR-025-chaos-test-module-separation.md)
- **Architecture**: [Chaos Test Module Architecture](/home/maple/MapleExpectation/docs/02_Technical_Guides/chaos-test-module-architecture.md)
- **Quick Start**: [Chaos Test Quick Start](/home/maple/MapleExpectation/docs/02_Technical_Guides/chaos-test-quick-start.md)
- **CI/CD Patterns**: [CI/CD Integration Patterns](/home/maple/MapleExpectation/docs/02_Technical_Guides/chaos-test-cicd-patterns.md)

---

**Last Updated**: 2026-02-11
**Owner**: SRE-Gatekeeper (Red Agent)
**Status**: ✅ Design Complete, ⏳ Awaiting Implementation Approval
