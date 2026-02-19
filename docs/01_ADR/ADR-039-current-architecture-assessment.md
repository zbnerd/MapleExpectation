# ADR-039: Current Architecture Assessment

**Status:** Accepted (Baseline Documentation)

**Date:** 2026-02-16

**Context:** Pre-refactoring architecture assessment for multi-module structure improvements

**Related Issues:** #282 (Multi-Module Refactoring), #283 (Scale-out Roadmap)

**Supersedes:** ADR-035 (Multi-Module Migration Completion) - Provides updated baseline

---

## Context

MapleExpectation's multi-module architecture was established in ADR-035 (February 2025) to resolve circular dependencies and enable scale-out. While the dependency direction follows DIP principles correctly, **module-app remains bloated** with 342 files containing mixed concerns that violate the intended architectural boundaries.

### Problem Statement

1. **Infrastructure Leakage in Application Module:** module-app contains 56 @Configuration classes, AOP aspects, schedulers, and monitoring logic that should reside in module-infra
2. **Service Layer Bloat:** 146 service files across v2/v4/v5 versions with unclear separation of concerns
3. **Empty Repository Package:** module-app/repository/ exists but is empty (should be deleted)
4. **Architectural Drift:** Over time, infrastructure concerns have accumulated in module-app, blurring module boundaries
5. **Monitoring Logic Placement:** 45 monitoring files in module-app should be in module-infra or separate module-observability

### Related Decisions

- **ADR-035:** Multi-Module Migration Completion (original structure)
- **ADR-036:** V5 CQRS Architecture (ongoing implementation)
- **docs/05_Reports/Multi-Module-Refactoring-Analysis.md:** Detailed analysis (2026-02-16)

---

## Decision

Document the current module structure, package ownership, and SOLID compliance as a **baseline for refactoring**. This ADR serves as the "before" snapshot for comparison with the post-refactoring state.

### Module Dependency Graph (Current State)

```
module-app (342 files) ← BLOATED
    ├── module-infra (177 files)
    │   ├── module-core (59 files)
    │   │   └── module-common (35 files)
    │   └── module-common (35 files)
    └── module-common (35 files)

module-chaos-test (test-only, depends on all)
```

**Dependency Direction:** ✅ CORRECT (follows DIP principle)
- module-app → module-infra → module-core → module-common

---

## Current Module Structure

### module-app (342 files) - BLOATED

**Intended Purpose:** Application layer (controllers, facades, application services)
**Actual State:** Mixed concerns with infrastructure leakage

| Package | File Count | Spring Components | Should Move To | Priority |
|---------|-----------|-------------------|----------------|----------|
| `service/` | 146 | 100+ | KEEP (needs v2/v4/v5 split) | P1 |
| `controller/` | ~8 | 8+ | KEEP in module-app | - |
| `config/` | 46 | 56 @Configuration | **module-infra** | **P0** |
| `monitoring/` | 45 | 50+ | **module-infra or module-observability** | **P0** |
| `aop/` | ~7 | 5+ | **module-infra** | **P0** |
| `scheduler/` | ~1 | 1+ | **module-infra** | P1 |
| `batch/` | ~1 | 1+ | **module-infra** | P1 |
| `alert/` | ~3 | 3 | **module-infra** | P1 |
| `lifecycle/` | ~1 | - | **module-infra** | P2 |
| `error/` | ~1 | 1 | **module-infra** | P1 |
| `repository/` | **0** | **0** | **DELETE** | **P0** |
| `dto/` | ~5 | - | KEEP in module-app | - |
| `application/` | ~5 | 1 | KEEP in module-app | - |
| `external/` | ~5 | 2+ | **module-infra** | P1 |
| `event/` | ~1 | - | module-app or module-infra | P2 |
| `interfaces/` | ~5 | - | KEEP in module-app | - |
| `parser/` | ~2 | - | KEEP (domain-specific) | - |
| `provider/` | ~2 | 2+ | KEEP (domain-specific) | - |
| `util/` | ~1 | - | **module-common or DELETE** | P2 |

**Key Findings:**
- **56 @Configuration classes** should be in module-infra
- **Empty repository package** should be deleted
- **Monitoring package (45 files)** is infrastructure concern
- **Service layer (146 files)** needs v2/v4/v5 analysis for potential split

### module-infra (177 files)

**Purpose:** Infrastructure implementations (Redis, DB, external APIs, configs)

