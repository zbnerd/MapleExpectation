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

## 🔧 8-1. Redis Lua Script & Cluster Hash Tag (Context7 Best Practice)

금융수준 데이터 안전을 위한 Redis Lua Script 원자적 연산 및 Cluster 호환성 규칙입니다.

### Lua Script 원자적 연산 (Redisson RScript)

Redis 단일 스레드에서 복수 명령을 원자적으로 실행해야 할 때 Lua Script를 사용합니다.

**Redisson RScript 사용 패턴:**
```java
// ✅ Good (원자적 RENAME + EXPIRE + HGETALL)
private static final String LUA_ATOMIC_MOVE = """
        local exists = redis.call('EXISTS', KEYS[1])
        if exists == 0 then return {} end
        redis.call('RENAME', KEYS[1], KEYS[2])
        redis.call('EXPIRE', KEYS[2], ARGV[1])
        return redis.call('HGETALL', KEYS[2])
        """;

RScript script = redissonClient.getScript(StringCodec.INSTANCE);
List<Object> result = script.eval(
        RScript.Mode.READ_WRITE,          // 데이터 변경 시
        LUA_ATOMIC_MOVE,
        RScript.ReturnType.MULTI,         // 복수 결과 반환 시
        Arrays.asList(sourceKey, tempKey), // KEYS[1], KEYS[2]
        String.valueOf(ttlSeconds)         // ARGV[1]
);
```

**RScript.Mode 선택:**
| Mode | 용도 |
|------|------|
| `READ_ONLY` | 조회만 (GET, HGETALL 등) |
| `READ_WRITE` | 데이터 변경 (SET, DEL, RENAME 등) |

**RScript.ReturnType 선택:**
| Type | 반환값 |
|------|--------|
| `INTEGER` | 단일 정수 |
| `STATUS` | "OK" 등 상태 |
| `VALUE` | 단일 값 |
| `MULTI` | 리스트 (HGETALL 등) |

### Redis Cluster Hash Tag 규칙 (CRITICAL)

Redis Cluster에서 다중 키 연산(RENAME, Lua Script 등)은 **모든 키가 동일 슬롯**에 있어야 합니다.
Hash Tag `{...}` 패턴을 사용하면 중괄호 내부만 해싱되어 같은 슬롯을 보장합니다.

```java
// ❌ Bad (다른 해시값 → Cluster에서 실패)
String sourceKey = "buffer:likes";
String tempKey = "buffer:likes:sync:uuid";
// CRC16("buffer:likes") ≠ CRC16("buffer:likes:sync:uuid")

// ✅ Good (Hash Tag → 같은 슬롯 보장)
String sourceKey = "{buffer:likes}";
String tempKey = "{buffer:likes}:sync:" + UUID.randomUUID();
// CRC16("buffer:likes") == CRC16("buffer:likes") → 동일 슬롯
```

**Hash Tag 적용 대상:**
- **RENAME 키 쌍**: `{domain}:source` ↔ `{domain}:target`
- **Lua Script 다중 키**: 모든 KEYS는 같은 Hash Tag
- **MGET/MSET 키들**: 같은 Hash Tag 사용

### ExceptionTranslator.forRedisScript() 사용

Lua Script 예외를 도메인 예외로 변환할 때 사용합니다.

```java
// ✅ Good (예외 변환 적용)
return executor.executeWithTranslation(
        () -> executeLuaScript(sourceKey, tempKey),
        ExceptionTranslator.forRedisScript(),  // Redis 예외 → AtomicFetchException
        TaskContext.of("AtomicFetch", "fetchAndMove", sourceKey)
);
```

### Orphan Key Recovery (JVM 크래시 대응)

JVM 크래시 시 임시 키에 데이터가 남아있을 수 있습니다.
서버 시작 시 자동 복구를 위해 `@PostConstruct`와 패턴 검색을 사용합니다.

```java
@PostConstruct
public void recoverOrphanKeys() {
    RKeys keys = redissonClient.getKeys();
    Iterable<String> orphans = keys.getKeysByPattern("{buffer:likes}:sync:*");

    for (String orphanKey : orphans) {
        // 임시 키 → 원본 키로 복원
        atomicFetchStrategy.restore(orphanKey, SOURCE_KEY);
    }
}
```

### 임시 키 TTL 안전장치 (메모리 누수 방지)

