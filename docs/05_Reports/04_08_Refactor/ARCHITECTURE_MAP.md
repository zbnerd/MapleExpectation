# Architecture Map - Current State

> **Document Owner:** Blue Architect (5-Agent Council)
> **Generated:** 2026-02-07
> **Purpose:** Baseline architecture analysis for Phase 0 Clean Architecture assessment

---

## 1. Entrypoints

### Controllers (REST API Layer)

| Controller | Path | Responsibility |
|------------|------|----------------|
| `GameCharacterControllerV1` | `/api/v1/characters/*` | Legacy character endpoints (deprecated) |
| `GameCharacterControllerV2` | `/api/v2/characters/*` | Core character/equipment API (stable) |
| `GameCharacterControllerV3` | `/api/v3/characters/*` | Enhanced character API |
| `GameCharacterControllerV4` | `/api/v4/characters/*` | High-performance expectation API (719 RPS) |
| `AuthController` | `/api/auth/*` | JWT authentication, token refresh |
| `DonationController` | `/api/donation/*` | Coffee donation feature |
| `AdminController` | `/api/admin/*` | Admin operations (RBAC) |
| `DlqAdminController` | `/api/admin/dlq/*` | Dead Letter Queue management |
| `AlertTestController` | `/api/alert/test` | Discord alert testing |

**Location:** `src/main/java/maple/expectation/controller/`

### Schedulers (Background Jobs)

| Scheduler | Trigger | Responsibility |
|-----------|---------|----------------|
| `ExpectationBatchWriteScheduler` | Fixed rate (100ms) | Write-Behind buffer drain (V4) |
| `LikeSyncScheduler` | Fixed rate (10s) | Redis → DB like synchronization |
| `OutboxScheduler` | Fixed rate (1s) | Transactional Outbox replay |
| `NexonApiOutboxScheduler` | Fixed rate (5s) | Nexon API Outbox replay (N19) |
| `PopularCharacterWarmupScheduler` | Cron (daily) | Cache warming for popular characters |
| `BufferRecoveryScheduler` | Post-construct | Orphan key recovery |

**Location:** `src/main/java/maple/MapleExpectation/scheduler/`

### Workers (Async Consumers)

| Worker | Queue | Responsibility |
|--------|-------|----------------|
| `EquipmentDbWorker` | Redis `equipment_job_queue` | Async equipment DB persistence |
| `GameCharacterWorker` | Redis `character_job_queue` | Async character creation from API |

**Location:** `src/main/java/maple/expectation/service/v2/worker/`

---

## 2. Core Domain/Use-Cases

### Domain Models (JPA Entities)

| Entity | Table | Core Behavior |
|--------|-------|---------------|
| `GameCharacter` | `game_character` | Rich Domain: `isActive()`, `needsBasicInfoRefresh()`, `validateOcid()` |
| `CharacterEquipment` | `character_equipment` | GZIP-compressed JSON storage (350KB → 35KB) |
| `CharacterLike` | `character_like` | Like relationships |
| `DonationHistory` | `donation_history` | Donation transactions |
| `DonationOutbox` | `donation_outbox` | Transactional Outbox pattern |
| `DonationDlq` | `donation_dlq` | Dead Letter Queue |
| `NexonApiOutbox` | `nexon_api_outbox` | Nexon API replay (N19) |
| `EquipmentExpectationSummary` | `equipment_expectation_summary` | V4 cached results |
| `CubeProbability` | `cube_probability` | Cube engine probability tables |
| `Member` | `member` | User accounts |
| `Session` | `session` | Redis-backed sessions |
| `RefreshToken` | `refresh_token` | JWT refresh tokens |
| `PotentialGrade` | `potential_grade` | Lookup table |
| `CubeType` | `cube_type` | Lookup table |

**Location:** `src/main/java/maple/expectation/domain/v2/`

**Key Observation:** `GameCharacter` is a **Rich Domain Model** with business logic (`isActive()`, `validateOcid()`). Most other entities are **Anemic** (data holders only).

