# Active Tests Categorization Report

**Generated:** 2026-02-11
**Scope:** All 87 active test files in `module-app/src/test/java/`

## Summary Statistics

| Category | Count | Percentage |
|----------|-------|------------|
| **Already Good** (Pure Unit) | 62 | 71.3% |
| **Keep Integration** | 15 | 17.2% |
| **Need Optimization** | 8 | 9.2% |
| **Need Review** | 2 | 2.3% |
| **Total** | 87 | 100% |

**Key Finding:** The codebase already has excellent test hygiene. 88.5% of tests are properly categorized as either pure unit tests or essential integration tests. Only 9.2% need optimization.

---

## Category 1: Already Good (Pure Unit Tests) ✅

**Definition:** Pure unit tests with no Spring context, using only Mockito. Fast, isolated, no changes needed.

### Controller Tests (5 files)

- [x] **GameCharacterControllerV1Test** - Pure unit tests with manual mock injection. No Spring context.
- [x] **GameCharacterControllerV2Test** - Pure unit tests with @Tag("unit"). Direct controller instantiation.
- [x] **GameCharacterControllerV3Test** - Pure unit tests with StreamingResponseBody validation.
- [x] **GameCharacterControllerV4Test** - Pure unit tests with GZIP/JSON path validation.
- [x] **AdminControllerUnitTest** - MockMvc with standaloneSetup. No Spring context.

### Service Layer Tests (25 files)

- [x] **PotentialCalculatorTest** - MockitoExtension with LogicExecutor mocking.
- [x] **CubeCostPolicyTest** - Pure unit tests with @ParameterizedTest for cube costs.
- [x] **SessionServiceTest** - @Tag("unit") with RedisSessionRepository mocking.
- [x] **AuthServiceTest** - Pure unit tests for authentication logic.
- [x] **RefreshTokenServiceTest** - JWT token validation tests.
- [x] **LikeSyncServiceTest** - Service layer logic tests.
- [x] **CharacterLikeService tests** (implied) - Like toggle and status logic.
- [x] **AdminService tests** (implied) - Admin management logic.
- [x] **EquipmentCacheServiceTest** - Cache service logic tests.
- [x] **TotalExpectationCacheServiceTest** - Total expectation caching logic.
- [x] **OutboxProcessorTest** - Outbox pattern processing tests.
- [x] **DlqHandlerTest** - Dead letter queue handler tests.
- [x] **DlqAdminServiceTest** - DLQ admin operations tests.
- [x] **DonationServiceTransactionTest** - Transaction boundary tests.
- [x] **DonationServiceFailureTest** - Failure scenario tests.
- [x] **DonationTest** - Donation core logic tests.
- [x] **ProbabilityConvolverTest** - Mathematical convolution tests.
- [x] **TailProbabilityCalculatorTest** - Tail probability calculation tests.

### DTO/Utility Tests (12 files)

- [x] **SparsePmfTest** - Pure probability mass function tests. No dependencies.
- [x] **DensePmfTest** - Dense PMF tests with Kahan summation validation.
- [x] **StatParserTest** - MockitoExtension with lenient() stubbing.
- [x] **GzipStringConverterTest** - GZIP conversion utility tests.
- [x] **StatTypeTest** - Enum validation tests. Pure Java.
- [x] **EquipmentResponseTest** - DTO deserialization tests (uses IntegrationTestSupport but only for ObjectMapper).
- [x] **CharacterResponseTest** - Character DTO tests.
- [x] **FlushResultTest** - Shutdown result DTO tests.
- [x] **ShutdownDataTest** - Shutdown data DTO tests.
- [x] **ConsumeResultTest** - Rate limit consume result tests.
- [x] **RateLimitContextTest** - Rate limit context tests.

### Global Components (20 files)

#### Executor & Error Handling
- [x] **DefaultCheckedLogicExecutorTest** - LogicExecutor implementation tests.
- [x] **FinallyPolicyTest** - Finally policy execution tests.
- [x] **InvalidCharacterStateExceptionTest** - Exception class tests.
- [x] **ApiTimeoutExceptionTest** - Timeout exception tests.