복구 로직이 실패하더라도 임시 키가 영구적으로 남지 않도록 TTL을 설정합니다.

```java
// ✅ Good (1시간 TTL → 영구 메모리 누수 방지)
redis.call('EXPIRE', KEYS[2], 3600)

// application.yml 설정화
like:
  sync:
    temp-key-ttl-seconds: 3600  # 1시간
```

### 보상 트랜잭션 패턴 (Command Pattern)

DB 저장 실패 시 원자적 Fetch 결과를 원본 키로 복원하는 보상 명령입니다.

```java
// CompensationCommand 인터페이스
public interface CompensationCommand {
    void save(FetchResult result);     // 상태 저장
    void compensate();                  // 실패 시 복원
    void commit();                      // 성공 시 정리
    boolean isPending();                // 보상 필요 여부
}

// 사용 패턴 (executeWithFinally)
CompensationCommand cmd = new RedisCompensationCommand(sourceKey, strategy, executor);
executor.executeWithFinally(
        () -> {
            FetchResult result = strategy.fetchAndMove(sourceKey, tempKey);
            cmd.save(result);
            processDatabase(result);  // DB 저장
            cmd.commit();             // 성공 → 임시 키 삭제
            return null;
        },
        () -> {
            if (cmd.isPending()) {
                cmd.compensate();     // 실패 → 원본 키 복원
            }
        },
        context
);
```

### DLQ (Dead Letter Queue) 패턴 (P0 - 데이터 영구 손실 방지)

보상 트랜잭션(compensate) 실행마저 실패하면 데이터가 영구 손실됩니다.
Spring Event + Listener로 DLQ 패턴을 구현하여 **최후의 안전망**을 제공합니다.

**구현 요소:**
| 컴포넌트 | 역할 |
|----------|------|
| `LikeSyncFailedEvent` | 실패 데이터 Record (불변) |
| `RedisCompensationCommand` | 복구 실패 시 이벤트 발행 |
| `LikeSyncEventListener` | 파일 백업 + Discord 알림 + 메트릭 |

```java
// 보상 실패 시 DLQ 이벤트 발행
private void compensate() {
    executor.executeOrCatch(
            () -> strategy.restore(tempKey, sourceKey),
            e -> {
                // P0 FIX: 복구 실패 시 DLQ 이벤트 발행
                LikeSyncFailedEvent event = LikeSyncFailedEvent.fromFetchResult(result, sourceKey, e);
                eventPublisher.publishEvent(event);
                return null;
            },
            context
    );
}

// Listener: 파일 백업 + 알림
@Async
@EventListener
public void handleSyncFailure(LikeSyncFailedEvent event) {
    // 1. 파일 백업 (데이터 보존 최우선)
    persistenceService.appendLikeEntry(event.userIgn(), event.lostCount());
    // 2. 메트릭 기록
    meterRegistry.counter("like.sync.dlq.triggered").increment();
    // 3. Discord 알림 (운영팀 인지)
    discordAlertService.sendCriticalAlert("DLQ 발생", event.errorMessage());
}
```

**DLQ 처리 우선순위:**
1. **파일 백업** (데이터 보존 최우선)
2. **메트릭 기록** (모니터링)
3. **알림 발송** (운영팀 인지)

### 루프 내 유틸리티 메서드 최적화 (P1 - Performance)

LogicExecutor의 `TaskContext.of()` 호출은 매번 새 객체를 생성합니다.
**루프 내 반복 호출되는 유틸리티 메서드**에서는 성능 오버헤드가 발생합니다.

```java
// ❌ Bad (루프 내 TaskContext 오버헤드)
private long parseLongSafe(Object value) {
    return executor.executeOrDefault(
            () -> Long.parseLong(String.valueOf(value)),
            0L,
            TaskContext.of("Parse", "long", value)  // 매번 새 객체
    );
}

// ✅ Good (Pattern Matching + 직접 예외 처리)
private long parseLongSafe(Object value) {
    if (value == null) return 0L;
    if (value instanceof Number n) return n.longValue();
    if (value instanceof String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.warn("Malformed data ignored: value={}", s);
            recordParseFailure();  // 메트릭으로 모니터링
            return 0L;
        }
    }
    return 0L;
}
```

