# Target Structure - Clean Architecture Proposal

> **Document Owner:** Blue Architect (5-Agent Council)
> **Generated:** 2026-02-07
> **Purpose:** Proposed Clean Architecture package structure to resolve identified violations

---

## Executive Summary

This document proposes a **Clean Architecture** refactoring to address 43 SOLID violations identified in `SOLID_VIOLATIONS.md`. The proposed structure enforces **dependency inversion** and **separation of concerns** while maintaining production-validated performance (719 RPS).

**Key Principles:**
1. **Domain Independence** - Pure Java, no framework dependencies
2. **Dependency Inversion** - Business logic defines interfaces
3. **Layered Isolation** - Clear boundaries between layers
4. **Performance Preservation** - Maintain V4 optimizations

---

## Current vs Target Structure

### Current Structure Issues

```
maple.expectation/
├── aop/                    # ❌ Mixed concerns
├── config/                 # ❌ 26 files, infrastructure + application mixed
├── controller/             # ⚠️  Contains DTOs (should be separate)
├── domain/                 # ❌ JPA annotations (infrastructure leakage)
├── dto/                    # ⚠️  Request/Response mixed
├── global/                 # ❌ Utilities + cross-cutting mixed
├── service/                # ❌ V2 + V4 monolith (80+ classes)
└── util/                   # ❌ Static utilities
```

**Problems:**
- No clear layer separation
- Infrastructure (JPA, Spring) in domain
- Business logic scattered
- God classes (SRP violations)
- Hard-to-test coupling

### Proposed Target Structure

```
maple.expectation/
├── domain/                      # ✅ Pure business logic (no framework deps)
│   ├── model/                   #    Entities, Value Objects
│   ├── service/                 #    Domain services (use cases)
│   ├── repository/              #    Repository interfaces (ports)
│   └── exception/               #    Domain exceptions
│
├── application/                 # ✅ Use case orchestration
│   ├── service/                 #    Application services (transaction boundaries)
│   ├── dto/                     #    Request/Response DTOs
│   ├── port/                    #    Port interfaces (inbound/outbound)
│   └── mapper/                  #    DTO ↔ Model mappers
│
├── infrastructure/              # ✅ External systems
│   ├── persistence/             #    JPA entities, repositories impl
│   ├── cache/                   #    Redis, Caffeine, TieredCache
│   ├── external/                #    Nexon API clients
│   ├── config/                  #    Spring configuration
│   ├── executor/                #    Thread pool, async executors
│   └── resilience/              #    Circuit breaker, retry
│
├── interfaces/                  # ✅ Controllers, adapters
│   ├── rest/                    #    REST controllers
│   ├── event/                   #    Event listeners
│   └── filter/                  #    Servlet filters
│
└── shared/                      # ✅ Cross-cutting (minimal)
    ├── error/                   #    Error handling, exceptions
    ├── util/                    #    Pure utilities (static methods)
    └── aop/                     #    Aspects for cross-cutting
```

---

## Layer Definitions

### 1. Domain Layer (Pure Java)

**Purpose:** Core business logic, completely independent of frameworks.

**Package Structure:**
```
domain/
├── model/
│   ├── character/
│   │   ├── GameCharacter.java          # Rich domain model
│   │   ├── CharacterId.java            # Value object
│   │   ├── UserIgn.java                # Value object
│   │   └── Ocid.java                   # Value object
│   ├── equipment/
│   │   ├── CharacterEquipment.java     # Domain model (no JPA)
│   │   ├── EquipmentData.java          # Value object
│   │   └── EquipmentSnapshot.java      # Value object
│   ├── like/
│   │   ├── CharacterLike.java          # Domain model
│   │   ├── LikeCount.java              # Value object
│   │   └── LikeBuffer.java             # Value object
│   ├── donation/
│   │   ├── Donation.java               # Domain model
│   │   ├── DonationId.java             # Value object
│   │   └── Point.java                  # Value object
│   └── calculator/
│       ├── Cost.java                   # Value object (BigDecimal)
│       ├── CostBreakdown.java          # Value object
│       └── ExpectationResult.java      # Value object
│
├── service/
│   ├── CharacterDomainService.java     # Character business logic
│   ├── EquipmentCalculationService.java # Calculation logic
│   ├── LikeDomainService.java          # Like business logic
│   └── DonationDomainService.java      # Donation business logic
│
├── repository/
│   ├── CharacterRepository.java        # Repository interface
│   ├── CharacterEquipmentRepository.java
│   ├── CharacterLikeRepository.java
│   └── DonationRepository.java
│
└── exception/
    ├── CharacterNotFoundException.java
    ├── SelfLikeNotAllowedException.java
    └── DomainException.java            # Base class
```

