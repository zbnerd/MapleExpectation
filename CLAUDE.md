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
- **Distributed Lock:** 동시성 제어 시 `RLock`을 사용하며 `executeWithFinally()` 또는 `LockStrategy`를 통해 데드락을 방지합니다.
- **Naming:** Redis 키는 `domain:sub-domain:id` 형식을 따르며 모든 데이터에 TTL을 설정합니다.
- **No Direct try-finally:** 락 해제 시 직접 `try-finally` 사용 금지 → `LogicExecutor.executeWithFinally()` 사용

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

## 🚨 11. Exception Handling Strategy (global.error 패키지 기반)
예외 처리는 시스템의 **회복 탄력성(Resilience)**과 **디버깅 가시성**을 확보하는 핵심 수단입니다.

### 예외 계층 구조 (`global.error.exception.base`)
```
RuntimeException
  └── BaseException (ErrorCode 기반)
        ├── ClientBaseException (4xx) + CircuitBreakerIgnoreMarker
        └── ServerBaseException (5xx) + CircuitBreakerRecordMarker
```

### BaseException 생성자 패턴
```java
// 1. 고정 메시지
public BaseException(ErrorCode errorCode)

// 2. 동적 메시지 (String.format 활용)
public BaseException(ErrorCode errorCode, Object... args)

// 3. Cause 체이닝 + 동적 메시지
public BaseException(ErrorCode errorCode, Throwable cause, Object... args)
```

### ErrorCode 인터페이스 (`global.error.ErrorCode`)
```java
public interface ErrorCode {
    String getCode();      // 예: "C002", "S005"
    String getMessage();   // 예: "존재하지 않는 캐릭터입니다 (IGN: %s)"
    HttpStatus getStatus();
}
```

### CommonErrorCode Enum (`global.error.CommonErrorCode`)
| 코드 | 분류 | 메시지 템플릿 |
| :--- | :--- | :--- |
| `C001` | Client | 잘못된 입력값입니다: %s |
| `C002` | Client | 존재하지 않는 캐릭터입니다 (IGN: %s) |
| `S001` | Server | 서버 내부 오류가 발생했습니다 |
| `S005` | Server | 외부 API 호출 실패 (%s) |

### Custom Exception 작성 규칙
```java
// ✅ Good: ClientBaseException + CircuitBreakerIgnoreMarker
public class CharacterNotFoundException extends ClientBaseException
        implements CircuitBreakerIgnoreMarker {
    public CharacterNotFoundException(String userIgn) {
        super(CommonErrorCode.CHARACTER_NOT_FOUND, userIgn);
    }
}

// ✅ Good: ServerBaseException + CircuitBreakerRecordMarker (외부 API)
public class ExternalServiceException extends ServerBaseException
        implements CircuitBreakerRecordMarker {
    public ExternalServiceException(String serviceName, Throwable cause) {
        super(CommonErrorCode.EXTERNAL_API_ERROR, cause, serviceName);
    }
}
```

### 핵심 규칙
- **No Ambiguous Exceptions:** `RuntimeException`, `Exception` 직접 throw 금지 → 반드시 Custom Exception 정의
- **Cause Chaining:** Checked 예외 변환 시 `cause`를 반드시 전달하여 스택 트레이스 보존
- **Dynamic Message:** `String.format` 기반 동적 인자로 디버깅 가시성 확보

---

## 🚨 12. Zero Try-Catch Policy & LogicExecutor (Architectural Core)
비즈니스 로직에서 `try-catch` 블록을 사용하는 것을 **엄격히 금지**합니다. 모든 실행 흐름과 예외 처리는 **`LogicExecutor`** 템플릿에 위임합니다.