| Package | Description | Components |
|---------|-------------|------------|
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
| `domain/repository/` | Repository interfaces | 9 interfaces |
| `domain/nexon/` | Nexon API data models | - |
| `domain/v2/` | JPA entities | 9 entities |
| `application/` | Application services | 2+ |

**Status:** ✅ Correct placement of infrastructure concerns

### module-core (59 files)

**Purpose:** Domain logic, business rules (DIP interfaces) - Framework-agnostic

| Package | Description | Components | Spring Annotations |
|---------|-------------|------------|-------------------|
| `domain/` | Pure domain models | ~20 classes | 0 ✅ |
| `domain/model/calculator/` | Calculator models | 3 | 0 ✅ |
| `domain/model/like/` | Like models | 2 | 0 ✅ |
| `domain/model/equipment/` | Equipment models | 2 | 0 ✅ |
| `domain/model/character/` | Character models | 2 | 0 ✅ |
| `domain/service/` | Domain services | 2 | 0 ✅ |
| `application/port/` | DIP ports (interfaces) | 7 | 0 ✅ |
| `error/` | Domain-specific errors | ~10 | 0 ✅ |

**Status:** ✅ Framework-agnostic (no Spring annotations detected)

**Note:** Analysis report mentions 2 Spring annotations in module-core, but current scan shows 0. May have been cleaned up since ADR-035.

### module-common (35 files)

**Purpose:** Shared utilities, constants, base classes

| Package | Description | Components | Spring Annotations |
|---------|-------------|------------|-------------------|
| `common/` | Common utilities | 2 | - |
| `error/` | Error handling (base exceptions, error codes) | ~30 | 1 ⚠️ |
| `event/` | Event interfaces | 3 | - |
| `response/` | Response DTOs | 1 | - |
| `shared/` | Shared utilities | - | - |

**Status:** ⚠️ Mostly framework-agnostic (1 annotation found - needs investigation)

---

## SOLID Compliance Assessment

### SRP (Single Responsibility Principle)

**Status:** ⚠️ PARTIAL COMPLIANCE

| Module | SRP Compliance | Issues |
|--------|---------------|---------|
| module-app | ❌ VIOLATED | Mixes application logic with infrastructure (56 @Configuration classes) |
| module-infra | ✅ COMPLIANT | Clear infrastructure responsibility |
| module-core | ✅ COMPLIANT | Pure domain logic |
| module-common | ✅ COMPLIANT | Shared utilities only |

**Recommendation:** Move infrastructure concerns (config, aop, scheduler, batch, monitoring) from module-app to module-infra.

### OCP (Open/Closed Principle)

**Status:** ✅ COMPLIANT

**Evidence:**
- Strategy pattern extensively used (CacheStrategy, LockStrategy, AlertChannelStrategy)
- Factory pattern for component creation
- DIP interfaces enable extension without modification

**Example:**
```java
// Core abstraction (module-core) - open for extension
public interface CacheStrategy {
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value, Duration ttl);
}

// Multiple implementations (module-infra) - closed for modification
@Component
public class RedisCacheStrategy implements CacheStrategy { ... }

@Component
public class CaffeineCacheStrategy implements CacheStrategy { ... }
```

### LSP (Liskov Substitution Principle)

**Status:** ✅ COMPLIANT

**Evidence:**
- Repository implementations correctly substitute interfaces
- Service inheritance follows contract
- Strategy implementations are substitutable

### ISP (Interface Segregation Principle)

**Status:** ✅ COMPLIANT

**Evidence:**
- Port interfaces in module-core are focused (7 interfaces)
- No fat interfaces with unused methods
- Client-specific interfaces (e.g., CacheStrategy vs LockStrategy)

### DIP (Dependency Inversion Principle)

**Status:** ✅ COMPLIANT (at module level)

**Dependency Direction:**
```
module-app → module-infra → module-core → module-common
```

**Evidence:**
- High-level modules (module-app) depend on abstractions (module-core)
- Low-level modules (module-infra) implement abstractions
- No circular dependencies detected at module level

**Example:**
```java
// module-app depends on abstraction (module-core)
@Service
@RequiredArgsConstructor
public class GameCharacterService {
    private final CacheStrategy cache;  // Abstraction from module-core
    private final GameCharacterRepository repository;  // Interface from module-infra
}

// module-infra implements abstraction
@Component
public class RedisCacheStrategy implements CacheStrategy {
    // Concrete implementation
}
```

---

## Circular Dependencies

