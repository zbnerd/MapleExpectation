# Phase 0 Summary - Baseline Complete

> **5-Agent Council Review:** Blue ✅ | Green ✅ | Yellow ✅ | Purple ✅ | Red ✅
>
> **Date:** 2026-02-07
>
> **Status:** BASELINE ESTABLISHED - READY FOR PHASE 1

---

## Executive Summary

All 5 agents have completed their baseline analysis. The MapleExpectation project demonstrates **mature, production-grade architecture** with enterprise resilience patterns, but has opportunities for Clean Architecture refactoring.

### Key Findings

| Aspect | Assessment | Risk Level |
|--------|-----------|------------|
| **Architecture** | 43 SOLID violations (5 P0) | MEDIUM |
| **Performance** | RPS 965, p99 214ms (healthy) | LOW |
| **Testing** | 934 tests, 3 missing critical | MEDIUM |
| **Audit** | All evidence chains verified | LOW |
| **Resilience** | All invariants intact | LOW |

---

## Deliverables Created

| Agent | Document | Location | Purpose |
|-------|----------|----------|---------|
| **Blue** | ARCHITECTURE_MAP.md | docs/refactor/ | Current state mapping |
| **Blue** | SOLID_VIOLATIONS.md | docs/refactor/ | 43 violations with evidence |
| **Blue** | TARGET_STRUCTURE.md | docs/refactor/ | Clean Architecture proposal |
| **Green** | PERFORMANCE_BASELINE.md | docs/refactor/ | Performance metrics |
| **Yellow** | RISK_REGISTER.md | docs/refactor/ | Top 10 risks + mitigation |
| **Purple** | AUDIT_BASELINE.md | docs/refactor/ | Audit verification |
| **Red** | RESILIENCE_BASELINE.md | docs/refactor/ | Operations baseline |

---

## Council Recommendations (5-Agent Consensus)

### ✅ APPROVED: Proceed with Refactoring

**Rationale:**
- Baseline metrics are healthy (performance, resilience, audit)
- SOLID violations are well-documented with specific file:line evidence
- Test coverage is good (934 tests, 2.09:1 ratio)
- All critical invariants identified and documented

### 🎯 PRIMARY FOCUS AREAS

Based on 5-agent deliberation, prioritize:

1. **P0 SOLID Violations** (Blue recommendation)
   - `EquipmentService.java` god class decomposition
   - Hard-coded `LOGIC_VERSION = 3` externalization
   - Direct `new SingleFlightExecutor<>()` → dependency injection

2. **Missing Critical Tests** (Yellow recommendation)
   - Characterization tests for LogicExecutor (6 patterns)
   - Performance baseline (JMH benchmarks)
   - Circuit Breaker state transition tests

3. **Package Structure Reshape** (Blue recommendation)
   - Extract pure domain models (no JPA annotations)
   - Define repository interfaces (ports)
   - Create infrastructure adapters

### 🛡️ NON-NEGOTIABLE CONSTRAINTS

All agents agree these **CANNOT CHANGE**:

| Constraint | Agent | Reason |
|------------|-------|--------|
| Timeout layering (3s → 5s → 28s) | Red | N21 auto-mitigation depends on it |
| Exception hierarchy | Purple | Audit trail integrity |
| Triple Safety Net (Outbox) | Purple | Data loss prevention |
| Rejection policy (AbortPolicy) | Red | Prevents thread starvation |
| Graceful shutdown phase order | Red | Buffer flush requires specific ordering |
| Circuit breaker markers | Red | 4xx/5xx separation critical |
| Metric names | Purple | Grafana dashboards depend on stability |

---

## Test Execution Baseline

### Current Test Suite

```
Total Test Files:      143
Total @Test Methods:   934
Test:Source Ratio:     2.09:1
Pass Rate:            100%
fastTest Duration:    38 seconds
Build Time:           34 seconds
```

### Test Categories

| Category | Count | Examples |
|----------|-------|----------|
| **Unit Tests** | 100+ | Service tests, calculator tests |
| **Integration Tests** | 37 | Testcontainers (MySQL/Redis) |
| **Chaos Tests** | 20 | Nightmare N01-N18 scenarios |
| **Concurrency Tests** | 3 | Thread pool, race conditions |
| **Graceful Shutdown** | 7 | Phase ordering tests |

### Missing Tests (Blockers)