### 🔑 LogicExecutor 사용 패턴 가이드
| 패턴 | 메서드 | 용도 |
| :--- | :--- | :--- |
| **패턴 1** | `execute(task, context)` | 일반적인 실행. 예외 발생 시 로그 기록 후 상위 전파. |
| **패턴 2** | `executeVoid(task, context)` | 반환값이 없는 작업(Runnable) 실행. |
| **패턴 3** | `executeOrDefault(task, default, context)` | 예외 발생 시 안전하게 기본값 반환 (조회 로직 등). |
| **패턴 4** | `executeOrCatch(task, recovery, context)` | 예외 발생 시 특정 복구 로직(람다) 실행. |
| **패턴 5** | `executeWithFinally(task, finalizer, context)` | 자원 해제 등 `finally` 블록이 반드시 필요한 경우 사용. |
| **패턴 6** | `executeWithTranslation(task, translator, context)` | 기술적 예외(IOException 등)를 도메인 예외로 변환. |
| **패턴 7** | `executeCheckedWithHandler(task, recovery, context)` | Checked 예외를 전파하면서 복구 로직 수행. |
| **패턴 8** | `executeWithFallback(task, fallback, context)` | Checked 예외 대응 Fallback 실행 (Tiered Lock 등). |

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

## 🛡️ 13. Circuit Breaker & Resilience Rules (`global.error.exception.marker`)
장애가 전체 시스템으로 전파되는 것을 방지하기 위해 Resilience4j 설정을 준수합니다.

### Marker Interface 규칙
| 마커 | 대상 | 서킷브레이커 영향 |
| :--- | :--- | :--- |
| `CircuitBreakerIgnoreMarker` | 비즈니스 예외 (4xx), 락 예외 | 기록 안 함 (무시) |
| `CircuitBreakerRecordMarker` | 외부 API 예외 (5xx) | 기록 → 서킷 오픈 가능 |

### 예외별 마커 적용 예시
```java
// CircuitBreakerIgnoreMarker: 사용자 입력 오류 → 서킷 영향 X
CharacterNotFoundException extends ClientBaseException implements CircuitBreakerIgnoreMarker
DistributedLockException extends ServerBaseException implements CircuitBreakerIgnoreMarker

// CircuitBreakerRecordMarker: 외부 서비스 장애 → 서킷 기록
ExternalServiceException extends ServerBaseException implements CircuitBreakerRecordMarker
```

### Logging Level 규칙
- **비즈니스 예외 (4xx):** `log.warn` - 비정상 요청 흐름 기록
- **서버/외부 API 예외 (5xx):** `log.error` - 스택 트레이스 포함 장애 기록

---

## 🎯 14. Global Error Mapping & Response (`global.error`)
모든 예외는 `GlobalExceptionHandler`를 통해 규격화된 응답으로 변환됩니다.

### GlobalExceptionHandler 처리 흐름
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 1순위: 비즈니스 예외 (동적 메시지 포함)
    @ExceptionHandler(BaseException.class)
    protected ResponseEntity<ErrorResponse> handleBaseException(BaseException e) {
        log.warn("Business Exception: {} | Message: {}", e.getErrorCode().getCode(), e.getMessage());
        return ErrorResponse.toResponseEntity(e);  // 동적 메시지 활용
    }

    // 2순위: 예측 못한 시스템 예외 (상세 내용 숨김)
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected System Failure: ", e);
        return ErrorResponse.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}
```

### ErrorResponse Record (`global.error.dto.ErrorResponse`)
```java
public record ErrorResponse(int status, String code, String message, LocalDateTime timestamp) {
    // BaseException → 동적 메시지 (e.getMessage())
    public static ResponseEntity<ErrorResponse> toResponseEntity(BaseException e);

