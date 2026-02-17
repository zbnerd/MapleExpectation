# Multi-Module Refactoring Analysis Report

**Date:** 2026-02-16
**Status:** Pre-Planning Analysis
**Analyst:** Metis (Pre-Planning Consultant)
**Related Issues:** #282 (Multi-Module Refactoring), #283 (Scale-out Roadmap)

---

## Executive Summary

This report analyzes the current state of MapleExpectation's multi-module architecture and provides a comprehensive assessment for the upcoming major refactoring. The codebase currently consists of **5 modules** with **613 total Java files** across module-app (342 files), module-infra (177 files), module-core (59 files), and module-common (35 files).

**Key Finding:** The multi-module structure established in ADR-035 is largely in place, but **module-app remains bloated** with 342 files containing mixed concerns including infrastructure implementations that should reside in module-infra.

---

## 1. Current State Assessment

### 1.1 Module Overview

| Module | Java Files | Spring Annotations | Purpose (Declared) | Purpose (Actual) |
|--------|------------|-------------------|-------------------|------------------|
| **module-common** | 35 | 1 | Shared utilities, constants, base classes | Error handling, events, responses |
| **module-core** | 59 | 2 | Domain logic, DIP interfaces | Domain models, ports |
| **module-infra** | 177 | 70 | Infrastructure implementations | Repositories, Redis, MongoDB, config |
| **module-app** | 342 | 228 | Application layer | Controllers, services, BUT ALSO infrastructure |
| **module-chaos-test** | - | - | Chaos testing | Test-only module |

### 1.2 Module Dependency Graph (Current)

```
module-app
    ├── module-infra
    │   ├── module-core
    │   │   └── module-common
    │   └── module-common
    └── module-common

module-chaos-test (test-only, depends on all)
```

**Dependency Direction:** CORRECT (DIP principle followed)
- module-app → module-infra → module-core → module-common

---

## 2. Package Structure Analysis

### 2.1 module-app Package Breakdown

**Top-level packages (21 total):**

| Package | Description | Spring Components | Should Move To |
|---------|-------------|-------------------|----------------|
| `alert/` | Discord alert system | 3 | module-infra |
| `aop/` | AOP aspects | 5+ | module-infra |
| `application/` | Application services | 1 | KEEP (or create module-app-service) |
| `batch/` | Spring batch jobs | 1+ | module-infra |
| `config/` | Spring configuration | 30+ | module-infra |
| `controller/` | REST controllers | 8+ | KEEP in module-app |
| `dto/` | Data transfer objects | - | KEEP in module-app |
| `error/` | Global error handler | 1 | module-infra |
| `event/` | Event publishers | - | module-app or module-infra |
| `external/` | External API clients | 2+ | module-infra |
| `interfaces/` | Interface adapters | - | module-app |
| `lifecycle/` | Lifecycle management | - | module-infra |
| `monitoring/` | Monitoring, AI SRE | 50+ | module-infra or module-observability |
| `parser/` | Data parsing | - | module-app (domain-specific) |
| `provider/` | Data providers | 2+ | module-app (domain-specific) |
| `repository/` | **EMPTY** | 0 | DELETE (already in module-infra) |
| `scheduler/` | Scheduled tasks | 1+ | module-infra |
| `service/` | Business logic | 100+ | SPLIT (v2/v4/v5 analysis needed) |
| `util/` | Utilities | - | module-common or DELETE |
| `provider/` | Equipment providers | 2+ | module-app |

### 2.2 module-infra Package Structure

**Top-level packages:**

| Package | Description | Spring Components |
|---------|-------------|-------------------|
| `domain/repository/` | Repository interfaces | 9 interfaces |
| `domain/nexon/` | Nexon API data models | - |
| `domain/v2/` | JPA entities | 9 entities |
| `infrastructure/concurrency/` | Single-flight executors | 3 |
| `infrastructure/resilience/` | Resilience patterns | 7 |
| `infrastructure/filter/` | MDC filter | 1 |
| `infrastructure/external/` | External API infrastructure | - |
| `infrastructure/ratelimit/` | Rate limiting | 10+ |
| `infrastructure/event/` | MySQL health events | 2 |
| `infrastructure/cache/` | Caching infrastructure | 5+ |
| `infrastructure/mongodb/` | MongoDB integration | 2+ |
| `infrastructure/persistence/` | JPA persistence | 10+ |
| `infrastructure/queue/` | Queue implementations | 10+ |
| `infrastructure/executor/` | LogicExecutor | 10+ |
| `infrastructure/config/` | Configuration | 5+ |
| `infrastructure/security/` | Security filters, JWT | 10+ |
| `infrastructure/messaging/` | Redis messaging | - |
| `infrastructure/redis/` | Redis infrastructure | - |
| `infrastructure/lock/` | Distributed locking | - |
| `infrastructure/shutdown/` | Graceful shutdown | - |
| `application/` | Application services | 2+ |

