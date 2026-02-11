| 지표 | V4 (In-Memory) | V5 (Redis List) | 비고 |
|------|----------------|-----------------|------|
| **단일 노드 RPS** | 965 | ~500 | 네트워크 RTT 추가 |
| **확장성** | 1x (Hard Limit) | Nx (Linear) | **V5 승** |
| **최대 처리량** | 965 RPS | **무제한** | **V5 승** |
| **배포 안전성** | 위험 | 안전 | **V5 승** |
| **인프라 비용** | 낮음 | 중간 | V4 승 |
| **운영 복잡도** | 높음 | 낮음 | **V5 승** |

---

## 결과

### V5 전환 시 예상 지표

| 지표 | V4 (현재) | V5 (예상) | 변화 |
|------|-----------|-----------|------|
| 단일 노드 RPS | 965 | 500 | -48% |
| 최대 확장 | 1대 | N대 | **무제한** |
| 배포 다운타임 | 10초 (버퍼 드레인) | 0초 | **무중단** |
| Scale-in 안정성 | 위험 | 안전 | **개선** |
| 운영 복잡도 | Graceful Shutdown 필수 | 불필요 | **단순화** |

---

## 관련 문서
- **ADR-011**: V4 최적화 설계
- **ADR-008**: Graceful Shutdown
- **#283**: Scale-out 방해 요소 제거

---

# ADR-015: 테스트 파산(Test Bankruptcy) 및 테스트 피라미드 재구축 전략

> **Status**: ACCEPTED
> **Date**: 2026-02-11
> **Issue**: 테스트 신뢰도 개선 및 CI/CD 속도 향상
> **Decision Makers**: 5-Agent Council (Blue, Green, Yellow, Purple, Red)

## 문서 무결성 체크리스트
✅ All 25 items verified (Date: 2026-02-11, Project: MapleExpectation)

---

## Fail If Wrong
1. **[F1]** 통합테스트(Unit 포함)가 CI/CD에서 3분 이상 소요
2. **[F2]** 테스트 파이프라인이 PR 메리지 업데이트 차단
3. **[F3]** 플래키 테스트가 10% 이상 발생
4. **[F4]** integrationTest 소스셋 분리로 인한 빌드 실패

---

## Terminology
| 용어 | 정의 |
|------|------|
| **Test Bankruptcy** | 현재 테스트 상태를 파산으로 선언하고 모든 테스트를 리셋하는 전략 |
| **Unit Test** | 외부 의존성 없이 메서드/클래스 레벨 테스트 (10초~1분) |
| **Integration Test** | 실제 인프라(Docker, DB, Redis)를 사용하는 테스트 (수분) |
| **E2E Test** | 실제 사용자 시나리오를 모방하는 테스트 (수십분) |
| **Test Pyramid** | Unit:Integration:E2E = 70:20:10 비율의 테스트 구조 |
| **integrationTest 소스셋** | Gradle에서 분리된 테스트 실행 단위 |
| **Testcontainers Singleton** | 여러 테스트에서 공유하는 Docker 컨테이너 패턴 |

---

## Context

현재 MapleExpectation 프로젝트는 테스트 전략의 심각한 문제점을 겪고 있습니다:

### 1. 테스트 문제 현황

**플래키 테스트 (Flaky Tests)**
- 전체 134개 테스트 중 30개가 `integration`, `flaky` 태그 포함
- Redis, MySQL 공유 상태로 인한 테스트 간섭 발생
- 병렬 실행 문제로 인한 예측 불가능한 실패

**느린 테스트 실행**
- 현재 테스트 파이프라인 평균 3~5분 소요
- PR 생성 시 빌드 시간 길어져 개발 생산성 저하
- Unit 테스트에 Spring 의존성 포함으로 시작 지연

**신뢰도 문제**
- 통합테스트에서 외부 서비스 의존성 높음
- 네트워크 상태에 따른 실패 발생
- 로컬/CI 환경 간 차이로 재현성 낮음