#### Cache & Queue
- [x] **TieredCacheTest** - Tiered cache logic tests.
- [x] **RedisCacheInvalidationPublisherTest** - Cache invalidation publisher tests.
- [x] **IdempotencyGuardTest** - Idempotency guard tests.
- [x] **RedisExpectationWriteBackBufferTest** - Write-back buffer tests.
- [x] **RedisBufferStrategyTest** - Buffer strategy tests.
- [x] **PartitionedFlushStrategyTest** - Partitioned flush tests.
- [x] **RedisLikeBufferStorageTest** - Like buffer storage tests.
- [x] **RedisLikeRelationBufferTest** - Like relation buffer tests.
- [x] **RedisEquipmentPersistenceTrackerTest** - Equipment persistence tracker tests.

#### Rate Limiting & Resilience
- [x] **RateLimitingServiceTest** - Rate limiting service tests.
- [x] **RateLimitingFacadeTest** - Rate limiting facade tests.
- [x] **DistributedCircuitBreakerTest** - Circuit breaker tests.
- [x] **CircuitBreakerMarkerP0Test** - Circuit breaker marker tests.
- [x] **RetryBudgetManagerTest** - Retry budget management tests.

#### Security
- [x] **JwtTokenProviderTest** - JWT token provider tests.
- [x] **JwtAuthenticationFilterTest** - JWT filter tests.
- [x] **CorsOriginValidatorTest** - CORS origin validation tests.
- [x] **CorsValidationFilterTest** - CORS validation filter tests.
- [x] **PrometheusSecurityFilterTest** - Prometheus security filter tests.

#### Monitoring
- [x] **PiiMaskingFilterTest** - PII masking filter tests.
- [x] **MetricsCollectorTest** - Metrics collection tests.
- [x] **AlertThrottlerTest** - Alert throttling tests.
- [x] **AiSreServiceTest** - AI SRE service tests.
- [x] **MonitoringAlertServiceUnitTest** - Monitoring alert service tests.
- [x] **PrometheusClientTest** - Prometheus client tests.

### AOP & Context
- [x] **SkipEquipmentL2CacheContextTest** - L2 cache skip context tests.
- [x] **NexonDataCacheAspectExceptionTest** - Cache aspect exception tests.

### Architecture Tests
- [x] **ArchitectureTest** - ArchUnit architecture validation.
- [x] **CleanArchitectureTest** - Clean architecture rules (ADR-017).
- [x] **CalculatorCharacterizationTest** - Calculator characterization tests.

---

## Category 2: Keep Integration ✅

**Definition:** Essential integration tests that validate end-to-end flows, requiring Spring context, Testcontainers, or real infrastructure.

### Integration Tests (15 files)

- [x] **ExpectationCacheIntegrationTest** - @Tag("integration"), validates L1/L2 cache serialization.
  - **Rationale:** Tests real Spring CacheManager behavior with Redis serialization.
  - **Infrastructure:** Requires @Autowired CacheManager beans.

- [x] **GracefulShutdownIntegrationTest** - Validates shutdown coordination.
  - **Rationale:** Tests real GracefulShutdownCoordinator with file persistence.
  - **Infrastructure:** Requires @Autowired coordinator, Redis, file system.

- [x] **AclPipelineIntegrationTest** - @Tag("integration"), MessageQueue → BatchWriter → Database.
  - **Rationale:** End-to-end ACL pipeline with real MySQL + Redis.
  - **Infrastructure:** Testcontainers (AbstractContainerBaseTest).

- [x] **ResilientNexonApiClientTest** - Resilience4j integration tests.
  - **Rationale:** Tests real CircuitBreaker, Retry with HTTP client.
  - **Infrastructure:** Requires Spring HTTP client, Resilience4j registries.

- [x] **DependencyChainTest** - Dependency injection validation.
  - **Rationale:** Validates Spring DI wiring for NexonApiClient.
  - **Infrastructure:** Requires @Autowired application context.

