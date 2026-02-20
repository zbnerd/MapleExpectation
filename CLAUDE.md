# claude.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

MapleExpectation is a Spring Boot application that calculates MapleStory equipment upgrade costs using Nexon's Open API. Built for resilience and scalability, it handles 1,000+ concurrent users on low-spec infrastructure (AWS t3.small) with 240 RPS throughput.

**Core Technologies:**
- **Java 21** - Virtual Threads, Records, Pattern Matching, Switch Expressions (#262)
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

# Project Guidelines

> **분리된 문서 참조 (떼끄 스타일 넘버링):**
> - [Architecture Overview](docs/00_Start_Here/architecture.md) - 시스템 아키텍처 다이어그램 (Mermaid)
> - [Infrastructure & Integration Guide](docs/03_Technical_Guides/infrastructure.md) - Redis, Cache, Security (Sections 7-10, 17-20)
> - [Async & Concurrency Guide](docs/03_Technical_Guides/async-concurrency.md) - 비동기, Thread Pool (Sections 21-22)
> - [Testing Guide](docs/03_Technical_Guides/testing-guide.md) - 테스트, Flaky Test 방지 (Sections 23-25)
> - [Flaky Test Management](docs/03_Technical_Guides/flaky-test-management.md) - 플래키 테스트 식별, 격리, 관리 절차
> - [Multi-Agent Protocol](docs/00_Start_Here/multi-agent-protocol.md) - 5-Agent Council
> - [Chaos Engineering](docs/02_Chaos_Engineering/) - Nightmare Tests N01-N18
> - [Templates](docs/98_Templates/) - PR, Issue, Report 템플릿

---

## 1. Tech Stack & Context (Refer to Context 7)

이 프로젝트의 빌드 환경과 라이브러리 구성을 반드시 참조하여 최신 권장 방식(Best Practice)으로 구현하십시오.
- **Core:** Java 21, Spring Boot 3.5.4, Gradle
- **Dependencies:** Resilience4j(BOM 2.2.0), Redisson(3.27.0), Caffeine, JPA, MySQL, Jackson CSV
- **Infrastructure:** Docker Socket (unix:///var/run/docker.sock) for Testcontainers

---

## 1-1. Documentation Management Rules

CLAUDE.md는 **핵심 규칙만** 포함하며, 상세 내용은 별도 문서로 분리합니다.

**문서 구조 (떼끄 스타일 넘버링 + 템플릿 + 아카이브):**
```
CLAUDE.md                                    # 핵심 규칙 (Sections 1-6, 11-16)
docs/
├── 00_Start_Here/                           # [필독] 프로젝트 개요 및 온보딩
│   ├── architecture.md                      # 시스템 아키텍처 다이어그램 (Mermaid)
│   ├── multi-agent-protocol.md              # 5-Agent Council
│   └── ROADMAP.md                           # 프로젝트 로드맵
│
├── 02_Chaos_Engineering/                    # [핵심] 카오스 테스트 - 메인 필살기
│   ├── 00_Overview/TEST_STRATEGY.md         # 테스트 전략
│   ├── 01_Core/ ~ 05_Data/                  # 시나리오 카테고리
│   └── 06_Nightmare/                        # Nightmare N01-N18 시나리오
│
├── 03_Technical_Guides/                     # [심화] 기술 가이드
│   ├── infrastructure.md                    # Redis, Cache, Security
│   ├── async-concurrency.md                 # 비동기, Thread Pool
│   ├── testing-guide.md                     # 테스트, Flaky Test 방지
│   └── resilience.md                        # 회복 탄력성 패턴
│
├── 04_Sequence_Diagrams/                    # [시각화] 시퀀스 다이어그램
├── 05_Reports/                              # [결과] 리포트 및 분석
├── 98_Templates/                            # [효율화] 템플릿 모음
└── 99_Archive/                              # [정리] 개인 자료 보관소
```

**규칙:**
- **핵심 규칙**: CLAUDE.md에 유지 (SOLID, 예외 처리, LogicExecutor 등)
- **인프라/기술 상세**: `docs/03_Technical_Guides/infrastructure.md`로 분리
- **비동기/동시성**: `docs/03_Technical_Guides/async-concurrency.md`로 분리
- **테스트 가이드**: `docs/03_Technical_Guides/testing-guide.md`로 분리
- **상호 참조**: 문서 간 링크로 연결 (예: `-> [docs/03_Technical_Guides/infrastructure.md](docs/03_Technical_Guides/infrastructure.md)`)

**새 규칙 추가 시:**
1. 핵심 원칙 -> CLAUDE.md
2. 기술 상세/Best Practice -> 관련 docs/ 문서
3. 문서 상단에 상위 문서 링크 추가

---

## 2. Git Strategy & Commit Convention

- **Branch:** `develop`에서 분기. `feature/{기능}`, `release-{버전}`, `hotfix-{버전}`
- **Commit 규칙:** 타입(영어): 제목(한글). 7대 규칙 준수. (예: `feat: 로그인 기능 구현`)
- **PR: ** PR은 `hotfix`가 아닌 한 반드시 base는 `develop`으로 해야함.
---

## 3. Pull Request (PR) Template (Mandatory)

- PR 제출 시 아래 양식을 반드시 사용하여 작성하십시오.
- PR 제출 전 해당 이슈가 100% 모두 충족이 된다음에 PR을 제출하여야합니다.

```markdown
## 관련 이슈
#이슈번호

## 개요
변경 사항 요약

## 작업 내용
- [ ] 세부 작업 항목

## 리뷰 포인트
리뷰어가 집중적으로 확인해야 할 부분

## 트레이드 오프 결정 근거
기술적 선택의 이유와 대안 비교

## 체크리스트
- [ ] 브랜치/커밋 규칙 준수 여부
- [ ] 테스트 통과 여부
```

---

## 4. Implementation Logic & SOLID

- **Sequential Thinking:** 작업 전 의존성, 최신 문법, 인프라 영향을 단계별로 분석하여 디테일을 확보합니다.
- **SOLID 원칙:** SRP, OCP, LSP, ISP, DIP를 엄격히 준수하여 응집도를 높이고 결합도를 낮춥니다.
- **Modern Java:** Java 21의 Virtual Threads, Records, Pattern Matching, Switch Expressions 등을 적극 활용합니다.

### Optional Chaining Best Practice (Modern Null Handling)

null 체크 로직은 **Optional 체이닝**으로 대체하여 선언적이고 가독성 높은 코드를 작성합니다.

**기본 패턴:**
```java
// Bad (Imperative null check)
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

// Good (Declarative Optional chaining)
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
// Bad (섹션 11, 12 위반)
.orElseGet(() -> {
    try { return loadFromDatabase(key); }
    catch (Exception e) { throw new RuntimeException(e); }
})

// Good (구조적 분리)
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
- Optional 체이닝 -> 예외 없는 작업만 (캐시 조회, 필터링)
- checked exception -> Optional 밖에서 직접 호출
- 예외 변환 -> LogicExecutor.executeWithTranslation() 사용

---

## 5. Anti-Pattern & Deprecation Prohibition

- **No Hardcoding:** 모든 값은 설정 파일, Enum, 상수로 관리합니다.
- **No Spaghetti:** 중첩 깊이(Indentation)는 최대 2단계로 제한하며 Fail Fast(Early Return)를 지향합니다.
- **No Deprecated:** @deprecated 기능은 절대 사용하지 않으며 최신 Best Practice API(예: RestClient)를 사용합니다.

---

## 6. Design Patterns & Structure

- **Essential Patterns:** Strategy, Factory, Template Method, Proxy 패턴 등을 상황에 맞게 적용합니다.
- **Naming:** 의도가 명확한 변수명(예: `activeSubscribers`)을 사용하고, 메서드는 20라인 이내로 유지합니다.
- **Injection:** 생성자 주입(@RequiredArgsConstructor)을 필수 사용합니다.

---

## 11. Exception Handling Strategy (AI Mentor Recommendation)

예외 처리는 시스템의 **회복 탄력성(Resilience)**과 **디버깅 가시성**을 확보하는 핵심 수단입니다.

- **Hierarchy:**
  - **ClientBaseException (4xx):** 비즈니스 예외. `CircuitBreakerIgnoreMarker`를 구현하여 서킷브레이커 상태에 영향을 주지 않음.
  - **ServerBaseException (5xx):** 시스템/인프라 예외. `CircuitBreakerRecordMarker`를 구현하여 장애 발생 시 서킷브레이커를 작동시킴.
- **No Ambiguous Exceptions:** `RuntimeException`, `Exception` 등을 직접 던지는 것을 금지하며, 반드시 비즈니스 맥락이 담긴 **Custom Exception**을 정의합니다.
- **Checked to Unchecked:** `IOException` 등 체크 예외는 발생 지점에서 `catch`하여 적절한 `ServerBaseException`으로 변환합니다. 이때 원인 예외(`cause`)를 넘겨 **Exception Chaining**을 유지합니다.
- **Dynamic Message:** `String.format`을 활용하여 에러 메시지에 구체적인 식별자(ID, IGN 등)를 포함해 디버깅 가시성을 높입니다.

---

## 12. Zero Try-Catch Policy & LogicExecutor (Architectural Core)

**비즈니스 로직, 인프라 모듈, 글로벌 모듈 전체**에서 `try-catch` 및 `try-finally` 블록을 사용하는 것을 **엄격히 금지**합니다. 모든 실행 흐름과 예외 처리는 **`LogicExecutor`** 템플릿에 위임합니다.

> **적용 범위:** `service/`, `scheduler/`, `config/`, `global/`, `aop/` 등 **모든 패키지**

### LogicExecutor 사용 패턴 가이드
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
// Bad (Legacy)
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
    return null;
}

// Good (Modern)
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", id)
);
```

**허용 예외 (LogicExecutor 순환참조/구조적 제약):**
- `TraceAspect`: AOP에서 LogicExecutor 호출 시 순환참조 발생
- `DefaultLogicExecutor`, `DefaultCheckedLogicExecutor`: LogicExecutor 구현체 내부
- `ExecutionPipeline`: LogicExecutor 실행 파이프라인 내부
- `TaskDecorator` (ExecutorConfig): Runnable 래핑 구조로 LogicExecutor 적용 불가 (MDC/ThreadLocal 전파)
- JPA Entity (`DonationOutbox` 등): Spring Bean 주입 불가, Section 11 규칙에 따라 직접 예외 변환

---

## 12-1. Circuit Breaker & Resilience Rules

장애가 전체 시스템으로 전파되는 것을 방지하기 위해 Resilience4j 설정을 준수합니다.

- **Marker Interface:** 예외 클래스에 `CircuitBreakerIgnoreMarker` 또는 `CircuitBreakerRecordMarker`를 명시하여 서킷브레이커의 기록 여부를 결정합니다.
- **Logging Level:**
  - 비즈니스 예외(4xx): `log.warn`을 사용하여 비정상적인 요청 흐름 기록.
  - 서버/외부 API 예외(5xx): `log.error`를 사용하여 스택 트레이스와 함께 장애 상황 기록.
- **Fallback:** 서킷이 오픈되거나 예외 발생 시, 사용자 경험을 해치지 않도록 적절한 폴백 로직을 고려합니다.

---

## 13. Global Error Mapping & Response

모든 예외는 `GlobalExceptionHandler`를 통해 규격화된 응답으로 변환됩니다.

- **Centralized Handling:** `@RestControllerAdvice`를 사용하여 전역적으로 예외를 포착합니다.
- **Consistent Format:** 모든 에러 응답은 `ErrorResponse` 레코드 형식을 따릅니다.
    - 비즈니스 예외: 가공된 동적 메시지를 포함하여 응답.
    - 알 수 없는 시스템 예외: 보안을 위해 상세 내용을 숨기고 `INTERNAL_SERVER_ERROR` 코드로 캡슐화.

---

## 14. Anti-Pattern: Error Handling & Maintenance

다음과 같은 안티 패턴은 발견 즉시 리팩토링 대상입니다.

- **Catch and Ignore:** 예외를 잡고 아무 처리도 하지 않거나 로그만 남기고 무시하는 행위 금지.
- **Hardcoded Error Messages:** 에러 메시지를 소스 코드에 직접 적지 말고 `ErrorCode` Enum에서 관리합니다.
- **Standard Output:** `e.printStackTrace()`나 `System.out.println()` 대신 반드시 `@Slf4j` 로거를 사용합니다.
- **God Class/Spaghetti:** 하나의 메서드가 여러 책임을 지거나 2단계를 초과하는 인덴트를 가지지 않도록 작게 쪼갭니다.
- **Direct try-catch:** 비즈니스 로직 내에 try-catch가 보이면 즉시 리팩토링 대상입니다.
- **Raw Thread Usage:** new Thread(), Future 직접 사용 금지. LogicExecutor 또는 비동기 어노테이션을 사용합니다.
- **Log Pollution:** 의미 없는 로그 산재 금지. TaskContext를 통해 구조화된 로그를 남깁니다.

---

## 15. Anti-Pattern: Lambda & Parenthesis Hell (Critical)

`LogicExecutor` 도입으로 `try-catch`는 사라졌지만, 과도한 람다 중첩으로 인한 **"괄호 지옥"**이 발생해서는 안 됩니다.

- **Rule of Thumb (3-Line Rule):** 람다 내부 로직이 **3줄**을 초과하거나 분기문(`if/else`)이 포함된다면, 즉시 **Private Method**로 추출합니다.
- **Method Reference Preference:** `() -> service.process(param)` 대신 `service::process` 또는 `this::process` 형태의 메서드 참조를 최우선으로 사용합니다.
- **Flattening:** `executor.execute(() -> executor.execute(() -> ...))` 형태의 중첩 실행을 금지합니다. 각 단계를 메서드로 분리하여 수직적 깊이를 줄이십시오.

**Code Example:**
```java
// Bad (Lambda Hell: 가독성 최악, 디버깅 어려움)
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

// Good (Method Extraction: 선언적이고 깔끔함)
return executor.execute(() -> this.processActiveUser(id), context);

// Private Helper Method
private List<Dto> processActiveUser(Long id) {
    User user = findUserOrThrow(id);
    return user.isActive() ? processUserData(user) : List.of();
}
```

---

## 16. Proactive Refactoring & Quality (ETC)

- **Refactoring First:**
  - 새로운 기능 구현 전, 기존 코드가 위 원칙(Facade, SOLID, Exception 전략 등)을 위반한다면 반드시 **리팩토링을 선행**합니다.
  - 기능 추가 전, 기존 코드가 LogicExecutor 패턴을 따르지 않는다면 우선 리팩토링을 수행합니다.
- **Sequential Thinking:** 작업 시작 전 `Context 7`의 기술 스택과 현재 가이드를 단계별로 대조하여 디테일을 놓치지 않습니다.
- **Update Rule:** 새로운 라이브러리나 기술 스택 추가 시, 해당 분야의 Best Practice를 조사하여 `CLAUDE.md`를 즉시 업데이트합니다.
- **Definition of Done:** 코드가 작동하는 것을 넘어, 모든 테스트가 통과하고 위 클린 코드 원칙을 준수했을 때 작업을 완료한 것으로 간주합니다.
- **Context Awareness:** 수정하려는 코드가 TieredCache나 LockStrategy 등 공통 모듈에 영향을 주는지 LogicExecutor의 파급력을 고려하여 작업합니다.

---

# Quick Reference (분리 문서 요약)

## Infrastructure (-> [docs/03_Technical_Guides/infrastructure.md](docs/03_Technical_Guides/infrastructure.md))
- **Section 7**: AOP & Facade Pattern
- **Section 8**: Redis & Redisson Integration
- **Section 8-1**: Redis Lua Script & Cluster Hash Tag
- **Section 9**: Observability & Validation
- **Section 10**: Mandatory Testing & Zero-Failure Policy
- **Section 17**: TieredCache & Cache Stampede Prevention
- **Section 18**: Spring Security 6.x Filter Best Practice
- **Section 19**: Security Best Practices (Logging & API Client)
- **Section 20**: SpringDoc OpenAPI (Swagger UI) Best Practice

## Async & Concurrency (-> [docs/03_Technical_Guides/async-concurrency.md](docs/03_Technical_Guides/async-concurrency.md))
- **Section 21**: Async Non-Blocking Pipeline Pattern
- **Section 22**: Thread Pool Backpressure Best Practice

## Testing (-> [docs/03_Technical_Guides/testing-guide.md](docs/03_Technical_Guides/testing-guide.md))
- **Section 23**: ExecutorService 동시성 테스트 Best Practice
- **Section 24**: Flaky Test 근본 원인 분석 및 해결 가이드
- **Section 25**: 경량 테스트 강제 규칙 (Issue #207)

## Multi-Agent Protocol (-> [docs/00_Start_Here/multi-agent-protocol.md](docs/00_Start_Here/multi-agent-protocol.md))
- **5-Agent Council**: Blue, Green, Yellow, Purple, Red
- **Pentagonal Pipeline Workflow**
- **Core Principles (Context7)**

## Chaos Engineering (-> [docs/02_Chaos_Engineering/](docs/02_Chaos_Engineering/))
- **06_Nightmare/Scenarios**: N01-N18 Nightmare 시나리오
- **06_Nightmare/Results**: 테스트 결과 리포트
- **05_Reports/**: Load Test, E2E Validation 결과

## Service Modules (-> [docs/03_Technical_Guides/service-modules.md](docs/03_Technical_Guides/service-modules.md))
- **V2 핵심 비즈니스**: 15개 모듈 (root, alert, auth, cache, calculator, cube, donation, facade, impl, like, mapper, policy, shutdown, starforce, worker)
- **V4 성능 강화**: 7개 모듈 (root, buffer, cache, fallback, persistence, warmup, calculator/v4)
- **설계 패턴 요약**: Facade, Decorator, Strategy, Transactional Outbox, Write-Behind 등 12개 패턴
- **모듈 의존성 그래프**: V2↔V4 Mermaid 다이어그램

## Scale-out & Architecture (-> [docs/05_Reports/](docs/05_Reports/), [docs/adr/](docs/adr/))
- **Scale-out 방해 요소 분석**: [docs/05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md](docs/05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md) - P0/P1 Stateful 컴포넌트 전수 분석 (22개)
- **대규모 트래픽 성능 분석**: [docs/05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md](docs/05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md) - P0/P1 Thread Pool, Connection Pool, Lock 경합 (11개)
- **로드맵 Phase 7**: [docs/00_Start_Here/ROADMAP.md](docs/00_Start_Here/ROADMAP.md) - #283 → #282 → #126 의존 관계

## Templates (-> [docs/98_Templates/](docs/98_Templates/))
- **Chaos_Report_Template.md**: 카오스 리포트 양식
- **ISSUE_TEMPLATE.md**: GitHub 이슈 양식
- **PR_TEMPLATE.md**: Pull Request 양식

## Skills

| 스킬 | 설명 |
|------|------|
| verify-module-structure | 멀티 모듈 의존성 방향 검증 (DIP 준수) |
| verify-package-structure | 패키지 구조 규칙 검증 (global → infrastructure 이관) |
| verify-circular-dependencies | 순환 의존성 감지 및 해결 추적 |
| verify-import-style | 불필요한 FQCN 사용 검출 및 import 스타일 준수 검증 |
| verify-adr | ADR 형식, 상태, 관련 문서 준수 검증 |
| verify-sequence-diagram | Mermaid 시퀀스 다이어그램 검증 (참여자, 메시지 흐름) |
| verify-7-core-modules | 7대 핵심 모듈 아키텍처 검증 (Facade, Executor 등) |
| verify-issue-dod | 이슈 DoD 100% 달성 검증 (체크리스트, 테스트, 문서) |
| verify-clean-architecture | 클린 아키텍처 준수 검증 (계층 분리, 의존성 방향) |
| verify-clean-code | 클린 코드 준수 검증 (명명, 함수 길이, 중첩) |
| verify-solids | SOLID 원칙 준수 검증 (SRP, OCP, LSP, ISP, DIP) |
| verify-claude-rules | CLAUDE.md와 하위 문서 규칙 위반 검증 |
| verify-stateless | Stateless 설계 준수 검증 (In-Memory 상태 제거) |
| verify-scaleout | Scale-out 측면 적절성 검증 (확장 가능성) |
| verify-security | 보안 취약점 검증 (OWASP Top 10) |
| verify-concurrency | 동시성 위험성 검증 (Race Condition, Deadlock) |
| verify-logic-executor | try-catch-finally 사용 시 LogicExecutor/CheckedLogicExecutor 사용 여부 검증 |
| verify-transactional-aop | @Transactional이나 커스텀 AOP 사용 시 메서드 내부호출 존재 여부 검증 |
| verify-compilation | 정적 분석으로 컴파일 오류와 테스트 실패 패턴을 사전에 검증 |
| verify-optional-chaining | Optional 체이닝, Tap 패턴, Checked Exception 구조적 분리 준수 검증 |
| verify-lambda-hell | 람다 3줄 초과, 중첩 람다, Method Reference 미사용 탐지 |
| verify-circuit-breaker | Circuit Breaker Marker Interface, Logging Level, Resilience4j 준수 검증 |
| verify-deprecated-api | @deprecated 기능 사용 탐지, 최신 Best Practice API 사용 확인 |
| verify-global-error | @RestControllerAdvice, ErrorResponse 형식, Exception Chaining 준수 검증 |