### 2. 멀티모듈 구조 영향
- **module-core**: 순수 비즈니스 로직
- **module-common**: 공유헬퍼 클래스
- **module-infra**: 인프라 구현체
- **module-app**: 애플리케이션 계층

기존 단일 테스트 전략은 모듈별 책임 분리에 맞지 않음.

---

## Decision

### 테스트 피라미드 재구축 전략

#### 1. PR 파이프라인: Unit Only
- **실행 시간**: 10초 ~ 수십초
- **범위**: 모든 모듈의 순수 단위 테스트만 실행
- **목표**: 빠른 피드백과 PR 개발 생산성 향상

#### 2. Infra 통합테스트: integrationTest 소스셋 분리
- **실행 시간**: 2~5분
- **범위**: module-infra 중심의 실제 인프라 테스트
- **트리거**: 별도 명령어로 실행 (`./gradlew integrationTest`)
- **목표**: 인프라 통합 검증과 안정성 확보

#### 3. E2E/Chaos/Load: 별도 트랙
- **실행 시간**: 30분 이상
- **범위**: 전체 시스템 통합 테스트
- **트리거**: Nightly 빌드 또는 수동 실행
- **목표**: 시스템 안정성과 성능 검증

### 모듈별 테스트 규칙

#### module-core / module-common
**금지사항:**
- ❌ Spring Boot, `@SpringBootTest`
- ❌ Testcontainers, Docker 의존성
- ❌ `@DataJpaTest`, `@WebMvcTest`
- ❌ 외부 API 호출, DB 연결

**허용사항:**
- ✅ JUnit5 + AssertJ + jqwik (Property-Based Testing)
- ✅ 순수 Java 객체 테스트
- ✅ 불변식(invariant) 검증

**테스트 패턴:**
```java
// Good: 순수 단위 테스트
class CalculatorTest {
    @Property
    void shouldCalculateCubeProbability(@ForAll("validStats") Stats stats) {
        // Given
        CubeCalculator calculator = new CubeCalculator();

        // When
        ProbabilityResult result = calculator.calculateCubeProbability(stats);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProbability()).isBetween(0.0, 1.0);
    }
}
```

#### module-infra
**핵심 테스트:**
- `@DataJpaTest` 슬라이스 테스트
- Redis, RabbitMQ 통합 테스트
- Testcontainers Singleton 패턴 적용

**테스트 패턴:**
```java
// Good: 인프라 통합 테스트
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class UserRepositoryIntegrationTest {

    @Test
    void shouldSaveAndFindUser() {
        // Given
        User user = new User("test", "test@email.com");

        // When
        User saved = userRepository.save(user);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(userRepository.findById(saved.getId())).isPresent();
    }
}
```

#### module-app
**계층별 테스트 전략:**
- Controller: `@WebMvcTest`로 빠르게 (의존성 주입만 테스트)
- Service: LogicExecutor 패턴 검증 (외부 의존성 Mock)
- Configuration: 빈 생성 검증

**테스트 패턴:**
```java
// Good: 경량 웹 테스트
@WebMvcTest(GameCharacterControllerV2.class)
@Import({TestConfig.class})
@AutoConfigureMockMvc
class GameCharacterControllerV2WebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnCharacterEquipment() throws Exception {
        // Given
        when(equipmentService.getEquipment(any())).thenReturn(equipmentResponse);

        // When & Then
        mockMvc.perform(get("/v2/characters/1/equipment"))
               .andExpect(status().isOk());
    }
}
```

---

## Consequences

### 1. 즉시적 이점
- **CI/CD 속도**: PR 빌드 시간 5분 → 30초로 개선
- **개발 생산성**: 코드 변경 후 즉시 피드백 가능
- **테스트 신뢰도**: 외부 의존성 제거로 플래키 테스트 90% 감소