**적용 기준:**
- **루프 내 호출**: 직접 처리 (오버헤드 제거)
- **단일 호출**: LogicExecutor 사용 (일관성 유지)
- **예외 메트릭**: 실패 시 카운터 기록 (데이터 품질 모니터링)

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

## 🔐 18. Spring Security 6.x Filter Best Practice (Context7)

Spring Security 6.x에서 커스텀 Filter 사용 시 반드시 준수해야 할 규칙입니다.

### CGLIB 프록시 문제 (CRITICAL)
`OncePerRequestFilter`를 상속한 필터에 `@Component`를 붙이면 CGLIB 프록시 생성 시 부모 클래스의 `logger` 필드가 초기화되지 않아 NPE 발생합니다.

```java
// ❌ Bad (@Component 사용 시 CGLIB 프록시 문제 발생)
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // java.lang.NullPointerException: Cannot invoke "Log.isDebugEnabled()"
    // because "this.logger" is null
}

// ✅ Good (@Bean으로 수동 등록)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // @Component 제거 → SecurityConfig에서 @Bean 등록
}
```

### Filter Bean 등록 패턴 (Context7 공식)
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 1. Filter Bean 직접 등록 (생성자 주입)
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider provider,
            SessionService service,
            FingerprintGenerator generator) {
        return new JwtAuthenticationFilter(provider, service, generator);
    }

    // 2. 서블릿 컨테이너 중복 등록 방지
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);  // 서블릿 컨테이너 등록 비활성화
        return registration;
    }

    // 3. SecurityFilterChain에 필터 추가
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter filter) throws Exception {
        http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### FilterRegistrationBean 필요성
| 시나리오 | 결과 |
|---------|------|
| `@Bean`만 등록 | Spring Boot가 서블릿 컨테이너에도 자동 등록 → 필터 2회 실행 |
| `FilterRegistrationBean.setEnabled(false)` | Spring Security만 필터 관리 → 1회 실행 |

### SecurityContext 설정 (Context7 Best Practice)
```java
// ❌ Bad (기존 컨텍스트 재사용 → 동시성 문제)
SecurityContextHolder.getContext().setAuthentication(auth);

// ✅ Good (새 컨텍스트 생성 → Thread-Safe)
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(auth);
SecurityContextHolder.setContext(context);
```

### 보안 헤더 설정 (Spring Security 6.x Lambda DSL)
```java
http.headers(headers -> headers
    .frameOptions(frame -> frame.deny())           // Clickjacking 방지
    .contentTypeOptions(Customizer.withDefaults()) // MIME 스니핑 방지
    .httpStrictTransportSecurity(hsts -> hsts      // HSTS
        .includeSubDomains(true)
        .maxAgeInSeconds(31536000)
    )
);
```

---

## 🔒 19. Security Best Practices (Logging & API Client)

민감한 정보 보호와 외부 API 에러 처리를 위한 필수 규칙입니다.

### 민감 데이터 로그 마스킹 (CRITICAL)
AOP(TraceAspect 등)에서 DTO를 자동 로깅할 때 민감 정보(API Key, 비밀번호 등)가 노출될 수 있습니다.
**Java Record의 기본 toString()은 모든 필드를 노출**하므로 반드시 오버라이드해야 합니다.

```java
// ❌ Bad (Record 기본 toString() → API Key 평문 노출)
public record LoginRequest(String apiKey, String userIgn) {}
// 로그: LoginRequest[apiKey=live_abcd1234efgh5678, userIgn=닉네임]

// ✅ Good (toString() 오버라이드 → 마스킹)
public record LoginRequest(String apiKey, String userIgn) {
    @Override
    public String toString() {
        return "LoginRequest[" +
                "apiKey=" + maskApiKey(apiKey) +
                ", userIgn=" + userIgn + "]";
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
// 로그: LoginRequest[apiKey=live****5678, userIgn=닉네임]
```

**마스킹 대상 필드:**
- API Key, Secret Key
- 비밀번호, 토큰
- 개인정보 (주민번호, 전화번호 등)

### WebClient 에러 처리: onErrorResume vs onStatus

| 패턴 | 장점 | 단점 |
|------|------|------|
| `onStatus()` | 상태 코드별 분기 간편 | **응답 본문 접근 불가** |
| `onErrorResume()` | 상태 코드 + 응답 본문 모두 접근 | 약간 더 복잡 |

**디버깅을 위해 외부 API의 실제 에러 메시지를 로깅해야 하므로 `onErrorResume()` 사용 권장.**

```java
// ❌ Bad (onStatus: 에러 본문 로깅 불가)
.retrieve()
.onStatus(
    HttpStatusCode::is4xxClientError,
    response -> {
        log.warn("Error: {}", response.statusCode());  // 상태 코드만
        return Mono.empty();
    }
)
.bodyToMono(Response.class)

// ✅ Good (onErrorResume: 에러 본문까지 로깅)
.retrieve()
.bodyToMono(Response.class)
.onErrorResume(WebClientResponseException.class, ex -> {
    if (ex.getStatusCode().is4xxClientError()) {
        // 상태 코드 + 실제 에러 메시지 로깅
        log.warn("API Failed. Status: {}, Body: {}",
                ex.getStatusCode(), ex.getResponseBodyAsString());
        return Mono.empty();
    }
    // 5xx: 서킷브레이커 동작을 위해 상위 전파
    return Mono.error(ex);
})
.timeout(API_TIMEOUT)
```

**패턴 적용 기준:**
- **클라이언트 에러 (4xx)**: 로깅 후 Mono.empty() 반환 (비즈니스 예외로 처리)
- **서버 에러 (5xx)**: Mono.error()로 상위 전파 (서킷브레이커 동작)

### API Key 저장 규칙 (JWT vs Redis)
- **JWT에 절대 포함 금지**: JWT는 클라이언트에 노출되므로 apiKey 저장 불가
- **Redis 세션에만 저장**: 서버 측에서만 접근 가능한 Redis 세션에 저장
- **Fingerprint 사용**: `HMAC-SHA256(serverSecret, apiKey)`로 변환하여 JWT에 저장

---

## 📖 20. SpringDoc OpenAPI (Swagger UI) Best Practice

API 문서 자동화를 위한 SpringDoc OpenAPI 설정 규칙입니다. (Context7 권장)

### 의존성 (Spring Boot 3.x)
```groovy
// build.gradle
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13'
```

**주의**: Spring Boot 3.x는 `springdoc-openapi-starter-webmvc-ui` 사용 (2.x는 `springdoc-openapi-ui`)

### OpenAPI 설정 패턴 (어노테이션 기반)
```java
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "API Title",
        version = "2.0.0",
        description = "API 설명"
    ),
    servers = {
        @Server(url = "http://localhost:8080", description = "Local"),
        @Server(url = "https://api.example.com", description = "Production")
    },
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT"
)
public class OpenApiConfig {}
```

### application.yml 설정
```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
    operations-sorter: method    # GET/POST/PUT/DELETE 순 정렬
    tags-sorter: alpha           # 태그 알파벳 순
    try-it-out-enabled: true     # "Try it out" 버튼 활성화
    persist-authorization: true  # JWT 토큰 세션 유지
  packages-to-scan: maple.expectation.controller
```

### 테스트 환경 설정 (비활성화)
```yaml
# src/test/resources/application.yml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

### SecurityConfig 통합
```java
// Swagger UI 엔드포인트 permitAll
.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
```

### Controller 어노테이션 (선택)
```java
@Tag(name = "Character", description = "캐릭터 관련 API")
@Operation(summary = "캐릭터 조회", description = "캐릭터 정보를 조회합니다")
@ApiResponse(responseCode = "200", description = "성공")
@ApiResponse(responseCode = "404", description = "캐릭터 없음")
public ResponseEntity<CharacterDto> getCharacter(@PathVariable String ign) { ... }
```

### 접근 경로
| 경로 | 설명 |
|------|------|
| `/swagger-ui.html` | Swagger UI (리다이렉트) |
| `/swagger-ui/index.html` | Swagger UI (직접) |
| `/v3/api-docs` | OpenAPI JSON |
| `/v3/api-docs.yaml` | OpenAPI YAML |

---

## 🚀 21. Async Non-Blocking Pipeline Pattern (Critical)

고처리량 API를 위한 비동기 논블로킹 파이프라인 설계 패턴입니다. (Trace Log 분석 기반)

### 핵심 원칙: 톰캣 스레드 즉시 반환 (0ms)

```java
// ❌ Bad (톰캣 스레드 블로킹 → 동시성 저하)
@GetMapping("/{userIgn}/expectation")
public ResponseEntity<Response> getExpectation(@PathVariable String userIgn) {
    Response result = service.calculate(userIgn);  // 블로킹 호출
    return ResponseEntity.ok(result);
}

// ✅ Good (톰캣 스레드 즉시 반환 → RPS 240+ 달성)
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<Response>> getExpectation(@PathVariable String userIgn) {
    return service.calculateAsync(userIgn)  // 비동기 호출
            .thenApply(ResponseEntity::ok);
}
```

### Two-Phase Snapshot 패턴

캐시 HIT 시 불필요한 DB 조회를 방지하는 단계적 데이터 로드 패턴입니다.

| Phase | 목적 | 로드 데이터 |
|-------|------|------------|
| **LightSnapshot** | 캐시 키 생성 | 최소 필드 (ocid, fingerprint) |
| **FullSnapshot** | 계산 (MISS 시만) | 전체 필드 |

```java
// ✅ Good (Two-Phase Snapshot)
return CompletableFuture
        .supplyAsync(() -> fetchLightSnapshot(userIgn), executor)  // Phase 1
        .thenCompose(light -> {
            // 캐시 HIT → 즉시 반환 (FullSnapshot 스킵)
            Optional<Response> cached = cacheService.get(light.cacheKey());
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(cached.get());
            }
            // 캐시 MISS → Phase 2
            return CompletableFuture
                    .supplyAsync(() -> fetchFullSnapshot(userIgn), executor)
                    .thenCompose(full -> compute(full));
        });
