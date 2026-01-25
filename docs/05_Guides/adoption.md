# Adoption Guide (도입 가이드)

> MapleExpectation 아키텍처 패턴을 프로젝트에 적용하는 단계별 가이드

---

## Step 0: Fit Check (적합성 확인)

### 이 아키텍처가 필요한가?

아래 중 **2개 이상** 해당하면 단순 최적화가 아닌 아키텍처 수준의 해결책이 필요합니다.

| Check | Condition | Description |
|:-----:|-----------|-------------|
| ☐ | payload > 100KB | 요청당 JSON 크기가 100KB 이상 |
| ☐ | 외부 API p95 > 500ms | 외부 의존성 응답이 느림 |
| ☐ | Thread Pool 잠김 경험 | 동시 요청에서 처리 지연 |
| ☐ | 캐시 만료 시 DB 폭주 | Cache Stampede 경험 |
| ☐ | 장애 전파 경험 | 일부 장애가 전체로 번짐 |
| ☐ | p99가 불안정 | p50은 괜찮은데 p99가 튐 |

### 필요 역량

| 역량 | 최소 수준 | 권장 수준 |
|------|----------|----------|
| Java | 17+ | 21+ (Virtual Threads) |
| Spring Boot | 3.0+ | 3.5+ |
| Redis | 기본 이해 | Sentinel/Cluster |
| Monitoring | 로그 확인 | Prometheus/Grafana |

---

## Step 1: Minimal Adoption (최소 도입)

> **목표**: 가장 큰 위험(외부 의존성 장애)부터 방어

### 적용 범위

- [x] Timeout Layering (3단계 타임아웃)
- [x] Circuit Breaker (기본 설정)
- [ ] TieredCache (미적용)
- [ ] SingleFlight (미적용)

### 구현 순서

**1. 의존성 추가**
```groovy
// build.gradle
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
```

**2. Timeout Layering 설정**
```yaml
# application.yml
resilience4j:
  timelimiter:
    instances:
      externalApi:
        timeoutDuration: 28s        # 전체 작업 상한
        cancelRunningFuture: true

# 외부 API 클라이언트 설정
external-api:
  connect-timeout: 3s      # TCP 연결 타임아웃
  response-timeout: 5s     # HTTP 응답 타임아웃
```

**3. Circuit Breaker 적용**
```yaml
# application.yml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 10
    instances:
      externalApi:
        baseConfig: default
```

```java
// 서비스 클래스
@CircuitBreaker(name = "externalApi", fallbackMethod = "fallback")
public Response callExternalApi() {
    return restClient.get()...;
}

public Response fallback(Throwable t) {
    log.warn("Fallback triggered: {}", t.getMessage());
    return Response.empty();
}
```

### 예상 효과

| 지표 | Before | After |
|------|--------|-------|
| 장애 전파 | 전체 영향 | 격리됨 |
| Thread Pool 고갈 | 발생 | 방지 |
| 외부 장애 복구 | 수동 | 자동 (10s) |

### 참고 코드
- `maple.expectation.config.ResilienceConfig`
- `maple.expectation.external.impl.ResilientNexonApiClient`

---

## Step 2: Standard Adoption (표준 운영)

> **목표**: 캐시 효율화 + 관측 가능성 확보

### 적용 범위

- [x] Timeout Layering
- [x] Circuit Breaker
- [x] TieredCache (L1 + L2)
- [x] 기본 Observability

### 추가 구현

**1. 의존성 추가**
```groovy
// build.gradle
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
implementation 'org.redisson:redisson-spring-boot-starter:3.27.0'
```

**2. TieredCache 설정**
```java
// CacheConfig.java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedissonClient redissonClient,
            MeterRegistry meterRegistry) {

        return new TieredCacheManager(
                createL1Manager(),   // Caffeine
                createL2Manager(connectionFactory),  // Redis
                redissonClient,      // 분산 락
                meterRegistry        // 메트릭
        );
    }

    private CacheManager createL1Manager() {
        CaffeineCacheManager l1 = new CaffeineCacheManager();
        l1.registerCustomCache("myCache",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .recordStats()
                        .build());
        return l1;
    }
}
```