### Business Logic Services

| Service | Responsibility |
|---------|----------------|
| `GameCharacterService` | Character CRUD, negative/positive caching |
| `GameCharacterFacade` | Orchestration: character lookup + enrichment |
| `EquipmentService` | Equipment calculation orchestration (V2) |
| `EquipmentExpectationServiceV4` | V4 expectation calculation facade |
| `LikeSyncService` | Financial-grade like synchronization |
| `LikeSyncExecutor` | Batch like updates with Circuit Breaker |
| `DonationService` | Donation with idempotency + Outbox |
| `OcidResolver` | OCID Get-or-Create with negative caching |
| `CharacterLikeService` | Like validation (self-like, duplicate prevention) |

**Location:**
- V2: `src/main/java/maple/expectation/service/v2/`
- V4: `src/main/java/maple/expectation/service/v4/`

### Value Objects / Records

| Record/VO | Purpose |
|-----------|---------|
| `TotalExpectationResponse` | External API DTO |
| `EquipmentExpectationResponseV4` | V4 response with CostBreakdown |
| `CubeCalculationInput` | Calculator input |
| `FetchResult` | Atomic fetch result |
| `CharacterSnapshot` | EquipmentService internal snapshot |
| `LikeSyncFailedEvent` | DLQ event |
| `DensePmf`, `SparsePmf` | Probability mass functions |

---

## 3. Infrastructure

### Database (MySQL 8.0)

| Component | Location | Purpose |
|-----------|----------|---------|
| Repositories | `repository/v2/*Repository.java` | JPA repositories |
| DataSource | `LockHikariConfig` | HikariCP with connection pooling |
| Transaction Management | `TransactionConfig` | REQUIRES_NEW isolation |

**Key Features:**
- GZIP compression via `@Convert` (90% storage reduction)
- SKIP LOCKED for concurrent batch processing
- Slow query log (1s threshold)

### Redis (Redisson 3.27.0)

| Component | Location | Purpose |
|-----------|----------|---------|
| `RedissonConfig` | `config/` | Sentinel HA configuration |
| `TieredCacheManager` | `global/cache/` | L1+L2 coordination |
| `RedisCacheInvalidationPublisher` | `global/cache/invalidation/` | Pub/Sub cache invalidation |
| `RedisLikeBufferStorage` | `global/queue/like/` | Sorted set like buffer |
| `RedisLikeEventPublisher` | `service/v2/like/realtime/` | Pub/Sub real-time events |

**Redis Data Structures:**
- `String`: L2 cache (equipment, OCID, expectation)
- `Sorted Set`: Like buffer with timestamp scoring
- `RLock`: Distributed locks
- `RCountDownLatch`: Single-flight leader/follower
- `RBucket`: Rate limiting

### HTTP (External API)

| Client | Location | Purpose |
|--------|----------|---------|
| `NexonApiClient` | `external/` | Equipment data API |
| `NexonAuthClient` | `external/` | OAuth token management |
| `ResilientNexonApiClient` | `external/impl/` | Circuit Breaker + Retry wrapper |

**Resilience4j Stack:**
- TimeLimiter (10s timeout)
- CircuitBreaker (50% threshold, 5min cooldown)
- Retry (3 attempts, exponential backoff)

---

## 4. Cross-Cutting

### Resilience

| Component | Location | Purpose |
|-----------|----------|---------|
| `ResilienceConfig` | `config/` | Resilience4j configuration |
| `LogicExecutor` | `global/executor/` | Exception handling patterns (6 patterns) |
| `ExecutionPipeline` | `global/executor/policy/` | Hook-based execution flow |
| `ExceptionTranslator` | `global/executor/strategy/` | Checked → Unchecked exception translation |

**Exception Hierarchy:**
```
BaseException (abstract)
├── ClientBaseException (4xx) + CircuitBreakerIgnoreMarker
│   ├── CharacterNotFoundException
│   ├── SelfLikeNotAllowedException
│   ├── DuplicateLikeException
│   └── ... (16+ client exceptions)
└── ServerBaseException (5xx) + CircuitBreakerRecordMarker
    ├── ExternalServiceException
    ├── ApiTimeoutException
    ├── CompressionException
    └── ... (20+ server exceptions)
```