```

### Write-Behind 패턴 (비동기 DB 저장)

API 응답 시간 단축을 위해 DB 저장을 응답 후 비동기로 처리합니다.

```java
// ✅ Good (응답 즉시 반환, DB 저장은 백그라운드)
return nexonApiClient.getEquipment(ocid)
        .thenApply(response -> {
            // 캐시 저장 (동기 - 응답에 필요)
            cacheService.put(ocid, response);

            // DB 저장 (비동기 - Fire-and-Forget)
            CompletableFuture.runAsync(() -> dbWorker.persist(ocid, response),
                    asyncTaskExecutor);

            return response;
        });
```

### 스레드 풀 분리 원칙

| Thread Pool | 역할 | 설정 기준 |
|-------------|------|----------|
| `http-nio-*` | 톰캣 요청 | 즉시 반환 (0ms 목표) |
| `expectation-*` | 계산 전용 | CPU 코어 수 기반 |
| `SimpleAsyncTaskExecutor-*` | Fire-and-Forget | @Async 비동기 |
| `ForkJoinPool.commonPool-*` | CompletableFuture 기본 | JVM 관리 |

```java
// ✅ Good (전용 스레드 풀 지정)
@Bean("expectationComputeExecutor")
public Executor expectationComputeExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
    executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("expectation-");
    executor.initialize();
    return executor;
}
```

### .join() 완전 제거 규칙 (Issue #118)

```java
// ❌ Bad (.join()은 호출 스레드 블로킹)
return service.calculateAsync(userIgn).join();

