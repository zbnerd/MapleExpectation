# ADR-049: Spring Boot 3.5.4 채택

## 제1장: 문제의 발견 (Problem)

**기술 부채와 생태계 변화**

MapleExpectation 프로젝트는 2025년 초 Spring Boot 2.x 기반으로 시작되었으나, 다음과 같은 문제들이 대두되었습니다:

1. **Jakarta EE 전환 필연성**: Java生态系统가 `javax.*`에서 `jakarta.*`로의 전환을 완료하며, Spring Boot 2.x는 유지보수 단계에 진입
2. **Legacy API 의존성**: `WebClient` (Spring 5) 대신 구형 `RestTemplate` 사용 중이었으며, 최신 HTTP/2 지원 부족
3. **Observability 부재**: Micrometer Observation API가 Spring Boot 3.x부터 기본 내장되어, 별도 의존성 없이 Distributed Tracing 가능
4. **Virtual Threads 지원**: Java 21의 Project Loom (Virtual Threads)을 활용한 고동시성 처리가 Spring Boot 3.x부터 공식 지원

**결정의 필요성**: 2025년 하반기 1,000+ 동시 사용자 트래픽 처리를 위해 최신 플랫폼의 성능 기능(Virtual Threads, Micrometer Tracing)이 필수적인 상황이었습니다.

---

## 제2장: 선택지 탐색 (Options)

### Option 1: Spring Boot 2.7.x (LTS 유지)

**장점**
- 안정적이고 문서화가 잘 되어 있음
- 마이그레이션 비용 최소화

**단점**
- `javax.*` 패키지 사용 (향상된 Jakarta EE 10 기능 활용 불가)
- Virtual Threads 비공식 지원 (런타임 에러 위험)
- Micrometer Observation 별도 의존성 필요

**판정**: 기술 부채 누적으로 기각

### Option 2: Spring Boot 3.0.x (초기 3.x)

**장점**
- `jakarta.*` 패키지 지원
- Virtual Threads 실험적 지원