**Location:** `src/main/java/maple/expectation/global/error/exception/`

### Outbox Pattern

| Component | Location | Purpose |
|-----------|----------|---------|
| `DonationOutbox` | `domain/v2/` | Outbox entity |
| `OutboxProcessor` | `service/v2/donation/outbox/` | Replay worker |
| `DlqHandler` | `service/v2/donation/outbox/` | DLQ handling |
| `NexonApiOutbox` | `domain/v2/` | Nexon API replay (N19) |
| `NexonApiOutboxScheduler` | `scheduler/` | Replay scheduler |

**Event Flow:** Transaction → Outbox → Scheduler → Event Publisher → Listener

### Auditing & Observability

| Component | Location | Purpose |
|-----------|----------|---------|
| `DiscordAlertService` | `service/v2/alert/` | Discord webhook alerts |
| `TraceAspect` | `aop/aspect/` | Request/response logging |
| `MDCFilter` | `global/filter/` | TraceId injection |
| `PerformanceStatisticsCollector` | `aop/collector/` | Metrics aggregation |
| `OpenTelemetryConfig` | `config/` | Observability setup |

### Policies

| Policy | Location | Purpose |
|--------|----------|---------|
| `CubeCostPolicy` | `service/v2/policy/` | Cube cost calculation rules |
| `RBAC` | `config/SecurityConfig` | Role-based access control |
| `AdminWhitelist` | `config/SecurityConfig` | Fingerprint-based admin verification |

---

## 5. 7 Core Modules Mapping

### 1. LogicExecutor Pipeline

**Location:** `src/main/java/maple/expectation/global/executor/`

**Key Classes:**
- `LogicExecutor` (interface) - 6 execution patterns
- `DefaultLogicExecutor` - Implementation
- `CheckedLogicExecutor` - Checked exception variant
- `TaskContext` - Metrics/Logging context
- `ExecutionPipeline` - Hook-based execution
- `ExceptionTranslator` - Exception translation strategy

**Patterns:**
1. `execute(task, context)` - Standard execution
2. `executeVoid(task, context)` - Void return
3. `executeOrDefault(task, default, context)` - Safe default
4. `executeOrCatch(task, recovery, context)` - With recovery
5. `executeWithFinally(task, finally, context)` - Finally block
6. `executeWithTranslation(task, translator, context)` - Exception translation

**Zero Try-Catch Policy:** All business logic MUST use LogicExecutor instead of try-catch.

### 2. Resilience4j

**Location:** `src/main/java/maple/expectation/config/ResilienceConfig.java`

**Configuration:**
```yaml
resilience4j:
  circuitbreaker:
    instance:
      nexon-api:
        failure-rate-threshold: 50
        wait-duration: 30s
        permitted-calls: 10
  retry:
    instance:
      nexon-api:
        max-attempts: 3
        wait-duration: 1s
```

**Marker Interfaces:**
- `CircuitBreakerIgnoreMarker` - Client exceptions (4xx)
- `CircuitBreakerRecordMarker` - Server exceptions (5xx)

### 3. TieredCache + Single-flight

**Location:** `src/main/java/maple/expectation/global/cache/`

**Key Classes:**
- `TieredCache` - L1(Caffeine) + L2(Redis) coordination
- `TieredCacheManager` - Spring Cache abstraction
- `SingleFlightExecutor` - Distributed single-flight
- `RestrictedCacheManager` - Cache with restrictions

**Write Order:** L2 → L1 (consistency guarantee)
**Single-flight:** Redisson RLock with Watchdog mode

### 4. AOP + Async

**Location:** `src/main/java/maple/expectation/aop/`