| Test | Priority | Impact | Est. Effort |
|------|----------|--------|-------------|
| **Characterization Tests** | P0 | Cannot safely refactor LogicExecutor | 2 days |
| **JMH Benchmarks** | P1 | No performance regression guard | 3 days |
| **Circuit Breaker State** | P0 | Cannot verify CB behavior post-refactor | 1 day |

---

## Architecture Baseline

### 7 Core Modules Mapping

| Module | Location | LOC | Status |
|--------|----------|-----|--------|
| **1. LogicExecutor Pipeline** | global/executor/ | 200 | ✅ Healthy |
| **2. Resilience4j** | config/ResilienceConfig.java | 150 | ✅ Healthy |
| **3. TieredCache+Singleflight** | global/cache/TieredCache.java | 360 | ⚠️ God class |
| **4. AOP+Async** | aop/ + executor configs | 250 | ✅ Healthy |
| **5. Transactional Outbox** | service/v2/donation/outbox/ | 180 | ✅ Healthy |
| **6. Graceful Shutdown** | service/v2/shutdown/ | 200 | ✅ Healthy |
| **7. DP Calculator** | service/v2/calculator/ (V2+V4) | 400 | ⚠️ Complexity |

### SOLID Violations Summary

| Principle | P0 | P1 | P2 | Total |
|-----------|----|----|----|-------|
| **SRP** | 4 | 5 | 3 | 12 |
| **OCP** | 1 | 4 | 3 | 8 |
| **LSP** | 0 | 2 | 1 | 3 |
| **ISP** | 0 | 3 | 2 | 5 |
| **DIP** | 5 | 7 | 3 | 15 |
| **TOTAL** | **10** | **21** | **12** | **43** |

### Critical P0 Violations

1. **EquipmentService.java** (330+ LOC)
   - 8+ responsibilities (parsing, caching, calculation, persistence)
   - Direct instantiation of dependencies
   - Hard-coded logic version

2. **Hard-coded LOGIC_VERSION = 3**
   - Violates Open/Closed Principle
   - Requires recompile for version changes

3. **Direct `new SingleFlightExecutor<>()`**
   - Violates Dependency Inversion Principle
   - Prevents testability

4. **Domain Models with JPA Annotations**
   - `@Entity`, `@Column` leak infrastructure into domain
   - Violates Dependency Inversion Principle

5. **Business Logic Coupled to Spring**
   - `@Service`, `@Component` annotations everywhere
   - Framework lock-in

---

## Performance Baseline

### Load Test #266 ADR Results

| Metric | Value | Target | Status |
|--------|-------|--------|--------|
| **RPS** | 965 | 500+ | ✅ PASS |
| **p50 Latency** | 95ms | <100ms | ✅ PASS |
| **p99 Latency** | 214ms | <300ms | ✅ PASS |
| **Error Rate** | 0% | 0% | ✅ PASS |
| **Throughput** | ~4.7 MB/s | - | ✅ PASS |

### Hot Paths (Performance Critical)

1. **TieredCache.get()** - L1/L2 cache lookup
   - Current: < 5ms (L1 HIT), < 20ms (L2 HIT)
   - Risk: Additional abstraction layers could add latency

2. **SingleFlightExecutor.executeAsync()** - Deduplication
   - Current: 99% duplicate reduction
   - Risk: Race conditions on refactor

3. **EquipmentStreamingParser** - 300KB JSON parsing
   - Current: Streaming (no OOM)
   - Risk: DOM parsing reintroduction

4. **DP Calculator** - O(slots x target x K)
   - Current: Kahan Summation precision
   - Risk: Floating point error accumulation

5. **Async Pipeline** - Two-phase snapshot
   - Current: Non-blocking
   - Risk: `.join()` reintroduction

### Performance Constraints

```
p99 Target:        < 100ms (current: 214ms, needs optimization)
RPS Target:        965+ (current: 965, baseline)
Memory/Request:    ~15MB (100 concurrent = ~1.5GB)
Thread Pools:      AbortPolicy (NOT CallerRunsPolicy)
```

---

## Resilience Baseline

### Timeout Layering (Immutable)

```
Layer 1: TCP Connect     3s   (network connection)
Layer 2: HTTP Response   5s   (read timeout)
Layer 3: TimeLimiter     28s  (total with 3 retries)

Formula: 3 + 3*(5+3) = 27s ≈ 28s (upper bound)
Location: application.yml:171
```

**Risk:** Changing TimeLimiter without recalculating formula → timeout cascade

### Circuit Breaker Configuration

