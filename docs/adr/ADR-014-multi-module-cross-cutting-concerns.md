# ADR-014: 멀티 모듈 전환 - 횡단 관심사 분리를 통한 CQRS 선행 기반 구축

## 상태
Proposed

## 선행 조건
- 이 ADR은 [#126 Pragmatic CQRS](https://github.com/zbnerd/MapleExpectation/issues/126)의 **필수 선행 작업**이다.
- CQRS로 조회/처리 서버를 분리하기 전에, 두 서버가 공유할 **공통 인프라 모듈**이 먼저 추출되어야 한다.

## 맥락 (Context)

### 현재 구조의 문제

현재 MapleExpectation은 **단일 모듈 모놀리식 구조**로, `global` 패키지에 약 130개 이상의 Java 파일이 **횡단 관심사(Cross-Cutting Concerns)**로 집중되어 있다.

```
src/main/java/maple/expectation/
├── global/           ← 130+ 파일 (횡단 관심사)
│   ├── executor/     ← LogicExecutor, ExecutionPipeline
│   ├── cache/        ← TieredCache, ProbabilisticCache
│   ├── lock/         ← LockStrategy (Redis, MySQL, Guava)
│   ├── shutdown/     ← GracefulShutdownCoordinator
│   ├── resilience/   ← CircuitBreaker, CompensationLog
│   ├── ratelimit/    ← RateLimiter, Bucket4j
│   ├── error/        ← GlobalExceptionHandler, 27+ Custom Exceptions
│   ├── security/     ← JWT, Authentication
│   ├── queue/        ← MessageQueue, Buffer
│   ├── redis/        ← Lua Scripts
│   ├── filter/       ← MDCFilter
│   └── ...
├── aop/              ← LoggingAspect, LockAspect, TraceAspect
├── domain/           ← JPA Entity, Repository
├── service/          ← 비즈니스 로직
└── controller/       ← API 엔드포인트
```

### 왜 지금 분리해야 하는가

| 시나리오 | 분리 없이 CQRS 진행 시 | 분리 후 CQRS 진행 시 |
|---------|----------------------|---------------------|
| 서버 분리 | 동일 코드 복붙 (Ctrl+C/V) | 모듈 의존성 추가 1줄 |
| 버그 수정 | N개 서비스 모두 수정 | 공통 모듈 1곳만 수정 |
| 새 서비스 추가 | 방어 로직 전부 재구현 | `implementation project(':maple-core')` |
| Kotlin 전환 | 공통 코드도 전부 재작성 | JVM 호환으로 그대로 사용 |

**분리하지 않으면** → "분산된 모놀리스(Distributed Monolith)"라는 **최악의 아키텍처**가 된다.

---

## 검토한 대안 (Options Considered)

### 옵션 A: 현재 유지 (단일 모듈)
```
구조 단순성: ★★★★★
확장성:     ★☆☆☆☆ (수평 확장 시 코드 복붙 불가피)
유지보수:   ★★☆☆☆ (서비스 증가 시 기하급수적 관리 비용)
```
- 장점: 구조 변경 불필요, 빌드 단순
- 단점: CQRS 분리 시 코드 복붙 강제, 변경 사항 N개 서비스에 동기화 필요
- **결론: 단일 서비스에서는 최적이나, CQRS 전환 시 치명적 제약**

### 옵션 B: 2-모듈 분리 (common + app)
```
구조 단순성: ★★★★☆
확장성:     ★★★☆☆
유지보수:   ★★★☆☆
```
- 장점: 단순한 구조, 빠른 전환
- 단점: common 모듈이 비대해짐 (POJO와 Spring Bean이 혼재), 도메인과 인프라 결합
- **결론: 중간 단계로는 가능하나, 장기적으로 모듈 비대화 문제 재발**

### 옵션 C: 4-모듈 분리 (common + core + domain + app) ← 채택
```
구조 단순성: ★★★☆☆
확장성:     ★★★★★ (모듈 재조합으로 서버 분리)
유지보수:   ★★★★★ (관심사 완전 분리)
```
- 장점: CQRS 전환 시 모듈 재조합만으로 서버 분리, JVM 호환 언어(Kotlin) 전환 시 common/core 재사용
- 단점: 초기 구조 변경 비용, Gradle 설정 복잡도 증가
- **결론: 채택. 확장성과 유지보수성에서 압도적**

### 옵션 D: Service Mesh (Istio/Linkerd)
```
구조 단순성: ★★☆☆☆ (K8s 필수)
확장성:     ★★★★★ (언어 독립적)
유지보수:   ★★☆☆☆ (인프라 복잡도)
```
- 장점: 코드 변경 없이 인프라 레벨에서 서킷브레이커, mTLS 등 적용
- 단점: Kubernetes 필수, 현재 AWS t3.small 환경에서 과도한 리소스 요구
- **결론: 현 단계에서 오버엔지니어링. 대규모 확장 시점에 재검토**

---

## 결정 (Decision)

### 옵션 C를 채택한다: 4-모듈 Gradle 멀티 프로젝트 구조

### 1. 모듈 구조

```
maple-expectation/
├── settings.gradle          ← 모듈 선언
├── build.gradle             ← 공통 의존성 관리
│
├── maple-common/            ← [경량] POJO, DTO, 함수형 인터페이스
│   ├── build.gradle
│   └── src/main/java/maple/expectation/common/
│       ├── error/           ← ErrorCode 인터페이스, BaseException 계층, ErrorResponse
│       ├── response/        ← ApiResponse
│       ├── function/        ← ThrowingSupplier, CheckedRunnable
│       └── util/            ← ExceptionUtils
│
├── maple-core/              ← [중량] Spring Infrastructure, AutoConfiguration
│   ├── build.gradle
│   └── src/main/java/maple/expectation/core/
│       ├── executor/        ← LogicExecutor, ExecutionPipeline, Policy
│       ├── lock/            ← LockStrategy (Redis, MySQL, Guava)
│       ├── cache/           ← TieredCache, ProbabilisticCache
│       ├── shutdown/        ← GracefulShutdownCoordinator
│       ├── resilience/      ← CircuitBreaker, CompensationLog
│       ├── ratelimit/       ← RateLimiter, Bucket4j
│       ├── redis/           ← LuaScript
│       ├── security/        ← JWT, Authentication
│       ├── queue/           ← MessageQueue, Buffer
│       ├── filter/          ← MDCFilter
│       ├── concurrency/     ← SingleFlightExecutor
│       ├── aop/             ← LoggingAspect, LockAspect, TraceAspect
│       └── config/          ← MapleCoreAutoConfiguration
│
├── maple-domain/            ← [도메인] JPA Entity, Repository
│   ├── build.gradle
│   └── src/main/java/maple/expectation/domain/
│       ├── entity/
│       └── repository/
│
└── maple-app/               ← [애플리케이션] Controller, Service (기존 모듈)
    ├── build.gradle
    └── src/main/java/maple/expectation/
        ├── controller/
        ├── service/
        └── scheduler/
```

### 2. 의존성 흐름 (단방향)

```
maple-app
  ├── maple-core
  │     └── maple-common
  └── maple-domain
        └── maple-common
```

- **상위 → 하위 방향으로만** 의존 (DIP 원칙)
- maple-common은 어떤 모듈에도 의존하지 않음 (Leaf 모듈)
- maple-core와 maple-domain은 서로 의존하지 않음 (병렬 빌드 가능)

### 3. AutoConfiguration 전략

`maple-core`에 `MapleCoreAutoConfiguration`을 제공하여, 의존성 추가만으로 모든 인프라 Bean이 자동 등록된다.

```java
// maple-core의 AutoConfiguration
@AutoConfiguration
@Import({
    ExecutorAutoConfiguration.class,
    CacheAutoConfiguration.class,
    LockAutoConfiguration.class,
    ShutdownAutoConfiguration.class,
    ResilienceAutoConfiguration.class,
    RateLimitAutoConfiguration.class,
    SecurityAutoConfiguration.class
})
public class MapleCoreAutoConfiguration {
}
```

**조건부 활성화 (@Red 요구사항 반영):**
```yaml
# Query Server - application.yml
maple:
  core:
    cache:
      enabled: true
    ratelimit:
      enabled: true
    queue:
      enabled: false    # Query Server는 큐 불필요

# Worker Server - application.yml
maple:
  core:
    cache:
      enabled: false    # Worker는 캐시 불필요
    ratelimit:
      enabled: false
    queue:
      enabled: true
```

### 4. 이관 우선순위

| Phase | 대상 | 내용 | 위험도 |
|-------|------|------|--------|
| Phase 1 | maple-common | error, response, util, function (의존성 없는 POJO) | Low |
| Phase 2 | maple-core | executor, lock, cache, shutdown, resilience, aop | Medium |
| Phase 3 | maple-domain | entity, repository | Medium |
| Phase 4 | maple-app 정리 | 기존 global 패키지 제거, import 경로 수정 | Low |

### 5. CQRS 연결 (#126)

멀티 모듈 완성 후, #126 Pragmatic CQRS는 다음과 같이 모듈을 **재조합**만 하면 된다:

```
[현재 - 단일 서버]
maple-app (모든 기능)
  ├── maple-core
  ├── maple-domain
  └── maple-common

[미래 - CQRS 분리]
maple-api (Query Server)          maple-worker (Command Server)
  ├── maple-core                    ├── maple-core
  ├── maple-domain                  ├── maple-domain
  ├── maple-common                  ├── maple-common
  └── Query Controllers             └── Kafka Consumers
```

---

## 5-Agent Council 피드백

### @Blue (Spring-Architect) → 전체
> "4-모듈 구조를 채택한다. DIP 원칙에 따라 상위 모듈(app)이 하위 모듈(core, domain)의 추상화에만 의존하도록 설계한다. 인터페이스는 common에, 구현체는 core에 배치한다."

### @Green (Performance-Guru) → @Blue
> "모듈 분리 시 Gradle 병렬 빌드를 활용해야 한다. maple-common과 maple-domain은 서로 의존하지 않으므로 병렬 컴파일 가능하다. `org.gradle.parallel=true` 설정으로 빌드 시간 증가를 최소화할 수 있다."

### @Yellow (QA-Master) → @Blue
> "각 모듈별 독립 테스트가 가능해야 한다. maple-core는 Testcontainers로, maple-common은 순수 단위 테스트로 검증한다. 테스트 fixtures 공유를 위해 `java-test-fixtures` 플러그인 활용을 제안한다."

### @Purple (Financial-Grade-Auditor) → @Red
> "LogicExecutor가 maple-core로 이관되면, 예외 처리 전략의 일관성이 반드시 보장되어야 한다. 버전 불일치로 인한 예외 계층 깨짐을 방지하기 위해 **동일 Gradle 프로젝트 내 모듈로 유지**할 것을 강력 권고한다. Maven Central 배포는 시기상조이다."

### @Red (SRE-Gatekeeper) → @Blue
> "AutoConfiguration에서 `@ConditionalOnProperty`를 활용하여 GracefulShutdown, CircuitBreaker 등을 서비스별로 ON/OFF 할 수 있어야 한다. Worker Server는 RateLimiter가 불필요하고, Query Server는 Queue 관련 Bean이 불필요하다."

### @Blue → @Red (수용)
> "동의한다. feature toggle 방식으로 `maple.core.{feature}.enabled` 형태의 프로퍼티를 제공하여 각 서비스가 필요한 기능만 활성화하도록 설계한다."

### 최종 합의
옵션 C(4-모듈 분리)를 채택하되, @Red의 조건부 설정과 @Purple의 동일 프로젝트 내 관리 원칙을 반영한다.

---

## 결과 (Consequences)

### 긍정적 결과
- **CQRS 기반 확보:** 모듈 재조합만으로 Query/Worker 서버 분리 가능
- **유지보수 단일화:** 공통 로직 수정 시 1곳만 변경하면 N개 서비스에 반영
- **신규 서비스 부트스트래핑:** `implementation project(':maple-core')` 한 줄로 방어 로직 전체 적용
- **JVM 언어 호환:** Kotlin 등 JVM 호환 언어로 신규 서비스 작성 시 기존 모듈 100% 재사용
- **빌드 최적화:** maple-common ↔ maple-domain 병렬 빌드로 전체 빌드 시간 최소화

### 부정적 결과 및 완화 방안
- **초기 구조 변경 비용:** import 경로 변경, Gradle 설정 추가 → Phase별 점진적 이관으로 완화
- **Gradle 설정 복잡도:** → root build.gradle에서 subprojects 블록으로 공통 설정 관리
- **모듈 간 순환 의존 위험:** → Gradle의 `api` vs `implementation` 구분으로 의존성 범위 제한

---

## 관련 문서
- [#126 Pragmatic CQRS: 조회/처리 서버 분리](https://github.com/zbnerd/MapleExpectation/issues/126)
- [ADR-012: Stateless 아키텍처 전환 로드맵](ADR-012-stateless-scalability-roadmap.md)
- [ADR-013: 대규모 트래픽 처리를 위한 비동기 이벤트 파이프라인](ADR-013-high-throughput-event-pipeline.md)