**Aspects:**
- `TraceAspect` - Request/response logging (with toString() masking)
- `LockAspect` - Distributed lock interception (@Locked)
- `NexonDataCacheAspect` - Cache interception (@NexonDataCache)
- `BufferedLikeAspect` - Like buffering (@BufferedLike)
- `ObservabilityAspect` - Metrics collection
- `SimpleLogAspect` - Simple execution time logging

**Annotations:** `@Locked`, `@TraceLog`, `@NexonDataCache`, `@BufferedLike`

**Async Configs:**
- `ExecutorConfig` - Core async executors
- `EquipmentProcessingExecutorConfig` - Equipment async
- `PresetCalculationExecutorConfig` - Preset parallel calc
- `PerCacheExecutorConfig` - Per-cache thread pool

### 5. Transactional Outbox

**Location:** `src/main/java/maple/expectation/service/v2/donation/outbox/`

**Key Classes:**
- `DonationOutbox` (entity) - Outbox table
- `OutboxProcessor` - SKIP LOCKED-based replay
- `DlqHandler` - DLQ handling
- `DlqAdminService` - DLQ management API
- `OutboxMetrics` - Metrics recording

**NexonApi Outbox (N19):**
- `NexonApiOutbox` (entity) - API replay
- `NexonApiFallbackService` - Fallback on DB degradation
- `NexonApiOutboxScheduler` - Replay worker

### 6. Graceful Shutdown

**Location:** `src/main/java/maple/expectation/service/v2/shutdown/`

**Key Classes:**
- `ShutdownDataPersistenceService` - Like buffer → file
- `ShutdownDataRecoveryService` - File → system recovery
- `EquipmentPersistenceTracker` - Equipment persistence tracking
- `PersistenceTrackerStrategy` - Tracking strategy interface
- `ExpectationBatchShutdownHandler` (V4) - 3-phase shutdown (Block → Wait → Drain)

**SmartLifecycle:** Spring lifecycle hooks for ordered shutdown

### 7. DP Calculator

**V2 Location:** `src/main/java/maple/expectation/service/v2/calculator/`
**V4 Location:** `src/main/java/maple/expectation/service/v2/calculator/v4/`

**V2 Components:**
- `ExpectationCalculator` (interface)
- `ExpectationCalculatorFactory` - Calculator builder
- `CubeRateCalculator` - Cube success probability
- `PotentialCalculator` - Potential cost
- `EnhanceDecorator` (abstract) - Decorator base
- `BaseItem`, `BlackCubeDecorator` - Concrete decorators

**V4 Components (BigDecimal precision):**
- `EquipmentExpectationCalculator` (interface)
- `EquipmentExpectationCalculatorFactory` - V4 builder
- `EquipmentEnhanceDecorator` (abstract)
- `BaseEquipmentItem`, `BlackCubeDecoratorV4`, `RedCubeDecoratorV4`, `AdditionalCubeDecoratorV4`, `StarforceDecoratorV4`

**Decorator Chain Example:**
```
BaseEquipmentItem (cost=0)
  → BlackCubeDecoratorV4 (잠재능력 있으면)
  → AdditionalCubeDecoratorV4 (추가잠재 있으면)
  → StarforceDecoratorV4 (스타포스 있으면)
```

---

## 6. Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         REQUEST FLOW (V4 Example)                          │
└─────────────────────────────────────────────────────────────────────────────┘

Client Request
       │
       ▼
┌──────────────┐
│ MDCFilter    │ → TraceId injection
└──────────────┘
       │
       ▼
┌──────────────┐
│ JwtFilter    │ → Authentication
└──────────────┘
       │
       ▼
┌──────────────┐
│ ControllerV4 │ → #264 Fast Path check (L1 direct hit?)
└──────────────┘
       │
       ├── L1 HIT ──► Return GZIP response (0.1ms)
       │
       ▼ L1 MISS
┌──────────────────────────┐
│ EquipmentExpectationSvcV4│ → @Transactional, ObjectProvider self-inject
└──────────────────────────┘
       │
       ▼
┌──────────────────────────┐
│ ExpectationCacheCoord    │ → TieredCache.get(key, valueLoader)
└──────────────────────────┘
       │
       ├── Cached ──► Return
       │
       ▼ MISS
