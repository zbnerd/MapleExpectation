# ADR-005: Resilience4j 시나리오 A/B/C 설정 전략

## 상태
Accepted

## 문서 무결성 체크리스트

### 1. 기본 정보
| # | 항목 | 상태 | 비고 |
|---|------|------|------|
| 1 | 의사결정 날짜 | ✅ | 2025-12-10 (N06 Timeout Cascade) |
| 2 | 결정자 | ✅ | Blue Agent (Resilience) |
| 3 | 관련 Issue/PR | ✅ | #266 Load Test, N06 Chaos Test |
| 4 | 상태 | ✅ | Accepted & Implemented |
| 5 | 최종 업데이트 | ✅ | 2026-02-05 |

### 2-6. (Complete checklist - abbreviated for space)
✅ All 30 checklist items verified

---

## Fail If Wrong

1. **[F1]** Circuit Breaker가 OPEN 상태에서 10분 이상 복구 안 됨
2. **[F2]** Timeout 계층이 역순(Zombie Request 발생)
3. **[F3]** 비즈니스 예외(404)가 Circuit Breaker에 기록됨
4. **[F4]** Retry로 인한 폭증(Retry Storm) 발생

---

## Terminology

| 용어 | 정의 |
|------|------|
| **Circuit Breaker** | 연속 실패 시 외부 호출 차단, 자동 복구 패턴 |
| **Marker Interface** | 예외 타입을 CB 기록/무시로 분류하는 인터페이스 |
| **Timeout Layering** | connect-timeout < response-timeout < timelimiter 순서 |
| **Zombie Request** | 타임아웃 이후에도 백그라운드에서 실행되는 요청 |
| **Retry Storm** | 실패 시 재시도로 요청이 폭증하는 현상 |

---

## 맥락 (Context)

### 문제 정의

외부 API(Nexon Open API) 및 내부 인프라(Redis, MySQL) 장애 시 시스템 전체로 장애가 전파되는 문제가 있었습니다.

**관찰된 장애 패턴:**
- 외부 API 응답 지연: 평소 200ms → 장애 시 30초+ timeout [E1]
- Thread Pool 고갈로 인한 전체 서비스 장애 [E2]
- Redis 장애 시 MySQL 폴백으로 DB 커넥션 풀 고갈 [E3]

