# Testing Guide

> **상위 문서:** [CLAUDE.md](../CLAUDE.md)
>
> **Last Updated:** 2026-02-05
> **Applicable Versions:** JUnit 5, Testcontainers 1.x, Java 21
> **Documentation Version:** 1.0

이 문서는 MapleExpectation 프로젝트의 테스트 작성, 동시성 테스트, Flaky Test 방지 규칙을 정의합니다.

## Terminology

| 용어 | 정의 |
|------|------|
| **Flaky Test** | 코드 변경 없이 때때로 실패하는 비결정적 테스트 |
| **Testcontainers** | Docker를 활용한 통합 테스트 환경 격리 |
| **Race Condition** | 비동기 작업 완료 순서에 의존하여 발생하는 결함 |
| **CountDownLatch** | 스레드 간 작업 완료 신호 동기화 도구 |

---

## 23. ExecutorService 동시성 테스트 Best Practice

동시성 테스트에서 Race Condition을 방지하기 위한 필수 패턴입니다.

### shutdown() vs awaitTermination() (Critical)

`ExecutorService.shutdown()`은 **새로운 작업 제출만 막고 즉시 반환**됩니다.
기존 작업 완료를 보장하려면 반드시 `awaitTermination()`을 호출해야 합니다.

```java
// Bad (Race Condition 발생)
executorService.shutdown();
// 아직 작업 실행 중인데 결과 검증!
assertEquals(expected, actualResult);

// Good (모든 작업 완료 보장)
executorService.shutdown();
executorService.awaitTermination(5, TimeUnit.SECONDS);
// 이제 안전하게 검증 가능
assertEquals(expected, actualResult);
```

### CountDownLatch + awaitTermination 조합 (Recommended)

```java
int taskCount = 100;
ExecutorService executor = Executors.newFixedThreadPool(16);
CountDownLatch latch = new CountDownLatch(taskCount);

for (int i = 0; i < taskCount; i++) {
    executor.submit(() -> {
        try {
            // 비즈니스 로직
            service.process();
        } finally {
            latch.countDown();  // 작업 완료 신호
        }
    });
}

// Step 1: 모든 작업이 finally 블록까지 도달 대기
latch.await(10, TimeUnit.SECONDS);

// Step 2: Executor 종료 및 완료 대기 (추가 안전장치)
executor.shutdown();
executor.awaitTermination(5, TimeUnit.SECONDS);

// Step 3: 결과 검증
assertResult();
```

### 왜 둘 다 필요한가?

| 단계 | latch.await() | awaitTermination() |
|------|--------------|-------------------|
| 목적 | 작업 완료 **신호** 대기 | 스레드 종료 대기 |
| 보장 | finally 블록 실행 완료 | 스레드 리소스 정리 |
| 누락 시 | 일부 작업 미완료 상태 검증 | 스레드 누수 가능 |

### Caffeine Cache + AtomicLong 동시성 패턴

```java
// LikeBufferStorage.java - Thread-Safe 패턴
private final Cache<String, AtomicLong> likeCache = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build();

// Caffeine.get()은 원자적이지만, 반환된 AtomicLong 조작과
// 후속 처리(Redis 전송) 사이에는 Race 가능
public AtomicLong getCounter(String userIgn) {
    return likeCache.get(userIgn, key -> new AtomicLong(0));
}

// flushLocalToRedis() 호출 전 반드시 awaitTermination() 필요!
```

### Flaky Test 방지 체크리스트

- [ ] `shutdown()` 후 `awaitTermination()` 호출
- [ ] latch.await() 타임아웃 충분히 설정 (10초 이상)
- [ ] 테스트 간 상태 격리 (캐시/DB 초기화)
- [ ] 비동기 AOP 사용 시 실제 작업 완료 시점 검증

---

## 24. Flaky Test 근본 원인 분석 및 해결 가이드 (Critical)

Flaky Test(비결정적 테스트)는 코드 변경 없이 때때로 실패하는 테스트입니다. CI/CD 신뢰도를 떨어뜨리고 개발 생산성을 저하시키므로 반드시 근본 원인을 파악하여 제거해야 합니다.

### Flaky Test의 6대 근본 원인