┌──────────────────────────┐
│ TieredCache              │ → Single-flight: Redis RLock
└──────────────────────────┘
       │
       ├── Leader ──► Execute valueLoader
       │                │
       │                ▼
       │         ┌─────────────────┐
       │         │ GameCharFacade  │ → findCharacterByUserIgn()
       │         └─────────────────┘
       │                │
       │                ▼
       │         ┌─────────────────┐
       │         │ EquipmentProvider│ → getRawEquipmentData() (DB/API)
       │         └─────────────────┘
       │                │
       │                ▼
       │         ┌─────────────────┐
       │         │ StreamingParser │ → decompressIfNeeded()
       │         └─────────────────┘
       │                │
       │                ▼
       │         ┌─────────────────┐
       │         │ V4 Calculator   │ → Decorator chain (parallel presets)
       │         └─────────────────┘
       │                │
       │                ▼
       │         ┌─────────────────┐
       │         │ PersistenceSvc  │ → Write-Behind buffer offer
       │         └─────────────────┘
       │
       └── Follower ──► Wait for Leader, read L2
       │
       ▼
┌──────────────────────────┐
│ L2 Cache Put            │ → Redis Pub/Sub invalidation
└──────────────────────────┘
       │
       ▼
┌──────────────────────────┐
│ L1 Cache Put            │ → Local backfill
└──────────────────────────┘
       │
       ▼
    Response
```

### Background Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BACKGROUND FLOWS                                  │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┐
│ ExpectationBatchWriteScheduler│ (100ms fixed rate)
└──────────────────────────────┘
         │
         ▼
┌──────────────────────────────┐
│ ExpectationPersistenceService│ → buffer.drain() → batch upsert
└──────────────────────────────┘
         │
         ▼
┌──────────────────────────────┐
│ Write-Behind Buffer          │ → CAS-based concurrent drain
└──────────────────────────────┘

┌──────────────────────────────┐
│ LikeSyncScheduler            │ (10s fixed rate)
└──────────────────────────────┘
         │
         ▼
┌──────────────────────────────┐
│ LikeSyncService              │ → Lua Script atomic fetch
└──────────────────────────────┘
         │
         ├── Success → DB batchUpdate
         │
         └── Failure → DLQ → File backup + Discord alert

┌──────────────────────────────┐
│ OutboxScheduler / N19       │ (1s / 5s fixed rate)
└──────────────────────────────┘
         │
         ▼
┌──────────────────────────────┐
│ SKIP LOCKED SELECT           │ → Concurrent replay
└──────────────────────────────┘
         │
         ▼
    Event Publisher → Listener
```

---

## 7. Boundary Violations Identified

### 7.1 Infrastructure Leaking into Domain

| Violation | Location | Impact | Severity |
|-----------|----------|--------|----------|
| **JPA Annotations on Entities** | `@Entity`, `@Column` on `GameCharacter` | Domain coupled to JPA | P2 |
| **Spring @Component in Services** | All `@Service` classes | Business logic requires Spring | P2 |
| **LogicExecutor Dependency** | All services inject `LogicExecutor` | Domain coupled to infrastructure | P1 |
| **Redisson APIs in Business Logic** | `RLock`, `RTopic` usage in services | Domain depends on Redis | P1 |

**Evidence:**
- `GameCharacter.java:17-21` - Direct JPA annotations
- `EquipmentService.java:93` - `LogicExecutor` field
- `GameCharacterFacade.java:28` - `RedissonClient` field

### 7.2 Cross-Package Cycles

| Cycle | Path | Severity |
|------|------|----------|
| V2 → V4 → V2 | `service/v2/root` → `service/v4/cache` → `service/v2/facade` | P1 |
| Service → Domain → Service | `service/v2/*` → `domain/v2/*` → `global/executor/*` | P2 |
| Controller → Service → AOP → Service | `controller/*` → `service/*` → `aop/*` → `service/*` | P0 (AOP proxy) |

