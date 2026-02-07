# MapleExpectation Refactoring Plan

> **5-Agent Council Approved:** Blue ✅ | Green ✅ | Yellow ✅ | Purple ✅ | Red ✅
>
> **Plan Version:** 1.0
>
> **Estimated Duration:** 17 weeks (~125 story points)
>
> **Approach:** Incremental, thin-slice, behavior-preserving

---

## Mission Statement

Transform MapleExpectation into a **Clean Architecture** codebase while:
- ✅ Preserving all existing behavior (no regressions)
- ✅ Maintaining performance posture (RPS ≥ 965, p99 ≤ 214ms)
- ✅ Upholding resilience invariants (timeouts, circuit breaker, outbox)
- ✅ Keeping audit trails intact (exception hierarchy, metrics)
- ✅ Passing all tests (934 tests, 100% pass rate)

---

## Non-Negotiable Constraints

### 1. Performance Constraints (Green)
```
RPS Target:        ≥ 965  (current: 965)
p99 Target:        ≤ 214ms (current: 214ms)
Memory/Request:    ~15MB  (100 concurrent = ~1.5GB)
Hot Path Latency:  No additional abstraction overhead
```

### 2. Resilience Constraints (Red)
```
Timeout Layering:  3s → 5s → 28s (application.yml:171)
Circuit Breaker:   50% threshold, marker interfaces preserved
Graceful Shutdown: 4-phase order maintained (MAX_VALUE-500 → MAX_VALUE-1000)
Rejection Policy:  AbortPolicy (NOT CallerRunsPolicy)
Outbox:           Triple safety net intact (DB + File + Discord)
```

### 3. Audit Constraints (Purple)
```
Exception Hierarchy:    ClientBaseException / ServerBaseException unchanged
Circuit Breaker Markers: Ignore (4xx) / Record (5xx) preserved
Metric Names:           Prometheus/Grafana compatibility maintained
SHA-256 Hash:           DonationOutbox integrity preserved
Kahan Summation:        DP calculator precision maintained
```

### 4. Testing Constraints (Yellow)
```
Test Pass Rate:         100% (no flaky tests)
fastTest Duration:      ≤ 40 seconds
Test Coverage:          Maintain or improve 2.09:1 ratio
Characterization Tests: All LogicExecutor patterns covered
```

---

## Refactoring Phases

### Phase 0: Baseline & Safety ✅ COMPLETE

**Status:** Done
**Duration:** 1 day (5 parallel agents)

**Deliverables:**
- ✅ ARCHITECTURE_MAP.md - Current state mapping
- ✅ SOLID_VIOLATIONS.md - 43 violations documented
- ✅ TARGET_STRUCTURE.md - Clean Architecture proposal
- ✅ PERFORMANCE_BASELINE.md - RPS 965, p99 214ms
- ✅ RISK_REGISTER.md - Top 10 risks + mitigation
- ✅ AUDIT_BASELINE.md - Exception hierarchy verified
- ✅ RESILIENCE_BASELINE.md - All invariants documented
- ✅ PHASE0_SUMMARY.md - Council consensus

**Key Findings:**
- 43 SOLID violations (10 P0, 21 P1, 12 P2)
- 3 missing critical tests (Characterization, JMH, CB State)
- All resilience invariants intact
- Performance baseline healthy

---

### Phase 1: Guardrails & Tooling 🚀 NEXT

**Status:** Ready to start
**Duration:** 1 week
**Effort:** 8 story points

**Objectives:**
1. Add ArchUnit architecture tests
2. Standardize code formatting (Spotless)
3. Add static analysis (SpotBugs/ErrorProne)
4. Configure CI lanes (fast vs nightly)

**Deliverables:**
- `build.gradle` - Spotless, SpotBugs, ArchUnit dependencies
- `src/test/java/archunit/` - Architecture test suite
- `.github/workflows/` - Updated CI configuration
- `docs/refactor/ARCHUNIT_RULES.md` - Architecture rule documentation

