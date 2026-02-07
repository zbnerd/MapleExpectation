# Phase 3 Agent Review - Domain Extraction

> **5-Agent Council Review Phase 3 Preparation**
>
> **Date:** 2026-02-07
>
> **Status:** COUNCIL DELIBERATION IN PROGRESS
>
> **Mission:** Review Phase 3 (Domain Extraction) plan for all architectural, performance, QA, audit, and SRE concerns before execution begins.

---

## Executive Summary

This document captures the 5-Agent Council's collaborative review of **Phase 3: Domain Extraction** from the Clean Architecture refactoring plan. Each agent provides their assessment, risks, and vote based on their specialized perspective.

**Phase 3 Scope:**
- Duration: 4 weeks (32 story points)
- 4 Slices: Equipment, Character, Calculator, Like domains
- Primary Goal: Extract pure domain models (remove JPA annotations, add behavior)

---

## Phase 3 Plan Summary

### Objectives

1. **Extract pure domain models** - Remove `@Entity`, `@Column`, JPA annotations
2. **Create rich domain models** - Add behavior (methods like `upgrade()`, `calculateCost()`)
3. **Define domain services** - Pure business logic without framework dependencies
4. **Update repository interfaces** - Complete Ports & Adapters pattern

### 4 Slices (1 week each)

| Slice | Domain | Key Entities | Complexity |
|-------|--------|--------------|------------|
| **Slice 1** | Equipment | `CharacterEquipment` | Medium - 5 fields, GZIP blob |
| **Slice 2** | Character | `GameCharacter` | Medium - Rich behavior exists |
| **Slice 3** | Calculator | `ProbabilityConvolver` | High - Kahan Summation precision |
| **Slice 4** | Like | `CharacterLike` | Low - Simple entity |

### Exit Criteria (Per Slice)

- [ ] Domain model pure (no framework deps)
- [ ] Rich domain behavior added
- [ ] Repository interface defined
- [ ] Infrastructure adapter implemented
- [ ] Tests pass (characterization + unit)
- [ ] ArchUnit rules pass
- [ ] No performance regression
- [ ] All invariant checks pass

---

## Blue Agent (Architect) Review

### Mandate
- SOLID principles compliance
- Design patterns appropriateness
- Clean Architecture adherence
- Dependency Inversion Principle (DIP)

### Analysis

#### SOLID Violations Being Addressed

Phase 3 directly addresses these documented violations:

| Violation | Type | File | Phase 3 Impact |
|-----------|------|------|----------------|
| **DIP-002** | P1 | `domain/v2/*.java` | All JPA entities → pure domain |
| **SRP-008** | P2 | Anemic domain entities | Rich domain behavior |
| **DIP-007** | P2 | Repository interfaces | Port/Adapter completion |

#### Architectural Assessment

**Current State (Before Phase 3):**
```
domain/v2/
├── GameCharacter.java        @Entity, @Id, @Column annotations
├── CharacterEquipment.java    @Entity, JPA dependencies
├── CharacterLike.java         @Entity, anemic (0 behavior methods)
└── DonationOutbox.java        @Entity, SHA-256 hash logic
```

**Target State (After Phase 3):**
```
domain/model/              # Pure Java (no jakarta.persistence.*)
├── GameCharacter.java     Pure domain with business logic
├── CharacterEquipment.java Pure domain with freshness behavior
├── CharacterLike.java      Pure domain with validation
└── CharacterId.java       Value object (type-safe ID)

infrastructure/persistence/  # JPA entities (implementations)
├── GameCharacterEntity.java    @Entity, maps to GameCharacter
├── CharacterEquipmentEntity.java
└── CharacterLikeEntity.java
```

#### Design Pattern Evaluation

**Pattern Being Applied:** Ports & Adapters (Hexagonal Architecture)

```java
// Domain Layer (Port) - Pure interface
public interface CharacterEquipmentRepository {
    Optional<CharacterEquipment> findByOcid(String ocid);
    boolean isFresh(String ocid, Duration ttl);  // Domain concept
}

// Infrastructure Layer (Adapter) - JPA implementation
@Repository
public class CharacterEquipmentRepositoryImpl
    implements CharacterEquipmentRepository {

    private final SpringDataCharacterEquipmentRepository jpaRepo;

    @Override
    public Optional<CharacterEquipment> findByOcid(String ocid) {
        return jpaRepo.findById(ocid)
            .map(CharacterEquipmentEntity::toDomain);
    }
}
```

**Assessment:** ✅ Appropriate - Standard DDD pattern

#### Rich Domain Behavior Assessment

**Current Rich Domain (Good Example):**
```java
// GameCharacter.java - Already has rich behavior
public boolean isActive() {
    return this.updatedAt != null
        && this.updatedAt.isAfter(LocalDateTime.now().minusDays(ACTIVE_DAYS_THRESHOLD));
}

public void validateOcid() {
    if (this.ocid == null || this.ocid.isBlank()) {
        throw new InvalidCharacterStateException("OCID은 필수입니다");
    }
}
```