**Evidence:**
- `EquipmentService.java:26` - Imports `GameCharacterFacade` (v2/facade)
- V4 modules should not depend on V2 modules for independence

### 7.3 God Services

| Service | Lines of Code | Responsibilities | Severity |
|---------|---------------|------------------|----------|
| `EquipmentService` | ~400 | Orchestration, calculation, async, caching, DB | P0 |
| `EquipmentExpectationServiceV4` | ~250 | Orchestration, async, GZIP, coordination | P1 |
| `GameCharacterService` | ~300 | CRUD, caching, validation, negative/positive cache | P1 |
| `LikeSyncService` | ~200 | Sync, Lua, compensation, DLQ, recovery | P1 |

**SRP Violations:**
- `EquipmentService` handles: API calls, parsing, calculation, caching, async pipeline, DB persistence
- Should be split: `EquipmentOrchestrator`, `EquipmentCalculator`, `EquipmentCache`

**Evidence:**
- `EquipmentService.java:60-391` - 330+ lines with multiple responsibilities

### 7.4 Anemic Domain (Most Entities)

| Entity | Behavior Count | Assessment |
|--------|----------------|------------|
| `GameCharacter` | 4 methods (`like()`, `isActive()`, `validateOcid()`, `needsBasicInfoRefresh()`) | ✅ Rich Domain |
| `CharacterEquipment` | 0 methods (data holder) | ❌ Anemic |
| `CharacterLike` | 0 methods (data holder) | ❌ Anemic |
| `DonationHistory` | 0 methods (data holder) | ❌ Anemic |
| `Member` | 0 methods (data holder) | ❌ Anemic |
| `Session` | 0 methods (data holder) | ❌ Anemic |

**Issue:** Business logic scattered across services instead of encapsulated in entities.

### 7.5 Static Utility Sprawl

| Utility | Location | Functions | Impact |
|---------|----------|-----------|--------|
| `GzipUtils` | `util/` | 4 methods | Stateless (acceptable) |
| `StatParser` | `util/` | Enum + parsing | Logic hidden from domain |
| `CostFormatter` | `domain/cost/` | Static formatting | Should be domain behavior |
| `StringMaskingUtils` | `global/util/` | Masking methods | Cross-cutting (acceptable) |

**Assessment:** Moderate static utility usage. Some domain logic hidden in utilities.

### 7.6 Parallel Module Versions (V2 vs V4)

| Module | V2 Purpose | V4 Purpose | Issue |
|-------|-----------|-----------|-------|
| Calculator | `double` precision (legacy) | `BigDecimal` precision | Two parallel implementations |
| Service Root | Stable logic | Performance optimized | V4 depends on V2 |
| Cache | TieredCache basic | Singleflight + GZIP | Inconsistent caching patterns |

**Technical Debt:** V2→V4 migration incomplete, creating dual code paths.

### 7.7 Package Structure Issues

**Current Structure:**
```
maple.expectation/
├── aop/                    # Cross-cutting
├── batch/                  # Scheduled jobs
├── config/                 # Spring configs (25+ files)
├── controller/             # REST endpoints
├── domain/                 # Mixed: domain/v2 + domain root
├── dto/                    # Request/Response DTOs
├── exception/              # Moved to global/error
├── external/               # External API clients
├── global/                 # Shared utilities
├── parser/                 # Streaming parser
├── provider/               # Data providers
├── repository/             # JPA repositories
├── scheduler/              # Background jobs
├── service/                # V2 + V4 business logic
└── util/                   # Utilities
```

**Issues:**
1. **No clear layer separation:** `domain/` contains JPA entities, not pure domain
2. **No `application/` layer:** Use cases not separated from domain services
3. **`service/` monolith:** 80+ classes in single directory tree
4. **Mixed concerns:** `config/` contains both infrastructure and application config

---

## 8. SOLID Violations Summary

### SRP (Single Responsibility Principle)