**ArchUnit Rules:**
```java
// 1. Domain isolation (no Spring/JPA dependencies)
@ArchTest
static final ArchRule DOMAIN_ISOLATION = noClasses()
    .that().resideInAPackage("..domain..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("..springframework..", "..persistence..");

// 2. No cyclic dependencies
@ArchTest
static final ArchRule NO_CYCLES = slices()
    .matching("maple.expectation.(*)..")
    .should().beFreeOfCycles();

// 3. Controller thinness (orchestration only)
@ArchTest
static final ArchRule CONTROLLERS_THIN = classes()
    .that().areAnnotatedWith(Controller.class)
    .should().haveOnlyFinalFields();

// 4. LogicExecutor usage (no try-catch in services)
@ArchTest
static final ArchRule NO_TRY_CATCH = noClasses()
    .that().resideInAPackage("..service..")
    .should().containNumberOfElements(tryCatch, 0);
```

**CI Configuration:**
```yaml
# .github/workflows/ci.yml
name: CI Pipeline
on: [pull_request]
jobs:
  fast-test:
    runs-on: ubuntu-latest
    steps:
      - ./gradlew test -PfastTest
      - ./gradlew spotlessCheck
      - ./gradlew archTest
      - ./gradlew spotbugsCheck

# .github/workflows/nightly.yml
name: Nightly Full
on:
  schedule:
    - cron: '0 0 * * *'  # Daily at midnight KST
jobs:
  full-test:
    runs-on: ubuntu-latest
    steps:
      - ./gradlew test
      - ./gradlew spotlessCheck
      - ./gradlew archTest
      - ./gradlew chaosTest
```

**Exit Criteria:**
- [ ] All ArchUnit rules pass
- [ ] Spotless formatting applied (0 violations)
- [ ] SpotBugs scan clean (0 high priority bugs)
- [ ] CI fastTest completes < 10 minutes
- [ ] Documentation updated

**Risk:** LOW - No behavior changes, only guardrails added

---

### Phase 2: Foundation - Package Structure

**Status:** Blocked by Phase 1
**Duration:** 2 weeks
**Effort:** 16 story points

**Objectives:**
1. Create Clean Architecture package structure
2. Define base interfaces (Repository, Service, DTO)
3. Set up dependency injection configuration
4. Write ArchUnit rules for new structure

**Deliverables:**
- `src/main/java/maple/expectation/domain/` - Empty package structure
- `src/main/java/maple/expectation/application/` - Empty package structure
- `src/main/java/maple/expectation/infrastructure/` - Empty package structure
- `src/main/java/maple/expectation/interfaces/` - Empty package structure
- `src/main/java/maple/expectation/shared/` - Cross-cutting concerns
- ArchUnit rules enforcing new structure

**Package Structure:**
```
maple.expectation/
├── domain/
│   ├── model/           # Entities, Value Objects
│   ├── service/         # Domain services
│   ├── repository/      # Repository interfaces (ports)
│   └── exception/       # Domain exceptions
│
├── application/
│   ├── service/         # Use cases
│   ├── dto/             # Request/Response DTOs
│   ├── port/            # Port interfaces
│   └── mapper/          # DTO ↔ Model mappers
│
├── infrastructure/
│   ├── persistence/     # JPA entities, repositories
│   ├── cache/           # Redis, Caffeine, TieredCache
│   ├── external/        # API clients (Nexon, OpenAI)
│   └── config/          # Spring @Configuration
│
├── interfaces/
│   ├── rest/            # REST controllers
│   ├── event/           # Event listeners
│   └── filter/          # Servlet filters
│
└── shared/              # Existing shared code
    ├── error/           # Error handling
    ├── executor/        # LogicExecutor
    ├── aop/             # Aspects
    └── util/            # Pure utilities
```

**Base Interfaces:**
```java
// domain/repository/EquipmentRepository.java
public interface EquipmentRepository {
    Optional<Equipment> findById(Long id);
    Equipment save(Equipment equipment);
}

// infrastructure/persistence/EquipmentRepositoryImpl.java
@Repository
public class EquipmentRepositoryImpl implements EquipmentRepository {
    private final JpaEquipmentRepository jpaRepository;
    // Implementation
}
```

**Exit Criteria:**
- [ ] All packages created (empty initially)
- [ ] Base interfaces defined
- [ ] ArchUnit rules enforce layer isolation
- [ ] Build succeeds (0 errors)
- [ ] All tests pass (934 tests)

**Risk:** LOW - Empty structure, no code moved yet

---

### Phase 3: Domain Extraction

**Status:** Blocked by Phase 2
**Duration:** 4 weeks
**Effort:** 32 story points

**Objectives:**
1. Extract pure domain models (remove JPA annotations)
2. Create rich domain models (add behavior)
3. Define domain services
4. Update repository interfaces