### 2.3 module-core Package Structure

**Top-level packages:**

| Package | Description | Components |
|---------|-------------|------------|
| `domain/` | Pure domain models | ~20 classes |
| `domain/model/calculator/` | Calculator models | 3 |
| `domain/model/like/` | Like models | 2 |
| `domain/model/equipment/` | Equipment models | 2 |
| `domain/model/character/` | Character models | 2 |
| `domain/service/` | Domain services | 2 |
| `application/port/` | DIP ports (interfaces) | 7 |
| `error/` | Domain-specific errors | ~10 |

### 2.4 module-common Package Structure

**Top-level packages:**

| Package | Description | Components |
|---------|-------------|------------|
| `common/` | Common utilities | 2 |
| `error/` | Error handling (base exceptions, error codes) | ~30 |
| `event/` | Event interfaces | 3 |
| `response/` | Response DTOs | 1 |
| `shared/` | Shared utilities | - |

---

## 3. Dependency Mapping

### 3.1 Spring Framework Usage by Module

| Module | @Component | @Service | @Repository | @Configuration | @Controller/@RestController |
|--------|------------|---------|------------|----------------|---------------------------|
| **module-app** | 1 | 20+ | 0 | 30+ | 8+ |
| **module-infra** | 40+ | 5 | 10+ | 5+ | 0 |
| **module-core** | 0 | 0 | 0 | 0 | 0 |
| **module-common** | 0 | 0 | 0 | 0 | 0 |

**Key Observations:**
1. **module-core is framework-agnostic** (correct - no Spring annotations)
2. **module-common is mostly framework-agnostic** (1 annotation found)
3. **module-infra has infrastructure concerns** (correct)
4. **module-app has excessive infrastructure** (30+ @Configuration classes should be in module-infra)

### 3.2 Cross-Module Imports Analysis

**module-app → module-infra imports:**
```java
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.infrastructure.ratelimit.exception.RateLimitExceededException;
import maple.expectation.domain.repository.RedisBufferRepository;
```

**module-core → module-common imports:**
```java
import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.error.ErrorCode;
```

**Finding:** Dependency direction is correct (app → infra → core → common), but leakage exists.

### 3.3 Circular Dependency Analysis

**Current State:** NO circular dependencies detected at module level.

**ArchUnit Tests:**
- `no_cyclic_dependencies()` test exists but is **DISABLED** due to false positives from legitimate same-package dependencies
- The test catches 12,148+ violations which are mostly forward references within same package

**Recommendation:** Re-enable with more granular package slicing.

---

## 4. Risk Identification

### 4.1 Architecture Risks (P0 - Critical)

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **module-app contains infrastructure** | High | Certain | Move 30+ @Configuration classes to module-infra |
| **Service layer bloat** | High | High | Split v2/v4/v5 into separate modules |
| **Empty repository package** | Medium | Certain | Delete from module-app |
| **Monitoring logic in app module** | Medium | High | Move to module-infra or new module-observability |

### 4.2 Migration Risks (P1 - High)

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Breaking imports during move** | Compilation failures | Use IDE refactoring, run full test suite after each move |
| **Test coverage gaps** | Runtime errors | Add integration tests before moving |
| **Spring Bean resolution** | Runtime failures | Verify @ComponentScan excludes moved packages |
| **Configuration property binding** | Startup failures | Update @PropertySource paths |

### 4.3 Operational Risks (P2 - Medium)

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Increased build time** | Slower CI/CD | Parallelize module builds |
| **Dependency hell** | Version conflicts | Use Gradle version catalog |
| **Module boundary violations** | Architectural drift | Add ArchUnit tests for module boundaries |

---

## 5. Recommended Refactoring Sequence

### Phase 1: Low-Risk Cleanups (Week 1)

**Goal:** Remove obvious violations and empty packages.

| Task | Effort | Risk |
|------|--------|------|
| Delete empty `repository/` package from module-app | 1 hour | Low |
| Move `util/` to module-common or delete if unused | 2 hours | Low |
| Consolidate duplicate `error/` packages | 4 hours | Medium |

### Phase 2: Infrastructure Migration (Week 2-3)

**Goal:** Move all infrastructure from module-app to module-infra.

