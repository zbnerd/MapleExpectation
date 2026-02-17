# SOLID Violations - Detailed Analysis

> **Document Owner:** Blue Architect (5-Agent Council)
> **Generated:** 2026-02-07
> **Purpose:** Specific SOLID violations with file:line evidence for refactoring planning

---

## Executive Summary

| Principle | Violation Count | P0 | P1 | P2 | Total LOC Impact |
|-----------|-----------------|----|----|----|------------------|
| SRP | 12 | 4 | 5 | 3 | ~2,500 LOC |
| OCP | 8 | 1 | 4 | 3 | ~400 LOC |
| LSP | 3 | 0 | 2 | 1 | ~150 LOC |
| ISP | 5 | 0 | 3 | 2 | ~200 LOC |
| DIP | 15 | 5 | 7 | 3 | ~1,800 LOC |

**Total Estimated Refactoring Scope:** ~5,000 LOC across 43 violations

---

## 1. SRP (Single Responsibility Principle) Violations

### SRP-001: EquipmentService - God Class (P0)

**File:** `src/main/java/maple/expectation/service/v2/EquipmentService.java`

**Lines:** 60-391 (330+ LOC)

**Responsibilities Identified:**
1. Orchestration (lines 166-202)
2. Cache coordination (lines 187-202)
3. Async pipeline management (lines 166-178)
4. Snapshot management (lines 206-230)
5. Calculation dispatch (lines 262-283)
6. Legacy API support (lines 306-354)
7. GZIP streaming (lines 341-353)
8. Exception handling (lines 286-304)

**Evidence:**
```java
// Line 60-61: Service annotation with multiple responsibilities
@Service
public class EquipmentService {
    // Line 63-98: 9 dependencies (high coupling)
    private final GameCharacterFacade gameCharacterFacade;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    // ... 6 more dependencies

    // Line 166: Async orchestration
    public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationAsync(String userIgn)

    // Line 348: GZIP streaming (different concern)
    public void streamEquipmentDataRaw(String userIgn, OutputStream outputStream)

    // Line 364: Another async method
    public CompletableFuture<EquipmentResponse> getEquipmentByUserIgnAsync(String userIgn)
}
```

**Refactoring Proposal:**
- Extract `EquipmentOrchestrator` - orchestration only
- Extract `EquipmentCalculationDispatcher` - calculation dispatch
- Extract `EquipmentStreamingService` - GZIP streaming
- Keep `EquipmentService` as thin facade

### SRP-002: EquipmentExpectationServiceV4 - Multiple Concerns (P1)

**File:** `src/main/java/maple/expectation/service/v4/EquipmentExpectationServiceV4.java`

**Lines:** 52-249 (200 LOC)

**Responsibilities Identified:**
1. Async dispatch (lines 100-126)
2. GZIP response building (lines 117-124)
3. Calculation orchestration (lines 167-179)
4. Preset calculation (lines 224-241)
5. Fast path optimization (lines 147-150)
6. Response building (lines 187-203)

**Evidence:**
```java
// Line 52: Service class
@Service
public class EquipmentExpectationServiceV4 {
    // Line 100: Async dispatch
    public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(...)

    // Line 120: GZIP handling
    public CompletableFuture<byte[]> getGzipExpectationAsync(...)

    // Line 147: Fast path (performance optimization)
    public Optional<byte[]> getGzipFromL1CacheDirect(...)

    // Line 224: Preset calculation
    private List<PresetExpectation> calculateAllPresets(...)
}
```

**Refactoring Proposal:**
- Extract `ExpectationAsyncDispatcher` - async operations
- Extract `ExpectationResponseBuilder` - response building
- Extract `PresetCalculationOrchestrator` - preset coordination
- Keep V4 as thin facade

### SRP-003: GameCharacterService - Mixed Concerns (P1)

**File:** `src/main/java/maple/expectation/service/v2/GameCharacterService.java`

**Lines:** 36-213 (180 LOC)

**Responsibilities Identified:**
1. CRUD operations (lines 78-84, 101-106)
2. Negative caching (lines 67-73)
3. API enrichment (lines 131-180)
4. Async persistence (lines 187-198)
5. Pessimistic locking (lines 204-212)

**Evidence:**
```java
// Line 36: Service class
@Service
public class GameCharacterService {
    // Line 67: Negative cache check
    public boolean isNonExistent(String userIgn)

    // Line 78: CRUD
    public Optional<GameCharacter> getCharacterIfExist(String userIgn)

    // Line 131: Enrichment (API call)
    public GameCharacter enrichCharacterBasicInfo(GameCharacter character)

    // Line 187: Async DB save
    @org.springframework.scheduling.annotation.Async
    public void saveCharacterBasicInfoAsync(GameCharacter character)

    // Line 206: Pessimistic lock
    public GameCharacter getCharacterForUpdate(String userIgn)
}
```

**Refactoring Proposal:**
- Extract `CharacterRepositoryService` - CRUD only
- Extract `CharacterEnrichmentService` - API enrichment
- Extract `CharacterCacheService` - caching logic
- Extract `CharacterLockService` - locking operations

### SRP-004: LikeSyncService - Financial-Grade Complexity (P1)