**Slice 1: Equipment Domain (1 week)**
- Extract `Equipment` entity → domain model
- Remove `@Entity`, `@Column` annotations
- Add domain behavior (`upgrade()`, `calculateCost()`)
- Create `EquipmentRepository` interface
- Move `EquipmentRepositoryImpl` to infrastructure

**Slice 2: Character Domain (1 week)**
- Extract `GameCharacter` entity → domain model
- Remove JPA annotations
- Add domain behavior (`like()`, `calculateExpectation()`)
- Create `CharacterRepository` interface
- Move implementation to infrastructure

**Slice 3: Calculator Domain (1 week)**
- Extract `ProbabilityConvolver` → domain service
- Remove Spring dependencies
- Ensure Kahan Summation precision
- Add domain tests

**Slice 4: Like Domain (1 week)**
- Extract `CharacterLike` entity → domain model
- Remove JPA annotations
- Add domain behavior (`validate()`, `toggle()`)
- Create `LikeRepository` interface
- Move implementation to infrastructure

**Exit Criteria (Per Slice):**
- [ ] Domain model pure (no framework deps)
- [ ] Rich domain behavior added
- [ ] Repository interface defined
- [ ] Infrastructure adapter implemented
- [ ] Tests pass (characterization + unit)
- [ ] ArchUnit rules pass

**Risk:** MEDIUM - Domain extraction requires care to preserve behavior

**Mitigation:**
- Characterization tests before each slice
- Parallel run (old + new) during transition
- Performance monitoring

---

### Phase 4: Infrastructure Adapters

**Status:** Blocked by Phase 3
**Duration:** 4 weeks
**Effort:** 32 story points

**Objectives:**
1. Implement repository adapters (JPA → domain mapping)
2. Create cache adapters (infrastructure layer)
3. Implement external API clients
4. Set up Spring configuration

**Slice 1: Repository Adapters (2 weeks)**
- `EquipmentRepositoryImpl` - JPA → Equipment mapping
- `CharacterRepositoryImpl` - JPA → GameCharacter mapping
- `LikeRepositoryImpl` - JPA → CharacterLike mapping
- Add unit tests for mapping logic

**Slice 2: Cache Adapters (1 week)**
- Move `TieredCache` to infrastructure
- Create cache configuration
- Implement cache invalidation (Pub/Sub)
- Add cache tests

**Slice 3: External API Clients (1 week)**
- Move `NexonApiClient` to infrastructure
- Implement WebClient configuration
- Add retry/resilience logic
- Add integration tests

**Exit Criteria:**
- [ ] All repositories implement domain interfaces
- [ ] Cache adapters configured
- [ ] External clients in infrastructure
- [ ] Tests pass (unit + integration)
- [ ] ArchUnit rules enforce layer separation

**Risk:** MEDIUM - Mapping bugs possible

**Mitigation:**
- Characterization tests for all repositories
- Integration tests with Testcontainers
- reconciliation queries (DB comparison)

---

### Phase 5: Application Services

**Status:** Blocked by Phase 4
**Duration:** 3 weeks
**Effort:** 24 story points

**Objectives:**
1. Create use case services (application layer)
2. Define DTOs (request/response)
3. Implement mappers (DTO ↔ domain)
4. Set up transaction boundaries

**Slice 1: Equipment Use Cases (1 week)**
- `EquipmentApplicationService` - orchestrates equipment operations
- `EquipmentDto` - request/response models
- `EquipmentMapper` - DTO ↔ domain conversion
- Transaction boundaries with `@Transactional`

**Slice 2: Character Use Cases (1 week)**
- `CharacterApplicationService` - orchestrates character operations
- `CharacterDto` - request/response models
- `CharacterMapper` - DTO ↔ domain conversion
- Transaction boundaries

**Slice 3: Calculator Use Cases (1 week)**
- `CalculatorApplicationService` - orchestrates calculations
- `ExpectationDto` - response models
- `ExpectationMapper` - domain → DTO conversion
- Caching strategy (application layer)

**Exit Criteria:**
- [ ] All use cases in application layer
- [ ] DTOs defined for all operations
- [ ] Mappers implemented
- [ ] Transaction boundaries correct
- [ ] Tests pass (unit + integration)

**Risk:** MEDIUM - Transaction boundary changes

**Mitigation:**
- Integration tests with rollback verification
- Outbox pattern checks
- Transaction boundary tests

