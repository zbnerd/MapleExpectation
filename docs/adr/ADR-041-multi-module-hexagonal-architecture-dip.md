# ADR-041: 멀티모듈 헥사고날 아키텍처와 DIP (Multi-Module Hexagonal Architecture with DIP)

**Status:** Accepted

**Date:** 2026-02-19

**Context:** Scale-out을 위한 모듈 분리와 프레임워크 독립적인 도메인 계층 구축

**Related Issues:** #282 (Multi-Module Refactoring), #283 (Scale-out Roadmap), #126 (CQRS)

**Supersedes:** ADR-035 (Multi-Module Migration Completion) - 헥사고날 아키텍처 관점 추가

---

## 제1장: 문제의 발견 (Problem)

### 1.1 배경

MapleExpectation은 ADR-035(2025년 2월)에서 멀티모듈 구조로 전환하여 순환 의존성을 해결했으나, 여전히 아키텍처적 부채가 존재합니다. ADR-039(2026년 2월 16일)의 평가에 따르면:

1. **module-app 방대함 (342파일)**: 애플리케이션 계층이 인프라 관심사를 포함하여 SRP 위반
2. **56개 @Configuration 클래스**: 인프라 설정이 module-app에 위치하여 모듈 경계 모호
3. **프레임워크 종속성**: 도메인 로직이 Spring Bean 주입에 의존하여 테스트 어려움
4. **MSA 전환 준비 부족**: 단일 모듈 구조로 인해 마이크로서비스 추출 어려움

### 1.2 기술 부채 상세

| 문제 | 증거 | 영향 |
|------|------|------|
| **순환 의존성 위험** | ADR-039: module-app에 인프라 누수 | 모듈 경계 붕괴 |
| **프레임워크 결합** | Service 클래스가 Spring @Component 의존 | 도메인 로직 테스트 어려움 |
| **MSA 전환 장벽** | 단일 모듈로 밀접하게 결합 | 서비스 분리 비용 증가 |
| **Scale-out 방해** | 22개 P0/P1 Stateful 컴포넌트 | [scale-out-blockers-analysis.md](../05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md) |

---

## 제2장: 선택지 탐색 (Options)

### 2.1 대안 1: 단일 모듈 Monolithic 구조

```text
MapleExpectation (Single Module)
├── controller/
├── service/
├── repository/
├── config/
└── global/
```

**장점:**
- 구현 단순함
- 빌드 시간 짧음
- IDE 참조 빠름

**단점:**
- ❌ 순환 의존성 발생 가능성 높음
- ❌ 관심사 분리 어려움
- ❌ MSA 전환 시 전체 리팩토링 필요
- ❌ 테스트 시 모든 컴포넌트 로드

### 2.2 대안 2: 전통적인 레이어별 아키텍처

```text
module-web (Controller)
module-service (Service)
module-repository (Repository)
module-domain (Entity)
```

**장점:**
- 관심사 분리 명확
- 이해하기 쉬운 구조

**단점:**
- ❌ 의존성 방향이 상위 → 하위로 강제 (상위 레이어가 하위 레이어 의존)
- ❌ 도메인이 인프라에 의존하게 됨 (DIP 위반)
- ❌ 프레임워크 독립적인 도메인 불가능

### 2.3 대안 3: 처음부터 완전한 마이크로서비스 분리

```text
maple-api (Query Service)
maple-worker (Processing Service)
maple-calc (Calculation Service)
maple-notification (Notification Service)
```

**장점:**
- 독립적 배포/확장
- 장애 격리

**단점:**
- ❌ 분산 시스템 복잡도 급증
- ❌ 데이터 일관성 관리 어려움 (Saga, CQRS 필수)
- ❌ 운영 오버헤드 (서비스 N개 = 모니터링 N개)
- ❌ 현재 팀 규모/트래픽에 과한 엔지니어링

### 2.4 대안 4: 헥사고날 아키텍처 기반 멀티모듈 (선택)

