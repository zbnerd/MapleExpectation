# claude.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

MapleExpectation is a Spring Boot application that calculates MapleStory equipment upgrade costs using Nexon's Open API. Built for resilience and scalability, it handles 1,000+ concurrent users on low-spec infrastructure (AWS t3.small) with 240 RPS throughput.

**Core Technologies:**
- **Java 17** - Modern features (Records, Pattern Matching, Switch Expressions)
- **Spring Boot 3.5.4** - Latest stable release
- **MySQL 8.0** - Persistent storage with GZIP compression
- **Redis** (Redisson 3.27.0) - Distributed caching and locking
- **Resilience4j 2.2.0** - Circuit breaker and resilience patterns
- **Caffeine Cache** - Local L1 caching layer
- **Gradle** - Build tool
- **Testcontainers** - Integration testing with Docker
- **Docker Socket** (`unix:///var/run/docker.sock`) - For Testcontainers

## Essential Commands

### Build & Test
```bash
# Build project (skips tests)
./gradlew clean build -x test

# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "maple.expectation.service.v2.GameCharacterServiceTest"

# Run specific test method
./gradlew test --tests "maple.expectation.service.v2.GameCharacterServiceTest.testMethodName"
```

### Local Development
```bash
# Start local MySQL + Redis via Docker Compose
docker-compose up -d

# Run application (default profile: local)
./gradlew bootRun

# Run with specific profile
./gradlew bootRun --args='--spring.profiles.active=prod'
```

### Database
```bash
# Access MySQL container
docker exec -it mysql_container mysql -u root -p

# Access Redis CLI
docker exec -it redis_container redis-cli
```

---

# 📂 CLAUDE.md (Project Guidelines)

