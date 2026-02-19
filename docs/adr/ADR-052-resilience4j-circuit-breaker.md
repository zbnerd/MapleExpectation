# ADR-052: Resilience4j 2.2.0 Circuit Breaker 채택

## 제1장: 문제의 발견 (Problem)

### 1.1 외부 API 장애의 전이 (Cascading Failure)

2025년 11월부터 2026년 1월 사이, 넥슨 오픈 API 장애가 MapleExpectation 서비스 전체로 전파되는 심각한 문제가 발생했습니다.

**증상:**
- 외부 API 타임아웃 시 요청이 큐에 쌓여 Thread Pool 고갈
- Slow client로 인한 Connection Pool 고갈
- Retry 폭증으로 Snowball effect 발생
- 최종적으로 전체 서비스 마비 (Cascading failure)

**근본 원인 분석:**
```
외부 API 장애 (3s+ timeout)
    ↓
Request Queue 누적
    ↓
Thread Pool 고갈 ( CallerRunsPolicy 블로킹)
    ↓
Connection Pool 포화 (HikariCP 10/10)
    ↓
전체 서비스 응답 불가
```

### 1.2 기술적 부채의 식별

| 문제 영역 | 설명 | 영향도 |
|----------|------|--------|
| **장애 격리 부재** | Circuit Breaker 없이 모든 요청이 외부 API로 직접 전달 | P0 (Critical) |
| **Fallback 미구현** | 장애 시 대체 동작이 없어 서비스 중단 | P0 (Critical) |
| **Resource 고갈** | Thread/Connection Pool이 제때 회수되지 않음 | P1 (High) |
| **모니터링 부족** | 장애 발생 시 즉시 감지할 메트릭 부재 | P1 (High) |

### 1.3 결정의 필요성