**Constraints:**
- ❌ NO Spring annotations (`@Service`, `@Component`, etc.)
- ❌ NO JPA annotations (`@Entity`, `@Column`, etc.)
- ❌ NO Lombok annotations (`@Getter`, `@Data`, etc.)
- ❌ NO framework dependencies
- ✅ Pure Java with business logic

**Example: Domain Model**
```java
package maple.expectation.domain.model.character;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Pure domain model - no framework dependencies
 */
public class GameCharacter {
    private final CharacterId id;
    private final UserIgn userIgn;
    private final Ocid ocid;
    private String worldName;
    private String characterClass;
    private LocalDateTime basicInfoUpdatedAt;
    private LocalDateTime updatedAt;
    private CharacterEquipment equipment;

    // Private constructor - use factory
    private GameCharacter(CharacterId id, UserIgn userIgn, Ocid ocid) {
        this.id = Objects.requireNonNull(id);
        this.userIgn = Objects.requireNonNull(userIgn);
        this.ocid = Objects.requireNonNull(ocid);
        this.updatedAt = LocalDateTime.now();
    }

    // Factory method
    public static GameCharacter create(UserIgn userIgn, Ocid ocid) {
        return new GameCharacter(CharacterId.generate(), userIgn, ocid);
    }

    // Business logic
    public boolean isActive() {
        return updatedAt != null &&
                updatedAt.isAfter(LocalDateTime.now().minusDays(30));
    }

    public boolean needsBasicInfoRefresh() {
        return worldName == null ||
                basicInfoUpdatedAt == null ||
                basicInfoUpdatedAt.isBefore(LocalDateTime.now().minusMinutes(15));
    }

    public void validateOcid() {
        if (ocid == null || ocid.value().isBlank()) {
            throw new InvalidOcidException("OCID is required");
        }
    }

    public void incrementLikeCount() {
        // Business rule
    }

    // Getters (manual, no Lombok)
    public CharacterId id() { return id; }
    public UserIgn userIgn() { return userIgn; }
    public Ocid ocid() { return ocid; }
    public String worldName() { return worldName; }
    // ... more getters

    // Setters (with validation)
    public void setWorldName(String worldName) {
        this.worldName = Objects.requireNonNull(worldName);
    }
    // ... more setters

    // Package-private for persistence mapper
    void setEquipment(CharacterEquipment equipment) {
        this.equipment = equipment;
    }
}
```

**Example: Repository Interface (Port)**
```java
package maple.expectation.domain.repository;

import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.domain.model.character.CharacterId;
import maple.expectation.domain.model.character.UserIgn;
import java.util.Optional;

/**
 * Repository interface defined by domain
 * Infrastructure layer provides implementation
 */
public interface CharacterRepository {
    GameCharacter save(GameCharacter character);
    Optional<GameCharacter> findByUserIgn(UserIgn userIgn);
    Optional<GameCharacter> findById(CharacterId id);
    boolean existsByUserIgn(UserIgn userIgn);
    void deleteByOcid(Ocid ocid);
}
```

### 2. Application Layer (Orchestration)

**Purpose:** Use case orchestration, transaction boundaries, DTO transformation.

**Package Structure:**
```
application/
├── service/
│   ├── CharacterApplicationService.java    # Transaction boundaries
│   ├── EquipmentApplicationService.java
│   ├── LikeApplicationService.java
│   └── DonationApplicationService.java
│
├── dto/
│   ├── request/
│   │   ├── GetCharacterRequest.java
│   │   ├── CalculateExpectationRequest.java
│   │   └── SendDonationRequest.java
│   └── response/
│       ├── CharacterResponse.java
│       ├── ExpectationResponse.java
│       └── DonationResponse.java
│
├── port/
│   ├── inbound/                             # Incoming (drivers)
│   │   ├── GetCharacterUseCase.java
│   │   ├── CalculateExpectationUseCase.java
│   │   └── SendLikeUseCase.java
│   └── outbound/                            # Outgoing (driven)
│       ├── EquipmentDataProvider.java        # Nexon API port
│       ├── CacheInvalidationPublisher.java   # Pub/Sub port
│       └── EventPublisher.java              # Domain event port
│
└── mapper/
    ├── CharacterMapper.java                 # DTO ↔ Domain
    ├── EquipmentMapper.java
    └── LikeMapper.java
```