**3. Prometheus 메트릭 활성화**
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
```

### 예상 효과

| 지표 | Before | After |
|------|--------|-------|
| 외부 API 호출 | 100% | 20~30% |
| DB 부하 | 100% | 10% |
| p95 응답시간 | 가변적 | 안정 |

### 참고 코드
- `maple.expectation.config.CacheConfig`
- `maple.expectation.global.cache.TieredCacheManager`
- `maple.expectation.global.cache.TieredCache`

---

## Step 3: Full Adoption (완전 활용)

> **목표**: 프로덕션 레벨 운영 체계 완성

### 적용 범위

- [x] 모든 Step 2 항목
- [x] LogicExecutor Pipeline
- [x] SingleFlight 패턴
- [x] Graceful Shutdown
- [x] Chaos Test Suite

### LogicExecutor 도입

```java
// LogicExecutor 인터페이스 - 8가지 패턴
public interface LogicExecutor {
    // 패턴 1: 기본 실행
    <T> T execute(ThrowingSupplier<T> task, TaskContext context);

    // 패턴 2: void 작업
    void executeVoid(ThrowingRunnable task, TaskContext context);

    // 패턴 3: 기본값 반환
    <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context);

    // 패턴 4: 예외 시 복구
    <T> T executeOrCatch(ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context);

    // 패턴 5: finally 보장
    <T> T executeWithFinally(ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context);

    // 패턴 6: 예외 변환
    <T> T executeWithTranslation(ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context);

    // 패턴 7: Checked 예외 + 복구
    <T> T executeCheckedWithHandler(ThrowingSupplier<T> task, ThrowingFunction<Throwable, T> recovery, TaskContext context) throws Throwable;

    // 패턴 8: Fallback
    <T> T executeWithFallback(ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context);
}
```

**사용 예시:**
```java
// try-catch 제거, 선언적 예외 처리
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", String.valueOf(id))
);
```

### SingleFlight 패턴

```java
// maple.expectation.global.concurrency.SingleFlightExecutor
@Component
public class MyService {
    private final SingleFlightExecutor<MyResult> singleFlight;

    public CompletableFuture<MyResult> getData(String key) {
        return singleFlight.executeAsync(
            key,
            () -> expensiveComputation(key)
        );
    }
}
```

### Graceful Shutdown

```java
// 4단계 순차 종료
@PreDestroy
public void onShutdown() {
    // Phase 1: 새 요청 거부
    // Phase 2: 진행 중 작업 완료 대기
    // Phase 3: 버퍼 플러시
    // Phase 4: 리소스 해제
}
```

### 예상 효과

| 지표 | Before | After |
|------|--------|-------|
| MTTR | 수분 | < 10ms (자동 격리) |
| 코드 일관성 | 개인 스타일 | 표준화 |
| 장애 예측 | 불가 | Chaos로 사전 검증 |

### 참고 코드
- `maple.expectation.global.executor.LogicExecutor`
- `maple.expectation.global.executor.DefaultLogicExecutor`
- `maple.expectation.global.executor.TaskContext`
- `maple.expectation.global.concurrency.SingleFlightExecutor`
- `maple.expectation.global.shutdown.GracefulShutdownCoordinator`

---

## 도입 로드맵 요약

```
Week 1: Step 1 (Minimal)
         ↓
Week 2-3: Step 2 (Standard)
         ↓
Week 4+: Step 3 (Full)
         ↓
Ongoing: 운영 + 최적화
```

---

## FAQ

### Q: 기존 프로젝트에 적용 가능한가요?
A: 네. Step 1부터 점진적으로 적용하면 됩니다. 전체 리팩토링 없이도 Timeout/Circuit만 추가해도 효과가 큽니다.

### Q: Redis가 필수인가요?
A: Step 1은 Redis 없이 가능합니다. Step 2부터 L2 캐시로 Redis가 필요합니다.

### Q: 팀 규모가 작아도 적용 가능한가요?
A: 1인 개발자도 Step 1~2는 충분히 적용 가능합니다. Step 3의 Chaos Test는 팀 규모에 맞게 축소 적용하세요.

### Q: 성능 오버헤드가 있나요?
A: Circuit Breaker/Timeout은 거의 없음 (< 1ms). TieredCache는 L1 HIT 시 < 5ms로 오히려 성능 향상.

---

## 관련 문서

- [Architecture Overview](../00_Start_Here/architecture.md)
- [ADR-001: Streaming Parser](../adr/ADR-001-streaming-parser.md)
- [ADR-002: Circuit Breaker](../adr/ADR-002-circuit-breaker-config.md)
- [ADR-003: TieredCache + SingleFlight](../adr/ADR-003-tiered-cache-singleflight.md)
- [Chaos Engineering](../01_Chaos_Engineering/)

---

*Last Updated: 2026-01-25*
