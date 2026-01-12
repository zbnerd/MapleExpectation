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

### Lua Script Atomicity (Context7 Best Practice)

Redis는 싱글 스레드로 동작하므로 Lua Script 실행 중 다른 명령이 개입할 수 없습니다.
이 특성을 활용하여 원자적 연산을 보장합니다.

**원자적 연산 보장:**
- `scriptLoad()` + `evalSha()`: SHA 캐싱으로 네트워크 최소화
- `useScriptCache: true`: 서버 측 캐싱 활성화 (Redisson 설정)

**NOSCRIPT 에러 핸들링:**
```java
// Redis 재시작 시 스크립트 캐시가 사라질 수 있음
try {
    return script.evalSha(sha, ...);
} catch (RedisException e) {
    if (isNoScriptError(e)) {
        sha = script.scriptLoad(luaScript);  // 재로드
        return script.evalSha(sha, ...);     // 재시도
    }
    throw e;
}
```

**Redis Cluster CROSSSLOT 방지:**
```
buffer:{likes}:hash        # Hash Tag {likes}로 동일 슬롯 보장
buffer:{likes}:total_count # 모든 관련 키가 같은 슬롯에 배치
buffer:{likes}:sync:{uuid} # 임시 키도 동일 슬롯
```

**멱등성 보장 패턴:**
```lua
-- 중복 실행 시 HDEL이 0을 반환하면 DECRBY 스킵
local deleted = redis.call('HDEL', KEYS[1], ARGV[1])
if deleted > 0 then
    redis.call('DECRBY', KEYS[2], ARGV[2])
end
return deleted  -- 0=이미 삭제됨, 1=정상 삭제
```

**AtomicReference 스레드 안전 패턴:**
```java
// volatile 대신 AtomicReference 사용 (레이스 컨디션 방지)
private final AtomicReference<String> shaRef = new AtomicReference<>();

public String getSha() {
    return shaRef.updateAndGet(current ->
        current != null ? current : reloadScript()
    );
}
```

**Lua Script 복잡도 제한:**
- **O(1) 유지**: 루프, 조건문 최소화
- **청킹**: 대량 배치 시 chunk size 제한 (100개)
- **lua-time-limit**: 5초 내 완료 보장 (기본 설정 권장)

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

## 🔄 16. Proactive Refactoring & Quality (ETC)
- **Refactoring First:** 
  - 새로운 기능 구현 전, 기존 코드가 위 원칙(Facade, SOLID, Exception 전략 등)을 위반한다면 반드시 **리팩토링을 선행**합니다.
  - 기능 추가 전, 기존 코드가 LogicExecutor 패턴을 따르지 않는다면 우선 리팩토링을 수행합니다.
- **Sequential Thinking:** 작업 시작 전 `Context 7`의 기술 스택과 현재 가이드를 단계별로 대조하여 디테일을 놓치지 않습니다.
- **Update Rule:** 새로운 라이브러리나 기술 스택 추가 시, 해당 분야의 Best Practice를 조사하여 `CLAUDE.md`를 즉시 업데이트합니다.
- **Definition of Done:** 코드가 작동하는 것을 넘어, 모든 테스트가 통과하고 위 클린 코드 원칙을 준수했을 때 작업을 완료한 것으로 간주합니다.
- **Context Awareness:** 수정하려는 코드가 TieredCache나 LockStrategy 등 공통 모듈에 영향을 주는지 LogicExecutor의 파급력을 고려하여 작업합니다.

---

## 🗄️ 17. TieredCache & Cache Stampede Prevention

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

---

## 🔄 18. 분산 트랜잭션 전략 (Distributed Transaction Strategy)

현재 모놀리식 아키텍처에서 MSA 확장 시 분산 트랜잭션 처리 전략.

### 현재 아키텍처: 분산 트랜잭션 불필요

**불필요 근거:**
| 항목 | 현재 상태 | 결론 |
|------|----------|------|
| **쓰기 대상** | MySQL 단일 DB | 분산 TX 불필요 |
| **Redis 역할** | 캐시 + 락 (쓰기 손실 허용) | 보상 패턴으로 충분 |
| **외부 API** | Nexon API (읽기 전용) | 트랜잭션 경계 없음 |
| **성능 요구** | 1000+ RPS, t3.small | 분산 TX 도입 시 성능 급락 |