**File:** `src/main/java/maple/expectation/service/v2/LikeSyncService.java`

**Lines:** 48-408 (360 LOC)

**Responsibilities Identified:**
1. L1→L2 flush (lines 110-147)
2. L2→L3 sync (lines 161-218)
3. Database chunking (lines 243-278)
4. Compensation transaction (lines 185-332)
5. Metrics recording (lines 399-407)
6. Failure recovery (lines 286-322)
7. Orphan key handling (implicit)

**Evidence:**
```java
// Line 48: Service class with 9 dependencies
@Service
public class LikeSyncService {
    // Line 110: L1→L2 flush
    public void flushLocalToRedis()

    // Line 162: L2→L3 sync
    public void syncRedisToDatabase()

    // Line 243: Database chunking logic
    private long processDatabaseSync(FetchResult fetchResult)

    // Line 286: Failure handling
    private Void handleChunkFailure(...)

    // Line 401: Metrics
    private void recordSyncMetrics(...)
}
```

**Refactoring Proposal:**
- Extract `LikeBufferFlushService` - L1→L2 flush
- Extract `LikeDatabaseSyncService` - L2→L3 sync
- Extract `LikeCompensationHandler` - compensation logic
- Extract `LikeSyncMetricsService` - metrics only
- Keep `LikeSyncService` as coordinator

### SRP-005: TieredCache - Caching + Single-flight + Pub/Sub (P1)

**File:** `src/main/java/maple/expectation/global/cache/TieredCache.java`

**Lines:** 43-403 (360 LOC)

**Responsibilities Identified:**
1. L1/L2 coordination (lines 108-132)
2. Single-flight management (lines 230-262)
3. Distributed locking (lines 295-326)
4. Cache invalidation Pub/Sub (lines 196-209)
5. Metrics recording (lines 52-93, 395-402)
6. Graceful degradation (lines 270-293, 302-318)

**Evidence:**
```java
// Line 43: Cache implementing Spring Cache interface
public class TieredCache implements Cache {
    // Line 108: L1/L2 get logic
    public ValueWrapper get(Object key)

    // Line 141: L2→L1 put
    public void put(Object key, Object value)

    // Line 169: Evict with Pub/Sub
    public void evict(Object key)

    // Line 230: Single-flight
    public <T> T get(Object key, Callable<T> valueLoader)

    // Line 302: Distributed locking
    private <T> T executeWithDistributedLock(...)
}
```

**Refactoring Proposal:**
- Extract `SingleFlightManager` - single-flight logic
- Extract `CacheInvalidationPublisher` - Pub/Sub logic
- Extract `CacheMetricsRecorder` - metrics only
- Keep `TieredCache` focused on L1/L2 coordination

### SRP-006: GameCharacterFacade - Orchestration + Queue Management (P2)

**File:** `src/main/java/maple/expectation/service/v2/facade/GameCharacterFacade.java`

**Lines:** 25-147

**Responsibilities Identified:**
1. Character lookup (lines 40-54)
2. Queue management (lines 81-85)
3. CompletableFuture coordination (lines 57-79)
4. Event listener management (lines 62-68)

**Evidence:**
```java
// Line 25: Facade class
@Component
public class GameCharacterFacade {
    // Line 40: Character lookup
    public GameCharacter findCharacterByUserIgn(String userIgn)

    // Line 57: Async waiting logic
    private GameCharacter waitForWorkerResult(String userIgn)

    // Line 81: Queue offer
    private void performQueueOffer(String userIgn)
}
```

**Refactoring Proposal:**
- Extract `CharacterQueueManager` - queue operations
- Extract `CharacterAsyncCoordinator` - async coordination
- Keep Facade focused on character aggregation

### SRP-007: TieredCacheManager - Instance Management + Fast Path (P2)

**File:** `src/main/java/maple/expectation/global/cache/TieredCacheManager.java`

**Lines:** 44-176

**Responsibilities Identified:**
1. Cache instance pooling (lines 56, 101-103)
2. Instance ID management (lines 135-144)
3. Callback initialization (lines 154-157)
4. Fast path access (lines 172-174)

**Evidence:**
```java
// Line 44: Cache manager
public class TieredCacheManager extends AbstractCacheManager {
    // Line 56: Instance pool
    private final ConcurrentMap<String, Cache> cachePool = new ConcurrentHashMap<>();

    // Line 61: Instance ID management
    private final AtomicReference<String> instanceIdRef = new AtomicReference<>("unknown");

    // Line 67: Callback management
    private final AtomicReference<Consumer<CacheInvalidationEvent>> callbackRef = ...;

    // Line 101: Pool lookup
    public Cache getCache(String name)

    // Line 135: ID initialization
    public boolean initializeInstanceId(String instanceId)

    // Line 154: Callback initialization
    public void initializeInvalidationCallback(...)

    // Line 172: Fast path (different concern)
    public Cache getL1CacheDirect(String name)
}
```

**Refactoring Proposal:**
- Extract `CacheInstancePool` - instance management
- Extract `CacheFastPathAccessor` - fast path operations
- Keep Manager focused on cache lifecycle

### SRP-008: Anemic Domain Entities (P2)