**Example: Application Service**
```java
package maple.expectation.application.service;

import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.domain.model.character.UserIgn;
import maple.expectation.domain.repository.CharacterRepository;
import maple.expectation.application.dto.response.CharacterResponse;
import maple.expectation.application.port.outbound.EquipmentDataProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service - transaction boundary
 * Orchestrates domain services and infrastructure
 */
@Service
public class CharacterApplicationService {
    private final CharacterRepository characterRepository;
    private final CharacterDomainService characterDomainService;
    private final EquipmentDataProvider equipmentDataProvider;
    private final CharacterMapper characterMapper;

    public CharacterApplicationService(
            CharacterRepository characterRepository,
            CharacterDomainService characterDomainService,
            EquipmentDataProvider equipmentDataProvider,
            CharacterMapper characterMapper) {
        this.characterRepository = characterRepository;
        this.characterDomainService = characterDomainService;
        this.equipmentDataProvider = equipmentDataProvider;
        this.characterMapper = characterMapper;
    }

    @Transactional(readOnly = true)
    public CharacterResponse getCharacter(String userIgn) {
        UserIgn ign = UserIgn.of(userIgn);

        // Domain service handles business logic
        GameCharacter character = characterDomainService.findOrCreate(ign);

        // Enrich with basic info if needed
        if (character.needsBasicInfoRefresh()) {
            character = enrichBasicInfo(character);
        }

        // Map to response DTO
        return characterMapper.toResponse(character);
    }

    @Transactional
    public CharacterId createCharacter(String userIgn, String ocid) {
        // Business logic in domain
        GameCharacter character = characterDomainService.create(
            UserIgn.of(userIgn),
            Ocid.of(ocid)
        );

        // Persistence
        return characterRepository.save(character).id();
    }

    // Private helpers
    private GameCharacter enrichBasicInfo(GameCharacter character) {
        // Call external port
        // ...
        return character;
    }
}
```

### 3. Infrastructure Layer

**Purpose:** External systems implementation, framework integration.

**Package Structure:**
```
infrastructure/
├── persistence/
│   ├── entity/                           # JPA entities (separate from domain)
│   │   ├── GameCharacterEntity.java
│   │   ├── CharacterEquipmentEntity.java
│   │   └── CharacterLikeEntity.java
│   ├── repository/
│   │   ├── JpaCharacterRepository.java    # Spring Data JPA
│   │   └── CharacterRepositoryImpl.java   # Implements domain interface
│   └── mapper/
│       ├── GameCharacterMapper.java       # Entity ↔ Domain
│       └── CharacterEquipmentMapper.java
│
├── cache/
│   ├── TieredCache.java                   # L1 + L2 coordination
│   ├── TieredCacheManager.java
│   ├── SingleFlightManager.java           # Single-flight
│   ├── CacheInvalidationPublisher.java    # Pub/Sub
│   └── config/
│       ├── CacheConfig.java
│       └── RedissonConfig.java
│
├── external/
│   ├── nexon/
│   │   ├── NexonApiClient.java            # Implements domain port
│   │   ├── NexonAuthClient.java
│   │   └── dto/                            # External API DTOs
│   └── resolver/
│       └── OcidResolver.java
│
├── resilience/
│   ├── CircuitBreakerConfig.java
│   ├── RetryConfig.java
│   └── TimeLimiterConfig.java
│
├── executor/
│   ├── EquipmentProcessingExecutor.java
│   ├── PresetCalculationExecutor.java
│   └── AsyncExecutorConfig.java
│
└── config/
    ├── DataSourceConfig.java
    ├── TransactionConfig.java
    ├── SecurityConfig.java
    └── ApplicationConfig.java              # Service beans
```