**현재 보상 패턴 구현:**
```java
// 1. LogicExecutor 복구 패턴
executor.executeWithRecovery(
    () -> donationProcessor.execute(transfer),
    ex -> eventPublisher.publishEvent(new DonationFailedEvent(transfer)),
    TaskContext.of("Donation", "Transfer", guestUuid)
);

// 2. Redis 임시 키 롤백 (LikeSyncService) - LogicExecutor 패턴 적용
executor.executeWithFinally(
    () -> {
        syncToDatabase(tempKey);
        redis.delete(tempKey);
        return null;
    },
    () -> {
        // 실패 시에도 정리: 임시 키가 남아있으면 복원
        if (redis.exists(tempKey)) {
            redis.rename(tempKey, originalKey);
        }
    },
    TaskContext.of("LikeSync", "SyncToDb", tempKey)
);

// 3. Graceful Shutdown 파일 백업 (Redis 실패 시 파일로 Fallback)
boolean flushedToRedis = executor.executeOrDefault(
    () -> { flushToRedis(); return true; },
    false,
    TaskContext.of("Shutdown", "FlushToRedis")
);
if (!flushedToRedis) {
    persistToFile();  // Fallback: 파일로 백업
}
```

### MSA 확장 시: Saga + Outbox 패턴 (Context7 Best Practice)

**Kafka + DB 트랜잭션 동기화 (Spring Kafka 권장):**
```java
// DB 트랜잭션이 먼저 커밋 → Kafka 실패 시 재배달
@KafkaListener(id = "orderListener", topics = "orders")
@Transactional("dataSourceTransactionManager")
public void processOrder(OrderEvent event) {
    // 1. DB 저장 (TX 내)
    orderRepository.save(event.toEntity());

    // 2. Kafka 발행 (TX 내, 실패 시 재배달)
    kafkaTemplate.send("order-completed", event.toCompletedEvent());

    // ⚠️ Idempotent 처리 필수 (requestId 중복 체크)
}
```

**Outbox Pattern (권장):**
→ 상세 구현은 "Transactional Outbox Pattern 구현" 섹션 참조

핵심 원칙:
- 비즈니스 TX 내에서 Outbox 테이블 저장 (원자성)
- Kafka 발행은 TX 밖에서 (분리)
- 발행 성공 후 별도 TX로 마킹 (REQUIRES_NEW)

### Saga 패턴 선택 가이드

| 패턴 | 장점 | 단점 | 적합 케이스 |
|------|------|------|-----------|
| **Choreography** | 느슨한 결합, 확장 용이 | 추적 어려움, 순환 위험 | 단순 워크플로우, 이벤트 중심 |
| **Orchestration** | 명확한 흐름, 디버깅 용이 | 중앙 집중, SPOF 위험 | 복잡한 워크플로우, 보상 다수 |

**MapleExpectation 권장: Choreography Saga**
- 현재 `ApplicationEventPublisher` 패턴과 호환
- Kafka로 전환 시 최소 변경
- 기대값 계산은 보상 로직 불필요 (읽기 중심)

### Kafka 필수 설정 (Context7 - Spring Kafka Best Practice)

**Consumer 설정 (Exactly-Once 필수):**
```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false          # 수동 오프셋 관리 필수
      properties:
        isolation.level: read_committed  # 커밋된 메시지만 읽기
    producer:
      transaction-id-prefix: tx-         # Producer 멱등성 활성화
      acks: all                          # 모든 복제본 확인
```

**왜 이 설정이 필수인가?**
| 설정 | 미설정 시 문제 | 효과 |
|------|--------------|------|
| `enable-auto-commit=false` | 처리 전 오프셋 커밋 → 메시지 유실 | 처리 완료 후 커밋 |
| `isolation.level=read_committed` | 롤백된 메시지 읽음 → 데이터 불일치 | 커밋된 것만 읽음 |
| `transaction-id-prefix` | Producer 중복 발행 가능 | 멱등성 보장 |

### Dead Letter Queue (DLQ) 패턴 (Context7 - Spring Kafka Best Practice)

실패 이벤트 무한 루프 방지:

```java
// ✅ @RetryableTopic으로 자동 DLQ 설정 (Spring Kafka 2.7+)
@KafkaListener(topics = "character.update.requested")
@RetryableTopic(
    attempts = "3",                                    // 최대 3회 시도
    backoff = @Backoff(delay = 1000, multiplier = 2),  // 1초, 2초, 4초
    dltTopicSuffix = ".DLT",                           // DLQ 토픽명
    autoCreateTopics = "true"
)
public void processUpdateRequest(UpdateRequestEvent event) {
    // 3회 실패 시 자동으로 character.update.requested.DLT로 이동
    updateProcessor.processUpdate(event.getUserIgn(), event.getId());
}

// DLQ Consumer: 수동 처리 또는 알림
@KafkaListener(topics = "character.update.requested.DLT")
public void handleDlt(UpdateRequestEvent event) {
    log.error("DLT received: {}", event.getId());
    alertService.notifyOperator("DLT event: " + event.getId());
}
```

**DLQ가 없으면?**
- 처리 불가 이벤트 무한 재시도 → Consumer 멈춤
- 후속 이벤트 처리 지연 → 전체 시스템 영향

### Idempotent 처리 필수화 (Context7 Best Practice)

Kafka 재배달 시 중복 처리 방지:

**Kafka 파티셔닝 보호 + DB 최종 방어선:**
```java
// Kafka Consumer Group은 파티션별 단일 Consumer 보장
// → 같은 eventId(같은 파티션)는 동시 처리 불가
// → 하지만 Consumer 재시작 시 재배달로 중복 가능
// → DB Unique Constraint가 최종 방어선!

@KafkaListener(topics = "donations")
@Transactional
public void processDonation(DonationEvent event) {
    // 1차 방어: 조회 (빠른 필터링)
    if (historyRepository.existsByRequestId(event.getRequestId())) {
        log.debug("Duplicate event ignored: {}", event.getRequestId());
        return;
    }
    // 2차 방어: DB Unique Constraint (동시성 보호)
    // → INSERT 실패 시 DataIntegrityViolationException
    // → @Transactional 롤백 → Kafka 재배달 없음 (이미 처리됨)
    historyRepository.save(new DonationHistory(event.getRequestId(), ...));
}

// ✅ Good: DB Unique Constraint (최종 방어선)
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "request_id"))
public class DonationHistory { ... }
```

**중복 처리 방어 계층:**
| 계층 | 메커니즘 | 보호 범위 |
|------|---------|----------|
| 1차 | Kafka 파티셔닝 | 같은 파티션 동시 처리 방지 |
| 2차 | existsBy 조회 | 빠른 필터링 (99% 중복 차단) |
| 3차 | DB Unique Constraint | 레이스 컨디션 최종 방어 |

### Query/Worker 분리 아키텍처 (Issue #126)

**목표 아키텍처 (Pragmatic CQRS):**
```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  ┌─────────────────┐         ┌─────────────────────────────┐   │
│  │  Query Server   │         │      Worker Server          │   │
│  │  (조회 전용)     │  Kafka  │      (처리 전용)            │   │
│  ├─────────────────┤ ──────▶ ├─────────────────────────────┤   │
│  │ • 캐시 조회     │         │ • Nexon API 호출            │   │
│  │ • 빠른 응답     │         │ • 350KB JSON 파싱           │   │
│  │ • Outbox 발행   │         │ • 기대값 계산               │   │
│  │ • "업데이트 중" │         │ • 17KB 압축 → DB 저장       │   │
│  └────────┬────────┘         └────────────┬────────────────┘   │
│           │                               │                     │
│           └───────────────┬───────────────┘                     │
│                           │                                     │
│                   ┌───────▼───────┐                             │
│                   │  Shared MySQL │  ← 공유 DB (핵심!)          │
│                   │  + Outbox     │                             │
│                   └───────────────┘                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**분산 TX 불필요 이유:**
| 서버 | 트랜잭션 범위 | 분산 TX |
|------|-------------|---------|
| Query Server | Outbox + 상태 = 단일 MySQL TX | 불필요 ✅ |
| Worker Server | 계산결과 + 상태 = 단일 MySQL TX | 불필요 ✅ |
| 서버 간 통신 | Kafka (비동기) | 불필요 ✅ |

### Transactional Outbox Pattern 구현 (Context7 - Debezium Best Practice)

**방법 1: Polling 기반 (단순, 권장 시작점)**

⚠️ **TX 경계 주의**: Kafka 발행과 DB 마킹은 반드시 분리해야 함!

```java
// 1. 비즈니스 로직 + Outbox 저장 (단일 TX) ✅
@Transactional
public void requestUpdate(String userIgn) {
    characterRepository.updateStatus(userIgn, Status.UPDATING);
    outboxRepository.save(new OutboxEvent(
        "character.update.requested",
        userIgn,
        Map.of("userIgn", userIgn, "requestedAt", Instant.now())
    ));
}