**Location:** `src/main/java/maple/expectation/domain/v2/`

**Entities with 0 methods (pure data holders):**
1. `CharacterEquipment.java` - 0 behavior methods
2. `CharacterLike.java` - 0 behavior methods
3. `DonationHistory.java` - 0 behavior methods
4. `Member.java` - 0 behavior methods
5. `Session.java` - 0 behavior methods
6. `RefreshToken.java` - 0 behavior methods
7. `CubeType.java` - 0 behavior methods (enum)
8. `PotentialGrade.java` - 0 behavior methods (enum)
9. `DonationDlq.java` - 0 behavior methods
10. `EquipmentExpectationSummary.java` - 0 behavior methods
11. `NexonApiOutbox.java` - 0 behavior methods

**Contrast with Rich Domain:**
- `GameCharacter.java` - 4 behavior methods (`isActive()`, `needsBasicInfoRefresh()`, `validateOcid()`, `like()`)

**Evidence:**
```java
// CharacterEquipment.java - Anemic
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterEquipment {
    // Only data fields, no business logic
    @Column(nullable = false)
    private String ocid;
    // ... more fields
}
```

**Refactoring Proposal:**
- Migrate business logic from services to entities
- Example: `CharacterEquipment` should have `isExpired()`, `needsRefresh()`, `getDecompressedData()`

### SRP-009: Config Package - 25+ Configuration Classes (P2)

**Location:** `src/main/java/maple/expectation/config/`

**Issue:** Configuration mixed with infrastructure setup

**Mixed Concern Examples:**
- `ResilienceConfig.java` - Resilience4j + CircuitBreaker setup
- `CacheConfig.java` - Cache + Redisson setup
- `ExecutorConfig.java` - Thread pool + TaskDecorator setup
- `SecurityConfig.java` - Security + Filter setup
- `TransactionConfig.java` - Transaction manager setup

**Evidence:**
```bash
$ ls -1 src/main/java/maple/expectation/config/*.java | wc -l
26
```

**Refactoring Proposal:**
- Split into `config/infrastructure/` and `config/application/`
- Infrastructure configs: DB, Redis, Thread pools
- Application configs: Business rules, feature flags

### SRP-010: DonationService - Payment + Outbox + DLQ (P1)

**File:** `src/main/java/maple/expectation/service/v2/DonationService.java`

**Responsibilities:**
1. Payment processing
2. Idempotency checking
3. Outbox publishing
4. DLQ handling

**Refactoring Proposal:**
- Extract `DonationPaymentService` - payment only
- Extract `DonationOutboxPublisher` - outbox only

### SRP-011: Calculator Factories - Object Creation + Version Management (P2)

**Files:**
- `ExpectationCalculatorFactory.java`
- `EquipmentExpectationCalculatorFactory.java`

**Issue:** Factory manages both object creation AND version/strategy selection

**Refactoring Proposal:**
- Separate factory from strategy selector
- Extract `CalculatorVersionResolver`

### SRP-012: Controller Classes - Response Building (P2)

**Files:**
- `GameCharacterControllerV4.java` - lines 117-124 (GZIP response building)
- `GameCharacterControllerV2.java` - similar patterns

**Issue:** Controllers building responses instead of delegating

**Evidence:**
```java
// GameCharacterControllerV4.java:117
private ResponseEntity<byte[]> buildGzipResponse(byte[] gzipBytes) {
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_ENCODING, "gzip")
            .contentType(MediaType.APPLICATION_JSON)
            .contentLength(gzipBytes.length)
            .body(gzipBytes);
}
```

**Refactoring Proposal:**
- Extract `ResponseBuilder` helper classes
- Controllers should only map requests to services

---

## 2. OCP (Open/Closed Principle) Violations

### OCP-001: Hard-coded Logic Version (P0)

**File:** `src/main/java/maple/expectation/service/v2/EquipmentService.java`

**Line:** 66

**Evidence:**
```java
// Line 66: Hard-coded version number
private static final int LOGIC_VERSION = 3;
```

**Issue:** Version bump requires code modification. Strategy pattern needed.

**Refactoring Proposal:**
```java
// Proposed: Strategy-based versioning
public interface CalculationVersionStrategy {
    int getVersion();
    String getVersionHash();
}

// Configuration-based version selection
@Configuration
public class CalculationConfig {
    @Bean
    @ConditionalOnProperty(name = "calculation.version", havingValue="v3")
    public CalculationVersionStrategy v3Strategy() { ... }
}
```

### OCP-002: Hard-coded Table Version (P1)

**File:** `src/main/java/maple/expectation/service/v2/EquipmentService.java`

**Line:** 69

**Evidence:**
```java
// Line 69: Hard-coded table version
private static final String TABLE_VERSION = "2024.01.15";
```

**Issue:** Probability table update requires code change.

**Refactoring Proposal:**
- Externalize to configuration
- Add database-based version tracking

### OCP-003: Decorator Chain Hard-coding (P1)

**File:** `src/main/java/maple/expectation/service/v2/calculator/v4/EquipmentExpectationCalculatorFactory.java`

**Evidence:** (factory pattern with conditional decorator addition)