**Example: Repository Implementation**
```java
package maple.expectation.infrastructure.persistence.repository;

import maple.expectation.domain.model.character.GameCharacter;
import maple.expectation.domain.model.character.UserIgn;
import maple.expectation.domain.repository.CharacterRepository;
import maple.expectation.infrastructure.persistence.entity.GameCharacterEntity;
import maple.expectation.infrastructure.persistence.mapper.GameCharacterMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Infrastructure implementation of domain repository interface
 */
@Repository
public class CharacterRepositoryImpl implements CharacterRepository {

    private final SpringDataGameCharacterRepository jpaRepo;
    private final GameCharacterMapper mapper;

    public CharacterRepositoryImpl(
            SpringDataGameCharacterRepository jpaRepo,
            GameCharacterMapper mapper) {
        this.jpaRepo = jpaRepo;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public GameCharacter save(GameCharacter character) {
        GameCharacterEntity entity = mapper.toEntity(character);
        GameCharacterEntity saved = jpaRepo.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GameCharacter> findByUserIgn(UserIgn userIgn) {
        return jpaRepo.findByUserIgn(userIgn.value())
                .map(mapper::toDomain);
    }

    // ... other methods
}
```

**Example: JPA Entity (separate from domain)**
```java
package maple.expectation.infrastructure.persistence.entity;

import jakarta.persistence.*;
import maple.expectation.infrastructure.persistence.converter.StringToOcidConverter;
import java.time.LocalDateTime;

/**
 * JPA entity - infrastructure concern only
 * Maps to database table
 */
@Entity
@Table(name = "game_character")
public class GameCharacterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userIgn;

    @Column(nullable = false, unique = true)
    private String ocid;

    @Column(length = 50)
    private String worldName;

    @Column(length = 50)
    private String characterClass;

    @Column(length = 2048)
    private String characterImage;

    private LocalDateTime basicInfoUpdatedAt;

    @Version
    private Long version;

    private Long likeCount;

    private LocalDateTime updatedAt;

    // JPA-specific methods only
    // No business logic here
}
```

### 4. Interfaces Layer

**Purpose:** Adapters for external communication (REST, events, etc.).

**Package Structure:**
```
interfaces/
├── rest/
│   ├── v1/
│   │   └── GameCharacterControllerV1.java
│   ├── v2/
│   │   └── GameCharacterControllerV2.java
│   ├── v3/
│   │   └── GameCharacterControllerV3.java
│   ├── v4/
│   │   └── GameCharacterControllerV4.java
│   ├── auth/
│   │   └── AuthController.java
│   └── admin/
│       ├── AdminController.java
│       └── DlqAdminController.java
│
├── event/
│   ├── listener/
│   │   ├── DonationEventListener.java
│   │   ├── LikeSyncEventListener.java
│   │   └── CacheInvalidationListener.java
│   └── publisher/
│       └── SpringEventPublisher.java
│
└── filter/
    ├── MDCFilter.java
    ├── JwtAuthenticationFilter.java
    └── RateLimitFilter.java
```

**Example: REST Controller**
```java
package maple.expectation.interfaces.rest.v4;

import maple.expectation.application.dto.response.ExpectationResponseV4;
import maple.expectation.application.service.EquipmentApplicationService;
import maple.expectation.application.dto.request.CalculateExpectationRequest;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller - thin adapter
 * Delegates to application service
 */
@RestController
@RequestMapping("/api/v4/characters")
public class GameCharacterControllerV4 {

    private final EquipmentApplicationService equipmentService;

    public GameCharacterControllerV4(EquipmentApplicationService equipmentService) {
        this.equipmentService = equipmentService;
    }

    @GetMapping("/{userIgn}/expectation")
    public CompletableFuture<ResponseEntity<?>> getExpectation(
            @PathVariable String userIgn,
            @RequestParam(defaultValue = "false") boolean force) {

        CalculateExpectationRequest request = new CalculateExpectationRequest(userIgn, force);

        return equipmentService.calculateExpectationAsync(request)
                .thenApply(ResponseEntity::ok);
    }

    // Response building delegated to application layer
    // Controller only handles HTTP concerns
}
```

### 5. Shared Layer

**Purpose:** Cross-cutting concerns that don't fit in other layers.