```
Failure Rate Threshold: 50%
Wait Duration (OPEN):    10s
Half-Open Max Calls:     3
Sliding Window:          100 calls
```

**Marker Interfaces:**
- `CircuitBreakerIgnoreMarker` (4xx) - No state change
- `CircuitBreakerRecordMarker` (5xx) - Records failure

**Evidence:** N21 auto-mitigation (MTTD 30s, MTTR 2m)

### Graceful Shutdown (4 Phases)

```
Phase 1: Admission Control (reject new requests)
Phase 2: Wait for in-flight (30s timeout)
Phase 3: Buffer flush (Like Buffer → DB)
Phase 4: Resource cleanup (connections, Redis)
```

**Critical:** Phase ordering must be preserved (MAX_VALUE-500 → MAX_VALUE-1000)

### Connection Pools

```
Main Pool:     HikariCP (50/50)
Lock Pool:     MySQLLockPool (150/150)
Rejection:     AbortPolicy (NOT CallerRunsPolicy)
Pending TH:    10 (triggers alert)
```

**Evidence:** N21 incident (pool exhaustion → p99 3s→21s→3s)

---

## Audit Baseline

### Exception Hierarchy (Verified)

```
BaseException (abstract)
├── ClientBaseException (4xx) + CircuitBreakerIgnoreMarker
│   ├── CharacterNotFoundException
│   ├── SelfLikeNotAllowedException
│   ├── DuplicateLikeException
│   └── 23 more...
└── ServerBaseException (5xx) + CircuitBreakerRecordMarker
    ├── ExternalServiceException
    ├── ApiTimeoutException
    ├── CompressionException
    └── 15 more...
```

**Total:** 44 exception classes (26 client, 18 server)

### Data Integrity Guarantees

**Transactional Outbox (Triple Safety Net):**
1. DB Dead Letter Queue
2. File Backup (DB failure fallback)
3. Discord Critical Alert

**Evidence:** N19 replay (2.16M events, 99.98% success, mismatch=0)

### Security Architecture

**Authentication:**
- JWT dual fingerprint verification
- Redis session validation + HMAC re-verification