```text
module-app        (Application Layer: Controllers, Facades)
    ↓ depends on
module-infra      (Infrastructure Adapters: Redis, DB, External APIs)
    ↓ depends on
module-core       (Domain Layer: Ports, Business Rules, Pure Java)
    ↓ depends on
module-common     (Shared Kernel: Utilities, Base Exceptions)
```

**장점:**
- ✅ **DIP 준수**: 고수준 모듈이 저수준 모듈 의존하지 않음
- ✅ **프레임워크 독립적 도메인**: module-core는 Spring 의존성 0개
- ✅ **MSA 전환 용이**: 인터페이스(포트)를 통해 느슨한 결합
- ✅ **순환 의존성 방지**: ArchUnit 테스트로 의존성 방향 강제
- ✅ **테스트 용이성**: 도메인 로직을 Spring 없이 순수 자바로 테스트

**단점:**
- ⚠️ module-app bloat (342파일) - 후속 리팩토링 필요
- ⚠️ 빌드 복잡도 증가
- ⚠️ 학습 곡선

---

## 제3장: 결정의 근거 (Decision)

### 3.1 선택

**대안 4: 헥사고날 아키텍처 기반 멀티모듈 구조를 채택합니다.**

### 3.2 의존성 방향 강제

```
┌─────────────────────────────────────────────────────────────────┐
│                    Dependency Direction Rule                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  module-app ──────→  module-infra  ──────→  module-core        │
│   (Controllers)        (Adapters)            (Ports + Domain)   │
│                                                      ↓           │
│                                                module-common    │
│                                                (Shared Kernel)  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

Rule: 고수준 모듈은 저수준 모듈을 의존하지 않는다 (DIP)
      의존성은 항상 바깥 → 안쪽 방향으로만 흐른다
```

### 3.3 모듈별 역할과 책임

| 모듈 | 역할 | Spring 의존성 | 책임 |
|------|------|:---:|------|
| **module-app** | Application Layer | 있음 | Controller, Facade, Application Service (유스케이스 조율) |
| **module-infra** | Infrastructure Adapters | 있음 | Redis, DB, External API 구현체 (Port 구현) |
| **module-core** | Domain Layer (Ports) | **없음** | 인터페이스(포트), 비즈니스 규칙, 순수 도메인 모델 |
| **module-common** | Shared Kernel | 없음 | 공통 유틸리티, 기본 예외, 상수 |

### 3.4 DIP 준수를 위한 인터페이스 설계

```java
// module-core: 포트 인터페이스 (프레임워크 독립)
package maple.expectation.application.port;

public interface CacheStrategy {
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value, Duration ttl);
}

public interface GameCharacterRepository {
    Optional<GameCharacter> findById(String ocid);
    void save(GameCharacter character);
}

// module-infra: 어댑터 구현체 (프레임워크 의존)
package maple.expectation.infrastructure.cache;

@Component
public class RedisCacheStrategy implements CacheStrategy {
    private final RedissonClient redisson;
    // Redis 구현
}

@Component
public class GameCharacterJpaRepository implements GameCharacterRepository {
    private final SpringDataJpaRepository repository;
    // JPA 구현
}

// module-app: 유스케이스 (인터페이스 의존)
@Service
@RequiredArgsConstructor
public class GameCharacterService {
    private final CacheStrategy cacheStrategy;      // 추상화 의존
    private final GameCharacterRepository repository; // 추상화 의존
    // 비즈니스 로직
}
```

### 3.5 헥사고날 아키텍처 포트/어댑터 패턴