// 2. Polling으로 Kafka 발행 (TX 없음!) ✅
@Scheduled(fixedDelay = 500)  // 100ms는 너무 공격적, 500ms 권장
public void publishOutboxEvents() {
    // TX 밖에서 조회
    List<OutboxEvent> events = outboxRepository.findUnpublishedWithLimit(50);

    for (OutboxEvent event : events) {
        executor.executeOrDefault(
            () -> {
                // Kafka 발행 (TX 밖, 동기 대기)
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(),
                    event.getPayload()).get(5, TimeUnit.SECONDS);
                // 성공 시 별도 TX로 마킹
                markAsPublished(event.getId());
                return true;
            },
            false,  // 실패 시 다음 폴링에서 재시도
            TaskContext.of("Outbox", "Publish", event.getId())
        );
    }
}

// 별도 TX로 마킹 (발행 성공 후에만 호출)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markAsPublished(Long eventId) {
    outboxRepository.updatePublishedAt(eventId, Instant.now());
}
```

**왜 TX를 분리해야 하는가?**
| 시나리오 | TX 내 발행 | TX 분리 발행 |
|---------|-----------|-------------|
| Kafka 성공 → DB 실패 | ❌ 이벤트 중복 | ✅ 재시도 시 재발행 (Idempotent로 처리) |
| DB 성공 → Kafka 실패 | ❌ 이벤트 누락 | ✅ 다음 폴링에서 재시도 |

**방법 2: CDC 기반 (Debezium, 고급)**
```properties
# Debezium Connector 설정
transforms=outbox
transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
transforms.outbox.table.expand.json.payload=true

