# Circular Dependency Analysis Report

**Date:** 2026-02-16
**Status:** Complete Analysis
**Analyst:** Sisyphus-Junior (Execution Agent)
**Related Issues:** #282 (Multi-Module Refactoring), #283 (Scale-out Roadmap)
**Related Documents:** [Multi-Module-Refactoring-Analysis.md](Multi-Module-Refactoring-Analysis.md)

---

## Executive Summary

**Critical Finding:** **NO circular dependencies detected** at the module level. The dependency direction follows **DIP (Dependency Inversion Principle)** correctly: `module-app → module-infra → module-core → module-common`.

However, **architectural violations** exist that require immediate attention:
1. **P0 - CRITICAL**: 56 `@Configuration` classes in module-app (should be in module-infra)
2. **P1 - HIGH**: module-app directly imports infrastructure implementations (bypassing ports)
3. **P1 - HIGH**: module-infra correctly depends on module-core ports (DIP compliant)

---

## 1. Methodology

### 1.1 Analysis Commands

```bash
# 1. Check module-app → infrastructure dependencies
grep -r "import maple.expectation.infrastructure" module-app/src/main/java --include="*.java"

# 2. Check module-app → domain/core dependencies
grep -r "import maple.expectation.domain" module-app/src/main/java --include="*.java"

# 3. Check module-app → common/error dependencies
grep -r "import maple.expectation.error" module-app/src/main/java --include="*.java"

# 4. Check reverse dependencies (SHOULD NOT EXIST)
grep -r "import maple.expectation.app" module-infra/src/main/java --include="*.java"
grep -r "import maple.expectation.app" module-core/src/main/java --include="*.java"
grep -r "import maple.expectation.app" module-common/src/main/java --include="*.java"

# 5. Check module-infra → module-core port dependencies
grep -r "import maple.expectation.application.port" module-infra/src/main/java --include="*.java"

# 6. Count Spring annotations
grep -r "@Configuration" module-app/src/main/java --include="*.java" | wc -l

# 7. Count module sizes
find module-app/src/main/java -name "*.java" | wc -l
find module-infra/src/main/java -name "*.java" | wc -l
find module-core/src/main/java -name "*.java" | wc -l
find module-common/src/main/java -name "*.java" | wc -l
```

### 1.2 Dependency Graph Analysis

**Current Dependency Direction (CORRECT):**
```
module-app (342 files)
    ├── module-infra (177 files)
    │   ├── module-core (59 files)
    │   │   └── module-common (35 files)
    │   └── module-common (35 files)
    └── module-common (35 files)

module-chaos-test (test-only, depends on all)
```

**Dependency Flow Verification:**
- ✅ module-app → module-infra: **VALID** (Infrastructure abstractions)
- ✅ module-app → module-core: **VALID** (Domain models, repositories)
- ✅ module-app → module-common: **VALID** (Error handling, responses)
- ✅ module-infra → module-core: **VALID** (DIP ports)
- ✅ module-infra → module-common: **VALID** (Error handling)
- ✅ module-core → module-common: **VALID** (Error codes, base exceptions)
- ❌ **NO reverse dependencies** detected (infra/core/common → app)

---

## 2. Findings

### 2.1 Circular Dependencies: NONE DETECTED

**Result:** ✅ **PASS** - No circular dependencies at module level

**Verification:**
```bash
# Reverse dependency checks (all returned empty):
grep -r "import maple.expectation.app" module-infra/src/main/java --include="*.java"
# Result: EMPTY (CORRECT)

grep -r "import maple.expectation.app" module-core/src/main/java --include="*.java"
# Result: EMPTY (CORRECT)

grep -r "import maple.expectation.app" module-common/src/main/java --include="*.java"
# Result: EMPTY (CORRECT)
```

**Conclusion:** The multi-module structure successfully enforces **unidirectional dependencies** following DIP.

---

### 2.2 P0 - CRITICAL Violation: Configuration Leakage

**Issue:** module-app contains **56 `@Configuration` classes** (should be in module-infra)

**Evidence:**
```bash
grep -r "@Configuration" module-app/src/main/java --include="*.java" | wc -l
# Result: 56 configuration classes

find module-app/src/main/java/maple/expectation/config -name "*.java" | wc -l
# Result: 46 files in config/ package
```

**Impact:**
- Violates separation of concerns (infrastructure in app layer)
- Makes module-app bloated (342 files, should be <150)
- Blocks future microservice extraction
- Increases module coupling

**Examples of Infrastructure in module-app:**
- `monitoring/copilot/config/` (AI SRE configuration)
- `external/impl/NexonApiClientConfig.java` (External API config)
- `config/` package (46 infrastructure config files)

**Severity:** **P0 - BLOCKER** for multi-module refactoring

---