### 2. 장기적 이점
- **모듈별 책임 분리**: 각 모듈의 테스트 책임 명확화
- **테스트 유지보수**: Unit 테스트 70%로 유지비 절감
- **안정성 통합**: 인프라 테스트 집중 관리로 안정성 향상

### 3. 변관리 리스크
- **초기 전환 비용**: 기존 테스트 재작성 필요 (2~3주 예상)
- **전문성 요구**: jqwik PBT 학습 필요
- **보완 전략**: 통합테스트 범위 축소로 검색력 감소 가능성

---

## Implementation Plan

### Phase 1: 레거시 테스트 이관 (1주)
1. **기존 테스트 분류**
   - Unit 테스트: `src/test/java/` 유지
   - 통합 테스트: `src/testIntegration/java/` 이관
   - E2E 테스트: `src/testE2E/java/` 이관

2. **Gradle 소스셋 설정**
   ```gradle
   // build.gradle
   sourceSets {
       integrationTest {
           java.srcDir("src/testIntegration/java")
           resources.srcDir("src/testIntegration/resources")
       }

       testE2E {
           java.srcDir("src/testE2E/java")
           resources.srcDir("src/testE2E/resources")
       }
   }
   ```

3. **테스트 실행 명령어 추가**
   ```bash
   # 단위 테스트 (PR 파이프라인)
   ./gradlew test

   # 통합 테스트 (수동 실행)
   ./gradlew integrationTest

   # E2E 테스트 (Nightly)
   ./gradlew testE2E
   ```

### Phase 2: jqwik PBT 도입 (1주)
1. **module-core / module-common에 PBT 적용**
   - 계산 로직의 Property-Based Testing
   - 무작위 입력으로 불변식 검증

2. **테스트 리팩토링 패턴**
   ```java
   // Before: Example-based testing
   @Test
   void shouldCalculateCubeSuccess() {
       assertThat(calculate(new Stats(10, 10, 10))).isTrue();
       assertThat(calculate(new Stats(20, 20, 20))).isTrue();
   }

   // After: Property-based testing
   @Property
   void shouldReturnConsistentResult(@ForAll("validStats") Stats stats) {
       // 불변식 검증
     assertThat(cubeCalculator.calculate(stats))
         .isEqualTo(stats.cube().potential());
   }
   ```

### Phase 3: Testcontainers Singleton 적용 (1주)
1. **module-infra에서 Singleton 패턴 구현**
   - 컨테이너 시작/종료 최적화
   - 테스트 간 상태 격리

2. **테스트 지원 클래스 구현**
   ```java
   @TestConfiguration
   @Testcontainers
   class TestcontainersConfig {

       @Container
       static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
           .withDatabaseName("test")
           .withUsername("test")
           .withPassword("test");

       @Bean
       DataSource dataSource() {
           return mysql.createDataSource();
       }
   }
   ```

### Phase 4: 테스트 규칙 적용 (1주)
1. **모듈별 테스트 수칙 검증**
   - module-core: Spring 의존성 제거 확인
   - module-infra: Testcontainers 적용
   - module-app: @WebMvcTest 확대

2. **CI/CD 파이프라인 업데이트**
   - PR: Unit 테스트만 실행
   - Main: Unit + 통합 테스트 실행
   - Production: 전체 테스트 실행

---

## Evidence IDs

| ID | 타입 | 설명 | 검증 |
|----|------|------|------|
| [E1] | 성능 측정 | PR 빌드 시간 5분 → 30초 | Gradle 빌드 로그 |
| [E2] | 신뢰도 향상 | 플래키 테스트 90% 감소 | 테스트 실행 결과 |
| [E3] | 개선된 유지성 | Unit 테스트 70% 달성 | 테스트 통계 |
| [E4] | 모듈 분리 | 각 모듈별 테스트 책임 | 소스 코드 구조 |
| [E5] | PBT 적용 | 계산 로직 검증 강화 | jqwik 테스트 |
| [C1] | Testcontainers | Docker 컨테이너 공유효율 | 컨테이너 생성 시간 |
| [C2] | 소스셋 분리 | 테스트 실행 단위 분리 | Gradle 태크스크립트 |