# Exactly-Once Delivery
exactly.once.support=required
transaction.boundary=poll
```

**Outbox 테이블 스키마:**
```sql
CREATE TABLE outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),  -- 마이크로초 정밀도
    published_at TIMESTAMP(6) NULL,
    retry_count TINYINT DEFAULT 0,

    -- 순서 보장: 같은 aggregate_id는 생성 순서대로 처리
    INDEX idx_unpublished_ordered (published_at, aggregate_id, created_at),
    -- 파티션 키로 사용할 aggregate_id 인덱스
    INDEX idx_aggregate (aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**메시지 순서 보장:**
```java
// Kafka 파티션 키 = aggregate_id
// 같은 캐릭터의 이벤트는 같은 파티션 → 순서 보장
kafkaTemplate.send(topic, event.getAggregateId(), payload);
```

### Worker Server Idempotent Consumer

⚠️ **실패 처리 필수**: 외부 API 실패 시 상태 고착 방지!

```java
@KafkaListener(topics = "character.update.requested")
public void processUpdateRequest(UpdateRequestEvent event) {
    String userIgn = event.getUserIgn();
    String eventId = event.getId();

    // 1. 중복 체크 (Idempotent) - TX 밖에서 먼저 체크
    if (processedEventRepository.existsByEventId(eventId)) {
        log.debug("Duplicate event ignored: {}", eventId);
        return;
    }

    // 2. 처리 실행 (실패 시 FAILED 상태로 전환)
    executor.executeWithRecovery(
        () -> updateProcessor.processUpdate(userIgn, eventId),
        ex -> updateProcessor.handleFailure(userIgn, eventId, ex),
        TaskContext.of("Worker", "ProcessUpdate", userIgn)
    );
}

// ⚠️ @Transactional은 반드시 public 메서드에만 적용 (Spring AOP 프록시 한계)
// 내부 호출 시 프록시를 우회하므로, 별도 @Component로 분리 권장
@Component
@RequiredArgsConstructor
class UpdateProcessor {
    private final ProcessedEventRepository processedEventRepository;
    private final NexonApiClient nexonApiClient;
    private final ExpectationCalculator calculator;
    private final DataCompressor compressor;
    private final CharacterRepository characterRepository;
    private final RetryCountRepository retryCountRepository;
    private final AlertService alertService;  // DLQ 알림용

    @Transactional
    public void processUpdate(String userIgn, String eventId) {
        // 처리 기록 먼저 저장 (재시도 시 중복 방지)
        processedEventRepository.save(new ProcessedEvent(eventId));

        // ⚠️ TX 내 외부 API 호출 주의:
        // - DB Connection 점유 시간 증가 (API 응답 시간만큼)
        // - 고부하 환경에서는 TX 분리 권장 (API 호출 → 별도 TX로 저장)
        // - 현재 구조: API 실패 시 TX 롤백 → 재시도 가능 (의도된 동작)
        EquipmentData data = nexonApiClient.fetchEquipment(userIgn);
        ExpectationResult result = calculator.calculate(data);
        byte[] compressed = compressor.compress(result);

        // DB 저장 + 상태 업데이트 (단일 TX 내)
        characterRepository.updateEquipmentData(userIgn, compressed);
        characterRepository.updateStatus(userIgn, Status.SUCCESS);
    }

    private static final int MAX_RETRY = 3;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailure(String userIgn, String eventId, Throwable ex) {
        log.error("Processing failed for {}: {}", userIgn, ex.getMessage());

        int retryCount = retryCountRepository.incrementAndGet(eventId);

        if (retryCount >= MAX_RETRY) {
            // 최대 재시도 초과 → 수동 처리 필요
            log.error("Max retry exceeded for {}, moving to DLQ", eventId);
            characterRepository.updateStatusWithError(
                userIgn, Status.FAILED_PERMANENT,
                "Max retry exceeded: " + ex.getMessage()
            );
            // DLQ 또는 알림 처리
            alertService.notifyOperator("Permanent failure: " + eventId);
        } else {
            // 재시도 가능 상태
            characterRepository.updateStatusWithError(
                userIgn, Status.FAILED,
                String.format("Retry %d/%d: %s", retryCount, MAX_RETRY, ex.getMessage())
            );
        }
    }
}  // UpdateProcessor 클래스 종료
```

**실패 시나리오별 처리:**
| 실패 지점 | 상태 | 재시도 가능 |
|----------|------|-----------|
| Nexon API 타임아웃 | FAILED | ✅ 별도 재시도 큐 |
| 계산 중 예외 | FAILED | ❌ 수동 확인 필요 |
| DB 저장 실패 | TX 롤백 → UPDATING | ✅ 다음 이벤트로 재처리 |

### 상태 관리 (Eventual Consistency UI/UX)

```java
public enum UpdateStatus {
    NONE,             // 데이터 없음 → "조회하기" 버튼 표시
    UPDATING,         // 업데이트 중 → "업데이트 중..." + 스피너
    SUCCESS,          // 완료 → 결과 표시
    FAILED,           // 실패 → "재시도" 버튼 표시 (자동 재시도 대기)
    FAILED_PERMANENT  // 영구 실패 → "문의하기" 버튼 표시 (수동 처리 필요)
}

// Query Server 응답
@GetMapping("/character/{userIgn}")
public ResponseEntity<CharacterResponse> getCharacter(@PathVariable String userIgn) {
    return characterRepository.findByUserIgn(userIgn)
        .map(c -> switch(c.getStatus()) {
            case UPDATING -> ResponseEntity.accepted()
                .body(CharacterResponse.updating(c.getUpdatedAt()));
            case SUCCESS -> ResponseEntity.ok(CharacterResponse.success(c));
            case FAILED -> ResponseEntity.ok(CharacterResponse.retryable(c.getErrorMessage()));
            case FAILED_PERMANENT -> ResponseEntity.ok(
                CharacterResponse.permanentFailure(c.getErrorMessage()));
            default -> ResponseEntity.notFound().build();
        })
        .orElseGet(() -> {
            requestUpdateAsync(userIgn);  // Outbox에 이벤트 발행
            return ResponseEntity.accepted()
                .body(CharacterResponse.updating(Instant.now()));
        });
}
```

---

## 🚀 19. MSA 전환 준비 가이드 (CQRS + Event Sourcing)

Kafka 도입 및 MSA 전환 시 준수해야 할 아키텍처 가이드라인.

### CQRS 패턴 적용 (Context7 - OpenCQRS Best Practice)

**Command/Query 분리:**
```java
// Command: 쓰기 모델 (현재 Service 메서드)
public record PurchaseBookCommand(String isbn, String author) implements Command {
    @Override
    public String getSubject() { return "/book/" + isbn; }

    @Override
    public SubjectCondition getSubjectCondition() {
        return SubjectCondition.PRISTINE;  // 신규 생성만 허용
    }
}

// Command Handler: 이벤트 발행
@CommandHandling(sourcingMode = SourcingMode.LOCAL)
public String purchase(PurchaseBookCommand cmd, CommandEventPublisher<Book> publisher) {
    publisher.publish(new BookPurchasedEvent(cmd.isbn(), cmd.author()));
    return cmd.isbn();
}

// Query: 읽기 모델 (현재 Cache 조회)
@QueryHandler
public BookDto getBook(GetBookQuery query) {
    return bookReadRepository.findById(query.isbn());
}
```

**MapleExpectation 현재 구조 → CQRS 매핑:**
| 현재 | CQRS 개념 | 전환 방향 |
|------|----------|----------|
| `GameCharacterService.createNewCharacter()` | Command Handler | CommandRouter 사용 |
| `EquipmentService.calculateExpectation()` | Query Handler | ReadModel 조회 |
| `TotalExpectationCacheService` | Read Model | Kafka Consumer로 갱신 |
| `ApplicationEventPublisher` | Event Publisher | Kafka Producer로 전환 |

### 이벤트 발행 추상화 (MSA 준비)

현재 Spring 이벤트 → Kafka 전환을 위한 인터페이스 분리:

```java
// ✅ Good: 추상화된 이벤트 발행자
public interface DomainEventPublisher {
    void publish(DomainEvent event);
}

// 현재 구현: Spring ApplicationEvent
@Component
public class SpringEventPublisher implements DomainEventPublisher {
    private final ApplicationEventPublisher publisher;

    @Override
    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}

// MSA 전환 시: Kafka Producer
@Component
@Profile("msa")
public class KafkaEventPublisher implements DomainEventPublisher {
    private final KafkaTemplate<String, DomainEvent> template;

    @Override
    public void publish(DomainEvent event) {
        template.send(event.getTopic(), event.getKey(), event);
    }
}
```

### Aggregate 경계 정의 (DDD)

MSA 분리 시 서비스 경계:

```
┌─────────────────────────────────────────────────────────┐
│ MapleExpectation (현재 모놀리스)                         │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Character    │  │ Equipment    │  │ Donation     │  │
│  │ Aggregate    │  │ Aggregate    │  │ Aggregate    │  │
│  ├──────────────┤  ├──────────────┤  ├──────────────┤  │
│  │ - GameChar   │  │ - Item       │  │ - Transfer   │  │
│  │ - Like       │  │ - Expectation│  │ - History    │  │
│  │ - Profile    │  │ - Cube       │  │ - Developer  │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│         │                 │                 │          │
│         ▼                 ▼                 ▼          │
│  ┌─────────────────────────────────────────────────┐   │
│  │              Kafka Topics (MSA 전환 시)          │   │
│  │  character.events | equipment.events | donation │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Exactly-Once Semantics (Spring Kafka Best Practice)

⚠️ **ChainedTransactionManager 사용 금지** (Spring 5.3+ Deprecated)

**왜 Chained TX가 안 되는가?**
```
1. DB 커밋 성공
2. Kafka 커밋 실패  ← 여기서 실패하면?
   → DB는 이미 커밋됨, 롤백 불가!
   → 데이터 불일치 발생
```

**올바른 방법: Outbox Pattern**
```java
// ❌ Bad: ChainedTransactionManager (Deprecated, 불일치 가능)
@Bean
public ChainedTransactionManager chainedTxManager(...) { ... }

// ✅ Good: Outbox Pattern (섹션 18 참조)
// 1. DB TX 내에서 Outbox 테이블에 이벤트 저장
// 2. 별도 프로세스가 Outbox → Kafka 발행
// 3. 발행 성공 시 Outbox 마킹 (별도 TX)
```

**Consumer 측 Exactly-Once (읽기 측):**
```properties
# Kafka Consumer 설정
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.properties.isolation.level=read_committed

# Idempotent Consumer로 중복 처리 방지 (섹션 18 참조)
```

### 전환 체크리스트

MSA 전환 전 필수 준비 사항:

- [ ] **이벤트 발행 추상화**: `DomainEventPublisher` 인터페이스 도입
- [ ] **Idempotent 처리**: 모든 핸들러에 `requestId` 중복 체크
- [ ] **Aggregate 경계**: DDD 기반 서비스 분리 설계
- [ ] **Outbox 테이블**: 이벤트 발행 보장 메커니즘
- [ ] **Read Model 분리**: Query 전용 데이터 저장소 (현재 Redis 캐시 활용)
- [ ] **Saga 패턴 선택**: Choreography vs Orchestration 결정
- [ ] **Kafka 토픽 설계**: Aggregate 당 1개 토픽 원칙

### 성능 고려사항

⚠️ **벤치마크 없이 수치 예측 금지** - 실측 후 판단

| 패턴 | 현재 성능 | MSA 전환 시 |
|------|----------|------------|
| **동기 호출** | 측정 필요 | 네트워크 레이턴시 추가 |
| **Kafka 발행** | N/A | 파티션/복제 설정에 따라 상이 |
| **Saga 완료** | N/A | 참여 서비스 수에 비례 |
| **전체 RPS** | Locust로 측정 | 분리 후 재측정 필수 |

**권장**: MSA 전환은 트래픽 증가 또는 팀 분리 시에만 고려.
현재 모놀리스 + 보상 패턴으로 충분한 성능과 안정성 확보.

**전환 전 필수 측정:**
1. 현재 API별 P50/P95/P99 레이턴시
2. 현재 최대 RPS (부하 테스트)
3. 병목 지점 프로파일링 (CPU/메모리/IO)

### 권장 서비스 분리 전략 (3개 서버) ⭐

**분산 TX 없이 MSA 전환이 가능한 Aggregate 경계:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    권장 서비스 분리 (3개)                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  서버 1: Character-Equipment Service                            │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ GameCharacter + CharacterEquipment + Like               │   │
│  │ DB: character_db                                        │   │
│  │ 트랜잭션: 내부 TX로 충분 ✅                               │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  서버 2: Member-Donation Service                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Member + Point + DonationHistory                        │   │
│  │ DB: donation_db                                         │   │
│  │ 트랜잭션: 내부 TX로 충분 ✅ (강결합 유지)                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  서버 3: Calculation Service (Stateless)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ 기대값 계산 엔진 + Redis 캐시                            │   │
│  │ DB: 없음 (읽기 전용, 캐시만)                             │   │
│  │ 트랜잭션: 불필요 ✅                                      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ⚠️ 주의: Member와 Donation을 분리하면 분산 TX(Saga) 필요!     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

| 서비스 | Aggregate Root | 포함 엔티티 | DB | 분산 TX |
|--------|---------------|------------|----|---------|
| Character-Equipment | GameCharacter | CharacterEquipment (1:1), Like | character_db | 불필요 |
| Member-Donation | Member | Point, DonationHistory | donation_db | 불필요 |
| Calculation | (Stateless) | 없음 | 없음 | 불필요 |

**⚠️ 주의**: `CharacterEquipment`는 `GameCharacter`의 일부 (CASCADE ALL)
→ 별도 서비스로 분리 시 분산 TX 필요!

### 지금 준비할 것 vs 나중에 할 것

**🔴 지금 준비해야 할 것 (모놀리스에서도 유용):**

| 항목 | 이유 | 현재 상태 |
|------|------|----------|
| **이벤트 발행 추상화** | Kafka 전환 시 최소 변경 | ⚠️ 미구현 |
| **Idempotent 처리** | 중복 요청 방지 | ✅ DonationService에 구현됨 |
| **Aggregate 경계 문서화** | 서비스 분리 기준 명확화 | ⚠️ 본 문서로 정의 |
| **인터페이스 분리** | 서비스 간 계약 명확화 | ✅ Strategy 패턴 적용됨 |

```java
// ✅ 지금 구현 권장: 이벤트 발행 추상화
public interface DomainEventPublisher {
    /** 동기 발행 (트랜잭션 내 사용) */
    void publish(DomainEvent event);

    /** 비동기 발행 (트랜잭션 밖 사용) */
    CompletableFuture<Void> publishAsync(DomainEvent event);

    /** 배치 발행 */
    void publishAll(List<? extends DomainEvent> events);
}

// 현재: Spring Event (동기 전용)
@Component
public class SpringDomainEventPublisher implements DomainEventPublisher {
    private final ApplicationEventPublisher publisher;
    private final LogicExecutor executor;

    @Override
    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }

    @Override
    public CompletableFuture<Void> publishAsync(DomainEvent event) {
        return CompletableFuture.runAsync(() -> publish(event));
    }

    @Override
    public void publishAll(List<? extends DomainEvent> events) {
        events.forEach(this::publish);
    }
}

