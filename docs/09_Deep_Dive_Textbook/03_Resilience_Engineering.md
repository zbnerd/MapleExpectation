# 03. Resilience Engineering: 서킷 브레이커와 벌크헤드의 심화 학습

> **"모든 것은 결국 실패합니다. 문제는 '실패할 것인가'가 아니라 '실패 후 어떻게 복구할 것인가'입니다."**

---

## 1. The Problem (본질: 우리는 무엇과 싸우고 있는가?)

### 1.1 연쇄적 실패 (Cascading Failure)의 재앙

**상황**: 외부 API(Nexon Open API)가 응답하지 않음

```
Timeline (실패 전파):
T0: Nexon API 서버 장애 발생
T1: 요청 1,000개가 Nexon API에 대기 (Timeout 10초)
T2: Tomcat Thread Pool 200개가 모두 Nexon API 대기로 소진
T3: 다른 엔드포인트(장비 조회, 캐릭터 조회)도 처리 불가
T4: 전체 서비스 마비 (Cascade Failure) 💥
```

**문제의 본질**: "하나의 의존성 실패가 전체 시스템을 멈추게 한다"

### 1.2 성능 저하의 질문 (Performance Degradation)

**Fast Failure vs Slow Death:**

```
Option A: Circuit Breaker OFF (서서히 죽음)
├─ 요청 1-100: 정상 (100ms)
├─ 요청 101-200: 지연 (1,000ms)  ← 사용자 불편
├─ 요청 201-300: 타임아웃 (10,000ms) ← 사용자 이탈
└─ 요청 301+: 전체 마비

Option B: Circuit Breaker ON (빠른 실패)
├─ 요청 1-100: 정상 (100ms)
├─ 요청 101: 서킷 OPEN → 즉시 실패 (1ms)
└─ 요청 102+: Fallback으로 안전 처리 ✅
```

**핵심**: "사용자에게 '느린 응답'보다 '빠른 실패'가 낫다"

---

## 2. The CS Principle (원리: 이 코드는 무엇에 기반하는가?)

### 2.1 State Machine으로서의 서킷 브레이커

**3가지 상태 (State Machine):**

```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│   CLOSED ─(실패 임계치 도달)→ OPEN ─(시간 경과)→ HALF_OPEN │
│      ↑                           ↓                     │
│      └───────(복구 성공)─────────┴─────────────────────┘
│                                                         │
│  상태       조건                           동작         │
│  ─────────────────────────────────────────────────      │
│  CLOSED     정상 (실패율 < 임계치)      요청 통과      │
│  OPEN       서킷 열림                  즉시 실패      │
│  HALF_OPEN  복구 시도                  테스트 요청    │
└─────────────────────────────────────────────────────────┘
```

**Resilience4j 설정:**

```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .slidingWindowType(SlidingWindowType.COUNT_BASED)  // 카운트 기반
    .slidingWindowSize(100)                             // 최근 100개 요청
    .failureRateThreshold(50)                           // 실패율 50%
    .waitDurationInOpenState(Duration.ofSeconds(30))    // 30초 후 HALF_OPEN
    .permittedNumberOfCallsInHalfOpenState(10)          // HALF_OPEN에서 10개 시도
    .build();
```

### 2.2 Bulkhead Pattern (자원 격리)

**문제**: 하나의 외부 API 장애가 전체 Thread Pool을 낭비

**해결**: 자원별 격리 (Thread Pool 분리)

```
┌────────────────────────────────────────────────────────┐
│  공유 Thread Pool (나쁜 예)                          │
├────────────────────────────────────────────────────────┤
│  [Thread 1-200]                                       │
│    ├── 150개: Nexon API 대기 (장애) 💀                │
│    ├── 30개: DB 조회                                 │
│    └── 20개: 캐시 조회                               │
│  문제: Nexon API 장애 시 전체 스레드 고갈           │
└────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────┐
│  Bulkhead Pattern (좋은 예)                          │
├────────────────────────────────────────────────────────┤
│  [ThreadPool A: Nexon API - 50 threads]               │
│    └── 50개: Nexon API 대기 (장애라도 A만 영향)      │
│                                                       │
│  [ThreadPool B: DB - 100 threads]                     │
│    └── 정상 운영 ✅                                   │
│                                                       │
│  [ThreadPool C: Cache - 50 threads]                   │
│    └── 정상 운영 ✅                                   │
└────────────────────────────────────────────────────────┘
```