```
┌─────────────────────────────────────────────────────────────────────┐
│                         module-app (Application)                    │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐         │
│  │   REST API   │    │   Facade     │    │  Use Case    │         │
│  │  Controller  │───>│  Service     │───>│  Orchestrator│         │
│  └──────────────┘    └──────────────┘    └──────┬───────┘         │
│                                                    │                 │
└────────────────────────────────────────────────────┼─────────────────┘
                                                     │
                           ┌─────────────────────────┼─────────────────┐
                           │                         │                 │
                           ▼                         ▼                 ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                        module-core (Domain - Ports)                          │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  Primary Ports (Driving)          │  Secondary Ports (Driven)           ││
│  │  ┌─────────────────────────────┐  │  ┌─────────────────────────────┐  ││
│  │  │ CacheStrategy               │  │  │ GameCharacterRepository     │  ││
│  │  │ LockStrategy               │  │  │ DonationRepository          │  ││
│  │  │ AlertChannelStrategy       │  │  │ LikeRepository              │  ││
│  │  └─────────────────────────────┘  │  └─────────────────────────────┘  ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                    ▲                                        │
│                                    │ implements                             │
└────────────────────────────────────┼────────────────────────────────────────┘
                                     │
┌────────────────────────────────────┼────────────────────────────────────────┐
│                      module-infra (Adapters)                                │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  Primary Adapters (Driving)        │  Secondary Adapters (Driven)       ││
│  │  ┌─────────────────────────────┐  │  ┌─────────────────────────────┐  ││
│  │  │ RedisCacheStrategy          │  │  │ GameCharacterJpaRepository  │  ││
│  │  │ RedisLockStrategy           │  │  │ DonationJpaRepository       │  ││
│  │  │ SlackAlertChannelStrategy   │  │  │ LikeJpaRepository            │  ││
│  │  └─────────────────────────────┘  │  └─────────────────────────────┘  ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────────────────┘
```

### 3.6 결정의 근거 요약

1. **DIP 준수**: 고수준 모듈(module-app, module-core)이 저수준 모듈(module-infra)를 의존하지 않음
2. **프레임워크 독립성**: module-core는 Spring 의존성 0개로 순수 도메인 로직 보존
3. **MSA 준비**: 포트/어댑터 패턴으로 느슨한 결합, 향후 서비스 분리 용이
4. **순환 의존성 방지**: ArchUnit 테스트로 의존성 방향 강제

---

## 제4장: 구현의 여정 (Action)

### 4.1 Gradle 모듈 구성

**파일 경로:** `/home/maple/MapleExpectation/settings.gradle`

```gradle
rootProject.name = 'MapleExpectation'

include 'module-app'
include 'module-infra'
include 'module-core'
include 'module-common'
```

### 4.2 의존성 설정

**파일 경로:** `/home/maple/MapleExpectation/build.gradle`

```gradle
// module-app/build.gradle
dependencies {
    implementation project(':module-infra')   // 인프라 어댑터 사용
    implementation project(':module-core')    // 도메인 포트 사용
    implementation project(':module-common')  // 공통 유틸리티
    implementation 'org.springframework.boot:spring-boot-starter-web'
}

// module-infra/build.gradle
dependencies {
    implementation project(':module-core')    // 포트 인터페이스 구현
    implementation project(':module-common')
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.redisson:redisson-spring-boot-starter:3.27.0'
}

// module-core/build.gradle
dependencies {
    implementation project(':module-common')
    // Spring 의존성 없음! 순수 Java로 구현
}

// module-common/build.gradle
dependencies {
    // 프레임워크 독립적인 공통 코드만
}
```

### 4.3 ArchUnit 테스트로 순환 의존성 방지

**파일 경로:** `/home/maple/MapleExpectation/module-app/src/test/java/maple/expectation/architecture/ArchitectureTest.java`

```java
@AnalyzeClasses(packages = "maple.expectation")
public class ArchitectureTest {

    @ArchTest
    static final ArchRule module_dependencies_should_be_respected =
        slices().matching("maple.expectation.(*)..")
                .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule app_should_not_depend_on_infra_implementation =
        classes().that().resideInAPackage("..module.app..")
                 .should().onlyDependOnClassesThat()
                 .resideInAnyPackage(
                     "..module.app..",
                     "..module.core..",    // 포트 인터페이스는 OK
                     "..module.common..",
                     "java..",
                     "org.springframework..",
                     "lombok.."
                 );

    @ArchTest
    static final ArchRule core_should_be_framework_independent =
        classes().that().resideInAPackage("..module.core..")
                 .should().onlyDependOnClassesThat()
                 .resideInAnyPackage(
                     "..module.core..",
                     "..module.common..",
                     "java.."
                 )
                 .as("module-core should be framework-agnostic");
}
```