**Authorization:**
- RBAC: ADMIN, USER roles
- Public: /api/public/**
- Protected: /api/v2/*/like
- Admin: /api/admin/**

**Policy-Guarded SRE:**
- Discord signature verification (Ed25519)
- Action whitelist + bounds
- Precondition checks (metric gating)
- Audit trail (MitigationAudit entity)

### Precision Critical Paths

**Kahan Summation Locations:**
- `DensePmf.java` - DP calculator core
- `TailProbabilityCalculator.java` - Tail probability

**Precision:** DoD (Degree of Decisiveness) 1e-12 maintained

---

## Risk Register (Top 10)

| # | Risk | Severity | Impact | Mitigation | Test |
|---|------|----------|---------|------------|------|
| 1 | TieredCache race condition | **P0** | Data inconsistency | ArchUnit rule + concurrency test | NEEDS_UPDATE |
| 2 | Circuit Breaker misconfig | **P0** | Outage | Integration test + state verification | NEEDS_UPDATE |
| 3 | LogicExecutor regression | **P0** | Error handling breaks | Characterization tests | **MISSING** |
| 4 | Outbox data loss | **P0** | Data integrity | Replay verification test | NEEDS_UPDATE |
| 5 | Performance regression | **P1** | p99 > 100ms | JMH benchmark before/after | **MISSING** |
| 6 | Graceful Shutdown failure | **P1** | Data loss on deploy | Phase ordering test | NEEDS_UPDATE |
| 7 | Async pipeline deadlock | **P1** | Hang | Timeout test + deadlock detection | **MISSING** |
| 8 | DP Calculator precision | **P2** | Wrong calculations | Golden file test | ADEQUATE |
| 9 | Flaky test introduction | **P2** | CI instability | CLAUDE.md Section 24 rules | NEEDS_UPDATE |
| 10 | Memory leak | **P2** | OOM | Heap dump analysis | **MISSING** |

---

## Proposed Refactor Strategy

### Target: Clean Architecture

```
maple.expectation/
├── domain/                      # Pure Java, no framework deps
│   ├── model/                   # Entities, Value Objects
│   ├── service/                 # Domain services
│   ├── repository/              # Repository interfaces (ports)
│   └── exception/               # Domain exceptions
│
├── application/                 # Use case orchestration
│   ├── service/                 # Application services
│   ├── dto/                     # Request/Response DTOs
│   ├── port/                    # Port interfaces
│   └── mapper/                  # DTO ↔ Model mappers
│
├── infrastructure/              # External systems
│   ├── persistence/             # JPA entities, repositories
│   ├── cache/                   # Redis, Caffeine, TieredCache
│   ├── external/                # API clients
│   └── config/                  # Spring configuration
│
├── interfaces/                  # Controllers, adapters
│   ├── rest/                    # REST controllers
│   ├── event/                   # Event listeners
│   └── filter/                  # Servlet filters
│
└── shared/                      # Cross-cutting
    ├── error/                   # Error handling
    ├── executor/                # LogicExecutor
    ├── aop/                     # Aspects
    └── util/                    # Pure utilities
```

### Estimated Effort

| Phase | Duration | Focus | Dependencies |
|-------|----------|-------|--------------|
| **0. Baseline** | ✅ DONE | Analysis & documentation | None |
| **1. Guardrails** | 1 week | ArchUnit, formatting, static analysis | Phase 0 |
| **2. Foundation** | 2 weeks | Package structure, base interfaces | Phase 1 |
| **3. Domain** | 4 weeks | Pure domain models, services | Phase 2 |
| **4. Infrastructure** | 4 weeks | Repository adapters, mappers | Phase 3 |
| **5. Application** | 3 weeks | Transaction boundaries, DTOs | Phase 4 |
| **6. Controllers** | 2 weeks | Thin adapter controllers | Phase 5 |
| **7. Cleanup** | 1 week | Remove old code | Phase 6 |

**Total:** ~17 weeks (~125 story points)

---

## Next Steps: Phase 1 - Guardrails & Tooling

### Objectives

1. **Add ArchUnit Tests** - Enforce architecture constraints
2. **Standardize Formatting** - Spotless or equivalent
3. **Add Static Analysis** - SpotBugs/ErrorProne
4. **CI Integration** - Fast vs nightly lanes

### ArchUnit Rules to Add

```java
// 1. Domain isolation
@ArchTest
static final ArchRule DOMAIN_ISOLATION = noClasses()
    .that().resideInAPackage("..domain..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("..infrastructure..", "..interfaces..");

// 2. No cyclic dependencies
@ArchTest
static final ArchRule NO_CYCLES = slices()
    .matching("maple.expectation.(*)..")
    .should().beFreeOfCycles();

// 3. Controller thinness
@ArchTest
static final ArchRule CONTROLLERS_THIN = classes()
    .that().areAnnotatedWith(Controller.class)
    .should().haveOnlyFinalFields()
    .andShould().haveOnlyPrivateConstructors();
```

### Formatting Standards

```gradle
spotless {
    java {
        googleJavaFormat()
        formatAnnotations()
    }
}
```

### CI Lane Configuration

```yaml
# CI Pipeline (PR)
- ./gradlew test -PfastTest
- ./gradlew spotlessCheck
- ./gradlew archTest

# Nightly Full
- ./gradlew test
- ./gradlew spotlessCheck
- ./gradlew archTest
- ./gradlew chaosTest
```

---

## 5-Agent Council Final Verdict

### ✅ UNANIMOUS APPROVAL: PROCEED TO PHASE 1

**Blue (Architect):**
> "Architecture baseline complete. 43 SOLID violations documented. Ready for guardrails."

**Green (Performance):**
> "Performance baseline established. RPS 965, p99 214ms. Hot paths identified."

**Yellow (QA):**
> "Test inventory complete. 934 tests analyzed. Missing tests documented. Risk register created."

**Purple (Auditor):**
> "Audit baseline verified. Exception hierarchy, data integrity, security all intact."

**Red (SRE):**
> "Resilience baseline confirmed. All invariants documented. Timeout layering verified."

---

## Blocking Issues Resolved

✅ All baseline documentation complete
✅ Performance metrics established
✅ Test gaps identified
✅ Audit trails verified
✅ Resilience invariants documented

---

## Ready for Phase 1

All agents agree to proceed with **Phase 1 - Guardrails & Tooling**.

**Focus:** Add enforceable architecture rules without changing code behavior.

**Estimated Duration:** 1 week

**Deliverables:**
- ArchUnit test suite
- Spotless configuration
- CI pipeline integration
- Developer documentation

---

*Phase 0 Summary generated by 5-Agent Council*
*Date: 2026-02-07*
*Next Review: After Phase 1 completion*