| 원인 | 설명 | 대표 사례 |
|------|------|----------|
| **1. 시간 의존성** | 현재 시간/날짜에 의존하는 로직 | `LocalDate.now()`, 타임아웃 기반 로직 |
| **2. 순서 의존성 (Race Condition)** | 비동기 작업 완료 순서에 의존 | `CompletableFuture`, 멀티스레드 테스트 |
| **3. 외부 의존성** | 네트워크, DB, 외부 API 상태에 의존 | Redis 연결, HTTP 호출, 파일 시스템 |
| **4. 환경 차이** | 로컬 vs CI 환경 차이 | CPU 코어 수, 메모리, Docker 가용성 |
| **5. 공유 상태** | 테스트 간 상태가 격리되지 않음 | static 변수, 싱글톤, DB 레코드 |
| **6. 무작위성** | Random, UUID 등 비결정적 값 사용 | `Math.random()`, `UUID.randomUUID()` |

### Flaky Test 제거 5대 원칙

```
+-------------------------------------------------------------+
|  1. Determinism (결정성)                                     |
|     -> 같은 입력 = 같은 출력 (시간, 랜덤 주입)                 |
+-------------------------------------------------------------+
|  2. Isolation (격리)                                         |
|     -> 테스트 간 상태 공유 금지 (@DirtiesContext, @BeforeEach) |
+-------------------------------------------------------------+
|  3. Independence (독립성)                                    |
|     -> 실행 순서에 관계없이 동일 결과                          |
+-------------------------------------------------------------+
|  4. Explicit Synchronization (명시적 동기화)                 |
|     -> Thread.sleep() 금지, CountDownLatch/Awaitility 사용    |
+-------------------------------------------------------------+
|  5. Observability (관찰 가능성)                              |
|     -> 실패 시 충분한 로그/스택트레이스 확보                   |
+-------------------------------------------------------------+
```

### 원인별 해결 패턴

#### 1. 시간 의존성 해결

```java
// Bad (현재 시간 직접 사용 -> 자정/월말 등에 실패)
public boolean isExpired() {
    return LocalDate.now().isAfter(expiryDate);
}

// Good (Clock 주입 -> 테스트에서 고정 시간 사용)
public boolean isExpired(Clock clock) {
    return LocalDate.now(clock).isAfter(expiryDate);
}

// 테스트 코드
Clock fixedClock = Clock.fixed(Instant.parse("2024-06-15T10:00:00Z"), ZoneId.of("UTC"));
assertTrue(service.isExpired(fixedClock));
```

#### 2. Race Condition 해결

```java
// Bad (비동기 완료 전 검증 -> 간헐적 실패)
executor.submit(() -> service.process());
assertEquals(expected, repository.findAll().size());

// Good (명시적 완료 대기)
CountDownLatch latch = new CountDownLatch(1);
executor.submit(() -> {
    try {
        service.process();
    } finally {
        latch.countDown();
    }
});
latch.await(10, TimeUnit.SECONDS);
executor.shutdown();
executor.awaitTermination(5, TimeUnit.SECONDS);
assertEquals(expected, repository.findAll().size());

// Alternative (Awaitility 라이브러리)
await().atMost(Duration.ofSeconds(10))
       .untilAsserted(() -> assertEquals(expected, repository.findAll().size()));
```

#### 3. 외부 의존성 해결

```java
// Bad (실제 Redis 연결 -> 네트워크 불안정 시 실패)
@Test
void testWithRealRedis() {
    redisTemplate.opsForValue().set("key", "value");
}

// Good (Testcontainers로 격리된 환경)
@Testcontainers
class RedisIntegrationTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", redis::getFirstMappedPort);
    }
}

// Alternative (Mock으로 완전 격리)
@MockBean
private RedisTemplate<String, String> redisTemplate;
```

#### 4. 환경 차이 해결

```java
// Bad (CI 환경에서 Docker 없으면 실패)
@Test
void testWithDocker() {
    // Docker 필수 테스트
}

// Good (조건부 실행)
@Test
@EnabledIf("isDockerAvailable")
void testWithDocker() {
    // Docker 있을 때만 실행
}

static boolean isDockerAvailable() {
    try {
        DockerClientFactory.instance().client();
        return true;
    } catch (Exception e) {
        return false;
    }
}

// Alternative (환경별 프로파일 분리)
@ActiveProfiles("test")  // test 프로파일에서는 embedded/mock 사용
```