**Anemic Domain (Needs Enhancement):**
```java
// CharacterEquipment.java - Currently just data
// Target: Add methods like:
// - boolean isExpired(Duration ttl)
// - boolean needsRefresh(Duration refreshThreshold)
// - DecompressedContent getDecompressedContent()
```

### Risks Identified

| Risk | Severity | Impact | Mitigation |
|------|----------|--------|------------|
| **Incomplete extraction** | MEDIUM | DIP violation remains | ArchUnit rule enforcement |
| **Behavior left in services** | MEDIUM | Anemic domain persists | Code review checklist |
| **Cyclic dependencies** | LOW | Compile errors | ArchUnit NO_CYCLES rule |
| **Over-extraction** | LOW | Unnecessary complexity | Apply YAGNI principle |

### Recommendations

1. **Start with Equipment Domain** - Simplest case, validates pattern
2. **Characterization tests first** - Capture behavior before moving
3. **Parallel run strategy** - Keep old code during transition
4. **Incremental migration** - One entity at a time, verify after each

### Blue Agent Vote

**DECISION:** ✅ **PASS - WITH CONDITIONS**

**Rationale:**
- Clean Architecture foundation established in Phase 2
- Domain extraction directly addresses DIP violations
- Rich domain pattern proven (GameCharacter already has 4 behavior methods)
- Ports & Adapters pattern is industry best practice

**Conditions:**
1. Characterization tests MUST be written before any code moves
2. ArchUnit rules MUST enforce domain isolation (no framework deps)
3. Each slice MUST pass all tests before proceeding to next
4. Performance baseline MUST be verified after each slice

**Confidence Level:** HIGH (85%)

---

## Green Agent (Performance) Review

### Mandate
- No regression on hot paths
- RPS >= 965 maintained
- p99 latency <= 214ms maintained
- Memory efficiency preserved

### Analysis

#### Hot Path Impact Assessment

**Hot Paths Affected by Phase 3:**

| Hot Path | Current Performance | Phase 3 Change | Risk Level |
|----------|---------------------|----------------|------------|
| **TieredCache.get()** | <5ms L1 HIT, <20ms L2 HIT | None (cache layer unchanged) | LOW |
| **SingleFlightExecutor** | 99% duplicate reduction | None (executor unchanged) | LOW |
| **StreamingParser** | 300KB JSON, streaming | None (parser unchanged) | LOW |
| **ProbabilityConvolver** | O(slots × target × K) | **Domain service extraction** | MEDIUM |
| **Async Pipeline** | Non-blocking, 30s timeout | None (service layer unchanged) | LOW |

#### Performance Critical: ProbabilityConvolver

**Current Location:** `service/v2/cube/component/ProbabilityConvolver.java`

**Current State:**
```java
@Component  // Spring-managed
public class ProbabilityConvolver {
    private final LogicExecutor executor;

    public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
        return executor.execute(() -> doConvolveWithClamp(...), context);
    }

    // Kahan Summation for precision
    private void validateInvariants(DensePmf pmf) {
        double sum = pmf.totalMassKahan();  // DoD 1e-12
        // ...
    }
}
```

**Target State (Domain Service):**
```java
// domain/service/ProbabilityConvolver.java
public class ProbabilityConvolver {  // NO @Component
    // Pure business logic - no Spring dependencies

    public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
        // Direct implementation - no executor wrapper
        return doConvolveWithClamp(slotPmfs, target, enableTailClamp);
    }

    // Kahan Summation preserved (CRITICAL)
    private void validateInvariants(DensePmf pmf) {
        double sum = pmf.totalMassKahan();
        // ...
    }
}
```

**Performance Consideration:**
- ✅ Kahan Summation algorithm unchanged (precision preserved)
- ✅ O(slots × target × K) complexity unchanged
- ✅ LogicExecutor wrapper removed (may improve performance slightly)
- ⚠️ JIT inlining may differ for domain service vs @Component

#### Memory Allocation Analysis

**Current:**
```java
@Service
public class EquipmentService {
    private final LogicExecutor executor;  // Spring singleton

    public CompletableFuture<TotalExpectationResponse> calculate(...) {
        // Uses executor.execute()
    }
}
```

**After Phase 3:**
```java
// Application service (thin orchestration)
@Service
public class EquipmentApplicationService {
    private final EquipmentDomainService domainService;  // Pure domain
    private final LogicExecutor executor;  // Spring-managed

    public CompletableFuture<TotalExpectationResponse> calculate(...) {
        return executor.execute(() -> domainService.calculate(...), context);
    }
}

// Domain service (pure business logic)
public class EquipmentDomainService {
    // No Spring dependencies
    // Direct method calls
}
```

**Memory Impact:** NEUTRAL
- Executor still Spring-managed (application layer)
- Domain service is stateless (no instance fields)
- JIT will inline domain service calls

#### JIT Compilation Considerations

**Concern:** Will removing `@Component` affect JIT optimization?

