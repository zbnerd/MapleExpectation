# ADR-002: Resilience4j Circuit Breaker 설정 전략

## 상태
Accepted

## 맥락 (Context)

외부 API(Nexon Open API) 장애 시 내부 시스템으로 장애가 전파되는 문제가 있었습니다.

관찰된 장애 패턴:
- 외부 API 응답 지연: 평소 200ms → 장애 시 30초+ timeout
- Thread Pool 고갈로 인한 전체 서비스 장애
- 장애 복구 후에도 지속적인 성능 저하

## 검토한 대안 (Options Considered)

### 옵션 A: 보수적 설정 (빠른 차단)
```yaml
failureRateThreshold: 30
slidingWindowSize: 5
waitDurationInOpenState: 60s
```
- 장점: 장애 전파 최소화
- 단점: 일시적 네트워크 지터에도 차단
- **결론: 너무 민감함**

### 옵션 B: 공격적 설정 (늦은 차단)
```yaml
failureRateThreshold: 80
slidingWindowSize: 20
waitDurationInOpenState: 10s
```
- 장점: 일시적 오류에 강건함
- 단점: 실제 장애 시 20건 실패 후에야 차단
- **결론: 반응이 너무 느림**

### 옵션 C: 균형 설정 + Marker Interface
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

**옵션 C의 균형 설정과 Marker Interface를 적용합니다.**

### 실제 application.yml 설정
```yaml
resilience4j:
  circuitbreaker:
    circuit-breaker-aspect-order: 400
    configs:
      default:
        registerHealthIndicator: true
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        ignoreExceptions:
          - maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker
        recordExceptions:
          - maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker
    instances:
      nexonApi:
        baseConfig: default
        minimumNumberOfCalls: 10
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

### 타임아웃 계층화 (Timeout Layering)
```yaml
# application.yml 실제 설정
resilience4j:
  timelimiter:
    instances:
      nexonApi:
        timeoutDuration: 28s  # 전체 상한: 3*(3s+5s) + 2*0.5s + 3s = 28s

nexon:
  api:
    connect-timeout: 3s      # TCP 연결 타임아웃
    response-timeout: 5s     # HTTP 응답 타임아웃
```

## 결과 (Consequences)

- 외부 API 장애 시 내부 시스템 영향: 최대 10건 → 이후 차단
- 비즈니스 예외(404 등)는 Circuit 상태에 영향 없음
- 장애 복구 시 10초 내 자동 정상화
- Chaos Test N06에서 검증 완료

## 참고 자료
- Resilience4j 공식 문서
- `maple.expectation.global.error.exception.marker/`
- `maple.expectation.config.ResilienceConfig`
- `docs/01_Chaos_Engineering/06_Nightmare/`