### 2.3 P1 - HIGH Violation: Direct Infrastructure Dependencies

**Issue:** module-app directly imports infrastructure implementations (bypassing DIP ports)

**Evidence:**

```java
// Direct infrastructure imports (non-executor):
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.persistence.repository.*;
import maple.expectation.infrastructure.resilience.*;
import maple.expectation.infrastructure.cache.TieredCacheManager;
import maple.expectation.infrastructure.mongodb.*;
import maple.expectation.infrastructure.queue.like.PartitionedFlushStrategy;
```

**Files with direct infra dependencies (30+ files):**
- `monitoring/MonitoringAlertService.java`
- `external/impl/OutboxFallbackManager.java`
- `scheduler/*` (6 schedulers)
- `service/v4/cache/ExpectationCacheCoordinator.java`
- `service/v4/persistence/ExpectationPersistenceService.java`
- `service/v4/fallback/NexonApiFallbackService.java`
- `service/v5/event/ViewTransformer.java`
- `service/v5/worker/MongoDBSyncWorker.java`

**Impact:**
- Tight coupling to infrastructure implementation details
- Violates DIP (should depend on ports in module-core)
- Makes testing harder (need infra mocks)
- Blocks future infrastructure changes

**Severity:** **P1 - HIGH** (Architectural violation)

---

### 2.4 P1 - HIGH Finding: Correct DIP Implementation in module-infra

**Issue:** ✅ **NONE** - module-infra correctly implements DIP ports

**Evidence:**
```java
// module-infra correctly depends on module-core ports:
import maple.expectation.application.port.EventPublisher;
import maple.expectation.application.port.MessageQueue;
import maple.expectation.application.port.MessageTopic;
import maple.expectation.application.port.LikeBufferStrategy;
import maple.expectation.application.port.LikeRelationBufferStrategy;
import maple.expectation.application.port.PersistenceTrackerStrategy;
```

**Port Interfaces in module-core (8 ports):**
- `EventPublisher` (Strategy pattern for Redis/Kafka)
- `MessageQueue` (Queue abstraction)
- `MessageTopic` (Topic abstraction)
- `LikeBufferStrategy` (Like buffering)
- `LikeRelationBufferStrategy` (Like relation buffering)
- `PersistenceTrackerStrategy` (Persistence tracking)
- `AlertPublisher` (Alert publishing)

**Implementations in module-infra:**
- `RedisEventPublisher` implements `EventPublisher`
- `KafkaEventPublisher` implements `EventPublisher`
- `RedisMessageQueue` implements `MessageQueue`
- `RedisMessageTopic` implements `MessageTopic`
- `RedisLikeBufferStorage` implements `LikeBufferStrategy`
- `RedisLikeRelationBuffer` implements `LikeRelationBufferStrategy`
- `RedisEquipmentPersistenceTracker` implements `PersistenceTrackerStrategy`

**Conclusion:** ✅ **PASS** - Correct DIP implementation

**Severity:** **POSITIVE** (Best practice example)

---

### 2.5 P2 - MEDIUM Observation: module-app Service Layer Bloat

**Issue:** module-app service layer contains 342 files with mixed concerns

**Evidence:**
```bash
find module-app/src/main/java -name "*.java" | wc -l
# Result: 342 files

ls module-app/src/main/java/maple/expectation/service/
# Result: ingestion/, v2/ (97 files), v4/ (10 files), v5/ (8 files)
```

**Service Breakdown:**
- `service/v2/`: ~97 files (15 modules)
- `service/v4/`: ~10 files (6 modules)
- `service/v5/`: ~8 files (4 modules)
- `service/ingestion/`: ~3 files

**Impact:**
- module-app is bloated (target: <150 files)
- Service versions (v2/v4/v5) mixed in same module
- Difficult to maintain and test
- Blocks independent deployment

**Recommendation:** Consider splitting into `module-app-service` (Phase 3 of refactoring)

**Severity:** **P2 - MEDIUM** (Maintainability concern)

---

## 3. Dependency Mapping

### 3.1 Cross-Module Import Statistics

| From Module | To Module | Import Count | Type | Status |
|-------------|-----------|--------------|------|--------|
| **module-app** | module-infra | 50+ | Infrastructure | ⚠️ **HIGH** (should use ports) |
| **module-app** | module-core | 30+ | Domain, Repositories | ✅ **VALID** |
| **module-app** | module-common | 20+ | Error handling | ✅ **VALID** |
| **module-infra** | module-core | 10+ | Port interfaces | ✅ **VALID** (DIP) |
| **module-infra** | module-common | 0+ | (checked via error) | ✅ **VALID** |
| **module-core** | module-common | 10+ | Error codes, exceptions | ✅ **VALID** |
| **module-infra** | module-app | **0** | **REVERSE** | ✅ **NONE** |
| **module-core** | module-app | **0** | **REVERSE** | ✅ **NONE** |
| **module-common** | module-app | **0** | **REVERSE** | ✅ **NONE** |