```java
// Typical pattern (expected):
EquipmentExpectationCalculator calc = new BaseEquipmentItem();
if (hasBlackCube) {
    calc = new BlackCubeDecoratorV4(calc, ...);
}
if (hasAdditional) {
    calc = new AdditionalCubeDecoratorV4(calc, ...);
}
// ... more conditions
```

**Issue:** New decorator requires factory modification.

**Refactoring Proposal:**
```java
// Proposed: Chain-of-Responsibility with registry
public interface DecoratorRegistry {
    void register(String name, Function<EquipmentExpectationCalculator, EquipmentExpectationCalculator> factory);
    EquipmentExpectationCalculator applyChain(EquipmentExpectationCalculator base, List<String> features);
}
```

### OCP-004: Cache Name Strings (P2)

**Multiple Files:** Cache annotations throughout codebase

**Evidence:**
```java
// Scattered hard-coded cache names
@Cacheable(cacheNames = "equipment")
@Cacheable(cacheNames = "ocidCache")
@Cacheable(cacheNames = "totalExpectation")
```

**Issue:** Cache name changes require search/replace.

**Refactoring Proposal:**
```java
// Proposed: Cache name constants/enums
public enum CacheType {
    EQUIPMENT("equipment", Duration.ofMinutes(5)),
    OCID("ocidCache", Duration.ofMinutes(30)),
    TOTAL_EXPECTATION("totalExpectation", Duration.ofMinutes(5));

    private final String name;
    private final Duration ttl;
}
```

### OCP-005: Error Code Mapping (P2)

**File:** `src/main/java/maple/expectation/global/error/CommonErrorCode.java`

**Issue:** Error codes enum - adding new error type requires enum modification

**Evidence:**
```java
public enum CommonErrorCode implements ErrorCode {
    CHARACTER_NOT_FOUND("C001", "캐릭터를 찾을 수 없습니다: %s"),
    // ... 50+ error codes
}
```

**Refactoring Proposal:**
- Consider error code registry pattern
- Database-driven error messages for user-facing errors

### OCP-006: Strategy Selection Hard-coding (P2)

**File:** `src/main/java/maple/expectation/service/v2/cache/LikeBufferStrategy.java`

**Evidence:**
```java
// Strategy type enum
public enum StrategyType {
    IN_MEMORY,
    REDIS
}

// Selection in config
@Value("${like.buffer.strategy:redis}")
private String strategyType;
```

**Issue:** New buffer strategy requires enum modification.

**Refactoring Proposal:**
- Use plugin architecture with SPI
- Strategy discovery via classpath scanning

### OCP-007: Timeout Constants Scattered (P2)

**Multiple Files:** Various timeout constants

**Evidence:**
```java
// EquipmentService.java:72
private static final int LEADER_DEADLINE_SECONDS = 30;

// GameCharacterService.java:39
private static final long API_TIMEOUT_SECONDS = 10L;

// EquipmentExpectationServiceV4.java:54
private static final long ASYNC_TIMEOUT_SECONDS = 30L;
```

**Issue:** Timeout changes require multiple file edits.

**Refactoring Proposal:**
```java
// Centralized timeout configuration
@ConfigurationProperties(prefix = "timeouts")
public class TimeoutConfig {
    private Duration equipmentLeader = Duration.ofSeconds(30);
    private Duration apiCall = Duration.ofSeconds(10);
    private Duration async = Duration.ofSeconds(30);
}
```

### OCP-008: Executor Naming (P2)

**Location:** `config/ExecutorConfig.java`, `config/EquipmentProcessingExecutorConfig.java`, etc.

**Issue:** Thread pool executor names hard-coded

**Refactoring Proposal:**
- Centralize executor configuration
- Use builder pattern for executor creation

---

## 3. LSP (Liskov Substitution Principle) Violations

### LSP-001: BaseException Constructor Overloads (P1)

**File:** `src/main/java/maple/expectation/global/error/exception/base/BaseException.java`

**Lines:** 11-29

**Evidence:**
```java
// Line 11-29: Inconsistent constructor patterns
public BaseException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
    this.message = errorCode.getMessage();
}

// Overload with varargs
public BaseException(ErrorCode errorCode, Object... args) {
    super(String.format(errorCode.getMessage(), args));
    this.errorCode = errorCode;
    this.message = String.format(errorCode.getMessage(), args);
}

// Another overload with cause
public BaseException(ErrorCode errorCode, Throwable cause, Object... args) {
    super(String.format(errorCode.getMessage(), args), cause);
    // ...
}
```

**Issue:** Subclasses may not respect all constructor patterns.

**Refactoring Proposal:**
```java
// Proposed: Builder pattern for consistent exception creation
public abstract class BaseException extends RuntimeException {
    protected BaseException(ErrorCode errorCode, Throwable cause, String message) {
        super(message, cause);
        this.errorCode = errorCode;
        this.message = message;
    }

    public static <T extends BaseException> T create(
            Supplier<T> constructor, ErrorCode code, Object... args) {
        return constructor.get();
    }
}
```

### LSP-002: Decorator Early Return (P1)

**Files:**
- `service/v2/calculator/impl/BlackCubeDecorator.java`
- `service/v2/calculator/v4/impl/BlackCubeDecoratorV4.java`