// ✅ Good (체이닝으로 논블로킹 유지)
return service.calculateAsync(userIgn)
        .thenApply(this::postProcess)
        .orTimeout(30, TimeUnit.SECONDS)
        .exceptionally(this::handleException);
```

### CompletableFuture 체이닝 Best Practice

| 메서드 | 용도 | 예외 전파 |
|--------|------|----------|
| `thenApply()` | 동기 변환 | O |
| `thenApplyAsync()` | 비동기 변환 (다른 스레드) | O |
| `thenCompose()` | Future 평탄화 | O |
| `orTimeout()` | 데드라인 설정 | TimeoutException |
| `exceptionally()` | 예외 복구 | 복구 값 반환 |
| `whenComplete()` | 완료 후 정리 (결과 변경 불가) | X |

```java
// ✅ Good (완전한 비동기 파이프라인)
return CompletableFuture
        .supplyAsync(() -> step1(), executor)
        .thenComposeAsync(r -> step2(r), executor)
        .thenApplyAsync(this::step3, executor)
        .orTimeout(DEADLINE_SECONDS, TimeUnit.SECONDS)
        .exceptionally(e -> handleException(e, context))
        .whenComplete((r, e) -> cleanup(context));
```

### 참고 문서
- `docs/expectation-sequence-diagram.md` - 전체 데이터 흐름 시각화

---

## 🧵 22. Thread Pool Backpressure Best Practice (Issue #168)

ThreadPoolTaskExecutor의 RejectedExecutionHandler 설정 및 메트릭 수집을 위한 필수 규칙입니다.

### CallerRunsPolicy 금지 (Critical)

```java
// ❌ Bad (톰캣 스레드 고갈 → 전체 API 마비)
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