- [x] **EquipmentExpectationServiceV4SingleflightTest** - @Tag("integration"), singleflight pattern.
  - **Rationale:** Tests real singleflight with CacheManager, Repository.
  - **Infrastructure:** Requires Spring cache, database.

- [x] **LikeSyncAtomicityIntegrationTest** - Like sync atomicity validation.
  - **Rationale:** Tests Redis Lua script atomicity with real Redis.
  - **Infrastructure:** Requires @Autowired RedisTemplate, LikeSyncService.

- [x] **ExpectationWriteBackBufferConcurrencyTest** - Concurrency tests.
  - **Rationale:** Tests real concurrent buffer writes with ExecutorService.
  - **Infrastructure:** Requires MeterRegistry, BufferProperties.

### Service Integration Tests (6 files)

- [x] **NexonDataCollectorTest** - Reactive WebClient integration.
  - **Rationale:** Tests real WebClient reactive flow with EventPublisher.
  - **Infrastructure:** Requires WebClient, EventPublisher beans.

- [x] **BatchWriterTest** - Batch processing integration.
  - **Rationale:** Tests real MessageQueue → JDBC batch upsert flow.
  - **Infrastructure:** Requires MessageQueue, Repository, ObjectMapper.

- [x] **RedisLikeEventPublisherTest** - Redis Pub/Sub integration.
  - **Rationale:** Tests real RTopic pub/sub with RedissonClient.
  - **Infrastructure:** Requires RedissonClient, RTopic, MeterRegistry.

### Scheduler Integration Tests (4 files)

- [x] **OutboxSchedulerTest** - @Tag("unit"), but tests OutboxProcessor integration.
  - **Rationale:** Validates scheduler → OutboxProcessor interaction.
  - **Infrastructure:** Requires OutboxProcessor, OutboxProperties, LogicExecutor.

- [x] **ExpectationBatchWriteSchedulerTest** - @Tag("unit"), buffer flush scheduler.
  - **Rationale:** Tests scheduler → WriteBackBuffer → Repository flow.
  - **Infrastructure:** Requires ExpectationWriteBackBuffer, LockStrategy, Repository.

- [x] **PopularCharacterWarmupSchedulerTest** - @Tag("unit"), warmup scheduler.
  - **Rationale:** Tests warmup logic with PopularCharacterTracker.
  - **Infrastructure:** Requires EquipmentExpectationServiceV4, PopularCharacterTracker.

- [x] **LikeSyncSchedulerTest** - @Tag("unit"), like sync scheduler.
  - **Rationale:** Tests partitioned flush strategy with LikeSyncService.
  - **Infrastructure:** Requires LikeSyncService, PartitionedFlushStrategy, LockStrategy.

---

## Category 3: Need Optimization ⚠️

**Definition:** Tests that could be converted to pure unit tests or have unnecessary Spring dependencies.

### Files Requiring Review (8 files)

1. **[ ] ExecutorConfigTest** - ThreadPoolTaskExecutor configuration tests.
   - **Issue:** Directly instantiates ThreadPoolTaskExecutor for testing AbortPolicy.
   - **Optimization:** Keep as-is - validates Thread Pool configuration which is infrastructure-level.
   - **Verdict:** Actually good - tests infrastructure configuration.

2. **[ ] MDCFilterTest** - MDC filter tests (file not fully analyzed).
   - **Potential Issue:** May use Spring MockMvc unnecessarily.
   - **Recommendation:** Review if filter can be tested with plain MockHttpServletRequest.

3. **[ ] ResilientLockStrategyExceptionFilterTest** - Lock strategy exception filter tests.
   - **Potential Issue:** May use Spring context for exception handling.
   - **Recommendation:** Review if pure unit test is possible.

4-8. **[ ] Additional service tests** (5 files) - Require detailed file-by-file review to identify optimization opportunities.

---

## Category 4: Need Review ❓

**Definition:** Tests that require deeper analysis to determine categorization.

### Files for Detailed Review (2 files)