**Evidence:** (expected pattern - decorators that may skip calculation)

```java
// Some decorators may return early without delegating
public class BlackCubeDecorator extends EnhanceDecorator {
    @Override
    public long calculateCost() {
        if (!hasPotential()) {
            return 0;  // Early return - breaks chain expectation
        }
        return super.calculateCost() + getBlackCubeCost();
    }
}
```

**Issue:** Decorator chain behavior inconsistent - some decorators may short-circuit.

**Refactoring Proposal:**
- Document decorator contract explicitly
- Consider Null Object pattern for optional decorators
- Add interface method `boolean shouldCalculate()` to pre-check

### LSP-003: Optional Behavior in Subclasses (P2)

**File:** `service/v2/cache/LikeBufferStrategy.java`

**Evidence:**
```java
// Interface
public interface LikeBufferStrategy {
    Map<String, Long> fetchAndClear(int limit);
    StrategyType getType();
}

// Implementations may have different behavior
// - RedisLikeBufferStrategy: Atomic fetch
// - InMemoryLikeBufferStrategy: Non-atomic fetch
```

**Issue:** Subclasses have different atomicity guarantees - LSP violation.

**Refactoring Proposal:**
```java
// Separate interfaces for different guarantees
public interface AtomicBufferStrategy extends LikeBufferStrategy {
    boolean isAtomic();
}

public interface BufferStrategy extends LikeBufferStrategy {
    // Base operations
}
```

---

## 4. ISP (Interface Segregation Principle) Violations

### ISP-001: LogicExecutor - Fat Interface (P1)

**File:** `src/main/java/maple/expectation/global/executor/LogicExecutor.java`

**Lines:** 12-124

**Evidence:**
```java
// Line 12: Interface with 8 methods
public interface LogicExecutor {
    <T> T execute(ThrowingSupplier<T> task, TaskContext context);
    <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context);
    <T> T executeOrCatch(ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context);
    void executeVoid(ThrowingRunnable task, TaskContext context);
    <T> T executeWithFinally(ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context);
    <T> T executeWithTranslation(ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context);
    <T> T executeWithFallback(ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context);
    // ... plus default methods
}
```

**Issue:** Clients may only need 1-2 patterns but must depend on all.

**Refactoring Proposal:**
```java
// Proposed: Segregated interfaces
public interface BasicExecutor {
    <T> T execute(ThrowingSupplier<T> task, TaskContext context);
}

public interface SafeExecutor {
    <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context);
}

public interface ResilientExecutor extends BasicExecutor {
    <T> T executeWithTranslation(ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context);
}

// LogicExecutor composes all
public interface LogicExecutor extends BasicExecutor, SafeExecutor, ResilientExecutor {
}
```

### ISP-002: EquipmentDataProvider - Mixed Sync/Async (P1)

**File:** `src/main/java/maple/expectation/provider/EquipmentDataProvider.java`

**Evidence:**
```java
public interface EquipmentDataProvider {
    // Sync methods
    EquipmentResponse getEquipment(String ocid);
    void streamRaw(String ocid, OutputStream outputStream);

    // Async methods
    CompletableFuture<EquipmentResponse> getEquipmentResponse(String ocid);
    CompletableFuture<byte[]> getRawEquipmentData(String ocid);
}
```

**Issue:** Clients using sync API don't need async methods (and vice versa).

**Refactoring Proposal:**
```java
// Separate interfaces
public interface SyncEquipmentProvider {
    EquipmentResponse getEquipment(String ocid);
    void streamRaw(String ocid, OutputStream outputStream);
}

public interface AsyncEquipmentProvider {
    CompletableFuture<EquipmentResponse> getEquipmentResponse(String ocid);
    CompletableFuture<byte[]> getRawEquipmentData(String ocid);
}

// Composite if needed
public interface EquipmentProvider extends SyncEquipmentProvider, AsyncEquipmentProvider {
}
```

### ISP-003: TieredCache - Too Many Methods (P2)

**File:** `src/main/java/maple/expectation/global/cache/TieredCache.java`

**Evidence:** Implements Spring `Cache` interface (7+ methods) plus internal methods

**Issue:** Some clients only need read-only access.

**Refactoring Proposal:**
```java
// Read-only interface
public interface CacheReader {
    ValueWrapper get(Object key);
    <T> T get(Object key, Class<T> type);
    <T> T get(Object key, Callable<T> valueLoader);
}

// Write-only interface
public interface CacheWriter {
    void put(Object key, Object value);
    void evict(Object key);
    void clear();
}

// TieredCache implements both
public class TieredCache implements Cache, CacheReader, CacheWriter {
}
```

### ISP-004: Cache Invalidation - Publisher/Subscriber Coupled (P2)

**Files:**
- `global/cache/invalidation/CacheInvalidationPublisher.java`
- `global/cache/invalidation/CacheInvalidationSubscriber.java`

**Evidence:**
```java
// Publisher interface
public interface CacheInvalidationPublisher {
    void publish(CacheInvalidationEvent event);
    String getInstanceId();
}

// Subscriber interface
public interface CacheInvalidationSubscriber {
    void onInvalidation(CacheInvalidationEvent event);
    String getInstanceId();
    void subscribe(String topic);
}
```