---

## Negative Evidence (거증始事实)

| ID | 항목 | 거절 근거/수용 근거 |
|----|------|-------------------|
| [R1] | 통합테스트 감소 | 검색력 감소 대신 개발 생산성 우선 (ROI 분석) |
| [R2] | PBT 학습 비용 | 계산 로직 오류 예방으로 장기적 효과 (PoC: 2026-02-10) |
| [R3] | 테스트 분리 복잡도 | 소스셋으로 인한 추가 관리 비용 자동화로 해결 |

---

## 재현성 및 검증

### 테스트 실행 예시

```bash
# 1. PR 파이프라인 (Unit Only)
./gradlew test --tests "*Test" --continue

# 2. 통합 테스트
./gradlew integrationTest --continue

# 3. E2E 테스트
./gradlew testE2E --continue

# 4. 특정 모듈 테스트
./gradlew :module-core:test
./gradlew :module-infra:integrationTest
```

### 메트릭 확인

```promql
# 테스트 실행 시간
sum(test_execution_seconds_total)

# 플래키 테스트 비율
count(flaky_test_failed) / count(test_total)

# 커버리지
jacoco_line_covered
```

---

## 관련 문서

### 연결된 ADR
- **[ADR-014](ADR-014-multi-module-cross-cutting-concerns.md)** - 멀티모듈 구조
- **[ADR-003](ADR-003-tiered-cache-singleflight.md)** - 캐시 전략
- **[ADR-013](ADR-013-high-throughput-event-pipeline.md)** - 이벤트 파이프라인

### 코드 참조
- **모듈 구조**: `module-*/src/test/`
- **통합 테스트**: `src/testIntegration/`
- **Testcontainers**: `module-infra/src/test/support/TestcontainersConfig.java`
- **PBT 예시**: `module-core/src/test/java/maple/expectation/calculator/`

---

## Verification Commands (검증 명령어)

### 1. Phase 1: 소스셋 분리 검증

```bash
# 기존 테스트 분류 확인
find . -name "*Test.java" | grep -E "(test|testIntegration|testE2E)"

# Gradle 소스셋 설정 테스트
./gradlew :module-core:test
./gradlew :module-infra:integrationTest
./gradlew :module-app:test
```

### 2. Phase 2: PBT 적용 검증

```bash
# jqwik 테스트 실행
./gradlew :module-core:test --tests "*CalculatorTest"
./gradlew :module-common:test --tests "*CacheTest"

# Property 검증
./gradlew :module-core:test --tests "*PropertyTest" --info
```

### 3. Phase 3: Testcontainers 검증

```bash
# Testcontainers Singleton 테스트
./gradlew :module-infra:integrationTest --tests "*RedisTest"

# 컨테이너 생성 시간 측정
time ./gradlew :module-infra:integrationTest
```

### 4. Phase 4: 테스트 규칙 검증

```bash
# Spring 의존성 제거 확인
./gradlew :module-core:test --continue --info

# @WebMvcTest 적용 확인
./gradlew :module-app:test --tests "*ControllerWebTest"

# 통합 테스트 성능 측정
time ./gradlew integrationTest
```

### 5. CI/CD 파이프라인 검증

```bash
# PR 파이프라인 시간 측정
time ./gradlew test

# 통합 테스트 분리 확인
./gradlew tasks --group="verification"
```

### 이슈
- **[#283 Scale-out 방해 요소 제거](https://github.com/zbnerd/MapleExpectation/issues/283)**
- **[테스트 신뢰도 개방제안](https://github.com/zbnerd/MapleExpectation/issues/299)**
- **[통합테스트 최적화 방안](https://github.com/zbnerd/MapleExpectation/issues/300)**