**Analysis:**
- Spring `@Component` adds proxy wrapper (CGLIB)
- Pure domain class has no proxy overhead
- JIT can inline pure methods more easily
- **Expected:** Slight performance IMPROVEMENT, not regression

#### Baseline Metrics (from Phase 0)

| Metric | Baseline | Target | Phase 3 Requirement |
|--------|----------|--------|---------------------|
| **RPS** | 965 | >= 965 | No regression allowed |
| **p99 Latency** | 214ms | <= 214ms | No regression allowed |
| **p50 Latency** | 95ms | < 100ms | No regression allowed |
| **Test Duration** | 38s | < 45s | +7s tolerance for new tests |

### Risks Identified

| Risk | Severity | Impact | Mitigation |
|------|----------|--------|------------|
| **JIT warmup difference** | LOW | Initial latency higher | Warmup tests before measurement |
| **Additional object creation** | LOW | GC pressure | Domain objects are short-lived |
| **Method call overhead** | LOW | +1-2ms per call | JIT will inline (verify with JMH) |
| **Kahan Summation regression** | CRITICAL | Precision loss | Characterization test with golden file |

### Recommendations

1. **JMH Benchmark before/after** - Quantify any performance change
2. **Warmup period in tests** - 10k iterations before measurement
3. **Preserve Kahan Summation exactly** - No algorithm changes allowed
4. **Monitor JIT compilation** - Check if domain methods are inlined

### Green Agent Vote

**DECISION:** ✅ **PASS - WITH VERIFICATION**

**Rationale:**
- Domain extraction should NOT affect hot paths (TieredCache, SingleFlight, Parser)
- Kahan Summation algorithm preserved (precision contract maintained)
- Removing Spring proxy may actually IMPROVE performance
- LogicExecutor pattern maintained at application layer

**Verification Required:**
1. JMH benchmark of ProbabilityConvolver before/after
2. Load test after each slice (verify RPS >= 965)
3. p99 latency measurement after Calculator slice
4. Heap dump analysis (verify memory neutrality)

**Confidence Level:** HIGH (90%)

---

## Yellow Agent (QA) Review

### Mandate
- Test coverage maintained or improved
- Characterization tests sufficient
- Flaky test prevention
- Test execution time controlled

### Analysis

#### Current Test Inventory (from Phase 0)

| Category | Count | Status |
|----------|-------|--------|
| **Unit Tests** | ~100 | Healthy |
| **Integration Tests** | 37 | Testcontainers-based |
| **Chaos Tests** | 20 | Nightmare N01-N18 |
| **Concurrency Tests** | 3 | Thread pool tests |
| **Graceful Shutdown** | 7 | Phase ordering tests |
| **TOTAL** | 934 | 100% pass rate |

#### Characterization Test Requirement

**Status:** ❌ **MISSING** (Phase 0 identified as P0 blocker)

**What's Needed:**

Characterization tests capture the EXACT current behavior before refactoring. They serve as a safety net - if refactoring breaks behavior, characterization tests fail.

```java
// Example: Characterization test for GameCharacter
@Test
@Tag("characterization")
void gameCharacter_shouldDetectActiveCharacterCorrectly() {
    // Given
    GameCharacter character = new GameCharacter("testUser", "testOcid");
    character.setUpdatedAt(LocalDateTime.now().minusDays(15));  // 15 days ago

    // When
    boolean isActive = character.isActive();

    // Then
    assertThat(isActive).isTrue();  // <30 days = active
}

@Test
@Tag("characterization")
void gameCharacter_shouldDetectInactiveCharacterCorrectly() {
    // Given
    GameCharacter character = new GameCharacter("testUser", "testOcid");
    character.setUpdatedAt(LocalDateTime.now().minusDays(35));  // 35 days ago

    // When
    boolean isActive = character.isActive();

    // Then
    assertThat(isActive).isFalse();  // >30 days = inactive
}
```

**Characterization Tests Needed for Phase 3:**

| Entity | Methods to Characterize | Est. Tests | Priority |
|--------|------------------------|------------|----------|
| **GameCharacter** | `isActive()`, `needsBasicInfoRefresh()`, `validateOcid()`, `like()` | 8 | P0 |
| **CharacterEquipment** | `isExpired()`, `isFresh()`, `hasData()`, `updateData()` | 10 | P0 |
| **CharacterLike** | Factory methods, validation | 6 | P1 |
| **ProbabilityConvolver** | `convolveAll()`, Kahan precision | 12 | P0 (CRITICAL) |

**Total Characterization Tests Needed:** ~36

#### Repository Adapter Test Strategy

**New Tests Required:**

Each repository adapter (infrastructure layer) needs:

```java
// CharacterEquipmentRepositoryImplTest.java
@Test
void domainEntity_shouldMapFromJpaEntityCorrectly() {
    // Given
    CharacterEquipmentEntity jpaEntity = createTestEntity();

    // When
    CharacterEquipment domain = jpaEntity.toDomain();

    // Then - EXACT field mapping
    assertThat(domain.getOcid()).isEqualTo(jpaEntity.getOcid());
    assertThat(domain.getJsonContent()).isEqualTo(jpaEntity.getJsonContent());
    assertThat(domain.getUpdatedAt()).isEqualTo(jpaEntity.getUpdatedAt());
}

@Test
void domainEntity_shouldMapToJpaEntityCorrectly() {
    // Given
    CharacterEquipment domain = createTestDomain();

    // When
    CharacterEquipmentEntity jpaEntity = CharacterEquipmentEntity.from(domain);

    // Then - EXACT field mapping
    assertThat(jpaEntity.getOcid()).isEqualTo(domain.getOcid());
    // ... all fields verified
}
```

**Mapping Tests:** 2 per entity (toDomain, fromEntity) × 4 entities = **8 tests**

#### Flaky Test Prevention

**Risk:** Repository adapter tests may be flaky due to Testcontainers resource contention

**Mitigation (from Phase 0):**
- `maxParallelForks=1` (already configured)
- Fixed ports for Testcontainers (MySQL, Redis)
- `@Tag("integration")` for isolation

**Test Duration Impact:**

| Category | Current | After Phase 3 | Change |
|----------|---------|---------------|--------|
| **fastTest** | 38s | ~45s | +7s (characterization) |
| **integrationTest** | 3-5 min | ~5-7 min | +2 min (mapping tests) |
| **Full Suite** | 30-60 min | ~35-65 min | +5 min |

**Acceptable:** Yes (within tolerance)

### Risks Identified

| Risk | Severity | Impact | Mitigation |
|------|----------|--------|------------|
| **Missing characterization tests** | CRITICAL | Refactoring breaks behavior | BLOCKER - must write first |
| **Mapping bugs** | MEDIUM | Data corruption | Contract tests for all adapters |
| **Test duration blowout** | LOW | CI timeout | Parallelize unit tests |
| **Testcontainers flakiness** | LOW | Random failures | Sequential execution confirmed |

### Recommendations

1. **BLOCKER:** Write all 36 characterization tests BEFORE Phase 3 starts
2. **Contract-first:** Write repository interface tests before implementation
3. **Parallel execution:** Run characterization tests in parallel with unit tests
4. **Golden file tests:** For ProbabilityConvolver (verify precision)

### Yellow Agent Vote

**DECISION:** ❌ **BLOCK - ADD CHARACTERIZATION TESTS FIRST**

**Rationale:**
- Phase 0 identified characterization tests as P0 blocker
- Phase 3 CANNOT proceed without behavior safety net
- 36 characterization tests estimated at 2-3 days work
- Small investment for massive risk reduction

**Required Before Phase 3:**
1. ✅ Write 36 characterization tests (GameCharacter, Equipment, Like, Calculator)
2. ✅ Write 8 repository mapping tests
3. ✅ Run all tests and capture baseline (100% pass)
4. ✅ Document characterization test patterns in CLAUDE.md

**After Characterization Tests Complete:**
- Re-review Yellow agent assessment
- Expected vote: ✅ PASS

**Confidence Level:** HIGH (95%) - This is a well-understood blocker

---

## Purple Agent (Auditor) Review

### Mandate
- Audit trails preserved
- SHA-256 hashing intact
- Exception hierarchy unchanged
- Data integrity verified

### Analysis

#### Exception Hierarchy Impact

**Requirement:** NO CHANGES to exception hierarchy during Phase 3

**Assessment:** ✅ **NO IMPACT**

**Reason:**
- Domain extraction doesn't change exception types
- Domain exceptions already defined (e.g., `InvalidCharacterStateException`)
- New domain layer doesn't introduce new exceptions
- `CircuitBreakerIgnoreMarker` / `CircuitBreakerRecordMarker` unchanged

**Verification:**
```bash
# Before Phase 3
grep -r "CircuitBreakerIgnoreMarker" src/main/java/maple/expectation/global/error/exception/ | wc -l
# Expected: 26 (all ClientBaseException subclasses)

# After Phase 3 - MUST BE SAME
grep -r "CircuitBreakerIgnoreMarker" src/main/java/maple/expectation/global/error/exception/ | wc -l
# Expected: 26 (NO CHANGE)
```

#### SHA-256 Hash Integrity

**Requirement:** DonationOutbox SHA-256 hash logic immutable

**Assessment:** ✅ **NO IMPACT**

**Reason:**
- `DonationOutbox.java` is NOT in Phase 3 scope
- SHA-256 computation is in infrastructure layer (not extracted)
- Phase 3 slices: Equipment, Character, Calculator, Like (excludes Donation)

**Evidence from Audit Baseline:**
```java
// DonationOutbox.java - NOT TOUCHED in Phase 3
private static String computeContentHash(String reqId, String type, String payload) {
    MessageDigest digest = getSha256Digest();
    byte[] hash = digest.digest((reqId + "|" + type + "|" + payload).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
}
```

#### Kahan Summation Precision

**Requirement:** Kahan Summation algorithm unchanged, DoD 1e-12 maintained

**Assessment:** ⚠️ **REQUIRES VERIFICATION**