## 🛠 1. Tech Stack & Context (Refer to Context 7)
이 프로젝트의 빌드 환경과 라이브러리 구성을 반드시 참조하여 최신 권장 방식(Best Practice)으로 구현하십시오.
- **Core:** Java 17, Spring Boot 3.5.4, Gradle
- **Dependencies:** Resilience4j(BOM 2.2.0), Redisson(3.27.0), Caffeine, JPA, MySQL, Jackson CSV
- **Infrastructure:** Docker Socket (unix:///var/run/docker.sock) for Testcontainers

---

## 🌿 2. Git Strategy & Commit Convention
- **Branch:** `develop`에서 분기. `feature/{기능}`, `release-{버전}`, `hotfix-{버전}`
- **Commit 규칙:** 타입(영어): 제목(한글). 7대 규칙 준수. (예: `feat: 로그인 기능 구현`)

---

## 🚀 3. Pull Request (PR) Template (Mandatory)
- PR 제출 시 아래 양식을 반드시 사용하여 작성하십시오.
- PR 제출 전 해당 이슈가 100% 모두 충족이 된다음에 PR을 제출하여야합니다.

```markdown
## 🔗 관련 이슈
#이슈번호

## 🗣 개요
변경 사항 요약

## 🛠 작업 내용
- [ ] 세부 작업 항목

## 💬 리뷰 포인트
리뷰어가 집중적으로 확인해야 할 부분

## 💱 트레이드 오프 결정 근거
기술적 선택의 이유와 대안 비교

## ✅ 체크리스트
- [ ] 브랜치/커밋 규칙 준수 여부
- [ ] 테스트 통과 여부
```
---

## 🧠 4. Implementation Logic & SOLID
- **Sequential Thinking:** 작업 전 의존성, 최신 문법, 인프라 영향을 단계별로 분석하여 디테일을 확보합니다.
- **SOLID 원칙:** SRP, OCP, LSP, ISP, DIP를 엄격히 준수하여 응집도를 높이고 결합도를 낮춥니다.
- **Modern Java:** Java 17의 Records, Pattern Matching, Switch Expressions 등을 적극 활용합니다.

### Optional Chaining Best Practice (Modern Null Handling)
null 체크 로직은 **Optional 체이닝**으로 대체하여 선언적이고 가독성 높은 코드를 작성합니다.

**기본 패턴:**
```java
// ❌ Bad (Imperative null check)
ValueWrapper wrapper = l1.get(key);
if (wrapper != null) {
    recordHit("L1");
    return wrapper;
}
wrapper = l2.get(key);
if (wrapper != null) {
    l1.put(key, wrapper.get());
    return wrapper;
}
return null;

// ✅ Good (Declarative Optional chaining)
return Optional.ofNullable(l1.get(key))
        .map(w -> tap(w, "L1"))
        .or(() -> Optional.ofNullable(l2.get(key))
                .map(w -> { l1.put(key, w.get()); return tap(w, "L2"); }))
        .orElse(null);
```

**Tap 패턴 (Side Effect with Return):**
```java
// 값을 반환하면서 부수 효과(메트릭 기록 등) 실행
private ValueWrapper tap(ValueWrapper wrapper, String layer) {
    recordCacheHit(layer);
    return wrapper;
}
```

**Checked Exception 구조적 분리 (try-catch/RuntimeException 금지):**

Optional.orElseGet()은 Supplier를 받아 checked exception을 던질 수 없습니다.
**절대로 try-catch로 감싸거나 RuntimeException으로 변환하지 마십시오.** (섹션 11, 12 위반)

대신 **구조적 분리**로 해결합니다:
```java
// ❌ Bad (섹션 11, 12 위반)
.orElseGet(() -> {
    try { return loadFromDatabase(key); }
    catch (Exception e) { throw new RuntimeException(e); }
})

// ✅ Good (구조적 분리)
private <T> T getWithFallback(Object key, Callable<T> loader) throws Exception {
    // 1. Optional은 예외 없는 캐시 조회에만 사용
    T cached = getCachedValue(key);
    if (cached != null) {
        return cached;
    }

    // 2. 예외 발생 가능한 작업은 Optional 밖에서 직접 호출
    return loader.call();  // checked exception 자연 전파
}

private <T> T getCachedValue(Object key) {
    return Optional.ofNullable(l1.get(key))
            .map(w -> tapAndCast(w, "L1"))
            .orElse(null);  // 예외 없음, null 반환
}
```

**핵심 원칙:**
- Optional 체이닝 → 예외 없는 작업만 (캐시 조회, 필터링)
- checked exception → Optional 밖에서 직접 호출
- 예외 변환 → LogicExecutor.executeWithTranslation() 사용

---

## 🚫 5. Anti-Pattern & Deprecation Prohibition
- **No Hardcoding:** 모든 값은 설정 파일, Enum, 상수로 관리합니다.
- **No Spaghetti:** 중첩 깊이(Indentation)는 최대 2단계로 제한하며 Fail Fast(Early Return)를 지향합니다.
- **No Deprecated:** @deprecated 기능은 절대 사용하지 않으며 최신 Best Practice API(예: RestClient)를 사용합니다.

---

## 🏗️ 6. Design Patterns & Structure
- **Essential Patterns:** Strategy, Factory, Template Method, Proxy 패턴 등을 상황에 맞게 적용합니다.
- **Naming:** 의도가 명확한 변수명(예: `activeSubscribers`)을 사용하고, 메서드는 20라인 이내로 유지합니다.
- **Injection:** 생성자 주입(@RequiredArgsConstructor)을 필수 사용합니다.

---

## 🏗️ 7. AOP & Facade Pattern (Critical)
AOP 적용 시 프록시 메커니즘 한계 극복을 위해 반드시 **Facade 패턴**을 사용합니다.
- **Avoid Self-Invocation:** 동일 클래스 내 AOP 메서드 내부 호출을 절대 금지합니다.
- **Orchestration:** Facade는 분산 락 획득 및 서비스 간 흐름을 제어하고, Service는 트랜잭션과 비즈니스 로직을 담당합니다.
- **Scope:** 락의 범위가 트랜잭션보다 커야 함(Lock -> Transaction -> Unlock)을 보장합니다.

---

## ⛓️ 8. Redis & Redisson Integration
- **Distributed Lock:** 동시성 제어 시 `RLock`을 사용하며 `try-finally`로 데드락을 방지합니다.
- **Naming:** Redis 키는 `domain:sub-domain:id` 형식을 따르며 모든 데이터에 TTL을 설정합니다.

---

## 📈 9. Observability & Validation
- **Logging:** @Slf4j 사용. INFO(주요 지점), DEBUG(장애 추적), ERROR(오류) 레벨을 엄격히 구분합니다.
- **Validation:** Controller(DTO 형식)와 Service(비즈니스 규칙)의 검증 책임을 분리합니다.
- **Response:** 일관된 `ApiResponse<T>` 공통 포맷을 사용하여 응답합니다.

---

## 🧪 10. Mandatory Testing & Zero-Failure Policy
- **Mandatory:** 모든 구현/리팩토링 시 테스트 코드를 반드시 세트로 작성합니다.
- **Policy:** 테스트를 통과시키기 위해 `@Disabled`를 사용하거나 테스트를 삭제하는 행위를 엄격히 금지합니다. 반드시 로직을 디버깅하여 100% 통과(All Green)를 달성해야 합니다.
- **Mocking:** `LogicExecutor` 테스트 시 `doAnswer`를 사용하여 Passthrough 설정을 적용, 실제 람다가 실행되도록 검증합니다.
---

## 🚨 11. Exception Handling Strategy (AI Mentor Recommendation)
예외 처리는 시스템의 **회복 탄력성(Resilience)**과 **디버깅 가시성**을 확보하는 핵심 수단입니다.

- **Hierarchy:**
  - **ClientBaseException (4xx):** 비즈니스 예외. `CircuitBreakerIgnoreMarker`를 구현하여 서킷브레이커 상태에 영향을 주지 않음.
  - **ServerBaseException (5xx):** 시스템/인프라 예외. `CircuitBreakerRecordMarker`를 구현하여 장애 발생 시 서킷브레이커를 작동시킴.
- **No Ambiguous Exceptions:** `RuntimeException`, `Exception` 등을 직접 던지는 것을 금지하며, 반드시 비즈니스 맥락이 담긴 **Custom Exception**을 정의합니다.
- **Checked to Unchecked:** `IOException` 등 체크 예외는 발생 지점에서 `catch`하여 적절한 `ServerBaseException`으로 변환합니다. 이때 원인 예외(`cause`)를 넘겨 **Exception Chaining**을 유지합니다.
- **Dynamic Message:** `String.format`을 활용하여 에러 메시지에 구체적인 식별자(ID, IGN 등)를 포함해 디버깅 가시성을 높입니다.

---

## 🚨 12. Zero Try-Catch Policy & LogicExecutor (Architectural Core)
비즈니스 로직에서 `try-catch` 블록을 사용하는 것을 **엄격히 금지**합니다. 모든 실행 흐름과 예외 처리는 **`LogicExecutor`** 템플릿에 위임합니다.

### 🔑 LogicExecutor 사용 패턴 가이드
| 패턴 | 메서드 | 용도 |
| :--- | :--- | :--- |
| **패턴 1** | `execute(task, context)` | 일반적인 실행. 예외 발생 시 로그 기록 후 상위 전파. |
| **패턴 2** | `executeVoid(task, context)` | 반환값이 없는 작업(Runnable) 실행. |
| **패턴 3** | `executeOrDefault(task, default, context)` | 예외 발생 시 안전하게 기본값 반환 (조회 로직 등). |
| **패턴 4** | `executeWithRecovery(task, recovery, context)` | 예외 발생 시 특정 복구 로직(람다) 실행. |
| **패턴 5** | `executeWithFinally(task, finalizer, context)` | 자원 해제 등 `finally` 블록이 반드시 필요한 경우 사용. |
| **패턴 6** | `executeWithTranslation(task, translator, context)` | 기술적 예외(IOException 등)를 도메인 예외로 변환. |

**Code Example:**
```java
// ❌ Bad (Legacy)
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
    return null;
}

// ✅ Good (Modern)
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", id)
);
```
단, TraceAspect는 예외로 try-catch-finally 를 허용합니다. (LogicExecutor 순환참조 발생)

## 🛡️ 12. Circuit Breaker & Resilience Rules
장애가 전체 시스템으로 전파되는 것을 방지하기 위해 Resilience4j 설정을 준수합니다.

- **Marker Interface:** 예외 클래스에 `CircuitBreakerIgnoreMarker` 또는 `CircuitBreakerRecordMarker`를 명시하여 서킷브레이커의 기록 여부를 결정합니다.
- **Logging Level:**
  - 비즈니스 예외(4xx): `log.warn`을 사용하여 비정상적인 요청 흐름 기록.
  - 서버/외부 API 예외(5xx): `log.error`를 사용하여 스택 트레이스와 함께 장애 상황 기록.
- **Fallback:** 서킷이 오픈되거나 예외 발생 시, 사용자 경험을 해치지 않도록 적절한 폴백 로직을 고려합니다.

---

## 🎯 13. Global Error Mapping & Response
모든 예외는 `GlobalExceptionHandler`를 통해 규격화된 응답으로 변환됩니다.

- **Centralized Handling:** `@RestControllerAdvice`를 사용하여 전역적으로 예외를 포착합니다.
- **Consistent Format:** 모든 에러 응답은 `ErrorResponse` 레코드 형식을 따릅니다.
    - 비즈니스 예외: 가공된 동적 메시지를 포함하여 응답.
    - 알 수 없는 시스템 예외: 보안을 위해 상세 내용을 숨기고 `INTERNAL_SERVER_ERROR` 코드로 캡슐화.

---

## 🚫 14. Anti-Pattern: Error Handling & Maintenance
다음과 같은 안티 패턴은 발견 즉시 리팩토링 대상입니다.

- **Catch and Ignore:** 예외를 잡고 아무 처리도 하지 않거나 로그만 남기고 무시하는 행위 금지.
- **Hardcoded Error Messages:** 에러 메시지를 소스 코드에 직접 적지 말고 `ErrorCode` Enum에서 관리합니다.
- **Standard Output:** `e.printStackTrace()`나 `System.out.println()` 대신 반드시 `@Slf4j` 로거를 사용합니다.
- **God Class/Spaghetti:** 하나의 메서드가 여러 책임을 지거나 2단계를 초과하는 인덴트를 가지지 않도록 작게 쪼갭니다.
- **Direct try-catch:** 비즈니스 로직 내에 try-catch가 보이면 즉시 리팩토링 대상입니다.
- **Raw Thread Usage:** new Thread(), Future 직접 사용 금지. LogicExecutor 또는 비동기 어노테이션을 사용합니다.
- **Log Pollution:** 의미 없는 로그 산재 금지. TaskContext를 통해 구조화된 로그를 남깁니다.

---

## 🚫 15. Anti-Pattern: Lambda & Parenthesis Hell (Critical)
`LogicExecutor` 도입으로 `try-catch`는 사라졌지만, 과도한 람다 중첩으로 인한 **"괄호 지옥"**이 발생해서는 안 됩니다.

- **Rule of Thumb (3-Line Rule):** 람다 내부 로직이 **3줄**을 초과하거나 분기문(`if/else`)이 포함된다면, 즉시 **Private Method**로 추출합니다.
- **Method Reference Preference:** `() -> service.process(param)` 대신 `service::process` 또는 `this::process` 형태의 메서드 참조를 최우선으로 사용합니다.
- **Flattening:** `executor.execute(() -> executor.execute(() -> ...))` 형태의 중첩 실행을 금지합니다. 각 단계를 메서드로 분리하여 수직적 깊이를 줄이십시오.

**Code Example:**
```java
// ❌ Bad (Lambda Hell: 가독성 최악, 디버깅 어려움)
return executor.execute(() -> {
    User user = repo.findById(id).orElseThrow(() -> new RuntimeException("..."));
    if (user.isActive()) {
        return otherService.process(user.getData().stream()
            .filter(d -> d.isValid())
            .map(d -> {
                // ... complex logic ...
                return d.toDto();
            }).toList());
    }
}, context);

// ✅ Good (Method Extraction: 선언적이고 깔끔함)
return executor.execute(() -> this.processActiveUser(id), context);

// Private Helper Method
private List<Dto> processActiveUser(Long id) {
    User user = findUserOrThrow(id);
    return user.isActive() ? processUserData(user) : List.of();
}
```

## 🔄 15. Proactive Refactoring & Quality (ETC)
- **Refactoring First:** 
  - 새로운 기능 구현 전, 기존 코드가 위 원칙(Facade, SOLID, Exception 전략 등)을 위반한다면 반드시 **리팩토링을 선행**합니다.
  - 기능 추가 전, 기존 코드가 LogicExecutor 패턴을 따르지 않는다면 우선 리팩토링을 수행합니다.
- **Sequential Thinking:** 작업 시작 전 `Context 7`의 기술 스택과 현재 가이드를 단계별로 대조하여 디테일을 놓치지 않습니다.
- **Update Rule:** 새로운 라이브러리나 기술 스택 추가 시, 해당 분야의 Best Practice를 조사하여 `CLAUDE.md`를 즉시 업데이트합니다.
- **Definition of Done:** 코드가 작동하는 것을 넘어, 모든 테스트가 통과하고 위 클린 코드 원칙을 준수했을 때 작업을 완료한 것으로 간주합니다.
- **Context Awareness:** 수정하려는 코드가 TieredCache나 LockStrategy 등 공통 모듈에 영향을 주는지 LogicExecutor의 파급력을 고려하여 작업합니다.

---

## 🗄️ 16. TieredCache & Cache Stampede Prevention

Multi-Layer Cache(L1: Caffeine, L2: Redis) 환경에서 데이터 일관성과 Cache Stampede 방지를 위한 필수 규칙.

### Write Order (L2 → L1) - 원자성 보장
- **필수**: L2(Redis) 저장 성공 후에만 L1(Caffeine) 저장
- **금지**: L1 먼저 저장 후 L2 저장 (L2 실패 시 불일치 발생)
- **L2 실패 시**: L1 저장 스킵, 값은 반환 (가용성 유지)

### Redisson Watchdog 규칙 (Context7 공식)
- **필수**: `tryLock(waitTime, TimeUnit)` - leaseTime 생략하여 Watchdog 모드 활성화
- **금지**: `tryLock(waitTime, leaseTime, TimeUnit)` - 작업이 leaseTime 초과 시 데드락
- **원리**: Watchdog이 `lockWatchdogTimeout`(기본 30초)마다 자동 연장
- **장애 시**: 클라이언트 크래시 → Watchdog 중단 → 30초 후 자동 만료

**Code Example:**
```java
// ❌ Bad (leaseTime 지정 → 작업 초과 시 락 해제됨)
lock.tryLock(30, 5, TimeUnit.SECONDS);

// ✅ Good (Watchdog 모드 → 자동 연장)
lock.tryLock(30, TimeUnit.SECONDS);
```

### unlock() 안전 패턴
- **필수**: `isHeldByCurrentThread()` 체크 후 unlock
- **이유**: 타임아웃으로 자동 해제된 후 unlock() 호출 시 IllegalMonitorStateException

```java
// ✅ Good
finally {
    if (acquired && lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 분산 Single-flight 패턴
- **Leader**: 락 획득 → Double-check L2 → valueLoader 실행 → L2 저장 → L1 저장
- **Follower**: 락 대기 → L2에서 읽기 → L1 Backfill
- **락 실패 시**: Fallback으로 직접 실행 (가용성 우선)

### Cache 메트릭 필수 항목 (Micrometer)
| 메트릭 | 용도 |
|--------|------|
| `cache.hit{layer=L1/L2}` | 캐시 히트율 모니터링 |
| `cache.miss` | Cache Stampede 빈도 확인 |
| `cache.lock.failure` | 락 경합 상황 감지 |
| `cache.l2.failure` | Redis 장애 감지 |

### TTL 규칙
- **필수**: L1 TTL ≤ L2 TTL (L2가 항상 Superset)
- **이유**: L2 먼저 만료되면 L1에만 데이터 존재 → 불일치

### Spring @Cacheable(sync=true) 호환성 (Context7 Best Practice)
- **TieredCache.get(key, Callable)** 구현이 sync 모드 지원
- `@Cacheable(sync=true)` 사용 시 동일 키 동시 요청 → 1회만 계산
- Spring Framework 공식 권장: 동시성 환경에서 sync=true 사용

```java
// ✅ 권장: sync=true로 Cache Stampede 방지
@Cacheable(cacheNames="equipment", sync=true)
public Equipment findEquipment(String id) { ... }
```

### Micrometer 메트릭 명명 규칙 (Context7 Best Practice)
- **필수**: 소문자 점 표기법 (예: `cache.hit`, `cache.miss`)
- **태그**: 차원 분리용 (예: `layer`, `result`)
- **금지**: CamelCase, snake_case

```java
// ✅ Good
meterRegistry.counter("cache.hit", "layer", "L1").increment();
meterRegistry.counter("cache.miss").increment();

// ❌ Bad
meterRegistry.counter("cacheHit").increment();
meterRegistry.counter("cache_hit").increment();
```

### Graceful Degradation Pattern (가용성 우선)
Redis 장애 시에도 서비스 가용성을 유지하기 위한 필수 패턴.

- **원칙**: 캐시 장애가 서비스 장애로 이어지면 안 됨
- **구현**: `LogicExecutor.executeOrDefault()`로 모든 Redis 호출 래핑
- **폴백**: 장애 시 null/false 반환 → valueLoader 직접 실행

**적용 대상 (4곳):**
| 위치 | 래핑 대상 | 기본값 |
|------|----------|--------|
| `getCachedValueFromLayers()` | L2.get() | null |
| `executeWithDistributedLock()` | lock.tryLock() | false |
| `executeDoubleCheckAndLoad()` | L2.get() (Double-check) | null |
| `unlockSafely()` | lock.unlock() | null |

```java
// ❌ Bad (Redis 장애 시 예외 전파 → 서비스 장애)
boolean acquired = lock.tryLock(30, TimeUnit.SECONDS);

// ✅ Good (Graceful Degradation → 가용성 유지)
boolean acquired = executor.executeOrDefault(
        () -> lock.tryLock(30, TimeUnit.SECONDS),
        false,  // Redis 장애 시 락 획득 실패로 처리 → Fallback 실행
        TaskContext.of("Cache", "AcquireLock", keyStr)
);
```

**Spring 대안 비교:**
- `CompositeCacheManager.setFallbackToNoOpCache(true)`: 캐시 없으면 No-Op 사용
- 우리 구현: No-Op 대신 valueLoader 직접 실행 (더 세밀한 제어)

### 면접 방어 포인트 (금융 수준 6개)
| 질문 | 답변 |
|------|------|
| leaseTime 없으면 무한 락? | Watchdog이 30초마다 자동 연장, 클라이언트 크래시 시 30초 후 자동 만료 (Context7) |
| 락 실패 시 왜 예외 안 던짐? | 금융 가용성 요구사항. Cache Stampede는 성능 문제, 서비스 장애보다 우선순위 낮음 |
| Double-check 오버헤드? | L2 조회 1회 ~1ms. 락 경합 상황에서만 발생 |
| keyStr.hashCode() 충돌? | 동일 캐시 내 충돌 확률 낮음. 충돌 시 불필요한 대기만 발생, 정합성 문제 없음 |
| tryLock 30초가 너무 길지 않나? | 30초는 락 획득 대기 시간. Leader 완료 시 즉시 해제. 30초 동안 획득 못하면 Fallback |
| 여러 서버에서 L1 캐시가 다를 수 있는데? | L1 TTL(5분) ≤ L2 TTL(10분) 규칙으로 최대 5분 내 일관성 보장. 기대값 계산은 실시간 금융 거래가 아니므로 허용 범위 |