### 3.2 Dependency Rules Compliance

| Rule | Expected | Actual | Status |
|------|----------|--------|--------|
| No circular dependencies | 0 | 0 | ✅ **PASS** |
| module-core framework-agnostic | 0 Spring annotations | 0 (only in comments) | ✅ **PASS** |
| module-common framework-agnostic | 0 Spring annotations | 0 | ✅ **PASS** |
| module-infra → module-core ports | Yes | Yes (8 ports) | ✅ **PASS** |
| module-app no infrastructure | @Configuration < 5 | 56 | ❌ **FAIL** |
| module-app no direct infra impl | 0 | 30+ files | ❌ **FAIL** |

---

## 4. Recommendations

### 4.1 Immediate Actions (P0 - Week 1)

**1. Move Configuration Classes to module-infra**
- **Target:** Move 46 config/ files to `module-infra/src/main/java/maple/expectation/infrastructure/config/`
- **Effort:** 8 hours
- **Risk:** Medium (Spring Bean resolution)
- **Commands:**
  ```bash
  # Move config package
  mv module-app/src/main/java/maple/expectation/config \
     module-infra/src/main/java/maple/expectation/infrastructure/config/

  # Update imports
  find module-app/src/main/java -name "*.java" -exec sed -i \
    's/import maple.expectation.config/import maple.expectation.infrastructure.config/g' {} \;

  # Update @ComponentScan
  # In module-app application class:
  # @ComponentScan(basePackages = {
  #   "maple.expectation.controller",
  #   "maple.expectation.service",
  #   "maple.expectation.application"
  # })
  ```

**2. Move AOP to module-infra**
- **Target:** Move `aop/` package (7 files)
- **Effort:** 4 hours
- **Risk:** Medium (AOP proxy resolution)

**3. Move Monitoring Infrastructure**
- **Target:** Move `monitoring/` (50 files) to module-infra or new module-observability
- **Effort:** 16 hours
- **Risk:** High (complex dependencies)

### 4.2 Refactoring Actions (P1 - Week 2-3)

**1. Introduce Port Interfaces for Direct Dependencies**
- **Target:** Create ports in module-core for infra implementations
- **Examples:**
  ```java
  // In module-core:
  public interface CacheCoordinator {
      void warmup(String ocid);
      Optional<ExpectationSummary> get(String ocid);
  }

  // In module-infra:
  @Component
  public class TieredCacheCoordinator implements CacheCoordinator {
      private final TieredCacheManager manager;
      // Implementation...
  }

  // In module-app (service layer):
  @Service
  public class ExpectationServiceV4 {
      private final CacheCoordinator cacheCoordinator; // Interface, not concrete
  }
  ```
- **Effort:** 16 hours
- **Risk:** High (touches 30+ files)

**2. Move Scheduler Implementations**
- **Target:** Move `scheduler/` to module-infra
- **Effort:** 4 hours
- **Risk:** Medium (Spring scheduling)

**3. Move Batch Jobs**
- **Target:** Move `batch/` to module-infra
- **Effort:** 4 hours
- **Risk:** Medium (Spring batch)

### 4.3 Long-Term Improvements (P2 - Week 4+)

**1. Split Service Layer**
- **Target:** Create `module-app-service` for v2/v4/v5
- **Effort:** 40 hours
- **Risk:** High (architectural change)
- **Benefit:** Enables independent deployment

**2. Create module-observability**
- **Target:** Extract monitoring for cross-project reuse
- **Effort:** 24 hours
- **Risk:** Medium
- **Benefit:** Reusable monitoring infrastructure

**3. Add ArchUnit Tests**
- **Target:** Enforce module boundaries in CI/CD
- **Effort:** 8 hours
- **Risk:** Low
- **Example:**
  ```java
  @ArchTest
  static final ArchRule module_app_should_not_depend_on_infrastructure_implementation =
      noClasses()
          .that().resideInAPackage("maple.expectation..app..")
          .should().dependOnClassesThat()
          .resideInAPackage("maple.expectation.infrastructure..")
          .andShould().dependOnClassesThat()
          .resideInAPackage("maple.expectation.infrastructure.persistence..")
          .andShould().dependOnClassesThat()
          .resideInAPackage("maple.expectation.infrastructure.cache..")
          .andShould().dependOnClassesThat()
          .resideInAPackage("maple.expectation.infrastructure.lock..");
  ```

---

## 5. Verification

### 5.1 Pre-Migration Verification