#### 5. 공유 상태 해결

```java
// Bad (static 변수 -> 테스트 간 오염)
public class CounterService {
    private static int count = 0;  // 전역 상태
}

// Good (테스트마다 컨텍스트 초기화)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CounterServiceTest { }

// Alternative (@BeforeEach에서 명시적 초기화)
@BeforeEach
void setUp() {
    repository.deleteAll();
    cacheManager.getCache("myCache").clear();
}
```

#### 6. 무작위성 해결

```java
// Bad (매번 다른 UUID -> 검증 어려움)
public String generateId() {
    return UUID.randomUUID().toString();
}

// Good (ID 생성기 주입)
public String generateId(Supplier<String> idGenerator) {
    return idGenerator.get();
}

// 테스트 코드
String fixedId = "test-id-12345";
String result = service.generateId(() -> fixedId);
assertEquals(fixedId, result);
```

### UI/E2E 테스트 안정화 규칙

| 규칙 | 설명 |
|------|------|
| **명시적 대기** | `Thread.sleep()` 대신 element visible/clickable 조건 대기 |
| **Retry 메커니즘** | 일시적 네트워크 오류에 대한 재시도 로직 |
| **스크린샷 캡처** | 실패 시 자동 스크린샷 저장 |
| **격리된 테스트 데이터** | 테스트마다 고유 사용자/데이터 생성 |

### 조직적 Flaky Test 관리 정책

```
+-------------------------------------------------------------+
|  1. Quarantine (격리)                                        |
|     -> 3회 이상 flaky 발생 시 @Tag("flaky")로 분리            |
+-------------------------------------------------------------+
|  2. Ownership (소유권)                                       |
|     -> flaky 테스트 발생 시 담당자 지정 (Issue 생성)           |
+-------------------------------------------------------------+
|  3. SLA (서비스 수준)                                        |
|     -> 7일 내 수정 또는 삭제 결정                             |
+-------------------------------------------------------------+
|  4. Metrics (측정)                                           |
|     -> flaky rate 모니터링 (목표: < 1%)                       |
+-------------------------------------------------------------+
```

### Flaky Test 디버깅 우선순위 체크리스트

1. **[ ] awaitTermination() 누락 확인** - 비동기 작업 완료 대기
2. **[ ] @BeforeEach 격리 확인** - 테스트 간 상태 초기화
3. **[ ] Testcontainers 연결 확인** - Docker 가용성 및 포트 충돌
4. **[ ] 시간 기반 로직 확인** - Clock 주입 여부
5. **[ ] static/싱글톤 상태 확인** - 전역 상태 오염
6. **[ ] CI 환경 리소스 확인** - 메모리, CPU 제한
7. **[ ] 단독 실행 vs 전체 실행** - 순서 의존성 확인

### Flaky Test 탐지 도구

```bash
# Gradle에서 반복 실행으로 flaky 탐지
./gradlew test --tests "TargetTest" --rerun-tasks -PmaxParallelForks=1

# 특정 테스트 N회 반복
for i in {1..10}; do ./gradlew test --tests "TargetTest" || echo "Failed at $i"; done

# Maven Surefire 반복 실행
mvn -Dsurefire.rerunFailingTestsCount=3 test
```

### MapleExpectation 프로젝트 특화 규칙

| 컴포넌트 | Flaky 원인 | 해결책 |
|----------|-----------|--------|
| **Redis 통합테스트** | Testcontainers 시작 지연 | `@Container` + `@DynamicPropertySource` |
| **LikeSync 테스트** | 비동기 버퍼 플러시 타이밍 | `awaitTermination()` + `CountDownLatch` |
| **Shutdown 테스트** | Spring Context 종료 타이밍 | `@DirtiesContext` + 명시적 대기 |
| **Cache 테스트** | TTL 기반 만료 | `Clock` 주입 또는 즉시 만료 설정 |

---

## 25. 경량 테스트 강제 규칙 (Lightweight Test Policy)