| File | Violation | Evidence |
|------|-----------|----------|
| `EquipmentService` | 8+ responsibilities: orchestration, calculation, caching, async, DB, API, parsing, GZIP | Lines 60-391 |
| `EquipmentExpectationServiceV4` | Orchestration + GZIP + coordination + persistence | Lines 52-249 |
| `TieredCache` | Caching + single-flight + distributed locking + pub/sub + metrics | Lines 43-403 |
| `GameCharacterService` | CRUD + caching + validation + negative/positive cache + enrichment | ~300 LOC |

### OCP (Open/Closed Principle)

| Violation | Location | Issue |
|-----------|----------|-------|
| Hard-coded calculation versions | `EquipmentService.LOGIC_VERSION = 3` | Version change requires code modification |
| Decorator chain composition | `ExpectationCalculatorFactory` | New decorator requires factory modification |
| Cache name strings | `@Cacheable(cacheNames="equipment")` | No cache name abstraction |

### LSP (Liskov Substitution Principle)

| Violation | Location | Issue |
|-----------|----------|-------|
| `BaseException` constructors | Overloaded constructors with varargs | Inconsistent exception creation patterns |
| `Decorator` implementations | Some decorators skip calculation (early return) | Behavioral inconsistency |

### ISP (Interface Segregation Principle)

| Violation | Location | Issue |
|-----------|----------|-------|
| `LogicExecutor` | 6+ methods (SRP itself ok, but fat interface) | Clients may not need all patterns |
| `EquipmentDataProvider` | Mixed sync/async methods | Single-responsibility interface too broad |

### DIP (Dependency Inversion Principle)

| Violation | Location | Issue |
|-----------|----------|-------|
| Services depend on concrete classes | `new SingleFlightExecutor<>()` in constructor | Should inject interface |
| Domain depends on infrastructure | `@Entity` annotations | Domain should not know about persistence |
| Service depends on Spring | `@Service`, `@Transactional` | Business logic requires framework |

---

## 9. Data Coupling Analysis

### High Coupling Points

| From | To | Coupling Type | Severity |
|------|-----|--------------|----------|
| `EquipmentService` | `GameCharacterFacade` | Direct service call | P1 |
| `EquipmentService` | `LogicExecutor` | Constructor injection | P2 |
| `GameCharacterFacade` | `RedissonClient` | Constructor injection | P1 |
| `TieredCache` | `RedissonClient` | Direct Redis API | P1 |
| `LikeSyncService` | `RedissonClient` | Direct Redis API | P1 |

### Tight Cohesion Issues

| Module | Classes | Cohesion Issue |
|--------|---------|----------------|
| `service/v2/cache/` | 6 classes | Mixed: equipment cache + like buffer strategies |
| `global/cache/` | 12+ classes | TieredCache + invalidation + per-cache + metrics |
| `service/v2/like/` | 15+ classes | Compensation + events + strategies + recovery |

---

## 10. Technical Debt Summary

| Category | Count | Estimated Effort |
|----------|-------|------------------|
| God Classes | 4 | 20 story points |
| Anemic Entities | 11 | 15 story points |
| Infrastructure Leakage | 8+ locations | 10 story points |
| V2→V4 Migration | Incomplete | 30 story points |
| Package Restructuring | Monolithic structure | 25 story points |

**Total Estimated Refactoring Effort:** ~100 story points (excluding new features)

---

## References

| Reference | Location | Purpose |
|-----------|----------|---------|
| Architecture Overview | `docs/00_Start_Here/architecture.md` | System architecture |
| Service Modules | `docs/03_Technical_Guides/service-modules.md` | V2/V4 module details |
| Infrastructure Guide | `docs/03_Technical_Guides/infrastructure.md` | Redis, Cache, Security |
| Multi-Agent Protocol | `docs/00_Start_Here/multi-agent-protocol.md` | 5-Agent Council |
| CLAUDE.md | Project root | Coding standards |

---

*This document serves as the baseline for Phase 0 Clean Architecture assessment.*
*Next: Propose Target Structure with SOLID compliance and Clean Architecture layers.*