    // ErrorCode → 고정 메시지 (보안용)
    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode);
}
```

### 응답 예시
```json
{
  "status": 404,
  "code": "C002",
  "message": "존재하지 않는 캐릭터입니다 (IGN: TestUser123)",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 🚫 15. Anti-Pattern: Error Handling & Maintenance
다음과 같은 안티 패턴은 발견 즉시 리팩토링 대상입니다.

### 예외 처리 안티 패턴
```java
// ❌ Bad: RuntimeException 직접 throw
throw new RuntimeException("캐릭터를 찾을 수 없습니다");

// ✅ Good: Custom Exception + ErrorCode
throw new CharacterNotFoundException(userIgn);
```

```java
// ❌ Bad: 하드코딩된 에러 메시지
throw new SomeException("존재하지 않는 캐릭터입니다 (IGN: " + ign + ")");

// ✅ Good: CommonErrorCode + 동적 인자
super(CommonErrorCode.CHARACTER_NOT_FOUND, userIgn);  // 메시지 템플릿: "존재하지 않는 캐릭터입니다 (IGN: %s)"
```

```java
// ❌ Bad: Cause 누락
throw new ExternalServiceException("NexonAPI");

// ✅ Good: Cause 체이닝
throw new ExternalServiceException("NexonAPI", originalException);
```

### 금지 항목
| 안티 패턴 | 대안 |
| :--- | :--- |
| **Catch and Ignore** | LogicExecutor.executeOrDefault() |
| **Hardcoded Error Messages** | `CommonErrorCode` Enum 사용 |
| **e.printStackTrace()** | `@Slf4j` 로거 사용 |
| **Direct try-catch** | LogicExecutor 패턴 사용 |
| **Raw Thread Usage** | LogicExecutor 또는 @Async |
| **Log Pollution** | TaskContext 기반 구조화 로그 |

---

## 🚫 16. Anti-Pattern: Lambda & Parenthesis Hell (Critical)
`LogicExecutor` 도입으로 `try-catch`는 사라졌지만, 과도한 람다 중첩으로 인한 **"괄호 지옥"**이 발생해서는 안 됩니다.

- **Rule of Thumb (3-Line Rule):** 람다 내부 로직이 **3줄**을 초과하거나 분기문(`if/else`)이 포함된다면, 즉시 **Private Method**로 추출합니다.
- **Method Reference Preference:** `() -> service.process(param)` 대신 `service::process` 또는 `this::process` 형태의 메서드 참조를 최우선으로 사용합니다.
- **Flattening:** `executor.execute(() -> executor.execute(() -> ...))` 형태의 중첩 실행을 금지합니다. 각 단계를 메서드로 분리하여 수직적 깊이를 줄이십시오.

**Code Example:**
```java
// ❌ Bad (Lambda Hell: 가독성 최악, 디버깅 어려움, RuntimeException 직접 사용)
return executor.execute(() -> {
    User user = repo.findById(id).orElseThrow(() -> new CharacterNotFoundException(id));
    if (user.isActive()) {
        return otherService.process(user.getData().stream()
            .filter(d -> d.isValid())
            .map(d -> {
                // ... complex logic ...
                return d.toDto();
            }).toList());
    }
    return List.of();
}, context);

// ✅ Good (Method Extraction: 선언적이고 깔끔함)
return executor.execute(() -> this.processActiveUser(id), context);

// Private Helper Method
private List<Dto> processActiveUser(Long id) {
    User user = findUserOrThrow(id);  // 내부에서 CharacterNotFoundException throw
    return user.isActive() ? processUserData(user) : List.of();
}
```

## 🔄 17. Proactive Refactoring & Quality (ETC)
- **Refactoring First:** 
  - 새로운 기능 구현 전, 기존 코드가 위 원칙(Facade, SOLID, Exception 전략 등)을 위반한다면 반드시 **리팩토링을 선행**합니다.
  - 기능 추가 전, 기존 코드가 LogicExecutor 패턴을 따르지 않는다면 우선 리팩토링을 수행합니다.
- **Sequential Thinking:** 작업 시작 전 `Context 7`의 기술 스택과 현재 가이드를 단계별로 대조하여 디테일을 놓치지 않습니다.
- **Update Rule:** 새로운 라이브러리나 기술 스택 추가 시, 해당 분야의 Best Practice를 조사하여 `CLAUDE.md`를 즉시 업데이트합니다.
- **Definition of Done:** 코드가 작동하는 것을 넘어, 모든 테스트가 통과하고 위 클린 코드 원칙을 준수했을 때 작업을 완료한 것으로 간주합니다.
- **Context Awareness:** 수정하려는 코드가 TieredCache나 LockStrategy 등 공통 모듈에 영향을 주는지 LogicExecutor의 파급력을 고려하여 작업합니다.
- **PR base:** PR남길경우 PR base는 반드시 develop으로 해야합니다.
- **try catch:** 예외처리시 try catch finally를 직접 사용해서는 안되며, 반드시 LogicExecutor또는 CheckedLogicExecutor중에 적절한 메서드를 사용하여 예외처리를 해야합니다.
- **Test Confirmation:** 전체 테스트(`./gradlew test`) 실행 전 반드시 사용자에게 컨펌을 받아야 합니다.

---

## 🔗 18. CheckedLogicExecutor (IO Boundary)
Checked 예외가 발생하는 IO 경계(파일 I/O, 네트워크, 외부 API)에서 **try-catch 없이** 예외를 처리하는 전용 템플릿입니다.

### LogicExecutor vs CheckedLogicExecutor
| 항목 | LogicExecutor | CheckedLogicExecutor |
| :--- | :--- | :--- |
| **사용처** | 서비스/도메인 내부 | IO 경계 (파일, 네트워크, 락 등) |
| **입력 타입** | `Supplier<T>` (unchecked only) | `CheckedSupplier<T>` (checked 허용) |
| **예외 처리** | RuntimeException 내부 번역 | Level 1: mapper로 명시적 변환 / Level 2: throws 전파 |

### 사용 패턴
```java
// Level 1: checked → runtime 변환 (try-catch 완전 제거)
String content = checkedExecutor.executeUnchecked(
    () -> Files.readString(Path.of("data.txt")),
    TaskContext.of("FileService", "ReadFile", "data.txt"),
    e -> new FileProcessingException("Failed to read file", e)
);

// Level 1 + finally: 락/자원 해제 보장
return checkedExecutor.executeWithFinallyUnchecked(
    () -> doWorkUnderLock(),
    () -> lock.unlock(),
    TaskContext.of("LockService", "Execute", "resource"),
    e -> new LockExecutionException("Failed", e)
);
```

---

## ⚙️ 19. Policy Pipeline Architecture (v2.4.0+)
`LogicExecutor`의 횡단 관심사(로깅, 메트릭, 자원 정리)를 **Stateless Policy**로 분리하여 조합합니다.

### ExecutionPolicy 인터페이스
```java
public interface ExecutionPolicy {
    default void before(TaskContext context) {}        // Lifecycle 훅
    default <T> void onSuccess(T result, long elapsedNanos, TaskContext context) {}  // Observability 훅
    default void onFailure(Throwable error, long elapsedNanos, TaskContext context) {}
    default void after(ExecutionOutcome outcome, long elapsedNanos, TaskContext context) {}
}
```

### 훅 실행 순서
1. `before()` - Task 시작 전
2. `[task 실행]`
3. `onSuccess()` 또는 `onFailure()` - Observability
4. `after()` - finally 블록 (before 성공한 정책만)

### 핵심 정책
| 정책 | 역할 |
| :--- | :--- |
| **LoggingPolicy** | 구조화된 로그 (TaskContext 기반) |
| **FinallyPolicy** | 자원 해제 보장 (락, 커넥션 등) |

---

## 🔐 20. Tiered Lock Strategy (Redis → MySQL Fallback)
`ResilientLockStrategy`는 Redis 락 실패 시 MySQL Named Lock으로 자동 복구합니다.

### 예외 필터링 정책
| 예외 타입 | 처리 |
| :--- | :--- |
| **ClientBaseException (Biz)** | Fallback 금지, 즉시 전파 |
| **Redis/CircuitBreaker (Infra)** | MySQL Fallback 허용 |
| **Unknown (NPE 등)** | 즉시 전파 (버그 조기 발견) |

### 사용 예시
```java
// executeWithLock: Redis tier 전체 실행 (락+task+해제)
return executor.executeWithFallback(
    () -> circuitBreaker.executeCheckedSupplier(() ->
        redisLockStrategy.executeWithLock(key, waitTime, leaseTime, task)
    ),
    (t) -> handleFallback(t, key, "executeWithLock", mysqlFallback),
    context
);
```

### 주의사항
- **Lock Scope > Transaction Scope**: 락 범위가 트랜잭션보다 커야 함
- **try-finally 금지**: `executeWithFinally()` 또는 Policy 사용
- **MySQL Session 고정**: `ConnectionCallback` 기반으로 GET_LOCK → task → RELEASE_LOCK 원자적 완결

---

## 🛡️ 21. Redis Sentinel HA Configuration
Redis 고가용성을 위해 Sentinel 모드를 사용합니다.

### 인프라 구성
- **Master/Slave 복제**: Redis 7.0
- **Sentinel 3대**: quorum 2
- **Failover 시간**: down-after-milliseconds 1000ms (1초 이내)

### application.yml 설정 (Sentinel 모드)
```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: localhost:26379,localhost:26380,localhost:26381  # local
        # nodes: ${REDIS_SENTINEL_NODES}  # prod (예: sentinel1:26379,sentinel2:26379,sentinel3:26379)
```

### Failover 검증 항목
- [ ] Master 장애 시 1초 이내 자동 Failover
- [ ] 데이터 무손실 검증
- [ ] Failover 후 분산 락 정상 동작
- [ ] Master 복구 후 Slave 재설정

---

## 🔄 22. Async Pipeline Policy (Issue #118 준수)

비즈니스 로직에서 **블로킹 호출을 완전히 제거**하고 `CompletableFuture` 파이프라인으로 전환합니다.

### 핵심 원칙: 비즈니스 로직 내 `.join()` / `.get()` 완전 금지

```java
// ❌ Bad: 블로킹 호출 (스레드 점유, Throughput 저하)
T result = future.join();
T result = future.get();
T result = future.get(timeout, unit);

// ✅ Good: 논블로킹 체이닝
future.thenCompose(result -> nextAsyncOperation(result))
      .exceptionallyCompose(e -> fallbackAsyncOperation(e))
      .orTimeout(30, TimeUnit.SECONDS)
      .whenComplete((r, e) -> cleanup());
```

### Java 9+ CompletableFuture 메서드 가이드

| 메서드 | 용도 | 비동기 버전 |
| :--- | :--- | :--- |
| `thenCompose()` | 연속 비동기 작업 체이닝 | `thenComposeAsync(fn, executor)` |
| `thenApply()` | 결과 변환 | `thenApplyAsync()` |
| `handle()` | 성공/실패 모두 처리 | `handleAsync()` |
| `whenComplete()` | 사이드 이펙트 (finally 역할) | `whenCompleteAsync()` |
| `orTimeout()` | 타임아웃 시 TimeoutException | - |
| `completeOnTimeout()` | 타임아웃 시 기본값 | - |

### Java 12+ 예외 복구 메서드

| 메서드 | 용도 |
| :--- | :--- |
| `exceptionally()` | 예외 시 기본값 반환 |
| `exceptionallyAsync()` | 비동기로 예외 처리 |
| `exceptionallyCompose()` | 예외 시 새 Future 반환 (★핵심) |
| `exceptionallyComposeAsync()` | 비동기로 예외 복구 |

### 즉시 완료 Future
```java
CompletableFuture.completedFuture(value)  // 이미 완료된 성공 Future
CompletableFuture.failedFuture(ex)        // 이미 완료된 실패 Future
```

### Spring MVC 비동기 컨트롤러
```java
// CompletableFuture 직접 반환 (Spring 4.2+)
@GetMapping("/async")
public CompletableFuture<ResponseEntity<T>> asyncEndpoint() {
    return service.processAsync()
            .thenApply(ResponseEntity::ok);
}

// DeferredResult 패턴 (수동 완료)
@GetMapping("/deferred")
public DeferredResult<T> deferredEndpoint() {
    DeferredResult<T> result = new DeferredResult<>();
    service.processAsync()
            .whenComplete((r, e) -> {
                if (e != null) result.setErrorResult(e);
                else result.setResult(r);
            });
    return result;
}
```

### Resilience4j 비동기 패턴
```java
// TimeLimiter + CircuitBreaker + 비동기 조합
CompletableFuture<String> future = Decorators
    .ofSupplier(() -> backendService.doSomething())
    .withThreadPoolBulkhead(threadPoolBulkhead)
    .withTimeLimiter(timeLimiter, scheduler)
    .withCircuitBreaker(circuitBreaker)
    .withFallback(List.of(TimeoutException.class, CallNotPermittedException.class),
        e -> "Async fallback")
    .get()
    .toCompletableFuture();

// 논블로킹 결과 처리
future.thenAccept(result -> log.info("Result: {}", result));

// Retry 비동기 패턴
Supplier<CompletionStage<T>> decoratedAsync =
    Retry.decorateCompletionStage(retry, scheduler, asyncSupplier);
```

### Single-flight 비동기 패턴
```java
private CompletableFuture<T> singleFlightAsync(String key,
        Supplier<CompletableFuture<T>> asyncSupplier) {

    CompletableFuture<T> promise = new CompletableFuture<>();
    InFlightEntry existing = inFlight.putIfAbsent(key, new InFlightEntry(promise));

    if (existing == null) {
        // Leader: 비동기 계산 시작
        return asyncSupplier.get()
            .whenComplete((r, e) -> {
                if (e != null) promise.completeExceptionally(e);
                else promise.complete(r);
            })
            .whenComplete((r, e) -> cleanupEntry(key));
    }

    // Follower: 비동기 대기 (타임아웃 포함)
    return existing.future()
            .orTimeout(5, TimeUnit.SECONDS)
            .exceptionallyCompose(e -> handleFollowerTimeout(key, e));
}
```

### GlobalExceptionHandler CompletionException 처리
```java
@ExceptionHandler(CompletionException.class)
protected ResponseEntity<ErrorResponse> handleCompletionException(CompletionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof BaseException be) {
        return handleBaseException(be);
    }
    log.error("Async Pipeline Failure: ", e);
    return ErrorResponse.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
}
```

### ThreadLocal 전파 주의사항
비동기 체이닝에서 ThreadLocal 전파를 위해 `TaskDecorator` 설정 필수:
```java
@Bean
public TaskDecorator contextPropagatingDecorator() {
    return runnable -> {
        // 호출 스레드에서 상태 캡처
        Boolean snap = SomeContext.snapshot();
        return () -> {
            Boolean before = SomeContext.snapshot();
            SomeContext.restore(snap);
            try {
                runnable.run();
            } finally {
                SomeContext.restore(before);  // 스레드풀 누수 방지
            }
        };
    };
}
```

### 동기 API 허용 범위
- **비즈니스 로직 내**: `.join()` / `.get()` 완전 금지
- **컨트롤러/어댑터 레이어**: 레거시 호환 시 제한적 허용 (비권장)

```java
// 레거시 동기 API (컨트롤러에서만 사용 - 비즈니스 로직 밖)
public T syncMethod() {
    return asyncMethod().join();  // 비권장하지만 컨트롤러에서는 허용
}
```

---

가장중요 !! 모든작업시 sequential thinking mcp을 사용하도록 합니다.