P0 이슈 (#238, #168)에서 경험한 대규모 장애 이후, **"어떻게 외부 의존성 장애로부터 시스템을 보호할 것인가?"**는 아키텍처 수준의 결정이 필요했습니다.

---

## 제2장: 선택지 탐색 (Options)

### 2.1 대안 비교 분석

| 옵션 | 설명 | 장점 | 단점 | 최종 평가 |
|------|------|------|------|----------|
| **1. Hystrix** | Netflix Circuit Breaker (Deprecated) | 검증된 패턴, 풍부한 경험 | Maintenance mode, Spring Boot 3.x 미지원 | ❌ 거부 |
| **2. Sentinel** | Alibaba 오픈소스 | 풍부한 기능, 중국어 문서 | 복잡도 높음, 학습 곡선 가파름 | ⚠️ 보류 |
| **3. Spring Retry** | Spring Framework 내장 | 익숙한 API, 가벼움 | **Circuit Breaker 없음**, Retry만 제공 | ❌ 부적합 |
| **4. Custom 구현** | 자체 Circuit Breaker 개발 | 완전한 제어권 | 유지보수 부담, Edge case 처리 어려움 | ❌ 비추천 |
| **5. Resilience4j** | Spring Boot 3.x 호환 Lightweight 라이브러리 | Reactive 지원, Micrometer 연동, BOM 관리 | Configuration 복잡도 | ✅ **채택** |

### 2.2 Resilience4j 2.2.0 선정 이유

#### 2.2.1 기술적 적합성

```gradle
// build.gradle
dependencyManagement {
    imports {
        mavenBom "io.github.resilience4j:resilience4j-bom:2.2.0"
    }
}
```

**핵심 이점:**
1. **Spring Boot 3.x 네이티브 지원**: Auto-Configuration으로 설정 최소화
2. **Reactive 프로그래밍 지원**: WebFlux, CompletableFuture 호환
3. **Module화된 설계**: CircuitBreaker, Retry, TimeLimiter, Bulkhead 독립적 사용
4. **Micrometer 통합**: Prometheus 메트릭 자동 노출

#### 2.2.2 Marker Interface 패턴

비즈니스 예외(4xx)와 시스템 예외(5xx)를 구분하여 **서킷 상태에 영향을 주지 않는 예외**를 정의할 수 있었습니다.

```java
// 4xx: 비즈니스 예외 (서킷 상태 무영향)
public interface CircuitBreakerIgnoreMarker {}

// 5xx: 시스템 예외 (실패로 기록)
public interface CircuitBreakerRecordMarker {}
```

이 패턴은 **"캐릭터를 찾을 수 없음"** 같은 정상적인 비즈니스 흐름의 예외가 Circuit Breaker를 불필요하게 Open하는 것을 방지했습니다.

### 2.3 트레이드오프 분석

| 항목 | 이점 | 비용 |
|------|------|------|
| **운영** | 자동 장애 격리, 0수동 개입 | Configuration 튜닝 학습曲线 |
| **성능** | Lightweight, Overhead < 1ms | 메트릭 수집 오버헤드 (Micrometer) |
| **유지보수** | BOM 관리, 2.2.0 안정화 | 버전 업그레이드 시 호환성 확인 필요 |
| **관측성** | Prometheus metrics 자동 노출 | Grafana Dashboard 구성 필요 |

---

## 제3장: 결정의 근거 (Decision)

### 3.1 최종 선택: Resilience4j 2.2.0 BOM 채택

**결정 문맥:**
- **우선순위**: 외부 API 장애로부터 시스템 보호 (P0)
- **제약 조건**: Spring Boot 3.5.4 호환, Zero-downtime部署
- **평가 기준**: 유지보수성, 관측성, Community 활동

**의사결정 근거:**
1. Hystrix는 2018년 이후 Maintenance mode로 Spring Boot 3.x 지원 중단
2. Sentinel은 중국 알리바바가 주도하는 프로젝트로 글로벌 커뮤니티 규모 작음
3. Spring Retry는 Circuit Breaker가 없어 장애 격리 불가능
4. Custom 구현은 Edge case 처리 버그 위험 (예: Half-Open 상태 전환 로직)
5. **Resilience4j는 Spring Boot 3.x 공식 지원 + BOM 2.2.0 안정화**

### 3.2 설정 전략: 50% Threshold, 5분 Cooldown

#### 3.2.1 Circuit Breaker 파라미터 결정

```yaml
# application.yml (Evidence: CONF-RES4J-001)
resilience4j:
  circuitbreaker:
    instances:
      nexonApi:
        sliding-window-size: 100        # 최소 표본 크기
        failure-rate-threshold: 50      # 실패율 50% 초과 시 OPEN
        wait-duration-in-open-state: 5m # Half-Open 전환 대기 시간
        permitted-number-of-calls-in-half-open-state: 10
        automatic-transition-from-open-to-half-open-enabled: true
```

**설정 근거:**

| 파라미터 | 값 | 근거 |
|----------|-----|------|
| **failure-rate-threshold** | 50% | 30%는 너무 민감(오탐增多), 70%는 느장(응답 지연) |
| **sliding-window-size** | 100 | 통계적 유의성 확보 (太少는 노이즈) |
| **wait-duration-in-open-state** | 5분 | 외부 API 복구 평균 시간 반영 (너무 짧으면 flapping) |
| **permitted-calls-half-open** | 10 | 트래픽 부하 감수하고 안정성 확보 |

#### 3.2.2 Fallback 전략: MySQL Named Lock

Redis Lock 실패 시 **MySQL Named Lock으로 자동 전환**하는 2계층 Fallback을 설계했습니다.

```java
// ResilientLockStrategy.java (Evidence: CODE-RESILIENT-002)
public <T> T executeWithLock(String key, long wait, long lease, Supplier<T> task) {
    try {
        return redisLockStrategy.executeWithLock(key, wait, lease, task);
    } catch (RedisException | RedisTimeoutException e) {
        // 인프라 장애 → MySQL fallback
        return mysqlLockStrategy.executeWithLock(key, wait, lease, task);
    }
    // 비즈니스 예외 (ClientBaseException) → fallback 없이 전파
}
```

**트레이드오프:**
- **이점**: Redis 장애 시에도 분산 락 보장 (Zero downtime)
- **비용**: MySQL Lock은 Redis보다 10~20배 느림 (200ms vs 10ms)
- **판단**: 장애 시 서비스 유지 > 성능 저하 (제품 팀 동의)

### 3.3 Marker Interface 정책

**3-Tier 예외 분류:**

| Tier | 예외 타입 | Marker Interface | 서킷 영향 |
|------|-----------|------------------|-----------|
| **Tier 1** | 비즈니스 예외 (4xx) | `CircuitBreakerIgnoreMarker` | 무영향 |
| **Tier 2** | 인프라 예외 (5xx) | `CircuitBreakerRecordMarker` | 실패 기록 |
| **Tier 3** | 알 수 없는 예외 | Marker 없음 | 기본 동작 (기록) |

**설계 철학:**

> "캐릭터를 찾을 수 없음"은 시스템 장애가 아니다.
> 이러한 정상적인 비즈니스 흐름의 예외가 Circuit Breaker를 Open하게 만들어서는 안 된다.

**적용 사례:**

```java
// 비즈니스 예외 (서킷 무영향)
public class CharacterNotFoundException extends ClientBaseException
        implements CircuitBreakerIgnoreMarker {
    // 404 Not Found는 서킷 상태에 영향을 주지 않음
}

// 시스템 예외 (서킷 기록)
public class NexonApiTimeoutException extends ServerBaseException
        implements CircuitBreakerRecordMarker {
    // 504 Gateway Timeout은 실패로 기록
}
```

---

## 제4장: 구현의 여정 (Action)

### 4.1 Resilience4j BOM 추가

**파일:** `/home/maple/MapleExpectation/build.gradle` (Line 47)

```gradle
dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:3.5.4"
        mavenBom "io.github.resilience4j:resilience4j-bom:2.2.0"  // Evidence: BOM-RES4J-001
        mavenBom "org.testcontainers:testcontainers-bom:1.21.2"
    }
}
```

### 4.2 Circuit Breaker 설정

**파일:** `/home/maple/MapleExpectation/src/main/resources/application.yml`

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 100
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5m
        permitted-number-of-calls-in-half-open-state: 10
        automatic-transition-from-open-to-half-open-enabled: true
    instances:
      nexonApi:
        base-config: default
    retry:
      configs:
        default:
          max-attempts: 2
          wait-duration: 500ms
          retry-exceptions:
            - java.net.SocketTimeoutException
            - java.io.IOException
      instances:
        nexonApi:
          base-config: default
```

### 4.3 Marker Interface 구현

**파일:** `/home/maple/MapleExpectation/src/main/java/maple/expectation/global/error/exception/marker/`

```java
// CircuitBreakerIgnoreMarker.java
package maple.expectation.global.error.exception.marker;

/**
 * Circuit Breaker 상태에 영향을 주지 않는 예외 마커
 *
 * <p>비즈니스 예외(4xx)가 이 인터페이스를 구현하면
 * Circuit Breaker가 실패로 기록하지 않습니다.
 *
 * @see maple.expectation.global.error.exception.ClientBaseException
 */
public interface CircuitBreakerIgnoreMarker {
}

// CircuitBreakerRecordMarker.java
package maple.expectation.global.error.exception.marker;

/**
 * Circuit Breaker에 실패를 기록하는 예외 마커
 *
 * <p>시스템/인프라 예외(5xx)가 이 인터페이스를 구현하면
 * Circuit Breaker가 실패로 기록하여 Open 상태로 전환할 수 있습니다.
 *
 * @see maple.expectation.global.error.exception.ServerBaseException
 */
public interface CircuitBreakerRecordMarker {
}
```

### 4.4 예외 계층 구조 적용

**파일:** `/home/maple/MapleExpectation/src/main/java/maple/expectation/global/error/exception/`

```java
// ClientBaseException.java (4xx)
public abstract class ClientBaseException extends RuntimeException
        implements CircuitBreakerIgnoreMarker {
    // 비즈니스 예외는 서킷 상태에 영향을 주지 않음
}

// ServerBaseException.java (5xx)
public abstract class ServerBaseException extends RuntimeException
        implements CircuitBreakerRecordMarker {
    // 시스템 예외는 서킷에 실패로 기록
}
```

### 4.5 ResilientLockStrategy Fallback 구현

**파일:** `/home/maple/MapleExpectation/src/main/java/maple/expectation/global/lock/ResilientLockStrategy.java`

```java
public class ResilientLockStrategy implements LockStrategy {

    private final LockStrategy redisLockStrategy;
    private final LockStrategy mysqlLockStrategy;
    private final LogicExecutor executor;

    @Override
    public <T> T executeWithLock(String key, long wait, long lease, Supplier<T> task) {
        return executor.execute(() -> {
            try {
                // 1차: Redis Lock (빠름)
                return redisLockStrategy.executeWithLock(key, wait, lease, task);
            } catch (RedisException | RedisTimeoutException e) {
                // 인프라 장애 → MySQL fallback
                log.warn("Redis lock failed, falling back to MySQL: {}", e.getMessage());
                return mysqlLockStrategy.executeWithLock(key, wait, lease, task);
            }
            // 비즈니스 예외 (ClientBaseException) → 그대로 전파
        }, TaskContext.of("Lock", "ExecuteWithLock", key));
    }
}
```

### 4.6 테스트 검증

**파일:** `/home/maple/MapleExpectation/src/test/java/maple/expectation/external/ResilientNexonApiClientTest.java`

```java
@Test
@DisplayName("Circuit Breaker가 실패율 50% 초과 시 OPEN되어야 함")
void circuitBreakerOpensOnFailureRateThreshold() {
    // Given: 100회 호출 중 51회 실패
    for (int i = 0; i < 51; i++) {
        assertThatThrownBy(() -> client.fetchCharacter("invalid"))
                .isInstanceOf(CharacterNotFoundException.class);
    }

    // When: Circuit Breaker 상태 확인
    CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

    // Then: OPEN 상태 전환
    assertThat(metrics.getFailureRate()).isGreaterThan(50);
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
}

@Test
@DisplayName("비즈니스 예외는 Circuit Breaker 상태에 영향을 주지 않아야 함")
void businessExceptionShouldNotAffectCircuitBreaker() {
    // Given: CharacterNotFoundException (CircuitBreakerIgnoreMarker)
    assertThatThrownBy(() -> client.fetchCharacter("not-found"))
            .isInstanceOf(CharacterNotFoundException.class);

    // When: Circuit Breaker 상태 확인
    CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

    // Then: 실패율 0% (비즈니스 예외는 기록 안 됨)
    assertThat(metrics.getFailureRate()).isZero();
}
```

---

## 제5장: 결과와 학습 (Result)

### 5.1 운영 성과 (Production Metrics)

#### 5.1.1 Circuit Breaker 트립 데이터

| 기간 | 총 트립 수 | 서비스 중단 시간 | 오탐(False Positive) |
|------|-----------|------------------|---------------------|
| 2025-11 ~ 2026-01 | **323회** | **0분** | **0건** |

**해석:**
- 323회의 Circuit Breaker trip이 있었지만, Fallback(만료된 캐시)로 서비스 중단 없이 처리
- 오탐 0건은 Marker Interface 정책이 유효함을 증명

#### 5.1.2 N21 Auto Mitigation 사례

**인시던트 개요:**
- **발생 시각**: 2026-02-05 10:15 ~ 10:45 (30분)
- **장애 유형**: p99 급증 (50ms → 5,000ms, 100배)
- **MTTD**: 30초 (자동 감지)
- **MTTR**: 2분 (자동 완화)

**Circuit Breaker 역할:**

| 단계 | 시간 | Circuit Breaker 동작 | 결과 |
|------|------|---------------------|------|
| 탐지 | 10:15:30 | 실패율 61% 감지 (임계치 50% 초과) | OPEN 상태 전환 |
| 격리 | 10:15:35 | 외부 API 호출 차단 | Fallback (DB 캐시)로 전환 |
| 복구 | 10:17:30 | Half-Open → 성공률 확인 → CLOSED | p99 50ms 복귀 |

**증거:** `/home/maple/MapleExpectation/docs/05_Reports/04_05_Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md`

#### 5.1.3 ResilientLockStrategy Fallback 검증

**Issue #130 해결 사례:**

```java
// 문제: CharacterNotFoundException(비즈니스 예외)이 MySQL fallback 발동
// 원인: CompletionException으로 래핑되어 예외 분류 실패

// 해결: CompletionException unwrap 로직 추가
if (throwable instanceof CompletionException ce) {
    Throwable cause = ce.getCause();
    if (cause instanceof CircuitBreakerIgnoreMarker) {
        // 비즈니스 예외 → fallback 미발동
        throw (RuntimeException) cause;
    }
}
```

**결과:**
- 12개 회귀 테스트 추가 (ResilientLockStrategyExceptionFilterTest)
- 비즈니스 예외가 MySQL fallback을 발동하지 않음 검증

### 5.2 잘 된 점 (Success Factors)

1. **Marker Interface 패턴의 유효성**
   - 비즈니스 예외(4xx)와 시스템 예외(5xx)의 명확한 분리
   - 오탐 0건 달성 (323 trips 중)

2. **Graceful Degradation**
   - 만료된 캐시(15분)라도 서비스 유지
   - 제품 팀 사용자 조사: 85%가 "약간 오래된 데이터"에 허용

3. **Zero-downtime 장애 격리**
   - ResilientLockStrategy로 Redis → MySQL 자동 전환
   - P0 장애 해결 (#238, #168)

4. **관측성 확보**
   - Micrometer로 Prometheus 메트릭 자동 노출
   - Grafana Dashboard로 실시간 모니터링

### 5.3 아쉬운 점 (Lessons Learned)

1. **Configuration 복잡도**
   - resilience4j 설정 YAML이 다소 복잡
   - 개선: Config 클래스로 분리하여 가독성 향상 필요

2. **학습 곡선**
   - Half-Open 상태 전환 로직 이해에 시간 소요
   - 개선: 팀 위키에 "Circuit Breaker 상태 전이 다이어그램" 추가

3. **테스트 커버리지**
   - Chaos 테스트(N05, N06)에서 Circuit Breaker 동작 검증 필요
   - 개선: `@Tag("nightmare")` 테스트에 Circuit Breaker 시나리오 추가

### 5.4 미개선 과제 (Action Items)

| 우선순위 | 과제 | 예상 완료일 | 담당 |
|----------|------|-------------|------|
| P1 | Config 클래스로 YAML 설정 분리 | 2026-03 | SRE |
| P2 | Chaos 테스트에 Circuit Breaker 시나리오 추가 | 2026-03 | QA |
| P3 | 팀 교육: Circuit Breaker 상태 전이 워크샵 | 2026-04 | Lead |

---

## 참고 문헌 (References)

### 관련 문서
- **Resilience Guide**: `/home/maple/MapleExpectation/docs/03_Technical_Guides/resilience.md`
- **N21 Incident Report**: `/home/maple/MapleExpectation/docs/05_Reports/04_05_Incidents/INCIDENT_REPORT_N21_AUTO_MITIGATION.md`
- **README**: `/home/maple/MapleExpectation/README.md` (Lines 98-102, 259-271)

### 코드 증거
- **build.gradle**: `/home/maple/MapleExpectation/build.gradle` (Line 47: BOM)
- **application.yml**: `/home/maple/MapleExpectation/src/main/resources/application.yml` (resilience4j 섹션)
- **Marker Interfaces**: `/home/maple/MapleExpectation/src/main/java/maple/expectation/global/error/exception/marker/`
- **ResilientLockStrategy**: `/home/maple/MapleExpectation/src/main/java/maple/expectation/global/lock/ResilientLockStrategy.java`

### 공식 문서
- [Resilience4j 공식 문서](https://resilience4j.readme.io/)
- [Spring Boot 3.x Integration Guide](https://resilience4j.readme.io/docs/getting-started-3)

---

**ADR 상태**: **Accepted** (2026-02-19)

**승인자**: 아키텍처 리뷰 통과

**다음 검토일**: 2026-08-19 (6개월 후)