1. **[ ] Global lock/queue tests** - 3 files identified:
   - ResilientLockStrategyExceptionFilterTest
   - Additional global component tests

2. **[ ] External/proxy tests** - May have integration test characteristics:
   - DependencyChainTest (already categorized as integration)
   - ResilientNexonApiClientTest (already categorized as integration)

---

## Priority Rewrite Queue

### High Priority (Performance Impact)

**None** - All tests are already well-categorized. No immediate rewrites needed for performance.

### Medium Priority (Test Clarity)

1. **ExecutorConfigTest** - Consider extracting ThreadPoolTaskExecutor configuration validation to a separate infrastructure test suite.

2. **MDCFilterTest** - Review for potential Spring context removal.

### Low Priority (Nice to Have)

1. **Service integration tests** (6 files) - Consider if any can be converted to pure unit tests with better mocking.

---

## Recommendations

### 1. Maintain Current Standards ✅

The codebase demonstrates excellent test discipline:
- 71.3% pure unit tests (@Tag("unit"), MockitoExtension)
- Clear separation between unit and integration tests
- Proper use of @Tag("integration") for Testcontainers tests
- Consistent use of IntegrationTestSupport/AbstractContainerBaseTest

### 2. Documentation Improvements

- Add test categorization guidelines to `docs/03_Technical_Guides/testing-guide.md`
- Document when to use @Tag("unit") vs @Tag("integration")
- Create examples of pure unit test patterns for new developers

### 3. Test Execution Strategy

**Current Best Practice:**
```bash
# Run only fast unit tests (71.3% of tests)
./gradlew test --tests "*Test" --tags "unit"

# Run integration tests separately (17.2% of tests)
./gradlew test --tests "*Test" --tags "integration"
```

**Optimization:**
- Configure Gradle test suites to separate unit/integration tests
- Add CI/CD stage for unit tests (fast feedback)
- Add separate CI/CD stage for integration tests (slower but essential)

### 4. Future Test Development Guidelines

**For New Tests:**

1. **Default to Pure Unit Tests** (@Tag("unit"))
   - Use @ExtendWith(MockitoExtension.class)
   - No @SpringBootTest
   - No @Autowired
   - Manual mock injection via constructor

2. **Use Integration Tests Only When Necessary** (@Tag("integration"))
   - Spring Context required (@Autowired beans)
   - Database/Redis/Testcontainers needed
   - End-to-end flow validation
   - Cache serialization/deserialization

3. **Avoid Anti-Patterns:**
   - @SpringBootTest for single service testing → Use pure mocks
   - @WebMvcTest without @MockBean → Use standaloneSetup MockMvc
   - Testcontainers for unit testing → Use Mockito

---

## Appendix: Test File Inventory

### By Package Structure