**Resilience4j Bulkhead:**

```java
ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
    .maxThreadPoolSize(50)      // 최대 스레드 수
    .coreThreadPoolSize(10)     // 코어 스레드 수
    .queueCapacity(100)         // 대기 큐 용량
    .build();
```

### 2.3 Fail-Fast 원칙

**정의**: "회복 불가능한 상황을 빠르게 감지하고, 즉시 실패를 반환"

**예시:**

```java
// 나쁜 예: 느린 실패
public Equipment getEquipment(Long id) {
    try {
        return nexonApi.getEquipment(id, 10, TimeUnit.SECONDS);  // 10초 대기
    } catch (TimeoutException e) {
        return null;  // 사용자는 10초 후 실패 확인
    }
}

// 좋은 예: 빠른 실패
@CircuitBreaker(name = "nexon", fallbackMethod = "getFromCache")
public Equipment getEquipment(Long id) {
    return nexonApi.getEquipment(id);  // 서킷 OPEN 시 즉시 실패
}

public Equipment getFromCache(Long id, Exception e) {
    return cache.get(id);  // Fallback으로 빠른 응답
}
```

---

## 3. Internal Mechanics (내부: Spring & Resilience4j는 어떻게 동작하는가?)

### 3.1 Resilience4j의 Sliding Window

**Count-based Sliding Window (카운트 기반):**

```
Window Size = 100, Failure Rate Threshold = 50%

Timeline:
Request 1-100:   [실패 40개, 성공 60개] → 실패율 40% (CLOSED)
Request 101:    [실패] → 실패율 41/100 = 41% (CLOSED)
Request 102-110: [실패 10개 연속] → 실패율 51/100 = 51% (OPEN!) 💥

Request 111:    [OPEN] → 즉시 CircuitBreakerOpenException
Request 112-140: [OPEN] → 30초간 모두 즉시 실패
Request 141:    [HALF_OPEN] → 테스트 요청 1개
Request 142-150: [HALF_OPEN] → 테스트 요청 9개 (성공 8개 이상 시 CLOSED)
```

**Time-based Sliding Window (시간 기반):**

```
Window Duration = 10초, Failure Rate Threshold = 50%

Timeline:
0s-10s:   [요청 100개, 실패 40개] → 실패율 40% (CLOSED)
5s-15s:   [요청 100개, 실패 60개] → 실패율 60% (OPEN!)
10s-20s:  [OPEN] → 모든 요청 즉시 실패
20s:      [HALF_OPEN] → 복구 시도 시작
```

### 3.2 AOP + Proxy로 Circuit Breaker 적용

**Spring AOP 흐름:**

```java
@CircuitBreaker(name = "nexon")
public Equipment getEquipment(Long id) {
    return nexonApi.call(id);
}

// Spring이 생성한 Proxy (개념적)
public class CircuitBreakerProxy implements EquipmentService {
    private final EquipmentService target;
    private final CircuitBreaker circuitBreaker;

    @Override
    public Equipment getEquipment(Long id) {
        // 1. 서킷 상태 확인
        if (!circuitBreaker.tryAcquirePermission()) {
            throw new CircuitBreakerOpenException("서킷이 열려 있습니다");
        }

        try {
            // 2. 실제 메서드 호출
            Equipment result = target.getEquipment(id);

            // 3. 성공 기록
            circuitBreaker.onSuccess();
            return result;
        } catch (Exception e) {
            // 4. 실패 기록
            circuitBreaker.onError(e);
            throw e;
        }
    }
}
```

### 3.3 Metrics Publishing (Prometheus 연동)