### Current State: ✅ NO CIRCULAR DEPENDENCIES DETECTED

**Module Level:** Clean dependency tree (app → infra → core → common)

**ArchUnit Tests:**
- `no_cyclic_dependencies()` test exists but is **DISABLED**
- Reason: False positives from legitimate same-package dependencies (12,148+ violations)
- The test catches forward references within same package (not actual circular dependencies)

**Recommendation:** Re-enable with more granular package slicing to detect actual circular dependencies.

### Historical Circular Dependencies (Resolved in ADR-035)

| Issue | Resolution |
|-------|------------|
| Global error handlers circular reference | Consolidated into module-common |
| Infrastructure depending on application logic | Extracted DIP interfaces to module-core |
| Static stateful components | Migrated to stateless design with Spring Beans |

---

## Risk Assessment

### P0 - Critical Risks (Must Address)

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **module-app contains 56 @Configuration classes** | Architectural drift, unclear ownership | Certain | Move to module-infra (Phase 2) |
| **Empty repository package** | Confusion, dead code | Certain | Delete from module-app (Phase 1) |
| **Service layer bloat (146 files)** | Maintainability nightmare | High | Analyze v2/v4/v5 split (Phase 3) |

### P1 - High Risks (Should Address)

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **Monitoring in module-app (45 files)** | Infrastructure leakage | High | Move to module-infra or module-observability |
| **AOP in module-app** | Cross-cutting concern misplacement | Certain | Move to module-infra |
| **Scheduler/Batch in module-app** | Infrastructure in application layer | Certain | Move to module-infra |

### P2 - Medium Risks (Consider Addressing)

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **Util package location** | Unclear ownership | Medium | Move to module-common or delete if unused |
| **Event package ownership** | Ambiguous placement | Low | Decide based on infrastructure vs domain |
| **ArchUnit test disabled** | Loss of architectural guardrail | Medium | Re-enable with granular slicing |

---

## Consequences

### If We Don't Refactor

**1. Architectural Drift Worsens**
- More infrastructure will accumulate in module-app
- Module boundaries will become increasingly blurred
- DIP compliance will degrade over time

**2. Maintainability Suffers**
- 342 files in module-app creates cognitive overload
- Unclear ownership leads to duplicated code
- Onboarding new developers becomes difficult

**3. Scale-out Readiness Compromised**
- Infrastructure in module-app creates tight coupling
- Harder to extract microservices in future
- Stateful components may hide in wrong modules

**4. Testing Complexity Increases**
- Application tests require infrastructure context
- Hard to mock dependencies due to concrete implementations
- Test execution time increases

### If We Refactor (Following Analysis Report)

**Benefits:**
1. **Clear Module Boundaries:** module-app becomes purely application layer
2. **Improved Maintainability:** Reduced file count per module (< 150 target for module-app)
3. **Better Testability:** Clear separation enables focused unit tests
4. **Scale-out Ready:** Infrastructure consolidated in module-infra
5. **Architectural Guardrails:** ArchUnit tests prevent future drift

**Costs:**
1. **Migration Effort:** 5-6 weeks of coordinated refactoring
2. **Breaking Changes:** Internal API updates required
3. **Learning Curve:** Team must understand new structure
4. **Testing Overhead:** Full test suite validation after each move

---

## Success Criteria

### Module Size Targets

| Module | Current Files | Target Files | Reduction |
|--------|--------------|--------------|------------|
| module-app | 342 | < 150 | -56% |
| module-infra | 177 | < 250 | +41% |
| module-core | 59 | < 80 | +36% |
| module-common | 35 | < 50 | +43% |

### Dependency Metrics

| Metric | Current | Target |
|--------|---------|--------|
| Circular dependencies | 0 (module level) | 0 |
| Spring annotations in module-core | 0 | 0 |
| @Configuration in module-app | 56 | < 5 |
| Empty packages | 1 (repository/) | 0 |

### Architectural Compliance

| Principle | Current | Target |
|-----------|---------|--------|
| SRP (module-app) | ❌ Violated | ✅ Compliant |
| DIP direction | ✅ Correct | ✅ Correct |
| Framework-agnostic core | ✅ Yes | ✅ Yes |
| Module boundary violations | Unknown | 0 (measured by ArchUnit) |

---

## Recommendations

### Immediate Actions (Before Refactoring)