---

### Phase 6: Controller Refactoring

**Status:** Blocked by Phase 5
**Duration:** 2 weeks
**Effort:** 16 story points

**Objectives:**
1. Thin controllers (orchestration only)
2. Remove business logic from controllers
3. Add validation (DTO level)
4. Update exception handling

**Slice 1: V1-V3 Controllers (1 week)**
- Refactor `EquipmentControllerV1`, `V2`, `V3`
- Delegate to application services
- Remove business logic
- Add DTO validation

**Slice 2: V4 + Admin Controllers (1 week)**
- Refactor `EquipmentControllerV4`
- Refactor `AdminController`
- Delegate to application services
- Add security (RBAC)

**Exit Criteria:**
- [ ] Controllers < 100 LOC
- [ ] No business logic in controllers
- [ ] All validation in DTOs
- [ ] Exception handling in `GlobalExceptionHandler`
- [ ] Tests pass (controller + integration)

**Risk:** LOW - Controllers are thin wrappers

**Mitigation:**
- Controller tests (MockMvc)
- Integration tests (full request/response)

---

### Phase 7: Cleanup & Verification

**Status:** Blocked by Phase 6
**Duration:** 1 week
**Effort:** 8 story points

**Objectives:**
1. Remove deprecated code
2. Update documentation
3. Final verification (all tests)
4. Performance validation

**Tasks:**
1. Delete old packages (v2, global, etc.)
2. Update CLAUDE.md with new structure
3. Update README with new architecture
4. Run full test suite (934 tests)
5. Run load test (verify RPS ≥ 965)
6. Run chaos tests (N01-N18)
7. Update Grafana dashboards (if needed)
8. Create ADR for architecture changes

**Exit Criteria:**
- [ ] No deprecated code remains
- [ ] Documentation updated
- [ ] All tests pass (100%)
- [ ] Load test passes (RPS ≥ 965)
- [ ] Chaos tests pass (N01-N18)
- [ ] No performance regression
- [ ] ADR published

**Risk:** LOW - Final polish

---

## Commit Strategy

### Commit Message Format

```bash
# Pattern
<TYPE>: <description>

[optional body]

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
```

### Example Commits

```bash
# Phase 1
feat: add ArchUnit architecture test suite
- Add domain isolation rule
- Add no-cyclic-dependencies rule
- Add controller thinness rule

# Phase 2
feat: create Clean Architecture package structure
- Add domain/ package (empty)
- Add application/ package (empty)
- Add infrastructure/ package (empty)
- Add interfaces/ package (empty)

# Phase 3
refactor: extract Equipment domain model
- Remove JPA annotations from Equipment
- Add domain behavior (upgrade, calculateCost)
- Create EquipmentRepository interface
- Move implementation to infrastructure

# Phase 4
refactor: implement Equipment repository adapter
- Add EquipmentRepositoryImpl (JPA → domain mapping)
- Add mapping unit tests
- Add integration tests with Testcontainers

# Phase 5
feat: create Equipment use case service
- Add EquipmentApplicationService
- Add EquipmentDto (request/response)
- Add EquipmentMapper
- Set up transaction boundaries

# Phase 6
refactor: thin EquipmentControllerV1
- Remove business logic
- Delegate to EquipmentApplicationService
- Add DTO validation

# Phase 7
chore: remove deprecated v2 package
- Delete old service layer
- Update imports
- Update documentation
```

### Branch Strategy

```bash
# Main branches
main (production)     ← Never commit directly
develop               ← Integration branch

# Feature branches
feature/phase1-guardrails
feature/phase2-foundation
feature/phase3-domain-extraction
feature/phase4-infrastructure
feature/phase5-application-services
feature/phase6-controllers
feature/phase7-cleanup

# PR flow
1. Create feature/XXX branch from develop
2. Make commits (small, reviewable)
3. Push to GitHub
4. Create PR (base: develop, NOT master)
5. CI checks (fastTest + spotless + archTest)
6. Code review (5-Agent Council)
7. Merge to develop
```

---

## Testing Strategy

### Pre-Refactor Tests (Phase 0-1)

```bash
# Characterization tests (before any code change)
./gradlew test --tests "*Characterization*"

# Baseline performance
./gradlew jmh  # or manual load test with wrk

# Test inventory
./gradlew test --tests
```

### Per-Slice Tests (Phase 2-6)