**Resilience4j가 수집하는 메트릭:**

```yaml
# Prometheus 형식
circuit_breaker_state{name="nexon",state="closed",} 0.0
circuit_breaker_state{name="nexon",state="open",} 0.0
circuit_breaker_state{name="nexon",state="half_open",} 1.0

circuit_breaker_failure_rate{name="nexon",} 45.0
circuit_breaker_buffered_calls{name="nexon",} 100
circuit_breaker_failed_calls{name="nexon",} 45
circuit_breaker_success_calls{name="nexon",} 55
```

**Grafana Dashboard Query:**

```promql
# 서킷 상태 변화 추이
circuit_breaker_state{state="open"}

# 실패율 모니터링
rate(circuit_breaker_failed_calls[5m]) / rate(circuit_breaker_total_calls[5m]) * 100

# 서킷 OPEN 발생 알림
increase(circuit_breaker_state{state="open"}[1m]) > 0
```

---

## 4. Alternative & Trade-off (비판: 왜 이 방법을 선택했는가?)

### 4.1 Resilience4j vs Hystrix

| 측정 항목 | Resilience4j | Hystrix (Deprecated) |
|---------|--------------|----------------------|
| **유지보수** | 활발 (Java 21 지원) | 2018년 이후 중단 |
| **성능** | 높음 (Zero Overhead) | 낮음 (RxJava 오버헤드) |
| **메트릭** | Micrometer 연동 | 자체 구현 |
| **Module 분리** | 독립 (CircuitBreaker만) | Monolithic |
| **동시성 모델** | Reactor 지원 | RxJava만 |

**선택 이유**: Hystrix는 2018년 Maintenance Mode 진입, Resilience4j는 Spring Boot 3.x의 표준

### 4.2 Retry vs Circuit Breaker

**Retry (재시도)**: 일시적 오류에 대해 재시도
```java
@Retry(name = "nexon", maxAttempts = 3)
public Equipment getEquipment(Long id) {
    return nexonApi.call(id);
}
```

**Circuit Breaker**: 만성적 오류에 대해 차단
```java
@CircuitBreaker(name = "nexon")
public Equipment getEquipment(Long id) {
    return nexonApi.call(id);
}
```

**조합 전략 (Retry + Circuit Breaker):**

```java
@Retry(name = "nexon", maxAttempts = 3)
@CircuitBreaker(name = "nexon")
public Equipment getEquipment(Long id) {
    return nexonApi.call(id);
}

// 실행 순서:
// 1. Circuit Breaker: 서킷 상태 확인
// 2. Retry: 3번 재시도 (모두 실패 시)
// 3. Circuit Breaker: 실패 기록 → OPEN 가능성
```

**주의사항**: Retry와 Circuit Breaker를 조합 시, 재시도 횟수가 실패율 계산에 포함되어 Open 조기 발생 가능

### 4.3 Thread Pool vs Semaphore Bulkhead

| 측정 항목 | ThreadPool Bulkhead | Semaphore Bulkhead |
|---------|---------------------|-------------------|
| **격리 레벨** | 별도 스레드 풀 | 공유 스레드 풀 |
| **Context Switching** | 많음 | 적음 |
| **메모리** | 높음 (스레드 스택) | 낮음 |
| **Timeout 지원** | ✅ Future.get(timeout) | ❌ 불가 |

**선택 가이드:**

- **I/O 작업 (외부 API 호출)**: ThreadPool Bulkhead (타임아웃 필요)
- **CPU 작업 (연산)**: Semaphore Bulkhead (가볍고 빠름)

```java
// Nexon API: I/O 작업 → ThreadPool Bulkhead
@Bulkhead(name = "nexon", type = Bulkhead.Type.THREADPOOL)
@CircuitBreaker(name = "nexon")
public Equipment getEquipment(Long id) {
    return nexonApi.call(id);  // 네트워크 I/O
}

// 계산 작업: CPU 작업 → Semaphore Bulkhead
@Bulkhead(name = "calculator", type = Bulkhead.Type.SEMAPHORE)
public long calculateCost(Equipment eq) {
    return dpCalculator.compute(eq);  // CPU 연산
}
```

