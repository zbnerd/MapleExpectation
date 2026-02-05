# ADR-005: Resilience4j 시나리오 A/B/C 설정 전략

## 상태
Accepted

## 맥락 (Context)

외부 API(Nexon Open API) 및 내부 인프라(Redis, MySQL) 장애 시 시스템 전체로 장애가 전파되는 문제가 있었습니다.

**관찰된 장애 패턴:**
- 외부 API 응답 지연: 평소 200ms → 장애 시 30초+ timeout
- Thread Pool 고갈로 인한 전체 서비스 장애
- Redis 장애 시 MySQL 폴백으로 DB 커넥션 풀 고갈

**부하테스트 결과 (#266):**
- 719 RPS 달성 시 Circuit Breaker가 모두 CLOSED 유지
- 0% Error Rate 유지

**README 문제 정의:**
> 외부 API 3초 지연 시 Thread Pool 고갈 → Resilience4j + 비동기 파이프라인 필요

## 검토한 대안 (Options Considered)

### 시나리오 A: 보수적 설정 (빠른 차단)
```yaml
failureRateThreshold: 30
slidingWindowSize: 5
waitDurationInOpenState: 60s
```
- 장점: 장애 전파 최소화
- 단점: 일시적 네트워크 지터에도 차단
- **결론: 너무 민감함**

### 시나리오 B: 공격적 설정 (늦은 차단)
```yaml
failureRateThreshold: 80
slidingWindowSize: 20
waitDurationInOpenState: 10s
```
- 장점: 일시적 오류에 강건함
- 단점: 실제 장애 시 20건 실패 후에야 차단
- **결론: 반응이 너무 느림**

### 시나리오 C: 균형 설정 + Marker Interface
```yaml
failureRateThreshold: 50
slidingWindowSize: 10
waitDurationInOpenState: 10s
permittedNumberOfCallsInHalfOpenState: 3
minimumNumberOfCalls: 10
```
- 장점: 10건 중 5건 실패 시 차단, 10초 후 점진적 복구
- **결론: 채택**

## 결정 (Decision)

**시나리오 C + Marker Interface + 3단계 타임아웃 레이어링을 적용합니다.**

### 3단계 타임아웃 레이어링
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

### Circuit Breaker 인스턴스별 설정
```yaml
resilience4j:
  circuitbreaker:
    instances:
      nexonApi:            # 외부 API 전용
        baseConfig: default
        minimumNumberOfCalls: 10

      likeSyncDb:          # DB Batch 전용
        slidingWindowSize: 5
        failureRateThreshold: 60
        waitDurationInOpenState: 30s
        minimumNumberOfCalls: 3

      redisLock:           # Redis 분산 락 전용
        slidingWindowSize: 20
        failureRateThreshold: 60
        waitDurationInOpenState: 30s
        recordExceptions:
          - org.redisson.client.RedisException
          - org.redisson.client.RedisTimeoutException
          - maple.expectation.global.error.exception.DistributedLockException
```

### Marker Interface로 예외 분류
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

### Retry + Circuit Breaker 조합
```yaml
resilience4j:
  retry:
    retry-aspect-order: 399  # Retry가 CircuitBreaker를 감싸도록
    instances:
      nexonApi:
        maxAttempts: 3
        waitDuration: 500ms
        retryExceptions:
          - java.util.concurrent.TimeoutException
          - io.netty.handler.timeout.ReadTimeoutException
          - maple.expectation.global.error.exception.ExternalServiceException
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| 외부 API 장애 시 영향 | 전체 시스템 | **10건 → 이후 차단** |
| 비즈니스 예외(404 등) | 실패 기록됨 | **Circuit 상태 무영향** |
| 장애 복구 시간 | 수동 | **10초 내 자동** |
| Thread Pool 고갈 | 발생 | **방지** |

**Chaos Test 검증 (#266):**
- N06 Timeout Cascade: 타임아웃 계층 정렬로 Zombie Request 방지
- 부하테스트: 모든 Circuit Breaker CLOSED 유지

## 참고 자료
- `maple.expectation.config.ResilienceConfig`
- `maple.expectation.global.error.exception.marker/`
- `src/main/resources/application.yml`
- `docs/01_Chaos_Engineering/06_Nightmare/N06_Timeout_Cascade/`