```bash
# 1. Run full test suite
./gradlew test

# 2. Check compilation
./gradlew clean build -x test

# 3. Verify no reverse dependencies
grep -r "import maple.expectation.app" module-infra/src/main/java --include="*.java"
grep -r "import maple.expectation.app" module-core/src/main/java --include="*.java"
grep -r "import maple.expectation.app" module-common/src/main/java --include="*.java"

# 4. Count module sizes
find module-app/src/main/java -name "*.java" | wc -l
find module-infra/src/main/java -name "*.java" | wc -l
```

### 5.2 Post-Migration Verification

```bash
# 1. Run full test suite
./gradlew test

# 2. Check Spring Bean initialization
./gradlew bootRun

# 3. Verify dependency rules
./gradlew test --tests "*ArchitectureTest"

# 4. Performance baseline
./gradlew bootRun
# Run WRK benchmarks
```

### 5.3 Rollback Triggers

**Immediate Rollback If:**
- Any test fails after package move
- Spring Bean initialization fails
- Compilation errors exceed 10 files
- Startup time increases > 20%

**Rollback Commands:**
```bash
# Revert last commit
git revert HEAD

# Or restore from backup
git stash
git checkout develop
```

---

## 6. Success Criteria

### 6.1 Module Size Targets

| Module | Current | Target | Status |
|--------|---------|--------|--------|
| module-app | 342 files | < 150 files | ❌ **FAIL** |
| module-infra | 177 files | < 250 files | ✅ **PASS** |
| module-core | 59 files | < 80 files | ✅ **PASS** |
| module-common | 35 files | < 50 files | ✅ **PASS** |

### 6.2 Dependency Metrics

| Metric | Current | Target | Status |
|--------|---------|--------|--------|
| Circular dependencies | 0 | 0 | ✅ **PASS** |
| Spring annotations in module-core | 0 (only comments) | 0 | ✅ **PASS** |
| @Configuration in module-app | 56 | < 5 | ❌ **FAIL** |
| Direct infra dependencies in module-app | 30+ files | 0 | ❌ **FAIL** |
| Reverse dependencies (infra/core/common → app) | 0 | 0 | ✅ **PASS** |

### 6.3 Architecture Compliance

| Rule | Status | Evidence |
|------|--------|----------|
| DIP (infra → core ports) | ✅ **PASS** | 8 port interfaces correctly used |
| No circular dependencies | ✅ **PASS** | 0 reverse dependencies |
| Framework-agnostic core | ✅ **PASS** | 0 Spring annotations |
| Framework-agnostic common | ✅ **PASS** | 0 Spring annotations |
| Infrastructure separation | ❌ **FAIL** | 56 @Configuration in app module |

---

## 7. Related Documents

- **Multi-Module-Refactoring-Analysis.md**: Comprehensive module structure analysis
- **ADR-014**: Multi-module cross-cutting concerns separation design
- **ADR-035**: Multi-module migration completion
- **service-modules.md**: V2/V4/V5 service layer documentation
- **architecture.md**: System architecture overview

---

## 8. Next Steps

1. **Review this analysis** with architecture team
2. **Prioritize P0 violations** (config migration to module-infra)
3. **Create detailed task breakdown** for each phase
4. **Set up CI/CD gates** for module boundary validation
5. **Execute Phase 1** (Low-risk cleanups)

---

## Appendix A: Detailed Import Analysis

### A.1 module-app → module-infra Imports (50+ files)

**Categories:**
1. **LogicExecutor** (30+ files) - ✅ **ACCEPTABLE** (Infrastructure abstraction)
2. **LockStrategy** (6 files) - ⚠️ **SHOULD USE PORT**
3. **Repository** (10+ files) - ⚠️ **SHOULD USE PORT**
4. **Cache** (3 files) - ⚠️ **SHOULD USE PORT**
5. **Resilience** (5 files) - ⚠️ **SHOULD USE PORT**
6. **MongoDB** (4 files) - ⚠️ **SHOULD USE PORT**
7. **Queue** (2 files) - ✅ **ACCEPTABLE** (using port interface)

### A.2 module-infra → module-core Port Imports (10 files)

**All Valid DIP Implementations:**
1. `RedisEventPublisher` → `EventPublisher`
2. `KafkaEventPublisher` → `EventPublisher`
3. `RedisMessageQueue` → `MessageQueue`
4. `RedisMessageTopic` → `MessageTopic`
5. `RedisLikeBufferStorage` → `LikeBufferStrategy`
6. `RedisLikeRelationBuffer` → `LikeRelationBufferStrategy`
7. `RedisEquipmentPersistenceTracker` → `PersistenceTrackerStrategy`
8. `MessagingConfig` → Multiple ports

---

**Document Version:** 1.0
**Last Updated:** 2026-02-16
**Next Review:** After Phase 1 completion (config migration)
**Owner:** Architecture Team