---

## 5. The Interview Defense (방어: 100배 트래픽에서 어디가 먼저 터지는가?)

### 5.1 "트래픽이 100배 증가하면?"

**실패 포인트 예측:**

1. **Thread Pool 고갈** (最先)
   - 현재: ThreadPool 200개
   - 100배 트래픽: 평균 응답 시간 100ms → 10,000ms (요청 큐 적재)
   - **해결**: Auto-scaling (Horizontal Scaling), KEDA (Kubernetes Event-driven Autoscaling)

2. **Circuit Breaker Open Snowball** (次点)
   - 하나의 서킷이 OPEN → 연쇄적으로 다른 서킷도 OPEN
   - **해결**: Fallback 강화 (캐시, 기본값, Queueing)

3. **Metrics Storage Overflow**
   - Resilience4j 메트릭이 메모리 과다 점유
   - **해결**: Prometheus Push Gateway (비동기 전송)

### 5.2 "외부 API가 완전히 다운되면?"

**현재 시스템의 취약점:**

```java
@CircuitBreaker(name = "nexon", fallbackMethod = "getFromCache")
public Equipment getEquipment(Long id) {
    return nexonApi.call(id);
}

public Equipment getFromCache(Long id, Exception e) {
    Equipment cached = cache.get(id);
    if (cached == null) {
        throw new ServiceException("일시적 장애입니다. 잠시 후 다시 시도해주세요");
    }
    return cached;  // 오래된 데이터일 수 있음
}
```

**개선안: Stale-while-revalidate**

```java
public Equipment getFromCache(Long id, Exception e) {
    Equipment cached = cache.get(id);
    if (cached != null) {
        // 1. 캐시는 즉시 반환 (사용자 경험 우선)
        asyncRevalidate(id);  // 백그라운드로 갱신 시도
        return cached;
    }

    // 2. 캐시 Miss 시 기본값 반환
    return Equipment.EMPTY;  // 또는 큐잉
}

@Async
public void asyncRevalidate(Long id) {
    try {
        Equipment fresh = nexonApi.call(id);
        cache.put(id, fresh);
    } catch (Exception e) {
        log.warn("Background revalidation failed: {}", id);
    }
}
```

### 5.3 "서킷이 자꾸 OPEN되면?"

**상황**: FAILURE_RATE_THRESHOLD를 낮춰도 계속 OPEN

**원인 분석:**

1. **부하 테스트 부족**: 실제 운영보다 낮은 임계치 설정
2. **외부 API SLA 위반**: SLO를 준수하지 않는 공급자
3. **데드라인 설정**: 타임아웃이 너무 짧음

**개선안: Adaptive Threshold**

```java
// 동적 임계치 조정
public class AdaptiveCircuitBreaker {
    private double failureRateThreshold = 50.0;  // 기본 50%

    public void adjustThreshold(double currentFailureRate) {
        if (currentFailureRate > 80) {
            failureRateThreshold = 30.0;  // 보수적으로 낮춤
        } else if (currentFailureRate < 20) {
            failureRateThreshold = 70.0;  // 느슨하게 높임
        }
    }
}
```

---

## 요약: 핵심 take-away

1. **Circuit Breaker는 State Machine**: CLOSED → OPEN → HALF_OPEN 순환
2. **Bulkhead는 자원 격리**: 실패가 전체로 전파되지 않도록 격리
3. **Fail-Fast는 사용자 경험**: 10초 대기보다 1ms 실패가 낫다
4. **Resilience4j는 Hystrix의 후계자**: Spring Boot 3.x의 표준
5. **100배 트래픽 대비**: Auto-scaling, Fallback 강화, Adaptive Threshold

---

**다음 챕터 예고**: "DB 배치 INSERT는 왜 단건 INSERT보다 100배 빠른가? TCP 패킷과 트랜잭션의 물리학"