// ✅ Good (즉시 거부 → 503 응답 → 클라이언트 재시도)
executor.setRejectedExecutionHandler(CUSTOM_ABORT_POLICY);
```

**CallerRunsPolicy 문제점:**
- "backpressure" 의도였으나 실제로는 **톰캣 스레드 고갈** 유발
- 큐 포화 시 요청 처리 시간 비정상 증가 (SLA 위반)
- 메트릭 기록 불가 (rejected count = 0으로 보임)
- 서킷브레이커 동작 불가 (예외가 발생하지 않음)

### AbortPolicy + 샘플링 로깅 패턴

```java
private static final AtomicLong rejectedCount = new AtomicLong(0);
private static final AtomicLong lastRejectNanos = new AtomicLong(0);
private static final long REJECT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

private static final RejectedExecutionHandler CUSTOM_ABORT_POLICY = (r, executor) -> {
    // 1. Shutdown 구분
    if (executor.isShutdown() || executor.isTerminating()) {
        throw new RejectedExecutionException("Executor rejected (shutdown)");
    }

    // 2. 샘플링 로깅 (1초 1회, log storm 방지)
    long dropped = rejectedCount.incrementAndGet();
    long now = System.nanoTime();
    long prev = lastRejectNanos.get();

    if (now - prev >= REJECT_LOG_INTERVAL_NANOS &&
        lastRejectNanos.compareAndSet(prev, now)) {
        long count = rejectedCount.getAndSet(0);
        log.warn("[Executor] Task rejected. droppedInLastWindow={}, poolSize={}, queueSize={}",
                count, executor.getPoolSize(), executor.getQueue().size());
    }

    // 3. 예외 던지기 (Future 완료 보장)
    throw new RejectedExecutionException("Executor queue full");
};
```

### Micrometer 메트릭 등록 (Context7 공식)

```java
// ExecutorServiceMetrics 등록
new ExecutorServiceMetrics(
    executor.getThreadPoolExecutor(),
    "executor.name",
    Collections.emptyList()
).bindTo(meterRegistry);

// rejected Counter 추가 (ExecutorServiceMetrics 미제공)
Counter rejectedCounter = Counter.builder("executor.rejected")
        .tag("name", "executor.name")
        .description("Number of tasks rejected due to queue full")
        .register(meterRegistry);
```

**제공 메트릭:**
| 메트릭 | 설명 |
|--------|------|
| `executor.completed` | 완료된 작업 수 |
| `executor.active` | 현재 활성 스레드 수 |
| `executor.queued` | 큐에 대기 중인 작업 수 |
| `executor.pool.size` | 현재 스레드 풀 크기 |
| `executor.rejected` | 거부된 작업 수 (커스텀) |

### 503 응답 + Retry-After 헤더 (HTTP 표준)

```java
// GlobalExceptionHandler에서 처리
@ExceptionHandler(CompletionException.class)
protected ResponseEntity<ErrorResponse> handleCompletionException(CompletionException e) {
    if (e.getCause() instanceof RejectedExecutionException) {
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "60")  // 60초 후 재시도 권장
            .body(errorResponse);
    }
    // ...
}
```

### ⚠️ Write-Behind 패턴 주의 (Critical)

AbortPolicy는 **읽기 전용 작업에만** 적용하세요!

```java
// ❌ DANGER: Write-Behind + AbortPolicy = 데이터 유실
CompletableFuture.runAsync(() -> {
    dbWorker.persist(ocid, data);  // DB 저장
}, writeExecutor);  // AbortPolicy 적용 시 거부 = 데이터 유실!