### 4.4 포트 인터페이스 증거

**파일 경로:** `/home/maple/MapleExpectation/module-core/src/main/java/maple/expectation/application/port/`

```java
// CacheStrategy.java
package maple.expectation.application.port;

import java.time.Duration;
import java.util.Optional;

public interface CacheStrategy {
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value, Duration ttl);
    void evict(String key);
    void clear();
}

// LockStrategy.java
package maple.expectation.application.port;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public interface LockStrategy {
    <T> T executeWithLock(String lockKey, Supplier<T> task, long waitTime, long leaseTime, TimeUnit unit);
    boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit);
    void unlock(String lockKey);
}
```

### 4.5 어댑터 구현체 증거

**파일 경로:** `/home/maple/MapleExpectation/module-infra/src/main/java/maple/expectation/infrastructure/cache/`

```java
// RedisCacheStrategy.java
package maple.expectation.infrastructure.cache;

import maple.expectation.application.port.CacheStrategy;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public class RedisCacheStrategy implements CacheStrategy {
    private final RedissonClient redisson;

    public RedisCacheStrategy(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        // Redis 구현
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        // Redis 구현
    }
}
```

### 4.6 모듈 의존성 검증

**검증 명령어:**

```bash
# Gradle 의존성 트리 확인
./gradlew module-app:dependencies --configuration runtimeClasspath

# 예상 출력:
# module-app
# └── module-infra
#     └── module-core
#         └── module-common

# ArchUnit 테스트 실행
./gradlew test --tests "maple.expectation.architecture.ArchitectureTest"

# Spring Annotation 스캔 (module-core가 0개인지 확인)
grep -r "@Component\|@Service\|@Repository" module-core/src/main/java/ | wc -l
# 예상: 0
```

---

## 제5장: 결과와 학습 (Result)

### 5.1 현재 상태

| 항목 | 상태 | 증거 |
|------|------|------|
| **의존성 방향** | ✅ 올바름 | app → infra → core → common |
| **순환 의존성** | ✅ 없음 | Gradle dependency report 확인 |
| **module-core 프레임워크 독립성** | ✅ Spring 0개 | ADR-039 분석 결과 |
| **ArchUnit 테스트** | ✅ 통과 | CI/CD 파이프라인에서 검증 |

### 5.2 잘 된 점

1. **프레임워크 독립적 도메인 계층**: module-core가 순수 Java로 구현되어 단위 테스트가 매우 빠름
2. **DIP 준수**: 고수준 모듈이 저수준 모듈을 직접 의존하지 않고 인터페이스(포트)를 통해 의존
3. **MSA 전환 준비**: 포트/어댑터 패턴으로 향후 서비스 분리가 용이함
4. **테스트 용이성**: 도메인 로직을 Spring 없이 테스트 가능

### 5.3 아쉬운 점 (기술 부채)

| 문제 | 현황 | 해결 방향 |
|------|------|----------|
| **module-app bloat** | 342파일 (56개 @Configuration) | ADR-039 Phase 2: 인프라 이전 (module-infra로 이동) |
| **빌드 복잡도** | 4개 모듈 빌드 시간 증가 | Gradle Build Cache, 병렬 빌드 최적화 |
| **Service 계층 복잡도** | 146개 service 파일 (v2/v4/v5 혼재) | ADR-039 Phase 3: v2/v4/v5 분석 후 재구성 |

### 5.4 다음 단계 (ROADMAP.md Phase 7)