**단점**
- 초기 버전 버그 리스크 (CGLIB proxy NPE 이슈 #238)
- Spring Security 6.x 아직 미성숙

**판정**: 안정성 문제로 기각

### Option 3: Quarkus

**장점**
- Native Image 빌드 지원
- 低 메모리 풋프린트

**단점**
- Spring Boot 생태계와 호환성 없음
- 팀 학습 곡선이 가파름
- 기존 코드베이스 전면 재작성 필요

**판정**: 이전 비용으로 기각

### Option 4: Spring Boot 3.5.4 (Latest Stable) **[선택]**

**장점**
- Jakarta EE 10 완전 지원
- Spring Security 6.x 안정화
- Virtual Threads 공식 지원
- Micrometer Observation 내장
- SpringDoc OpenAPI 3.x 완전 호환

**단점**
- `javax.*` → `jakarta.*` 패키지 리팩토링 필요
- 일부 의존성 호환성 확인 필요

---

## 제3장: 결정의 근거 (Decision)

**채택: Spring Boot 3.5.4**

### 핵심 근거

1. **성능**: Virtual Threads로 10,000+ 동시 요청 처리 가능 (Platform Thread 200개 제한 해소)
   - 검증: V4 async pipeline에서 719 RPS 달성 (V2 95 RPS 대비 7.6x 개선)

2. **Observability**: Micrometer Observation + OpenTelemetry 내장으로 Distributed Tracing 무료 구현
   - Prometheus + Grafana 스택과 직접 통합

3. **Security**: Spring Security 6.x의 Lambda DSL로 보안 설정 선언적 구현
   - P0 #238 CGLIB proxy NPE 이슈 해결: Filter Bean 수동 등록 패턴 도입

4. **미지원성**: 2025년 12월 기준 Latest Stable이므로 3년 이상 지원 보장

### 트레이드오프 수용

| 항목 | 비용 | 완화 방안 |
|------|------|-----------|
| `javax.*` → `jakarta.*` | IDE 일괄 변환 + 수동 검증 | Lombok 1.18.30+ 호환성 확보 |
| Spring Security 6.x 변경 | Filter Bean 등록 패턴 학습 | P0 #238 해결 가이드 문서화 |
| 의존성 호환성 | Testcontainers 1.21.2로 업그레이드 | BOM으로 버전 관리 |

---

## 제4장: 구현의 여정 (Action)

### 1. Build Configuration (build.gradle)

**Evidence**: `/home/maple/MapleExpectation/build.gradle` (lines 45-46)

```groovy
dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:3.5.4"
        mavenBom "io.github.resilience4j:resilience4j-bom:2.2.0"
        mavenBom "org.testcontainers:testcontainers-bom:1.21.2"
    }
}
```

- **BOM 패턴**: 의존성 버전 충돌 방지를 위해 Spring Boot BOM 사용
- **Java 21 Toolchain**: L21-L23에서 `languageVersion = JavaLanguageVersion.of(21)` 설정

### 2. Jakarta EE 10 Migration

**패키지 전환 예시**:
```java
// Before (Spring Boot 2.x)
import javax.persistence.Entity;
import javax.servlet.http.HttpServletRequest;

// After (Spring Boot 3.5.4)
import jakarta.persistence.Entity;
import jakarta.servlet.http.HttpServletRequest;
```

**자동화 방법**:
- IntelliJ IDEA "Rename package" 기능으로 일괄 변환
- Git diff로 수동 검증 후 커밋

### 3. Spring Security 6.x Filter Registration (P0 #238 해결)

**Evidence**: `/home/maple/MapleExpectation/docs/03_Technical_Guides/infrastructure.md` (lines 420-522)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Good: Filter Bean 직접 등록 (CGLIB NPE 방지)
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider provider,
            SessionService service,
            FingerprintGenerator generator) {
        return new JwtAuthenticationFilter(provider, service, generator);
    }

    // 서블릿 컨테이너 중복 등록 방지
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);  // Spring Security만 필터 관리
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter filter) throws Exception {
        // Spring Security 6.x Lambda DSL
        http.headers(headers -> headers
            .frameOptions(frame -> frame.deny())
            .contentSecurityPolicy(csp -> csp
                .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'")
            )
        );
        http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

**해결한 문제**: `@Component`를 사용한 Filter 클래스는 CGLIB 프록시 생성 시 부모 클래스의 `logger` 필드가 초기화되지 않아 NPE 발생. 수동 Bean 등록으로 해결.

### 4. Virtual Threads Enable (application.yml)

**설정 예시**:
```yaml
spring:
  threads:
    virtual:
      enabled: true  # Spring Boot 3.2+ 에서 지원

server:
  tomcat:
    threads:
      max: 200  # Virtual Threads 사용 시 필요 없으나 안전장치로 유지
```

**적용 결과**: V4 async pipeline에서 `CompletableFuture` + Virtual Threads로 719 RPS 달성 (Evidence: `/home/maple/MapleExpectation/docs/03_Technical_Guides/async-concurrency.md` lines 31-56)

### 5. Observability Integration (Micrometer + Prometheus)

**Evidence**: `/home/maple/MapleExpectation/docs/03_Technical_Guides/infrastructure.md` (lines 353-389)

**메트릭 명명 규칙**:
```java
// Good (Context7 Best Practice)
meterRegistry.counter("cache.hit", "layer", "L1").increment();
meterRegistry.counter("cache.miss").increment();

// Bad (CamelCase, snake_case 금지)
meterRegistry.counter("cacheHit").increment();
meterRegistry.counter("cache_hit").increment();
```

**필수 메트릭 항목**:
| 메트릭 | 용도 |
|--------|------|
| `cache.hit{layer=L1/L2}` | 캐시 히트율 모니터링 |
| `cache.miss` | Cache Stampede 빈도 확인 |
| `cache.lock.failure` | 락 경합 상황 감지 |
| `cache.l2.failure` | Redis 장애 감지 |

### 6. SpringDoc OpenAPI 3.x Integration

**Evidence**: `/home/maple/MapleExpectation/docs/03_Technical_Guides/infrastructure.md` (lines 633-726)

**의존성**:
```groovy
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13'
```

**설정**:
```java
@OpenAPIDefinition(
    info = @Info(
        title = "MapleExpectation API",
        version = "2.0.0",
        description = "메이플스토리 장비 강화 비용 계산 API"
    ),
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

---

## 제5장: 결과와 학습 (Result)

### 잘 된 점 (Success)

1. **성능 향상**: V4 async pipeline에서 719 RPS 달성 (V2 95 RPS 대비 7.6x 개선)
   - Virtual Threads로 10,000+ 동시 요청 처리 가능 확인

2. **안정성 확보**: P0 #238 CGLIB proxy NPE 이슈 해결
   - Filter Bean 수동 등록 패턴이 Spring Security 6.x 표준 Best Practice로 문서화

3. **Observability 내장**: Micrometer Observation으로 별도 의존성 없이 Distributed Tracing 구현
   - Prometheus + Grafana 스택과 직접 통합으로 운영 오버헤드 감소

4. **Jakarta EE 10**: 최신 표준 준수로 향후 3년 이상 플랫폼 지원 보장

### 아쉬운 점 (Lessons Learned)

1. **마이그레이션 리드타임**: `javax.*` → `jakarta.*` 패키지 전환에 예상보다 긴 시간 소요
   - Lombok 어노테이션 처리기와의 호환성 문제로 수동 검증 필요
   - **개선**: Lombok 1.18.30+로 업그레이드하여 Jakarta 완전 호환성 확보

2. **문서화 부족 초기**: Spring Security 6.x 변경사항에 대한 팀 학습 곡선 존재
   - **개선**: `infrastructure.md` Section 18에 Filter Best Practice 상세 문서화

3. **의존성 호환성**: Testcontainers 1.19.x에서 MySQL 8.0 컨테이너 시작 속도 저하
   - **개선**: Testcontainers 1.21.2로 업그레이드하여 Docker 29.x 호환성 문제 해결

### 현재 상태 (2026-02-19 기준)

- **운영 환경**: Spring Boot 3.5.4 안정적 운영 중
- **성능**: 240 RPS sustained on AWS t3.small (최대 1,000+ 동시 사용자 처리)
- **안정성**: P0 이슈 0건, P1 이슈 2건 해결 완료
- **테스트**: 619개 테스트 전체 통과 (Flaky Test 0건)

### 향후 로드맵

1. **Spring Boot 3.6.x**: 2026년 Q2에 예정된 3.6.0 출시 후 안정화 확인 시 업그레이드 검토
2. **GraalVM Native Image**: Virtual Threads + Native Image 조합으로 더 낮은 메모리 풋프린트 도전
3. **Kotlin Coroutines**: Spring Boot 3.x의 Kotlin Coroutine 공식 지원 활용 검토

---

## 관련 문서

- [Spring Boot 3.5 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes)
- [Jakarta EE 10 Specification](https://jakarta.ee/specifications/)
- [ADR-011: V4 Controller Optimization](../adr/ADR-011-controller-v4-optimization.md)
- [infrastructure.md Section 18: Spring Security 6.x Filter Best Practice](../03_Technical_Guides/infrastructure.md#18-spring-security-6x-filter-best-practice-context7)
- [async-concurrency.md Section 21: Async Non-Blocking Pipeline](../03_Technical_Guides/async-concurrency.md#21-async-non-blocking-pipeline-pattern-critical)

---

**상태**: Accepted (2026-02-19)

**승인자**: Architecture Team

**다음 검토 일자**: 2026-08-19 (6개월 후)