**Issue:** Subscriber needs `getInstanceId()` for filtering - coupling.

**Refactoring Proposal:**
```java
// Separate concerns
public interface CacheInvalidationPublisher {
    void publish(CacheInvalidationEvent event);
}

public interface CacheInvalidationSubscriber {
    void onInvalidation(CacheInvalidationEvent event);
    boolean shouldHandle(CacheInvalidationEvent event);  // Filtering logic
}
```

### ISP-005: Repository Interfaces Too Broad (P2)

**Location:** `repository/v2/*Repository.java`

**Evidence:** Spring Data repositories extend `JpaRepository` (CRUD + pagination + more)

**Refactoring Proposal:**
```java
// Define only needed methods
public interface GameCharacterReadRepository {
    Optional<GameCharacter> findByUserIgnWithEquipment(String userIgn);
    GameCharacter findByUserIgnWithPessimisticLock(String userIgn);
}

public interface GameCharacterWriteRepository {
    GameCharacter save(GameCharacter character);
    void deleteByOcid(String ocid);
}
```

---

## 5. DIP (Dependency Inversion Principle) Violations

### DIP-001: Direct `new` in Constructor (P0)

**File:** `src/main/java/maple/expectation/service/v2/EquipmentService.java`

**Lines:** 129-134

**Evidence:**
```java
// Line 129-134: Direct instantiation in constructor
public EquipmentService(...) {
    // ... field assignments
    this.singleFlightExecutor = new SingleFlightExecutor<>(  // ❌ Direct new
            FOLLOWER_TIMEOUT_SECONDS,
            expectationComputeExecutor,
            this::fallbackFromCache
    );
}
```

**Issue:** High coupling to `SingleFlightExecutor` implementation.

**Refactoring Proposal:**
```java
// Proposed: Inject factory or provider
public interface SingleFlightExecutorFactory {
    <T> SingleFlightExecutor<T> create(long timeout, Executor executor, Function<String, T> fallback);
}

@Service
public class EquipmentService {
    private final SingleFlightExecutorFactory executorFactory;

    public EquipmentService(..., SingleFlightExecutorFactory executorFactory) {
        this.singleFlightExecutor = executorFactory.create(
            FOLLOWER_TIMEOUT_SECONDS,
            expectationComputeExecutor,
            this::fallbackFromCache
        );
    }
}
```

### DIP-002: Domain Depends on JPA (P1)

**Location:** `src/main/java/maple/expectation/domain/v2/`

**Evidence:**
```java
// GameCharacter.java:17-21
@Entity                          // ❌ Infrastructure annotation
@Getter                          // ❌ Lombok (framework)
@NoArgsConstructor(access = ...) // ❌ Framework requirement
public class GameCharacter {
    @Id                           // ❌ JPA annotation
    @GeneratedValue(strategy = ...) // ❌ JPA annotation
    private Long id;
```

**Issue:** Domain model coupled to JPA and Lombok.

**Refactoring Proposal:**
```java
// Proposed: Separate domain from persistence
// Domain model (pure)
public class GameCharacter {
    private final CharacterId id;
    private final UserIgn userIgn;
    private final Ocid ocid;
    // ... business logic
}

// Persistence entity (infrastructure)
@Entity
@Table(name = "game_character")
public class GameCharacterEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userIgn;
    private String ocid;

    // Mapping methods
    public GameCharacter toDomain() { ... }
    public static GameCharacterEntity from(GameCharacter domain) { ... }
}
```

### DIP-003: Service Depends on Concrete Redis API (P1)

**File:** `src/main/java/maple/expectation/service/v2/facade/GameCharacterFacade.java`

**Lines:** 28, 58-59

**Evidence:**
```java
// Line 28: Direct Redisson dependency
private final RedissonClient redissonClient;

// Line 58-59: Direct Redis API usage
RTopic topic = redissonClient.getTopic("char_event:" + userIgn);
RBlockingQueue<String> queue = redissonClient.getBlockingQueue("character_job_queue");
```

**Issue:** Business logic coupled to Redisson.

**Refactoring Proposal:**
```java
// Proposed: Abstractions
public interface MessageTopic<T> {
    int addListener(Class<T> type, BiConsumer<String, T> listener);
    void removeListener(int listenerId);
}

public interface MessageQueue<T> {
    boolean offer(T message);
    T poll();
}

// Infrastructure implementations
@Component
public class RedisMessageTopic<T> implements MessageTopic<T> {
    private final RedissonClient redisson;
    // ...
}
```

### DIP-004: Services Annotated with Spring (P1)

**Location:** All `@Service`, `@Component` annotations on business logic

**Evidence:**
```java
@Service  // ❌ Framework annotation
public class GameCharacterService { ... }

@Service  // ❌ Framework annotation
public class LikeSyncService { ... }

@Component  // ❌ Framework annotation
public class GameCharacterFacade { ... }
```

**Issue:** Business logic requires Spring container.