| Task | Effort | Risk |
|------|--------|------|
| Move `config/` package to module-infra | 8 hours | Medium |
| Move `aop/` to module-infra | 4 hours | Medium |
| Move `monitoring/` to module-infra | 16 hours | High |
| Move `scheduler/` to module-infra | 4 hours | Medium |
| Move `batch/` to module-infra | 4 hours | Medium |
| Move `lifecycle/` to module-infra | 2 hours | Low |
| Update @ComponentScan in module-app | 2 hours | High |

### Phase 3: Service Layer Restructuring (Week 4-5)

**Goal:** Analyze and potentially split service layer.

| Task | Effort | Risk |
|------|--------|------|
| Analyze v2/v4/v5 service dependencies | 8 hours | Low |
| Create module-app-service if needed | 16 hours | High |
| Move stateless services to new module | 16 hours | High |

### Phase 4: Test Strategy & Validation (Week 6)

**Goal:** Ensure test coverage and validate refactoring.

| Task | Effort | Risk |
|------|--------|------|
| Add ArchUnit tests for module boundaries | 8 hours | Low |
| Run full test suite | 4 hours | Low |
| Performance regression testing | 8 hours | Medium |
| Update documentation | 8 hours | Low |

---

## 6. Test Strategy

### 6.1 Existing Test Coverage

| Module | Unit Tests | Integration Tests | Chaos Tests |
|--------|------------|-------------------|-------------|
| module-app | ✅ Extensive | ✅ | ✅ |
| module-infra | ✅ | ✅ | ✅ |
| module-core | ✅ (jqwik) | ❌ Not needed | ❌ Not needed |
| module-common | ✅ | ❌ Not needed | ❌ Not needed |
| module-chaos-test | N/A | N/A | ✅ (22 tests) |

### 6.2 Testing Approach for Refactoring

**Pre-Migration:**
1. Run full test suite and document baseline
2. Add ArchUnit tests for module boundaries
3. Add integration tests for critical paths

**During Migration:**
1. Move one package at a time
2. Run full test suite after each move
3. Verify Spring Bean initialization
4. Check for broken imports

**Post-Migration:**
1. Run full test suite
2. Run chaos tests (nightmare scenarios)
3. Performance testing (WRK benchmarks)
4. Update documentation

### 6.3 Rollback Plan

**Trigger:** Any test failure or compilation error

**Actions:**
1. Revert last package move
2. Investigate failure cause
3. Fix and retry
4. Document lesson learned

---

## 7. Module Boundary Rules (Recommended)

### 7.1 Dependency Rules

```java
// ArchUnit rule template
@ArchTest
static final ArchRule module_app_should_only_depend_on_infra_core_common =
    noClasses()
        .that().resideInAPackage("maple.expectation..app..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "maple.expectation..infrastructure..",
            "maple.expectation..domain..",
            "maple.expectation..core..",
            "maple.expectation..common.."
        );

@ArchTest
static final ArchRule module_infra_should_only_depend_on_core_common =
    noClasses()
        .that().resideInAPackage("maple.expectation..infrastructure..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "maple.expectation..app..",
            "maple.expectation..service.."
        );
```

### 7.2 Spring Annotation Rules

```java
@ArchTest
static final ArchRule module_core_should_not_contain_spring_annotations =
    noClasses()
        .that().resideInAPackage("maple.expectation.domain..")
        .should().beMetaAnnotatedWith("org.springframework..")
        .orShould().beMetaAnnotatedWith("jakarta.persistence..");

@ArchTest
static final ArchRule module_app_should_not_contain_repository =
    noClasses()
        .that().resideInAPackage("maple.expectation..app..")
        .should().beAnnotatedWith("org.springframework.stereotype.Repository");
```

---

## 8. Success Criteria

### 8.1 Module Size Targets

| Module | Current Files | Target Files | Reduction |
|--------|--------------|--------------|------------|
| module-app | 342 | < 150 | -56% |
| module-infra | 177 | < 250 | +41% |
| module-core | 59 | < 80 | +36% |
| module-common | 35 | < 50 | +43% |

### 8.2 Dependency Metrics

| Metric | Current | Target |
|--------|---------|--------|
| Circular dependencies | 0 (module level) | 0 |
| Spring annotations in module-core | 2 | 0 |
| @Configuration in module-app | 30+ | < 5 |
| Module boundary violations | Unknown | 0 (measured by ArchUnit) |

### 8.3 Build Time Targets

| Metric | Current | Target |
|--------|---------|--------|
| Full build time | Baseline | < 120% of baseline |
| Parallel build capability | Limited | Full parallelization |

---

## 9. Open Questions