// ✅ Safe: Write-Behind에는 CallerRunsPolicy 또는 DLQ 패턴
executor.setRejectedExecutionHandler(new CallerRunsPolicy());  // 지연 > 유실
```

**적용 가이드:**
| Executor 용도 | 권장 정책 | 이유 |
|--------------|----------|------|
| 조회/계산 (읽기) | AbortPolicy | 재시도 가능, 멱등성 |
| DB 저장 (쓰기) | CallerRunsPolicy/DLQ | 데이터 유실 방지 |
| 알림 전송 | AbortPolicy | Best-effort 허용 |

---

## 🧪 23. ExecutorService 동시성 테스트 Best Practice

동시성 테스트에서 Race Condition을 방지하기 위한 필수 패턴입니다.

### shutdown() vs awaitTermination() (Critical)

`ExecutorService.shutdown()`은 **새로운 작업 제출만 막고 즉시 반환**됩니다.
기존 작업 완료를 보장하려면 반드시 `awaitTermination()`을 호출해야 합니다.

```java
// ❌ Bad (Race Condition 발생)
executorService.shutdown();
// 아직 작업 실행 중인데 결과 검증!
assertEquals(expected, actualResult);

// ✅ Good (모든 작업 완료 보장)
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

# 🤖 MapleExpectation Multi-Agent Protocol

## 1. The Council of Five (Agent Roles)
이 프로젝트는 5개의 특화된 에이전트 페르소나를 통해 개발 및 검증됩니다. 작업 요청 시 적절한 에이전트를 호출하거나, 복합적인 작업 시 아래 순서대로 검토를 거쳐야 합니다.

* **🟦 Blue: Spring-Architect (The Designer)**
    * **Mandate:** SOLID 원칙, 디자인 패턴(Strategy, Facade, Factory 등), DDD, Clean Architecture 준수.
    * **Check:** "코드가 유지보수 가능한 구조인가?", "의존성 역전(DIP)이 지켜졌는가?"
* **🟩 Green: Performance-Guru (The Optimizer)**
    * **Mandate:** O(1) 지향, Redis Lua Script, SQL Tuning, Non-blocking I/O.
    * **Check:** "이 로직이 10만 RPS를 견디는가?", "불필요한 객체 생성이나 루프가 없는가?"
* **🟨 Yellow: QA-Master (The Tester)**
    * **Mandate:** JUnit 5, Mockito, Testcontainers, Locust, Edge Case 발굴.
    * **Check:** "테스트 커버리지가 충분한가?", "경계값(Boundary)에서 터지지 않는가?"
* **🟪 Purple: Financial-Grade-Auditor (The Sheriff)**
    * **Mandate:** 무결성(Integrity), 보안(Security), Kahan Summation 정밀도, 트랜잭션 검증.
    * **Check:** "확률 계산에 오차 누적이 없는가?", "PII 정보가 로그에 남지 않는가?"
* **🟥 Red: SRE-Gatekeeper (The Guardian)**
    * **Mandate:** Resilience(Circuit Breaker, Timeout), Thread Pool, Config, Infra.
    * **Check:** "서버가 죽지 않는 설정인가?", "CallerRunsPolicy 같은 폭탄이 없는가?"

## 2. Best Practice: The "Pentagonal Pipeline" Workflow
모든 주요 기능 구현(Feature) 및 리팩토링은 다음 파이프라인을 거쳐야 한다.

1.  **Draft (Blue):** 아키텍트가 인터페이스와 패턴을 설계하여 구조를 잡는다.
2.  **Optimize (Green):** 퍼포먼스 구루가 쿼리와 알고리즘을 최적화한다.
3.  **Test (Yellow):** QA 마스터가 테스트 케이스(TC)를 작성하고 검증한다.
4.  **Audit (Purple):** 오디터가 데이터 무결성과 보안을 최종 승인한다.
5.  **Deploy Check (Red):** 게이트키퍼가 설정 파일과 안정성 장치를 검토한다.

## 3. Core Principles (Context7)
* **Sequential Thinking:** 문제 해결 시 `배경 -> 정의 -> 분석 -> 설계 -> 구현 -> 검증 -> 회고`의 단계를 건너뛰지 않는다.
* **SOLID:** 특히 SRP(단일 책임)와 OCP(개방 폐쇄)를 철저히 지킨다.
* **Design Patterns:** 관습적인 사용이 아니라, 문제 해결을 위한 적절한 패턴(예: 복잡한 분기 처리는 Strategy, 외부 통신은 Facade)을 적용한다.