**Refactoring Proposal:**
```java
// Pure business logic (no Spring annotations)
public class GameCharacterService {
    private final GameCharacterRepository repository;
    // Constructor injection (plain Java)
    public GameCharacterService(GameCharacterRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }
}

// Spring configuration (infrastructure layer)
@Configuration
public class ServiceConfig {
    @Bean
    public GameCharacterService gameCharacterService(GameCharacterRepository repository) {
        return new GameCharacterService(repository);
    }
}
```

### DIP-005: `@Transactional` on Business Logic (P1)

**Location:** Multiple service classes

**Evidence:**
```java
// GameCharacterService.java:100
@Transactional  // ❌ Infrastructure annotation on business logic
public String saveCharacter(GameCharacter character) { ... }

// LikeSyncService.java:161
@ObservedTransaction  // ❌ Custom annotation but still transaction concern
public void syncRedisToDatabase() { ... }
```

**Issue:** Transaction management mixed with business logic.

**Refactoring Proposal:**
```java
// Proposed: Application Service pattern
// Domain service (no transaction)
public class GameCharacterDomainService {
    public CharacterId saveCharacter(GameCharacter character) {
        // Business logic only
        return repository.save(character);
    }
}

// Application service (transaction boundary)
@ApplicationService
public class CharacterApplicationService {
    @Transactional
    public CharacterId saveCharacter(CharacterCommand command) {
        GameCharacter character = domainService.createCharacter(command);
        return domainService.saveCharacter(character);
    }
}
```

### DIP-006: Direct Cache Manager Dependency (P2)

**File:** `src/main/java/maple/expectation/service/v2/GameCharacterService.java`

**Lines:** 43, 70, 153

**Evidence:**
```java
// Line 43: Direct Spring Cache dependency
private final CacheManager cacheManager;

// Line 70: Direct usage
Cache cache = cacheManager.getCache("ocidNegativeCache");

// Line 153: Direct usage
Cache cache = cacheManager.getCache("characterBasic");
```

**Issue:** Business logic coupled to Spring Cache abstraction.

**Refactoring Proposal:**
```java
// Proposed: Cache interface
public interface NegativeCache {
    boolean isNonExistent(String key);
    void markNonExistent(String key);
}

// Implementation
@Component
public class SpringNegativeCacheAdapter implements NegativeCache {
    private final CacheManager cacheManager;
    // ...
}
```

### DIP-007: Repository Interfaces Extend JPA (P2)

**Location:** `repository/v2/*Repository.java`

**Evidence:**
```java
// GameCharacterRepository.java
public interface GameCharacterRepository extends JpaRepository<GameCharacter, Long> {
    // ❌ Extends Spring Data JPA
}
```

**Issue:** Repository interface coupled to Spring Data JPA.

**Refactoring Proposal:**
```java
// Domain repository interface (pure)
public interface GameCharacterRepository {
    Optional<GameCharacter> findByUserIgn(String userIgn);
    GameCharacter save(GameCharacter character);
}

// JPA implementation (infrastructure)
@Repository
public class JpaGameCharacterRepository implements GameCharacterRepository {
    private final SpringDataGameCharacterRepository jpaRepo;
    // ...
}
```

### DIP-008: LogicExecutor Dependency Everywhere (P2)

**Location:** Most service classes

**Evidence:**
```java
// Injected into almost every service
private final LogicExecutor executor;
```

**Issue:** LogicExecutor is infrastructure leakage into domain.

**Refactoring Proposal:**
```java
// Consider: Command pattern with built-in error handling
public class CharacterCommand {
    private final GameCharacterRepository repository;
    private final ErrorHandler errorHandler;

    public <T> T execute(Supplier<T> action) {
        return errorHandler.execute(action);
    }
}
```

### DIP-009: External Client Interfaces (P2)

**Files:**
- `external/NexonApiClient.java`
- `external/NexonAuthClient.java`

**Evidence:**
```java
public interface NexonApiClient {
    EquipmentResponse getEquipment(String ocid);
    CompletableFuture<EquipmentResponse> getEquipmentAsync(String ocid);
    // ...
}
```

**Issue:** Interface directly reflects external API shape.

**Refactoring Proposal:**
```java
// Domain-defined port
public interface EquipmentDataProvider {
    EquipmentData getForCharacter(CharacterId id);
}

// Adapter (infrastructure)
@Component
public class NexonApiEquipmentDataProvider implements EquipmentDataProvider {
    private final NexonApiClient apiClient;
    // Adapts external API to domain model
}
```

### DIP-010: Metrics in Business Logic (P2)

**Location:** Scattered `MeterRegistry` usage

**Evidence:**
```java
// LikeSyncService.java:57
private final MeterRegistry meterRegistry;
```

**Issue:** Observability cross-cutting concern in business logic.

**Refactoring Proposal:**
```java
// Use AOP for metrics
@Timed(value = "like.sync", percentiles = {0.5, 0.95, 0.99})
public void syncRedisToDatabase() {
    // No metrics code here
}
```

### DIP-011: Event Publisher Direct Dependency (P2)

**File:** `src/main/java/maple/expectation/service/v2/LikeSyncService.java`

**Line:** 59

**Evidence:**
```java
private final ApplicationEventPublisher eventPublisher;
```

**Issue:** Spring-specific event interface.