**Slice 3 (Calculator) affects:** `ProbabilityConvolver.java`

**Current Implementation:**
```java
// ProbabilityConvolver.java:64-68
public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
    return executor.execute(
        () -> doConvolveWithClamp(slotPmfs, target, enableTailClamp),
        TaskContext.of("Convolver", "ConvolveAll", "target=" + target)
    );
}

// ProbabilityConvolver.java:131-142
private void validateInvariants(DensePmf pmf) {
    double sum = pmf.totalMassKahan();  // ← Kahan Summation call
    if (Math.abs(sum - 1.0) > MASS_TOLERANCE) {
        throw new ProbabilityInvariantException("질량 보존 위반: Σp=" + sum);
    }
    // ...
}
```

**After Phase 3 (Domain Service):**
```java
// domain/service/ProbabilityConvolver.java
public class ProbabilityConvolver {
    // NO LogicExecutor wrapper - direct call

    public DensePmf convolveAll(List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
        // Direct implementation - LogicExecutor at application layer
        DensePmf result = doConvolveWithClamp(slotPmfs, target, enableTailClamp);
        validateInvariants(result);
        return result;
    }

    // Kahan Summation validation - MUST BE PRESERVED EXACTLY
    private void validateInvariants(DensePmf pmf) {
        double sum = pmf.totalMassKahan();  // ← This line UNCHANGED
        if (Math.abs(sum - 1.0) > MASS_TOLERANCE) {
            throw new ProbabilityInvariantException("질량 보존 위반: Σp=" + sum);
        }
        // ...
    }
}
```

**Verification Required:**
1. Characterization test with golden file (known input/output)
2. `pmf.totalMassKahan()` call path identical
3. `MASS_TOLERANCE = 1e-12` unchanged
4. `NEGATIVE_TOLERANCE = -1e-15` unchanged

#### Metric Names Stability

**Requirement:** Prometheus metric names unchanged (Grafana dashboards depend on these)

**Assessment:** ✅ **NO IMPACT**

**Reason:**
- Domain extraction doesn't change metric emission
- Metrics are emitted at application layer (LogicExecutor, controllers)
- Domain layer has no metrics (pure business logic)

**Critical Metrics (from Audit Baseline):**
```promql
# These MUST NOT change
hikaricp_connections_active{pool="MySQLLockPool"}
cache_hits_total{layer="L1|L2"}
resilience4j_circuitbreaker_state{name="nexonApi"}
outbox_processed_total
```

**Verification:** No `@Timed`, `@Counted` annotations added to domain classes

### Risks Identified

| Risk | Severity | Impact | Mitigation |
|------|----------|--------|------------|
| **Kahan Summation regression** | CRITICAL | Precision loss, wrong calculations | Characterization test with golden file |
| **Exception hierarchy change** | LOW | Circuit breaker breaks | Code review checklist |
| **Metric name change** | LOW | Grafana dashboards break | ArchUnit rule (no @Timed in domain) |
| **SHA-256 hash change** | NONE | Not affected | DonationOutbox not in Phase 3 |

### Recommendations

1. **Characterization test with golden file** - For ProbabilityConvolver
2. **ArchUnit rule** - Prohibit metrics annotations in domain layer
3. **Code review checklist** - Verify exception types unchanged
4. **Audit trail verification** - Run N19 replay test after Calculator slice

### Purple Agent Vote

**DECISION:** ✅ **PASS - WITH CALCULATOR VERIFICATION**