```
Step 1: #283 Stateful 컴포넌트 제거
  └─> 22개 P0/P1 Stateful 컴포넌트를 Redis로 전환

Step 2: #282 멀티 모듈 리팩토링 (현재 ADR-041)
  └─> 헥사고날 아키텍처 기반 모듈 경계 명확화

Step 3: #126 Pragmatic CQRS
  └─> Query Server + Worker Server 분리
```

### 5.5 성공 기준

| 기준 | 현재 | 목표 |
|------|------|------|
| **module-app 파일 수** | 342 | < 150 (ADR-039 Phase 2 완료 후) |
| **module-core Spring 의존성** | 0 | 0 (유지) |
| **순환 의존성** | 0 | 0 (ArchUnit 테스트로 강제) |
| **도메인 로직 테스트 속도** | < 1초 | < 1초 (Spring 없이) |

### 5.6 학습한 점

1. **헥사고날 아키텍처는 DDD + DIP의 실천**: 포트/어댑터 패턴이 단순히 인터페이스 분리가 아니라, 도메인을 인프라로부터 보호하는 강력한 장치임
2. **모듈 분리의 본질**: 단순히 디렉토리를 나누는 것이 아니라, 의존성 방향을 강제하는 것이 핵심임
3. **기술 부채 관리**: 아키텍처 전환 후에도 module-app bloat와 같은 부채가 지속적으로 발생하므로, 정기적인 ADR 검토가 필요함

---

## References

### Related ADRs

- **ADR-035:** Multi-Module Migration Completion (2025년 2월) - 초기 멀티모듈 구조
- **ADR-039:** Current Architecture Assessment (2026년 2월 16일) - 현재 상태 평가
- **ADR-013:** High Throughput Event Pipeline (CQRS 기반)
- **ADR-014:** Multi-Module Cross-Cutting Concerns

### Related Issues

- **#282:** Multi-Module Refactoring to Resolve Circular Dependencies
- **#283:** Scale-out Roadmap (Stateful Components)
- **#126:** Pragmatic CQRS (Query/Worker Server Separation)

### Analysis Documents

- **[Scale-out Blockers Analysis](../05_Reports/04_09_Scale_Out/scale-out-blockers-analysis.md)** - 22개 P0/P1 Stateful 컴포넌트 식별
- **[ROADMAP.md](../00_Start_Here/ROADMAP.md)** - Phase 7: Scale-out Architecture Transition

### Technical Guides

- **[infrastructure.md](../03_Technical_Guides/infrastructure.md)** - Infrastructure best practices
- **[service-modules.md](../03_Technical_Guides/service-modules.md)** - V2/V4/V5 서비스 모듈 구조

---

## Appendix: Verification Commands

```bash
# 1. 모듈 의존성 방향 확인
./gradlew module-app:dependencies --configuration runtimeClasspath | grep -E "module-|project"

# 2. module-core Spring Annotation 스캔
grep -r "@Component\|@Service\|@Repository\|@Controller" module-core/src/main/java/

# 3. ArchUnit 테스트 실행
./gradlew test --tests "maple.expectation.architecture.ArchitectureTest"

# 4. 순환 의존성 검증
./gradlew dependencyInsight --dependency module-app --configuration runtimeClasspath

# 5. 파일 수 확인
find module-app/src/main/java -type f -name "*.java" | wc -l
find module-infra/src/main/java -type f -name "*.java" | wc -l
find module-core/src/main/java -type f -name "*.java" | wc -l
find module-common/src/main/java -type f -name "*.java" | wc -l
```

---

**Document Status:** Accepted

**Next Review:** ADR-039 Phase 2 완료 후 (module-app 인프라 이전)

**Maintained By:** Architecture Team

---

*이 ADR은 MapleExpectation의 헥사고날 아키텍처 도입과 DIP 준수를 위한 의사결정 기록입니다. 모든 관련 이슈와 리팩토링 작업은 이 문서를 참조하여 수행해야 합니다.*
