# Module-wise Test Guide

> **Test Bankruptcy → Pyramid Rebuild**
> This guide defines the testing strategy for each module following the new test pyramid architecture.

---

## Overview

### Test Pyramid Structure

```
        ┌────────────────┐
        │   E2E/Chaos    │  ← Separate track (module-chaos-test)
        ├────────────────┤
        │  Integration   │  ← integrationTest source set (module-infra)
        ├────────────────┤
        │   Unit Tests   │  ← test source set (all modules)
        └────────────────┘
```

### Execution Strategy

| Scope | Source Set | PR Pipeline | Nightly | Duration |
|-------|------------|-------------|----------|----------|
| Unit | `src/test/java` | ✅ Always | ✅ Always | Seconds |
| Integration | `src/integrationTest/java` | ❌ Skip | ✅ Full | Minutes |
| Chaos/E2E | `module-chaos-test` | ❌ Skip | ✅ Full | Tens of minutes |

---

## 1. module-core: Pure Domain (Unit Only)

### Principles

**NO Spring / NO Docker / NO DB / NO I/O**

- Pure JUnit 5 + AssertJ + jqwik PBT
- Domain logic only: probability, valuation, transitions
- Test invariants, not implementation details
- Seed-controlled randomness for reproducibility

### Directory Structure

```
module-core/
├── src/main/java/          # Domain logic
├── src/test/java/          # Unit tests only
│   └── maple/expectation/
│       ├── domain/         # Pure unit tests
│       └── properties/     # jqwik PBT (invariants)
└── src/test/resources/
    └── junit-platform.properties  # jqwik configuration
```

### Test Types

#### A. Pure Unit Tests (`/domain/`)

```java
@Test
void calculateCost_withValidInputs_returnsExpectedValue() {
    // Given
    var target = TargetLevel.of(20);
    var current = CurrentLevel.of(10);

    // When
    var cost = calculator.calculate(target, current);

    // Then
    assertThat(cost.value()).isGreaterThan(0);
}
```

#### B. Property-Based Tests (`/properties/`)

Use jqwik for invariants:

```java
@Property(tries = 200)
void probabilities_sum_to_one(@ForAll("probVectors") ProbVector pv) {
    assertThat(pv.sum()).isCloseTo(1.0, offset(1e-12));
}
```

**Key Invariants to Test:**

1. **Probability Range**: `0 ≤ p ≤ 1`
2. **Normalization**: `Σp = 1`
3. **Non-Negativity**: Costs, values ≥ 0
4. **Monotonicity**: Higher level → ≥ cost (for deterministic calculators)
5. **Determinism**: Same seed → same output
6. **Boundary Safety**: Min/max values don't crash

### Build Configuration

```gradle
test {
    useJUnitPlatform {
        includeEngines 'jqwik', 'junit-jupiter'
    }
}
```

### jqwik Settings

`src/test/resources/junit-platform.properties`:

```properties
jqwik.tries.default = 200
jqwik.failures.after.default = PREVIOUS_SEED
jqwik.seeds.whenfixed = WARN
jqwik.reporting.usejunitplatform = true
```

### Forbidden Patterns

❌ **NOT ALLOWED in module-core:**
- `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`
- `@Testcontainers`, `@Container`
- `@MockBean` (use plain mocks if needed)
- DB, Redis, HTTP calls
- Time-dependent tests (use `Clock` injection)

---

## 2. module-infra: Infrastructure Layer (Integration)

### Principles

**Real DB/Redis via Testcontainers (Singleton)**

- Repository/Adapter integration only
- Slice tests with minimal Spring context
- Shared containers (MySQL, Redis) per JVM
- Data isolation via TRUNCATE/FLUSHDB

### Directory Structure

```
module-infra/
├── src/main/java/              # Repository, Adapter implementations
├── src/test/java/              # Unit tests (no Spring)
│   └── maple/expectation/...
│       └── infrastructure/...
├── src/integrationTest/java/   # Integration tests with Testcontainers
│   └── maple/expectation/
│       ├── repository/         # Repository tests
│       ├── adapter/            # Adapter tests
│       └── support/            # Test support classes
│           ├── SharedContainers.java
│           └── InfraIntegrationTestSupport.java
└── src/integrationTest/resources/
    └── application-test.yml
```