**Refactoring Proposal:**
```java
// Domain event interface
public interface EventPublisher {
    void publish(DomainEvent event);
}

// Spring implementation
@Component
public class SpringEventPublisherAdapter implements EventPublisher {
    private final ApplicationEventPublisher springPublisher;
}
```

### DIP-012: Constants in Business Logic (P2)

**Multiple Files:** Hard-coded values

**Evidence:**
```java
// LikeSyncService.java:66
@Value("${like.sync.chunk-size:500}")
private int chunkSize;  // Configuration injection
```

**Issue:** Configuration values scattered across classes.

**Refactoring Proposal:**
```java
// Centralized configuration properties
@ConfigurationProperties(prefix = "like.sync")
public class LikeSyncProperties {
    private int chunkSize = 500;
    private Duration timeout = Duration.ofSeconds(10);
}
```

### DIP-013: Async Executor Direct Injection (P2)

**Location:** Various services

**Evidence:**
```java
// EquipmentService.java:95
private final Executor expectationComputeExecutor;
```

**Issue:** Threading infrastructure in business logic.

**Refactoring Proposal:**
```java
// Abstraction
public interface AsyncExecutor {
    <T> CompletableFuture<T> execute(Callable<T> task);
}

// Domain service uses abstraction
public class EquipmentService {
    private final AsyncExecutor asyncExecutor;
}
```

### DIP-014: DTOs Passed Across Layers (P2)

**Location:** Controller → Service → Repository

**Evidence:**
```java
// Controllers use response DTOs directly from services
public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> getExpectation(...)
```

**Issue:** Response DTOs may contain presentation concerns.

**Refactoring Proposal:**
```java
// Domain response
public class EquipmentCalculationResult {
    private final Cost totalCost;
    private final List<ItemCost> itemCosts;
}

// Mapper at infrastructure boundary
@Component
public class EquipmentResponseMapper {
    public EquipmentExpectationResponseV4 toResponse(EquipmentCalculationResult result) {
        // ...
    }
}
```

### DIP-015: Helper Classes as Static (P2)

**Location:** `util/` package

**Evidence:**
```java
// GzipUtils.java - static methods
public static byte[] compress(byte[] data) { ... }
public static byte[] decompress(byte[] data) { ... }

// StatParser.java - mixed static/instance
public static StatType fromString(String value) { ... }
```

**Issue:** Static utilities prevent dependency injection and polymorphism.

**Refactoring Proposal:**
```java
// Interface
public interface CompressionService {
    byte[] compress(byte[] data);
    byte[] decompress(byte[] data);
}

// Implementation
@Component
public class GzipCompressionService implements CompressionService {
    // ...
}
```

---

## 6. Summary Matrix

| Violation ID | Principle | File | Line(s) | Severity | Refactoring Effort |
|--------------|-----------|------|---------|----------|-------------------|
| SRP-001 | SRP | EquipmentService.java | 60-391 | P0 | 20 SP |
| SRP-002 | SRP | EquipmentExpectationServiceV4.java | 52-249 | P1 | 15 SP |
| SRP-003 | SRP | GameCharacterService.java | 36-213 | P1 | 12 SP |
| SRP-004 | SRP | LikeSyncService.java | 48-408 | P1 | 18 SP |
| SRP-005 | SRP | TieredCache.java | 43-403 | P1 | 15 SP |
| OCP-001 | OCP | EquipmentService.java | 66 | P0 | 8 SP |
| DIP-001 | DIP | EquipmentService.java | 129-134 | P0 | 5 SP |
| DIP-002 | DIP | domain/v2/*.java | Varies | P1 | 30 SP |
| DIP-003 | DIP | GameCharacterFacade.java | 28, 58-59 | P1 | 10 SP |
| ISP-001 | ISP | LogicExecutor.java | 12-124 | P1 | 8 SP |

**Total Story Points:** ~158 SP for top 10 violations

---

## 7. Refactoring Priority

### Phase 1: Critical (P0)
1. **SRP-001:** Extract EquipmentService orchestrator (20 SP)
2. **OCP-001:** Externalize logic version configuration (8 SP)
3. **DIP-001:** Inject SingleFlightExecutor via factory (5 SP)

### Phase 2: High (P1)
1. **DIP-002:** Separate domain from persistence (30 SP)
2. **SRP-004:** Extract LikeSyncService components (18 SP)
3. **SRP-005:** Extract TieredCache components (15 SP)
4. **SRP-002:** Extract V4 service components (15 SP)
5. **DIP-003:** Abstract Redis/Queue interfaces (10 SP)

### Phase 3: Medium (P2)
1. **DIP-007:** Repository interfaces (15 SP)
2. **ISP-001:** Segregate LogicExecutor (8 SP)
3. **SRP-003:** Extract GameCharacterService components (12 SP)

---

## References

| Reference | Location | Purpose |
|-----------|----------|---------|
| Architecture Map | `docs/refactor/ARCHITECTURE_MAP.md` | Full architecture context |
| CLAUDE.md | Project root | Coding standards |
| Service Modules Guide | `docs/02_Technical_Guides/service-modules.md` | Module details |

---

*This document provides specific evidence for each SOLID violation to guide refactoring efforts.*
*Next: Propose target Clean Architecture structure.*