// MSA 전환 시: Outbox 기반 발행자
@Component
@Profile("msa")
public class OutboxDomainEventPublisher implements DomainEventPublisher {
    private final OutboxRepository outboxRepository;

    @Override
    @Transactional  // 비즈니스 TX에 참여
    public void publish(DomainEvent event) {
        outboxRepository.save(OutboxEvent.from(event));
    }
    // ... 나머지 구현
}
```

**🟢 MSA 전환 시 도입해도 될 것:**

| 항목 | 이유 | 도입 시점 |
|------|------|----------|
| **Seata/Saga 프레임워크** | 복잡도 증가, 현재 불필요 | 별도 DB 분리 시 |
| **Kafka 인프라** | 운영 부담 | Query/Worker 분리 시 (Issue #126) |
| **분산 추적 (Zipkin/Jaeger)** | 모놀리스에선 로그로 충분 | 서비스 분리 후 |
| **Service Mesh (Istio)** | K8s 환경 필수 | K8s 도입 시 |

**🟡 Kafka와 함께 도입할 것:**

| 항목 | 이유 |
|------|------|
| **Outbox 테이블** | 이벤트 발행 보장 (At-least-once) |
| **Idempotent Consumer** | 중복 이벤트 처리 방지 |
| **Dead Letter Queue** | 처리 불가 이벤트 격리 |

### MSA 전환 의사결정 기준

**전환해야 할 때:**
- [ ] 단일 서버로 트래픽 감당 불가 (수평 확장 필요)
- [ ] 팀이 분리되어 독립 배포 필요
- [ ] 특정 기능만 스케일 아웃 필요 (예: 계산 서비스만)
- [ ] 기술 스택 다양화 필요 (Python ML 서비스 등)

**전환하지 말아야 할 때:**
- [ ] 현재 성능으로 충분 (240+ RPS)
- [ ] 팀 규모가 작음 (1-3명)
- [ ] 운영 복잡도 증가를 감당할 인력 부족
- [ ] 단순히 "MSA가 트렌드라서"

### 분산 TX 솔루션 비교 (Context7 - Seata Best Practice)

MSA 전환 후 분산 TX가 필요해지면:

| 솔루션 | 모드 | 적합 케이스 | 복잡도 |
|--------|------|-----------|--------|
| **Seata AT** | 자동 보상 (Undo Log) | 기존 코드 변경 최소화 | 낮음 |
| **Seata TCC** | Try-Confirm-Cancel | 높은 성능, 명시적 보상 | 중간 |
| **Seata Saga** | 장기 트랜잭션 | 비동기, 복잡한 워크플로우 | 높음 |
| **Spring Kafka** | Outbox + CDC | 이벤트 기반 | 중간 |

**MapleExpectation 권장: Spring Kafka + Outbox**
- Seata 없이 Kafka만으로 충분
- 현재 보상 패턴과 자연스럽게 연결
- 추가 인프라(Seata Server) 불필요