**Rationale:**
- Exception hierarchy not affected (domain layer doesn't change exceptions)
- SHA-256 hashing not affected (DonationOutbox not in scope)
- Metric names stable (domain layer has no metrics)
- Kahan Summation requires characterization test

**Conditions:**
1. Characterization test for ProbabilityConvolver with golden file
2. ArchUnit rule: No `@Timed`/`@Counted` in domain package
3. After Calculator slice: Run N19 replay to verify calculations

**Confidence Level:** HIGH (90%)

---

## Red Agent (SRE) Review

### Mandate
- Resilience invariants maintained
- Timeout layering preserved
- Circuit breaker behavior intact
- Graceful shutdown functional

### Analysis

#### Timeout Layering Assessment

**Requirement:** 3s → 5s → 28s timeout formula unchanged

**Assessment:** ✅ **NO IMPACT**

**Reason:**
- Timeouts configured in `application.yml` (infrastructure)
- Domain extraction doesn't change timeout values
- `@Transactional`, `@TimeLimiter` at application layer (not domain)

**Current Configuration (from Resilience Baseline):**
```yaml
resilience4j:
  timelimiter:
    configs:
      default:
        timeoutDuration: 28s  # 3*(3s+5s) + 2*0.5s + 3s = 28s
```

**After Phase 3:** Same (domain layer has no timeout concerns)

#### Circuit Breaker Configuration

**Requirement:** Circuit breaker markers, thresholds unchanged

**Assessment:** ✅ **NO IMPACT**

**Reason:**
- Circuit breaker configured in `application.yml`
- `@CircuitBreaker` annotations at application layer
- Domain layer has no circuit breaker concerns

**Current State:**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        ignoreExceptions:
          - maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker
```

**After Phase 3:** Same (domain exceptions unchanged)

#### Graceful Shutdown

**Requirement:** 4-phase shutdown order maintained

**Assessment:** ✅ **NO IMPACT**

**Reason:**
- Graceful shutdown logic in `GracefulShutdownCoordinator` (infrastructure)
- Domain extraction doesn't affect shutdown sequence
- Phase ordering: MAX_VALUE-500 (buffer) → MAX_VALUE-1000 (main)

**Current 4-Phase Shutdown:**
```
Phase 1: Equipment async storage completion wait (20s timeout)
Phase 2: Local like buffer flush
Phase 3: DB final sync (distributed lock, 3s wait, 10s lease)
Phase 4: Backup data final storage
```

**After Phase 3:** Same (shutdown logic not in domain layer)

#### Connection Pool Configuration

**Requirement:** Pool sizes, rejection policies unchanged

**Assessment:** ✅ **NO IMPACT**

**Reason:**
- HikariCP configuration in `application.yml`
- Repository adapters use existing Spring Data JPA
- Domain extraction doesn't change pool settings

**Current Configuration:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 50
      connection-timeout: 3000ms
```

**After Phase 3:** Same (infrastructure concern)

#### Transaction Boundaries

**Requirement:** `@Transactional` boundaries correct

**Assessment:** ⚠️ **REQUIRES ATTENTION**

**Current State:**
```java
@Service
@Transactional
public class EquipmentService {
    // Transaction managed at service level
    public EquipmentResponse getEquipment(String ocid) {
        // ...
    }
}
```

**After Phase 3:**
```java
// Application service (transaction boundary)
@Service
public class EquipmentApplicationService {
    private final EquipmentDomainService domainService;
    private final EquipmentRepository repository;

    @Transactional  // ← Transaction at APPLICATION layer
    public EquipmentResponse getEquipment(String ocid) {
        // Domain service has NO transaction concerns
        return domainService.getEquipment(ocid, repository);
    }
}

// Domain service (pure business logic - no transaction)
public class EquipmentDomainService {
    // Pure business logic - no @Transactional
    public EquipmentResponse getEquipment(String ocid, EquipmentRepository repository) {
        // ...
    }
}
```

**Verification Required:**
1. `@Transactional` at application layer (not domain)
2. Transaction boundary tests (rollback verification)
3. Outbox pattern still works with new structure

### Risks Identified

| Risk | Severity | Impact | Mitigation |
|------|----------|--------|------------|
| **Transaction boundary wrong** | MEDIUM | Data inconsistency | Integration tests with rollback |
| **Circuit breaker not triggered** | LOW | No fail-fast | ArchUnit rule + integration test |
| **Graceful shutdown broken** | LOW | Data loss on deploy | Shutdown phase test |
| **Connection leak** | LOW | Pool exhaustion | HikariCP metrics monitoring |

### Recommendations

1. **Transaction boundary tests** - Verify rollback works after refactoring
2. **ArchUnit rule** - Prohibit `@Transactional` in domain package
3. **Integration test** - Circuit breaker still triggers correctly
4. **Graceful shutdown test** - All 4 phases complete after refactoring

### Red Agent Vote

**DECISION:** ✅ **PASS - WITH TRANSACTION VERIFICATION**

**Rationale:**
- Timeouts, circuit breaker, shutdown all infrastructure concerns (unchanged)
- Transaction boundaries moving to application layer (correct pattern)
- Connection pools unchanged (repository adapters use existing infrastructure)

**Conditions:**
1. ArchUnit rule: No `@Transactional` in domain package
2. Integration test: Transaction rollback works
3. Graceful shutdown test: All 4 phases complete
4. Circuit breaker test: Still triggers on ServerBaseException

**Confidence Level:** HIGH (85%)

---

## Council Deliberation Summary

### Agent Votes

| Agent | Vote | Conditions | Confidence |
|-------|------|------------|------------|
| **Blue (Architect)** | ✅ PASS | Characterization tests, ArchUnit rules | 85% |
| **Green (Performance)** | ✅ PASS | JMH benchmark, load test | 90% |
| **Yellow (QA)** | ❌ BLOCK | Write 36 characterization tests FIRST | 95% |
| **Purple (Auditor)** | ✅ PASS | Calculator golden file test | 90% |
| **Red (SRE)** | ✅ PASS | Transaction boundary tests | 85% |

### Consensus Status

**Current Status:** ❌ **NOT UNANIMOUS - YELLOW AGENT BLOCKING**

**Blocker:** Characterization tests not written (P0 from Phase 0)

### Blocking Issue Resolution

**Issue:** Phase 0 identified characterization tests as P0 blocker, but they haven't been written yet.

**Effort Estimate:** 2-3 days for 36 characterization tests

**Recommended Action:**
1. Write characterization tests (Yellow agent owns this)
2. Re-run Council review after tests complete
3. Expected outcome: Unanimous PASS

---

## Risk Register (Phase 3 Specific)

| # | Risk | Agent | Severity | Mitigation | Status |
|---|------|-------|----------|------------|--------|
| 1 | Missing characterization tests | Yellow | **P0** | Write 36 tests before Phase 3 | ❌ BLOCKING |
| 2 | Kahan Summation regression | Purple | **P0** | Golden file test for Calculator | ⚠️ Needs Test |
| 3 | Transaction boundary wrong | Red | P1 | Integration tests with rollback | ⚠️ Needs Test |
| 4 | Performance regression | Green | P1 | JMH benchmark before/after | ⚠️ Needs Setup |
| 5 | Domain-JPA cyclic dependency | Blue | P1 | ArchUnit rule enforcement | ✅ Rule Ready |

---

## Mitigation Strategies

### Before Phase 3 Begins

| Task | Owner | Effort | Exit Criteria |
|------|-------|--------|---------------|
| **Write 36 characterization tests** | Yellow | 2-3 days | 100% pass, documented |
| **Create ArchUnit rules** | Blue | 4 hours | Domain isolation enforced |
| **Set up JMH benchmarks** | Green | 4 hours | Baseline captured |
| **Create golden file test** | Purple | 2 hours | Calculator precision verified |
| **Write transaction boundary tests** | Red | 4 hours | Rollback verified |

**Total Prep Time:** 3-4 days

### During Phase 3 Execution

| Per-Slice Checkpoint | Verification | Owner |
|----------------------|--------------|-------|
| **After code move** | Characterization tests pass | Yellow |
| **After implementation** | ArchUnit rules pass | Blue |
| **After tests** | No performance regression | Green |
| **After Calculator slice** | Kahan Summation verified | Purple |
| **After each slice** | Transaction boundary works | Red |

---

## Final Council Recommendation

### Phase 3 Readiness: ❌ **NOT READY**

**Blocker:** Characterization tests not written

### Action Plan

**Step 1:** Write 36 characterization tests (Yellow agent)
- GameCharacter: 8 tests
- CharacterEquipment: 10 tests
- CharacterLike: 6 tests
- ProbabilityConvolver: 12 tests

**Step 2:** Set up mitigation infrastructure
- ArchUnit rules for domain isolation
- JMH benchmarks for hot paths
- Transaction boundary tests
- Golden file test for Calculator

**Step 3:** Re-run Council review
- All agents re-assess after prep complete
- Expected outcome: Unanimous PASS

**Step 4:** Begin Phase 3 execution
- Start with Equipment domain (simplest)
- Follow slice-by-slice approach
- Verify all checkpoints after each slice

### Estimated Timeline

| Activity | Duration | Start | End |
|----------|----------|-------|-----|
| **Characterization tests** | 3 days | - | Day 3 |
| **Mitigation infrastructure** | 1 day | Day 4 | Day 4 |
| **Council re-review** | 1 day | Day 5 | Day 5 |
| **Phase 3 execution** | 4 weeks | Day 6 | Day 34 |

**Total:** 5 weeks (1 week prep + 4 weeks execution)

---

## Appendix A: Characterization Test Templates

### GameCharacter Characterization Tests

```java
@Tag("characterization")
class GameCharacterCharacterizationTest {

    @Test
    void character_shouldBeActive_whenUpdatedWithin30Days() {
        // Given
        GameCharacter character = new GameCharacter("user", "ocid123");
        character.setUpdatedAt(LocalDateTime.now().minusDays(15));

        // When
        boolean result = character.isActive();

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void character_shouldNotBeActive_whenUpdatedOver30DaysAgo() {
        // Given
        GameCharacter character = new GameCharacter("user", "ocid123");
        character.setUpdatedAt(LocalDateTime.now().minusDays(31));

        // When
        boolean result = character.isActive();

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void character_shouldNeedRefresh_whenWorldNameIsNull() {
        // Given
        GameCharacter character = new GameCharacter("user", "ocid123");
        character.setWorldName(null);

        // When
        boolean result = character.needsBasicInfoRefresh();

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void character_shouldNeedRefresh_whenBasicInfoExpired() {
        // Given
        GameCharacter character = new GameCharacter("user", "ocid123");
        character.setBasicInfoUpdatedAt(LocalDateTime.now().minusMinutes(16));

        // When
        boolean result = character.needsBasicInfoRefresh();

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void character_shouldNotNeedRefresh_whenBasicInfoFresh() {
        // Given
        GameCharacter character = new GameCharacter("user", "ocid123");
        character.setWorldName("Scania");
        character.setBasicInfoUpdatedAt(LocalDateTime.now().minusMinutes(10));

        // When
        boolean result = character.needsBasicInfoRefresh();

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void character_shouldIncrementLikeCount_whenLikeCalled() {
        // Given
        GameCharacter character = new GameCharacter("user", "ocid123");
        long initialCount = character.getLikeCount();

        // When
        character.like();

        // Then
        assertThat(character.getLikeCount()).isEqualTo(initialCount + 1);
    }

    @Test
    void character_shouldThrow_whenValidatingNullOcid() {
        // Given
        GameCharacter character = new GameCharacter("user", null);

        // When/Then
        assertThatThrownBy(() -> character.validateOcid())
            .isInstanceOf(InvalidCharacterStateException.class)
            .hasMessageContaining("OCID은 필수입니다");
    }

    @Test
    void character_shouldThrow_whenValidatingBlankOcid() {
        // Given
        GameCharacter character = new GameCharacter("user", "   ");

        // When/Then
        assertThatThrownBy(() -> character.validateOcid())
            .isInstanceOf(InvalidCharacterStateException.class);
    }
}
```

### ProbabilityConvolver Characterization Tests

```java
@Tag("characterization")
class ProbabilityConvolverCharacterizationTest {

    @Test
    void convolveAll_shouldPreserveMass() {
        // Given
        ProbabilityConvolver convolver = new ProbabilityConvolver(executor);
        List<SparsePmf> slotPmfs = createTestSlots();

        // When
        DensePmf result = convolver.convolveAll(slotPmfs, 12, true);

        // Then - Kahan Summation verification
        double totalMass = result.totalMassKahan();
        assertThat(totalMass).isEqualTo(1.0, within(1e-12));
    }

    @Test
    void convolveAll_shouldMatchGoldenFile() throws Exception {
        // Given
        ProbabilityConvolver convolver = new ProbabilityConvolver(executor);
        List<SparsePmf> slotPmfs = createTestSlots();
        Path goldenFile = Path.of("src/test/resources/golden/calculator-golden.json");

        // When
        DensePmf result = convolver.convolveAll(slotPmfs, 12, true);

        // Then - Compare with golden file
        String actual = objectMapper.writeValueAsString(result);
        String expected = Files.readString(goldenFile);
        assertThat(actual).isEqualTo(expected);
    }

    // Additional tests...
}
```

---

## Appendix B: ArchUnit Rules for Phase 3

```java
@AnalyzeClasses(packages = "maple.expectation")
public class Phase3ArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_LAYER_PURE = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "..springframework..",
            "..jakarta.persistence..",
            "..hibernate.."
        );

    @ArchTest
    static final ArchRule NO_TRANSACTIONAL_IN_DOMAIN = noClasses()
        .that().resideInAPackage("..domain..")
        .should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule NO_METRICS_IN_DOMAIN = noClasses()
        .that().resideInAPackage("..domain..")
        .should().beAnnotatedWith任何(Micrometer metrics annotation);

    @ArchTest
    static final ArchRule INFRASTRUCTURE_IMPLEMENTS_DOMAIN = classes()
        .that().resideInAPackage("..infrastructure.persistence..")
        .should().implementInterfacesThat()
        .resideInAPackage("..domain.repository..");

    @ArchTest
    static final ArchRule NO_CYCLES_BETWEEN_LAYERS = slices()
        .matching("maple.expectation.(*)..")
        .should().beFreeOfCycles();
}
```

---

## Appendix C: Transaction Boundary Test Template

```java
@SpringBootTest
@Transactional
class TransactionBoundaryTest {

    @Autowired
    private EquipmentApplicationService applicationService;

    @Test
    void transaction_shouldRollback_onException() {
        // Given
        String ocid = "test-ocid";

        // When/Then
        assertThatThrownBy(() -> applicationService.updateWithException(ocid))
            .isInstanceOf(ServerBaseException.class);

        // Verify rollback - entity not persisted
        Optional<CharacterEquipment> result = repository.findByOcid(ocid);
        assertThat(result).isEmpty();
    }

    @Test
    void transaction_shouldCommit_onSuccess() {
        // Given
        String ocid = "test-ocid";

        // When
        applicationService.updateSuccessfully(ocid);

        // Verify commit - entity persisted
        Optional<CharacterEquipment> result = repository.findByOcid(ocid);
        assertThat(result).isPresent();
    }
}
```

---

*Phase 3 Agent Review generated by 5-Agent Council*
*Date: 2026-02-07*
*Next Review: After characterization tests complete*
*Status: BLOCKED - Write characterization tests first*

---

## References

- [REFACTOR_PLAN.md](REFACTOR_PLAN.md) - Full Phase 3 plan
- [PHASE0_SUMMARY.md](PHASE0_SUMMARY.md) - Baseline with characterization test blocker
- [PHASE2_SUMMARY.md](PHASE2_SUMMARY.md) - Foundation completion
- [SOLID_VIOLATIONS.md](SOLID_VIOLATIONS.md) - DIP-002 being addressed
- [AUDIT_BASELINE.md](AUDIT_BASELINE.md) - Exception hierarchy, Kahan Summation
- [RESILIENCE_BASELINE.md](RESILIENCE_BASELINE.md) - Timeout, circuit breaker, shutdown
- [PERFORMANCE_BASELINE.md](PERFORMANCE_BASELINE.md) - RPS 965, p99 214ms