### 9.1 Questions That Weren't Asked

1. **Service Module Granularity**: Should v2/v4/v5 be separate modules or remain in module-app?
   - **Why it matters:** v4 has 6 modules, v5 is emerging - splitting could enable independent deployment
   - **Default answer:** Keep in module-app until microservice migration is planned

2. **Monitoring Module Separation**: Should monitoring become module-observability?
   - **Why it matters:** 50+ files in monitoring/, could be reused across projects
   - **Default answer:** Yes, create module-observability if cross-project reuse is planned

3. **Config Strategy**: Should each module have its own @Configuration classes?
   - **Why it matters:** Centralized vs distributed configuration affects startup time and maintainability
   - **Default answer:** Keep infrastructure config in module-infra, app config in module-app

4. **Test Module Organization**: Should unit tests move to corresponding modules?
   - **Why it matters:** Currently all tests in module-app, module-infra
   - **Default answer:** Current structure is acceptable

### 9.2 Missing Guardrails

1. **Module size limits**: No automated enforcement of module file counts
   - **Suggested definition:** Add Gradle task that fails if module exceeds target file count

2. **Spring annotation leakage**: No automated detection of framework annotations in module-core
   - **Suggested definition:** Add ArchUnit test (Section 7.2)

3. **Package ownership**: No clear documentation of which packages belong to which module
   - **Suggested definition:** Create package-info.java files for each top-level package

### 9.3 Scope Risks

1. **V5 CQRS expansion**: V5 is still evolving (ADR-038)
   - **How to prevent:** Freeze V5 changes during refactoring, or include V5 in refactoring scope

2. **Configuration drift**: Multiple configuration files across modules
   - **How to prevent:** Centralize configuration properties in module-common

3. **Test migration**: Moving packages breaks test imports
   - **How to prevent:** Update tests immediately after package moves

---

## 10. Recommendations

### 10.1 Immediate Actions (Before Planning)

1. **Enable and fix ArchUnit tests** for module boundaries
2. **Add module size metrics** to CI/CD pipeline
3. **Document package ownership** in package-info.java files
4. **Create dependency diagram** with current state
5. **Baseline performance metrics** (WRK, test execution time)

### 10.2 Prioritized Refactoring Sequence

1. **Phase 1:** Delete empty packages, consolidate utilities (1 week)
2. **Phase 2:** Move infrastructure from module-app to module-infra (2 weeks)
3. **Phase 3:** Analyze service layer, create module-app-service if needed (1 week)
4. **Phase 4:** Test validation and documentation (1 week)

### 10.3 Long-Term Considerations

1. **Microservice migration:** Current module structure supports future service extraction
2. **Observability module:** Consider extracting monitoring for cross-project reuse
3. **V5 stabilization:** Complete V5 CQRS implementation before major refactoring
4. **Gradle version catalog:** Migrate to version catalog for dependency management

---

## 11. Next Steps

1. **Review this analysis** with architecture team
2. **Answer open questions** (Section 9)
3. **Create detailed task breakdown** for each phase
4. **Update ADR-035** with refactoring plan
5. **Set up CI/CD gates** for module boundary validation

---

## Appendix A: File Counts by Package

### module-app Detailed Breakdown

```
service/v2/          ~97 files  (15 modules)
service/v4/          ~10 files  (6 modules)
service/v5/          ~8 files   (4 modules)
monitoring/          ~50 files  (8 packages)
controller/          ~8 files
config/              ~30 files
aop/                 ~7 files
external/            ~5 files
application/         ~5 files
dto/                 ~5 files
alert/               ~3 files
batch/               ~1 file
scheduler/           ~1 file
parser/              ~2 files
provider/            ~2 files
event/               ~1 file
interfaces/          ~5 files
lifecycle/           ~1 file
error/               ~1 file
repository/          EMPTY
util/                ~1 file
```

### module-infra Detailed Breakdown

```
infrastructure/       ~150 files (20 packages)
domain/repository/     ~9 files
domain/nexon/          ~1 file
domain/v2/             ~9 files
application/           ~2 files
```

---

## Appendix B: Related Documents

- **ADR-014:** Multi-module cross-cutting concerns separation design
- **ADR-035:** Multi-module migration completion
- **ADR-036:** V5 CQRS architecture design
- **ADR-037:** V5 CQRS command side implementation
- **ADR-038:** V5 CQRS implementation report
- **service-modules.md:** Service layer documentation
- **architecture.md:** System architecture overview

---

**Document Version:** 1.0
**Last Updated:** 2026-02-16
**Next Review:** After Phase 1 completion
**Owner:** Architecture Team