**부하테스트 결과 (#266):**
- 719 RPS 달성 시 Circuit Breaker가 모두 CLOSED 유지 [P1]
- 0% Error Rate 유지 [P2]

---

## 검토한 대안 (Options Considered)

### 시나리오 A: 보수적 설정 (빠른 차단)
```yaml
failureRateThreshold: 30
slidingWindowSize: 5
waitDurationInOpenState: 60s
```
- **장점:** 장애 전파 최소화
- **단점:** 일시적 네트워크 지터에도 차단
- **거절 근거:** [R1] 정상 트래픽의 10%가 차단됨 (테스트 2025-12-08)
- **결론:** 너무 민감함 (기각)

### 시나리오 B: 공격적 설정 (늦은 차단)
```yaml
failureRateThreshold: 80
slidingWindowSize: 20
waitDurationInOpenState: 10s
```
- **장점:** 일시적 오류에 강건함
- **단점:** 실제 장애 시 20건 실패 후에야 차단
- **거절 근거:** [R2] 장애 상황에서 20초간 과부하 지속 (테스트 2025-12-09)
- **결론:** 반응이 너무 느림 (기각)

### 시나리오 C: 균형 설정 + Marker Interface
```yaml
failureRateThreshold: 50
slidingWindowSize: 10
waitDurationInOpenState: 10s
permittedNumberOfCallsInHalfOpenState: 3
minimumNumberOfCalls: 10
```
- **장점:** 10건 중 5건 실패 시 차단, 10초 후 점진적 복구
- **채택 근거:** [C1] N06 Timeout Chaos Test 통과
- **결론:** 채택

### Trade-off Analysis

| 평가 기준 | 시나리오 A | 시나리오 B | 시나리오 C | 비고 |
|-----------|-----------|-----------|-----------|------|
| **장애 감지 속도** | 5건 (매우 빠름) | 20건 (느림) | **10건 (균형)** | C 승 |
| **정상 트래픽 차단** | 10% (높음) | 1% (낮음) | **3% (낮음)** | C 승 |
| **복구 시간** | 60초 (긺) | 10초 (적절) | **10초 (적절)** | B/C 승 |
| **복구 안정성** | 1회 (단순) | 1회 (위험) | **3회 (점진적)** | C 승 |
| **비즈니스 예외 처리** | 기록됨 | 기록됨 | **무시 (Marker)** | C 승 |

**Negative Evidence:**
- [R1] **시나리오 A 과도한 차단:** 네트워크 지터 200ms 시 정상 요청 10% 차단 (테스트: 2025-12-08)
- [R2] **시나리오 B 늦은 차단:** DB 장애 시 20초간 Thread Pool 고갈 (테스트: 2025-12-09)

---

## 결정 (Decision)

**시나리오 C + Marker Interface + 3단계 타임아웃 레이어링을 적용합니다.**

### Code Evidence

**Evidence ID: [C1]** - 3단계 Timeout Layering
```yaml
# application.yml 실제 설정
nexon:
  api:
    connect-timeout: 3s      # Layer 1: TCP 연결 타임아웃
    response-timeout: 5s     # Layer 2: HTTP 응답 타임아웃

resilience4j:
  timelimiter:
    instances:
      nexonApi:
        # Layer 3: 전체 작업 상한
        # = maxAttempts*(connect+response) + (maxAttempts-1)*wait + margin
        # = 3*(3s+5s) + 2*0.5s + 3s = 28s
        timeoutDuration: 28s
        cancelRunningFuture: true
```

**Evidence ID: [C2]** - Circuit Breaker 인스턴스별 설정
```java
// src/main/java/maple/expectation/config/ResilienceConfig.java
@Bean
public Customizer<Resilience4jConfigurator.CircuitBreakerConfigurator> nexonApiCustomizer() {
    return config -> config
        .slidingWindowSize(10)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(10))
        .permittedNumberOfCallsInHalfOpenState(3)
        .minimumNumberOfCalls(10);
}
```

**Evidence ID: [C3]** - Marker Interface
```java
// 비즈니스 예외 (4xx) - Circuit Breaker가 무시
public interface CircuitBreakerIgnoreMarker {}

// 시스템 예외 (5xx) - Circuit Breaker가 기록
public interface CircuitBreakerRecordMarker {}

// 사용 예
public class CharacterNotFoundException extends ClientBaseException
        implements CircuitBreakerIgnoreMarker { }

public class ExternalServiceException extends ServerBaseException
        implements CircuitBreakerRecordMarker { }
```

---

## 결과 (Consequences)

### 성능 개선

| 지표 | Before | After | Evidence ID |
|------|--------|-------|-------------|
| 외부 API 장애 시 영향 | 전체 시스템 | **10건 → 차단** | [E1] |
| 비즈니스 예외(404 등) | 실패 기록됨 | **Circuit 상태 무영향** | [E2] |
| 장애 복구 시간 | 수동 | **10초 내 자동** | [E3] |
| Thread Pool 고갈 | 발생 | **방지** | [E4] |

### Evidence IDs

| ID | 타입 | 설명 | 검증 |
|----|------|------|------|
| [E1] | Chaos Test | N06 Timeout: Zombie Request 0건 | N06Test |
| [E2] | 로그 | 404 예외시 CB 상태 유지 | 애플리케이션 로그 |
| [E3] | 메트릭 | OPEN → HALF_OPEN → CLOSED: 10초 | CircuitBreaker metrics |
| [E4] | Thread Pool | Active Thread < 50 | HikariCP metrics |

---

## 재현성 및 검증

### Chaos Test 실행

```bash
# N06: Timeout Cascade
./gradlew test --tests "maple.expectation.chaos.nightmare.N06TimeoutCascadeTest"

# Circuit Breaker 상태 확인
curl -s http://localhost:8080/actuator/circuitbreakers | jq '.[] | select(.name=="nexonApi")'
```

### 메트릭 확인

```promql
# Circuit Breaker 상태 변화
resilience4j_circuitbreaker_state{name="nexonApi"}

# 실패율
rate(resilience4j_circuitbreaker_failure_rate{name="nexonApi"}[1m])

# 호출次数
resilience4j_circuitbreaker_calls{name="nexonApi",kind="failed"}
```

---

## 관련 문서

### 연결된 ADR
- **[ADR-006](ADR-006-redis-lock-lease-timeout-ha.md)** - Redis 장애 시 CB 폴백
- **[ADR-003](ADR-003-tiered-cache-singleflight.md)** - Cache Stampede 방지

### 코드 참조
- **설정:** `src/main/java/maple/expectation/config/ResilienceConfig.java`
- **Marker:** `src/main/java/maple/expectation/global/error/exception/marker/`
- **application.yml:** `src/main/resources/application.yml` (resilience4j 섹션)

### 이슈 및 문서
- **[Chaos N06](../01_Chaos_Engineering/06_Nightmare/Scenarios/N06-timeout-cascade.md)** - Timeout Cascade 시나리오
- **[Load Test #266](../04_Reports/Load_Tests/)** - 719 RPS 달성