1. **Enable ArchUnit tests** for module boundaries with granular slicing
2. **Document package ownership** in package-info.java files
3. **Baseline metrics** (file counts, dependency graph, test coverage)
4. **Create dependency diagram** with current state visualization
5. **Verify Spring annotations** count in module-core (currently 0, but analysis reported 2)

### Refactoring Sequence (From Analysis Report)

**Phase 1: Low-Risk Cleanups (Week 1)**
- Delete empty `repository/` package from module-app
- Move `util/` to module-common or delete if unused
- Consolidate duplicate error packages

**Phase 2: Infrastructure Migration (Week 2-3)**
- Move `config/` package to module-infra (56 @Configuration classes)
- Move `aop/` to module-infra
- Move `monitoring/` to module-infra or module-observability (45 files)
- Move `scheduler/`, `batch/`, `alert/`, `lifecycle/` to module-infra
- Update @ComponentScan in module-app

**Phase 3: Service Layer Analysis (Week 4-5)**
- Analyze v2/v4/v5 service dependencies
- Determine if module-app-service split is needed
- Move stateless services if split decision made

**Phase 4: Test Strategy & Validation (Week 6)**
- Add ArchUnit tests for module boundaries
- Run full test suite after each move
- Performance regression testing
- Update documentation

---

## References

### Related ADRs

- **ADR-035:** Multi-Module Migration Completion (February 2025) - Original structure
- **ADR-036:** V5 CQRS Architecture with MongoDB (ongoing implementation)
- **ADR-037:** V5 CQRS Command Side Implementation
- **ADR-038:** V5 CQRS Implementation Report

### Analysis Documents

- **docs/05_Reports/Multi-Module-Refactoring-Analysis.md** - Detailed analysis (2026-02-16)
- **docs/00_Start_Here/architecture.md** - System architecture overview
- **docs/03_Technical_Guides/service-modules.md** - Service layer documentation
- **docs/00_Start_Here/ROADMAP.md** - Phase 7: Multi-module refactoring

### Related Issues

- **#282:** Multi-Module Refactoring to Resolve Circular Dependencies
- **#283:** Scale-out Roadmap
- **#126:** V5 CQRS Implementation

### Technical Guides

- **docs/03_Technical_Guides/infrastructure.md** - Infrastructure best practices
- **docs/03_Technical_Guides/testing-guide.md** - Testing strategy
- **CLAUDE.md** - Project guidelines (SOLID, LogicExecutor, Exception handling)

---

## Appendix A: File Count Verification

**Verification Date:** 2026-02-16

```bash
# Module file counts
find module-app/src/main/java -type f -name "*.java" | wc -l
# Result: 342

find module-infra/src/main/java -type f -name "*.java" | wc -l
# Result: 177

find module-core/src/main/java -type f -name "*.java" | wc -l
# Result: 59

find module-common/src/main/java -type f -name "*.java" | wc -l
# Result: 35

# @Configuration count in module-app
grep -r "@Configuration" module-app/src/main/java --include="*.java" | wc -l
# Result: 56

# Empty repository package verification
ls -la module-app/src/main/java/maple/expectation/repository/
# Result: Empty directory (total 8, only . and ..)
```

---

## Appendix B: Package Ownership Map

### Current Ownership

| Package | Current Module | Correct Module | Priority |
|---------|---------------|----------------|----------|
| `controller/` | module-app | module-app | - |
| `service/` | module-app | module-app (needs split) | P1 |
| `config/` | module-app ❌ | module-infra | **P0** |
| `aop/` | module-app ❌ | module-infra | **P0** |
| `monitoring/` | module-app ❌ | module-infra or module-observability | **P0** |
| `scheduler/` | module-app ❌ | module-infra | P1 |
| `batch/` | module-app ❌ | module-infra | P1 |
| `alert/` | module-app ❌ | module-infra | P1 |
| `repository/` | module-app ❌ | **DELETE** | **P0** |
| `lifecycle/` | module-app ❌ | module-infra | P2 |
| `util/` | module-app | module-common or DELETE | P2 |
| `infrastructure/` | module-infra | module-infra ✅ | - |
| `domain/` | module-infra, module-core | Split review needed | P2 |

---

**Document Status:** Baseline Documentation (Pre-Refactoring)

**Next Review:** After Phase 2 completion (Infrastructure Migration)

**Owner:** Architecture Team

**Maintained By:** Oracle (Architectural Advisor)

---

*This ADR serves as the architectural baseline for the multi-module refactoring effort (Issue #282). All subsequent ADRs should reference this document when comparing "before" and "after" states.*