### Test Types

#### A. Unit Tests (`src/test/java/`)

Plain unit tests without Spring:

```java
@Test
void toEntity_fromDomain_convertsFieldsCorrectly() {
    var domain = ExpectationSnapshot.of(...);
    var entity = mapper.toEntity(domain);

    assertThat(entity.getOcid()).isEqualTo(domain.ocid());
}
```

#### B. Integration Tests (`src/integrationTest/java/`)

`@DataJpaTest` with shared containers:

```java
@DataJpaTest
@Import(JpaConfig.class)
class CharacterEquipmentRepositoryTest extends InfraIntegrationTestSupport {

    @Autowired
    private CharacterEquipmentRepository repository;

    @Test
    void save_andRetrieve_succeeds() {
        var entity = new CharacterEquipmentEntity(...);
        repository.save(entity);

        var found = repository.findById(entity.getOcid());
        assertThat(found).isPresent();
    }

    @BeforeEach
    void cleanup() {
        // TRUNCATE all tables (enforced by base class)
        cleanupDatabase();
    }
}
```

### SharedContainers Pattern

```java
public final class SharedContainers {
    public static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb");

    public static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
    }
}
```

### Base Test Class

```java
@TestInstance(PER_CLASS)
abstract class InfraIntegrationTestSupport {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SharedContainers.MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", SharedContainers.MYSQL::getUsername);
        r.add("spring.datasource.password", SharedContainers.MYSQL::getPassword);
        r.add("spring.data.redis.host", SharedContainers.REDIS::getHost);
        r.add("spring.data.redis.port", () -> SharedContainers.REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void cleanupDatabase() {
        // TRUNCATE all tables
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        Arrays.asList("table1", "table2", ...).forEach(table ->
            jdbcTemplate.execute("TRUNCATE TABLE " + table));
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @BeforeEach
    void cleanupRedis() {
        redisTemplate.getConnectionFactory().getConnection()
            .flushDb();
    }
}
```

### Build Configuration

```gradle
sourceSets {
    integrationTest {
        java.srcDir file('src/integrationTest/java')
        resources.srcDir file('src/integrationTest/resources')
        compileClasspath += sourceSets.main.output + configurations.testRuntimeClasspath
        runtimeClasspath += output + compileClasspath
    }
}

tasks.register('integrationTest', Test) {
    description = 'Runs integration tests (Testcontainers, DB, Redis).'
    group = 'verification'

    testClassesDirs = sourceSets.integrationTest.output.classesDirs
    classpath = sourceSets.integrationTest.runtimeClasspath
    useJUnitPlatform()

    shouldRunAfter tasks.test

    // NOT added to check.dependsOn - must be explicitly run
}
```

### Running Tests

```bash
# Unit tests only (PR default)
./gradlew :module-infra:test

# Integration tests (manual/nightly)
./gradlew :module-infra:integrationTest

# Both
./gradlew :module-infra:test :module-infra:integrationTest
```

---

## 3. module-app: Application Layer (Slice Tests)

### Principles

**Fast Slice Tests + Minimal Smoke Tests**

- Controllers: `@WebMvcTest` (mock services)
- Services: Plain unit tests or `@MockBean`
- Integration: Only 1-3 smoke tests with `@SpringBootTest`
- Chaos/Nightmare: Separate module (`module-chaos-test`)

### Directory Structure

```
module-app/
├── src/main/java/              # Controllers, Services, Config
├── src/test/java/              # Unit + Slice tests
│   └── maple/expectation/
│       ├── controller/         # @WebMvcTest
│       ├── service/            # Plain unit tests
│       ├── global/             # Unit tests for global components
│       ├── support/            # Test support
│       │   ├── IntegrationTestSupport.java
│       │   └── SharedContainers.java
│       └── ...smoke/           # Smoke tests (1-3 @SpringBootTest)
├── src/test-legacy/            # Legacy tests (excluded from build)
└── src/test/resources/
    └── application-test.yml
```

### Test Types

#### A. Controller Tests (`@WebMvcTest`)

```java
@WebMvcTest(GameCharacterController.class)
class GameCharacterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GameCharacterService service;

    @Test
    void getCharacter_returns200() throws Exception {
        mockMvc.perform(get("/api/v2/characters/{ocid}", "test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ocid").value("test"));
    }
}
```