```bash
# Before slice
./gradlew test -PfastTest
./gradlew spotlessCheck
./gradlew archTest

# After slice (same verification)
./gradlew test -PfastTest
./gradlew spotlessCheck
./gradlew archTest

# If tests fail, investigate before proceeding
```

### Phase Completion Tests (Phase 7)

```bash
# Full test suite
./gradlew test

# Load test (verify RPS ≥ 965)
wrk -t4 -c100 -d30s load-test/wrk-script.lua http://localhost:8080/api/v4/character/test/expectation

# Chaos tests (N01-N18)
./gradlew test --tests "*Chaos*"
./gradlew test --tests "*Nightmare*"
```

---

## Rollback Strategy

### Per-Slice Rollback

If a slice causes issues:
```bash
# 1. Identify the breaking commit
git log --oneline

# 2. Revert the commit
git revert <commit-hash>

# 3. Run tests
./gradlew test -PfastTest

# 4. Push revert
git push origin feature/XXX
```

### Phase Rollback

If an entire phase is problematic:
```bash
# 1. Abandon the feature branch
git checkout develop
git branch -D feature/phaseX

# 2. Start fresh from develop
git checkout -b feature/phaseX-redo

# 3. Review approach
# 4. Adjust strategy
# 5. Retry with smaller slices
```

---

## 5-Agent Council Review Schedule

### Phase 1 Review (After 1 week)
- Blue: ArchUnit rules adequate?
- Green: No performance impact?
- Yellow: CI pipeline fast enough?
- Purple: Audit trails intact?
- Red: Resilience invariants preserved?

### Phase 3 Review (After 7 weeks)
- Blue: Domain models pure?
- Green: No regression in hot paths?
- Yellow: Characterization tests passing?
- Purple: Exception hierarchy unchanged?
- Red: Transaction boundaries correct?

### Phase 7 Review (After 17 weeks)
- Blue: Clean Architecture achieved?
- Green: Performance baseline maintained?
- Yellow: All tests passing?
- Purple: Audit trails complete?
- Red: Resilience invariants intact?

---

## Definition of Done (Overall)

### Code Quality
- [ ] All 43 SOLID violations addressed
- [ ] Clean Architecture structure in place
- [ ] No cyclic dependencies (ArchUnit verified)
- [ ] All packages follow layer isolation

### Testing
- [ ] All 934 tests passing (100% pass rate)
- [ ] Characterization tests added
- [ ] Performance baseline maintained (RPS ≥ 965)
- [ ] Chaos tests passing (N01-N18)

### Documentation
- [ ] ADR published (architecture decisions)
- [ ] CLAUDE.md updated (new structure)
- [ ] README updated (architecture diagrams)
- [ ] API documentation updated (if changed)

### Operations
- [ ] No performance regression
- [ ] Resilience invariants intact
- [ ] Audit trails complete
- [ ] Monitoring dashboards working

---

## Success Metrics

### Quantitative
- **SOLID Violations:** 43 → 0
- **Test Coverage:** Maintain 2.09:1 ratio
- **Test Pass Rate:** 100% (934/934)
- **RPS:** ≥ 965 (no regression)
- **p99 Latency:** ≤ 214ms (no regression)
- **Build Time:** ≤ 40 seconds (fastTest)

### Qualitative
- **Code Readability:** Improved (Clean Architecture)
- **Maintainability:** Improved (SOLID compliance)
- **Testability:** Improved (pure domain models)
- **Auditability:** Maintained (all trails intact)
- **Operability:** Maintained (resilience patterns)

---

## References

- **Phase 0 Summary:** `docs/refactor/PHASE0_SUMMARY.md`
- **Architecture Map:** `docs/refactor/ARCHITECTURE_MAP.md`
- **SOLID Violations:** `docs/refactor/SOLID_VIOLATIONS.md`
- **Target Structure:** `docs/refactor/TARGET_STRUCTURE.md`
- **Performance Baseline:** `docs/refactor/PERFORMANCE_BASELINE.md`
- **Risk Register:** `docs/refactor/RISK_REGISTER.md`
- **Audit Baseline:** `docs/refactor/AUDIT_BASELINE.md`
- **Resilience Baseline:** `docs/refactor/RESILIENCE_BASELINE.md`

---

*Plan approved by 5-Agent Council*
*Date: 2026-02-07*
*Plan Version: 1.0*
*Next Review: After Phase 1 completion*