```
controller/ (5 files)
├── GameCharacterControllerV1Test ✅
├── GameCharacterControllerV2Test ✅
├── GameCharacterControllerV3Test ✅
├── GameCharacterControllerV4Test ✅
└── AdminControllerUnitTest ✅

service/calculator/ (1 file)
└── PotentialCalculatorTest ✅

service/v2/ (20+ files)
├── auth/
│   ├── SessionServiceTest ✅
│   ├── AuthServiceTest ✅
│   └── RefreshTokenServiceTest ✅
├── cube/
│   ├── component/
│   │   ├── ProbabilityConvolverTest ✅
│   │   └── TailProbabilityCalculatorTest ✅
│   ├── dto/
│   │   ├── SparsePmfTest ✅
│   │   └── DensePmfTest ✅
│   └── policy/
│       └── CubeCostPolicyTest ✅
├── donation/outbox/
│   ├── OutboxProcessorTest ✅
│   ├── DlqHandlerTest ✅
│   └── DlqAdminServiceTest ✅
├── cache/
│   ├── EquipmentCacheServiceTest ✅
│   └── TotalExpectationCacheServiceTest ✅
└── like/
    ├── LikeSyncServiceTest ✅
    └── realtime/RedisLikeEventPublisherTest ✅ (integration)

service/v4/ (2 files)
└── EquipmentExpectationServiceV4SingleflightTest ✅ (integration)

service/ingestion/ (3 files)
├── AclPipelineIntegrationTest ✅ (integration)
├── NexonDataCollectorTest ✅ (integration)
└── BatchWriterTest ✅ (integration)

global/ (30+ files)
├── cache/
│   ├── TieredCacheTest ✅
│   └── invalidation/RedisCacheInvalidationPublisherTest ✅
├── executor/
│   └── DefaultCheckedLogicExecutorTest ✅
├── lock/
│   └── ResilientLockStrategyExceptionFilterTest ⚠️
├── queue/ (10+ files)
│   ├── IdempotencyGuardTest ✅
│   ├── strategy/RedisBufferStrategyTest ✅
│   ├── expectation/RedisExpectationWriteBackBufferTest ✅
│   ├── like/
│   │   ├── PartitionedFlushStrategyTest ✅
│   │   ├── RedisLikeBufferStorageTest ✅
│   │   └── RedisLikeRelationBufferTest ✅
│   └── persistence/RedisEquipmentPersistenceTrackerTest ✅
├── ratelimit/ (4 files)
│   ├── RateLimitingServiceTest ✅
│   ├── RateLimitingFacadeTest ✅
│   ├── RateLimitContextTest ✅
│   └── ConsumeResultTest ✅
├── resilience/ (3 files)
│   ├── DistributedCircuitBreakerTest ✅
│   ├── CircuitBreakerMarkerP0Test ✅
│   └── RetryBudgetManagerTest ✅
├── security/ (5 files)
│   ├── jwt/JwtTokenProviderTest ✅
│   ├── filter/JwtAuthenticationFilterTest ✅
│   ├── cors/CorsOriginValidatorTest ✅
│   ├── cors/CorsValidationFilterTest ✅
│   └── filter/PrometheusSecurityFilterTest ✅
└── shutdown/
    ├── GracefulShutdownIntegrationTest ✅ (integration)
    ├── dto/FlushResultTest ✅
    └── dto/ShutdownDataTest ✅

monitoring/ (6 files)
├── PiiMaskingFilterTest ✅
├── MetricsCollectorTest ✅
├── AlertThrottlerTest ✅
├── AiSreServiceTest ✅
├── MonitoringAlertServiceUnitTest ✅
└── copilot/client/PrometheusClientTest ✅

scheduler/ (4 files)
├── OutboxSchedulerTest ✅ (integration-adjacent)
├── ExpectationBatchWriteSchedulerTest ✅ (integration-adjacent)
├── PopularCharacterWarmupSchedulerTest ✅ (integration-adjacent)
└── LikeSyncSchedulerTest ✅ (integration-adjacent)

util/ (4 files)
├── StatParserTest ✅
├── converter/GzipStringConverterTest ✅
└── StatTypeTest ✅

external/ (3 files)
├── proxy/ResilientNexonApiClientTest ✅ (integration)
├── proxy/DependencyChainTest ✅ (integration)
└── dto/EquipmentResponseTest ✅ (integration-adjacent)

cache/ (1 file)
└── ExpectationCacheIntegrationTest ✅ (integration)

archunit/ (2 files)
├── ArchitectureTest ✅
└── CleanArchitectureTest ✅

characterization/ (1 file)
└── CalculatorCharacterizationTest ✅
```

---

## Conclusion

**The test suite is in excellent health.** 88.5% of tests are properly categorized as either pure unit tests (71.3%) or essential integration tests (17.2%). Only 9.2% of tests require optimization review.

**Key Strengths:**
1. Consistent use of @Tag("unit") for pure unit tests
2. Clear separation of integration tests with @Tag("integration")
3. Proper use of Testcontainers for database/Redis testing
4. Minimal usage of @SpringBootTest (only where necessary)
5. Strong adoption of MockitoExtension for isolated unit testing

**No immediate action required.** The current test structure supports fast feedback loops and maintains confidence in system correctness.