#### B. Service Unit Tests

```java
class GameCharacterServiceTest {

    @Mock
    private CharacterRepository repository;

    @Test
    void getCharacter_withValidOcid_returnsCharacter() {
        when(repository.findById("test"))
            .thenReturn(Optional.of(mockCharacter));

        var result = service.getCharacter("test");

        assertThat(result).isPresent();
    }
}
```

#### C. Smoke Tests (`@SpringBootTest`)

**Limit to 1-3 happy-path tests only:**

```java
@SpringBootTest
@ActiveProfiles("test")
class SmokeTest extends IntegrationTestSupport {

    @Autowired
    private GameCharacterController controller;

    @Test
    void contextLoads() {
        assertThat(controller).isNotNull();
    }

    @Test
    void mainApiEndpoint_returns200() {
        // Verify full stack works end-to-end
    }
}
```

### Build Configuration

```gradle
test {
    useJUnitPlatform()

    // Exclude legacy tests
    exclude '**/test-legacy/**'
}
```

---

## 4. module-common: Shared Utilities (Unit Only)

Same principles as `module-core`:
- Pure unit tests
- No Spring/DB
- Test utility class behavior in isolation

---

## 5. module-chaos-test: Chaos Engineering (Separate Track)

### Principles

**Separate Module, Separate Execution**

- Not included in PR pipeline
- Run manually or in nightly builds
- Tests system resilience under failure conditions

### Directory Structure

```
module-chaos-test/
├── src/chaos-test/java/       # Chaos scenarios
│   └── maple/expectation/chaos/
│       ├── connection/        # Network chaos
│       ├── core/              # Core component failures
│       ├── resource/          # Resource exhaustion
│       └── nightmare/         # N01-N18 scenarios
└── build.gradle               # Custom test task configuration
```

### Execution

```bash
# Explicitly run chaos tests (not in PR pipeline)
./gradlew :module-chaos-test:chaosTest
```

---

## Flaky Test Prevention Checklist

### Time/Randomness

✅ **DO:**
- Use `Clock` injection for time-dependent tests
- Use `RandomGenerator` with fixed seeds
- Test invariants, not exact random outputs

❌ **DON'T:**
- Use `System.currentTimeMillis()`, `Instant.now()`
- Use `Math.random()`, `ThreadLocalRandom` without seed
- Sleep with fixed timeouts (use Awaitility polling)

### External Dependencies

✅ **DO:**
- Mock external APIs
- Use `@DynamicPropertySource` for Testcontainers
- Verify behavior with fakes

❌ **DON'T:**
- Call real external APIs in tests
- Hardcode localhost ports (use dynamic allocation)

### State Management

✅ **DO:**
- TRUNCATE tables in `@BeforeEach`
- Use `@Transactional` for rollback (when appropriate)
- Flush Redis/Cache between tests

❌ **DON'T:**
- Share state between tests
- Assume clean DB state
- Forget to clean up singletons/static state

### Performance

✅ **DO:**
- Run integration tests in sequential order
- Share containers across tests (Singleton)
- Use test slicing for faster context loading

❌ **DON'T:**
- Start new containers per test class (unless necessary)
- Use `@DirtiesContext` unnecessarily
- Run parallel tests with shared DB state

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Test

on: pull_request

jobs:
  unit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run unit tests
        run: ./gradlew test

  integration:
    runs-on: ubuntu-latest
    if: github.event_name == 'schedule'  # Nightly only
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run integration tests
        run: ./gradlew integrationTest

  chaos:
    runs-on: ubuntu-latest
    if: github.event_name == 'schedule'  # Nightly only
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run chaos tests
        run: ./gradlew :module-chaos-test:chaosTest
```

---

## References

- [jqwik User Guide](https://jqwik.net/docs/current/user-guide.html)
- [Testcontainers Lifecycle](https://testcontainers.com/guides/testcontainers-container-lifecycle/)
- [Spring Boot Test Slices](https://docs.spring.io/spring-boot/appendix/test-auto-configuration/slices.html)
- [ADR-015: Test Rebuild Pyramid](../adr/ADR-015-test-reboot-pyramid.md)
- [ADR-025: Chaos Test Module Separation](../adr/ADR-025-chaos-test-module-separation.md)