**Package Structure:**
```
shared/
├── error/
│   ├── exception/
│   │   ├── BaseException.java
│   │   ├── ClientBaseException.java
│   │   ├── ServerBaseException.java
│   │   └── marker/
│   │       ├── CircuitBreakerIgnoreMarker.java
│   │       └── CircuitBreakerRecordMarker.java
│   ├── code/
│   │   └── ErrorCode.java
│   └── handler/
│       └── GlobalExceptionHandler.java
│
├── executor/
│   ├── LogicExecutor.java                # Exception handling patterns
│   ├── DefaultLogicExecutor.java
│   ├── TaskContext.java
│   └── strategy/
│       └── ExceptionTranslator.java
│
├── aop/
│   ├── aspect/
│   │   ├── TraceAspect.java
│   │   ├── LockAspect.java
│   │   └── CacheAspect.java
│   └── annotation/
│       ├── @TraceLog.java
│       ├── @Locked.java
│       └── @Cached.java
│
└── util/
    ├── GzipUtils.java                    # Static utilities (pure functions)
    ├── JsonMapper.java
    └── StringMaskingUtils.java
```

---

## Migration Strategy

### Phase 1: Foundation (Week 1-2)

**Goal:** Establish new layer structure without breaking existing code.

1. Create new packages:
   ```bash
   mkdir -p src/main/java/maple/expectation/domain/model
   mkdir -p src/main/java/maple/expectation/domain/service
   mkdir -p src/main/java/maple/expectation/domain/repository
   mkdir -p src/main/java/maple/expectation/application/service
   mkdir -p src/main/java/maple/expectation/infrastructure/persistence
   mkdir -p src/main/java/maple/expectation/interfaces/rest
   ```

2. Create base interfaces and exceptions:
   - `DomainException.java`
   - Repository interfaces in `domain/repository/`

3. Set up parallel structure (old and new coexist).

### Phase 2: Domain Extraction (Week 3-6)

**Goal:** Extract pure domain logic from services.

| Step | Action | Effort |
|------|--------|--------|
| 2.1 | Create value objects (CharacterId, UserIgn, Ocid, etc.) | 3 SP |
| 2.2 | Create `GameCharacter` domain model (no JPA) | 5 SP |
| 2.3 | Create domain services (CharacterDomainService, etc.) | 8 SP |
| 2.4 | Move business logic from services to domain | 10 SP |
| 2.5 | Create repository interfaces | 3 SP |

### Phase 3: Infrastructure Implementation (Week 7-10)

**Goal:** Implement adapters for domain interfaces.

| Step | Action | Effort |
|------|--------|--------|
| 3.1 | Create JPA entities (separate from domain) | 5 SP |
| 3.2 | Implement repository adapters | 8 SP |
| 3.3 | Create entity ↔ domain mappers | 5 SP |
| 3.4 | Abstract external API clients behind ports | 5 SP |
| 3.5 | Abstract cache/executor behind interfaces | 8 SP |

### Phase 4: Application Services (Week 11-13)

**Goal:** Create application service layer.

| Step | Action | Effort |
|------|--------|--------|
| 4.1 | Create DTOs (request/response) | 5 SP |
| 4.2 | Create application services (transaction boundaries) | 10 SP |
| 4.3 | Create port interfaces (inbound/outbound) | 5 SP |
| 4.4 | Implement mappers (DTO ↔ domain) | 5 SP |

### Phase 5: Controller Refactoring (Week 14-15)

**Goal:** Thin controllers as pure adapters.

| Step | Action | Effort |
|------|--------|--------|
| 5.1 | Refactor V4 controller to use application services | 5 SP |
| 5.2 | Refactor V2/V3 controllers | 8 SP |
| 5.3 | Refactor auth/admin controllers | 5 SP |

### Phase 6: Cleanup (Week 16)

**Goal:** Remove old code and finalize.

| Step | Action | Effort |
|------|--------|--------|
| 6.1 | Delete deprecated `service/v2/*` classes (moved to domain) | 3 SP |
| 6.2 | Delete deprecated `domain/v2/*` JPA entities | 2 SP |
| 6.3 | Update all imports | 5 SP |
| 6.4 | Integration testing | 10 SP |

**Total Estimated Effort:** ~125 story points (~16 weeks for 1 developer)

---

## Dependency Rules

### Allowed Dependencies (↓)