CI 파이프라인 속도 최적화를 위한 테스트 베이스 클래스 선택 규칙입니다. (Issue #207)

### 테스트 베이스 클래스 계층 (Template Method Pattern)

```
SimpleRedisContainerBase          (MySQL + Redis, ~3초)
    -> IntegrationTestSupport     (+ Mock Beans)
        -> 대부분의 통합 테스트 (90%)

AbstractContainerBaseTest         (MySQL + Redis + Toxiproxy, ~5초)
    -> P0 Chaos 테스트 (장애 주입 필요)

SentinelContainerBase             (7개 컨테이너, ~30초)
    -> Redis Sentinel HA 테스트만
```

### 베이스 클래스 선택 가이드 (MANDATORY)

| 테스트 유형 | 베이스 클래스 | @Tag | 이유 |
|------------|--------------|------|------|
| **일반 통합 테스트** | `IntegrationTestSupport` | `@Tag("integration")` | 기본 선택 |
| **단위 테스트** | 없음 (순수 JUnit) | `@Tag("unit")` | 컨테이너 불필요 |
| **장애 주입 테스트** | `AbstractContainerBaseTest` | `@Tag("chaos")` | Toxiproxy 필요 |
| **Sentinel HA 테스트** | `SentinelContainerBase` | `@Tag("sentinel")` | 7개 컨테이너 필요 |

### 금지 규칙 (Critical)

```java
// FORBIDDEN: Sentinel이 필요 없는데 IntegrationTestSupport에 7개 컨테이너 로드
// (Issue #207 이전의 안티 패턴)
class MySimpleTest extends IntegrationTestSupport {  // 30초 오버헤드!
    @Test void simpleDbTest() { }  // DB만 필요
}

// CORRECT: 필요한 컨테이너만 사용
class MySimpleTest extends IntegrationTestSupport {  // 3초만 소요
    @Test void simpleDbTest() { }
}
```

### 새 테스트 작성 시 체크리스트

1. **[ ] 단위 테스트 가능한가?** -> 베이스 클래스 없이 작성
2. **[ ] DB/Redis만 필요한가?** -> `IntegrationTestSupport` 상속
3. **[ ] 장애 주입(Toxiproxy)이 필요한가?** -> `AbstractContainerBaseTest` 상속
4. **[ ] Sentinel Failover 테스트인가?** -> `SentinelContainerBase` 상속

### @Tag 분류 체계

| 태그 | 실행 시점 | 예상 시간 |
|------|----------|----------|
| `@Tag("unit")` | PR Gate | < 30초 |
| `@Tag("integration")` | PR Gate | 2-3분 |
| `@Tag("chaos")` | PR Gate | 1-2분 |
| `@Tag("sentinel")` | Nightly | 5분+ |
| `@Tag("slow")` | Nightly | 다양 |

### JUnit 5 병렬 실행 설정

`src/test/resources/junit-platform.properties`:
```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.config.strategy=dynamic
junit.jupiter.execution.parallel.config.dynamic.factor=0.5
```

### Gradle 태그 필터링

```bash
# PR Gate (빠른 피드백)
./gradlew test -PfastTest

# Nightly (전체 검증)
./gradlew test
```

## Evidence Links
- **Test Base Classes:** `src/test/java/maple/expectation/config/`
- **Example Tests:** `src/test/java/maple/expectation/service/v2/`
- **JUnit Config:** `src/test/resources/junit-platform.properties`

## Fail If Wrong

이 가이드가 부정확한 경우:
- **awaitTermination() 누락**: 비동기 테스트가 Race Condition으로 실패
- **Testcontainers 미사용**: 외부 의존성(Redis, MySQL)으로 인한 Flaky Test
- **@BeforeEach 미사용**: 테스트 간 상태 공유로 간헐적 실패
- **Thread.sleep() 사용**: 환경별 성능 차이로 Flaky Test

### Verification Commands
```bash
# awaitTermination 사용 확인
grep -r "awaitTermination" src/test/java --include="*.java"

# Thread.sleep 사용 확인 (금지)
grep -r "Thread\.sleep" src/test/java --include="*.java"

# @BeforeEach 사용 확인
grep -r "@BeforeEach" src/test/java --include="*.java" | wc -l
```