```
interfaces/
    ↓ can use
application/
    ↓ can use
domain/
    ↓ can use
(NONE - pure Java)

infrastructure/
    ↓ can use
domain/ (implements interfaces)
    ↓ can use
shared/
```

### Forbidden Dependencies (✗)

| From | To | Rule | Rationale |
|------|-----|------|-----------|
| domain | infrastructure | ✗ | DIP violation |
| domain | Spring | ✗ | Framework independence |
| domain | JPA | ✗ | Persistence independence |
| application | JPA | ✗ | Use repository interfaces |
| interfaces | domain | ✗ | Use application DTOs |

### Example: Correct Dependency

```java
// ✅ CORRECT: Application uses domain
package maple.expectation.application.service;
import maple.expectation.domain.model.GameCharacter;  // OK
import maple.expectation.domain.repository.CharacterRepository;  // OK

// ✅ CORRECT: Infrastructure implements domain interface
package maple.expectation.infrastructure.persistence;
import maple.expectation.domain.repository.CharacterRepository;  // OK
import maple.expectation.domain.model.GameCharacter;  // OK

// ✗ WRONG: Domain uses infrastructure
package maple.expectation.domain.model;
import org.springframework.data.jpa.repository.JpaRepository;  // ✗ DIP violation
```

---

## Trade-offs

| Decision | Pros | Cons | Mitigation |
|----------|------|------|------------|
| **Separate domain models** | Clean architecture, testable | More mapping code | Generate mappers with annotation processors |
| **Repository interfaces** | Domain independence, mockable | More files | Use default methods for common queries |
| **Value objects** | Type safety, self-validating | More classes | Use records for simplicity |
| **Application services** | Clear transaction boundaries | Extra layer | Keep thin, delegate to domain |
| **Port interfaces** | Swappable implementations | More abstractions | Start with concrete, extract when needed |

---

## Performance Considerations

### Preserving V4 Optimizations

1. **Fast Path (L1 Direct Access)**: Keep in `infrastructure/cache/`
2. **Single-flight**: Keep in `infrastructure/cache/`
3. **Write-Behind Buffer**: Keep in `infrastructure/cache/`
4. **Async Pipeline**: Keep in `application/service/`

### Cache Strategy (No Change)

```
L1 (Caffeine) → L2 (Redis) → DB
```

Cache logic remains in `infrastructure/cache/`, domain uses repository interface.

---

## Testing Strategy

### Unit Tests (Pure Domain)

```java
@Test
void test_isActive_returnsTrue_whenRecentlyUpdated() {
    // Given
    GameCharacter character = GameCharacter.create(
        UserIgn.of("test"),
        Ocid.of("ocid123")
    );

    // When
    boolean isActive = character.isActive();

    // Then
    assertTrue(isActive);
}
```

### Integration Tests (Application Layer)

```java
@SpringBootTest
@Test
void test_getCharacter_returnsResponse() {
    // Given
    String userIgn = "test";

    // When
    CharacterResponse response = characterService.getCharacter(userIgn);

    // Then
    assertNotNull(response);
    assertEquals("test", response.userIgn());
}
```

---

## Risk Assessment

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| **Performance regression** | High | Low | Keep V4 optimizations in infrastructure |
| **Breaking changes** | High | Medium | Parallel structure, gradual migration |
| **Over-engineering** | Medium | High | Start simple, add layers as needed |
| **Team adoption** | Medium | Medium | Training sessions, pair programming |
| **Increased LOC** | Low | High | Acceptable trade-off for maintainability |

---

## References

| Document | Location | Purpose |
|----------|----------|---------|
| Architecture Map | `docs/refactor/ARCHITECTURE_MAP.md` | Current state analysis |
| SOLID Violations | `docs/refactor/SOLID_VIOLATIONS.md` | Detailed violations |
| CLAUDE.md | Project root | Coding standards |
| Service Modules | `docs/02_Technical_Guides/service-modules.md` | Current module details |

---

## Next Steps

1. **Review with 5-Agent Council** - Get consensus on proposed structure
2. **Create Spike** - Implement one module (e.g., Character) as proof of concept
3. **Update ADR** - Document architecture decision record
4. **Begin Phase 1** - Set up package structure

---

*This proposal maintains production-validated performance while achieving Clean Architecture principles.*
*Estimated migration timeline: 16 weeks (4 months)*
