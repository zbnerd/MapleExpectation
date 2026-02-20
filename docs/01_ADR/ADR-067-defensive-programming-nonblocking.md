# ADR-067: 방어적 프로그래밍과 논블로킹 전환

## Status
**Accepted** (2026-02-20)

## Context

### 1장: 문제의 발견 (Problem)

#### 1.1 Blocking Call으로 인한 Thread Pool 병목

PR #199와 관련 Issues (#195, #196, #197, #198)에서 **Tomcat Worker Thread가 Blocking Call로 점유되어 시스템 전체의 처리량이 급감**하는 문제가 발견되었습니다.

**문제의 본질**:
```java
// Anti-Pattern: Blocking Call on Tomcat Thread
@GetMapping("/api/characters/{ign}")
public ResponseEntity<Character> getCharacter(@PathVariable String ign) {
    // Tomcat Thread(I/O Worker)가 block() 호출로 대기
    CharacterData data = webClient.get()
        .uri(uriBuilder -> uriBuilder.path("/api/characters/{ign}").build(ign))
        .retrieve()
        .bodyToMono(CharacterData.class)
        .block();  // ⚠️ BLOCKING CALL

    return ResponseEntity.ok(data);
}
```

**영향**:
- **Thread Pool 고갈**: 200개 Tomcat Thread가 외부 API 응답 대기로 점유
- **처리량 저하**: 새로운 요청 수신 불가, RPS 급락
- **Timeout Snowball**: 하나의 느린 외부 API가 전체 시스템 병목

#### 1.2 누락된 Timeout 설정

Issue #196에서 **WebClient Timeout 설정이 전혀 없어** 외부 API가 응답하지 않을 때 Thread가 무한 대기하는 문제가 발견되었습니다.

```java
// 문제: Timeout 설정 없음
WebClient webClient = WebClient.builder()
    .baseUrl("https://api.nexon.com")
    // .clientConnector(new ReactorClientConnector(HttpClient.create()))  // 누락
    .build();
```

#### 1.3 타임아웃 계층 불일치

Issue #225에서 **Redis, MySQL, WebClient, Circuit Breaker의 Timeout이 계층별로不一致**하여 "Zombie Request"가 발생하는 문제가 확인되었습니다:

- **WebClient**: 30초 (너무 김)
- **Redis**: 5초
- **MySQL**: 10초
- **Circuit Breaker**: 60초

**결과**: WebClient가 30초 동안 기다리는 동안, 상위 Circuit Breaker는 이미 타임아웃으로 간주하지만 Thread는 여전히 점유됨.

#### 1.4 입력값 검증 누락

Issue #197에서 **CubeCostPolicy 등에서 입력값 검증이 누락**되어 DB 제약 조건 위반 예외가 발생하는 문제가 확인되었습니다.

```java
// Anti-Pattern: 입력값 검증 없이 DB에 전달
public void calculateCost(CubeRequest request) {
    // request.getCost()가 null이면 NPE 또는 DB Constraint Violation
    repository.save(new CubeCost(request.getCost(), request.getCount()));
}
```

---

### 2장: 선택지 탐색 (Options)

#### 2.1 선택지 1: Sync Blocking 방식 유지 (Status Quo)

**방식**: 기존 Blocking 방식 유지, Thread Pool 크기만 증설

```yaml
server:
  tomcat:
    threads:
      max: 500  # 200 → 500 증설
```

**장점**:
- 코드 변경 최소화
- 기존 테스트 그대로 사용

**단점**:
- **근본적 해결 불가**: Thread는 여전히 Blocking
- **메모리 사용량 급증**: Thread 당 1MB Stack Memory
- **확장성 한계**: 500개 Thread로도 부족할 수 있음

**결론**: **임시 방편일 뿐 근본 해결책 아님**

---

#### 2.2 선택지 2: CompletableFuture + Async 방식

**방식**: Spring `@Async`와 CompletableFuture 사용

```java
@Async
public CompletableFuture<Character> getCharacterAsync(String ign) {
    return webClient.get()
        .uri("/api/characters/{ign}", ign)
        .retrieve()
        .bodyToMono(CharacterData.class)
        .toFuture();
}
```

**장점**:
- Spring Framework 기본 기능
- Blocking 회피

**단점**:
- **여전히 Thread Pool 사용**: `@Async`는 별도 ThreadPool 생성
- **백프레셔 부재**: 유입 속도 > 처리 속도 시 메모리 폭발
- **체인 복잡성**: CompletableFuture 체이닝이 가독성 저해

**결론**: **Virtual Threads보다 효율이 낮음**

---

#### 2.3 선택지 3: Virtual Threads + Non-Blocking Reactive (선택)

**방식**:
1. **Virtual Threads로 I/O 대기 제거**
2. **Non-Blocking WebClient 사용**
3. **Timeout 계층 일치**
4. **입력값 검증 강화**

```java
// 1. Virtual Threads 활성화 (ADR-045 참고)
@Bean
public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreads() {
    return protocolHandler -> {
        protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    };
}

// 2. Non-Blocking API
@GetMapping("/api/characters/{ign}")
public Mono<ResponseEntity<Character>> getCharacter(@PathVariable String ign) {
    return webClient.get()
        .uri("/api/characters/{ign}", ign)
        .retrieve()
        .bodyToMono(CharacterData.class)
        .timeout(Duration.ofSeconds(5))  // 3. Timeout 일치
        .map(ResponseEntity::ok);
}

// 4. 입력값 검증
public record CubeRequest(@NotNull @Min(0) BigInteger cost, @NotNull @Min(1) Integer count) {}
```

**장점**:
- **Thread Pool 고갈 해결**: Virtual Thread는 Blocking I/O 시 Platform Thread 반환
- **확장성**: Platform Thread 수에 제한받지 않음
- **Timeout 일치**: 계층별 Timeout 통일로 Zombie Request 제거
- **검증 강화**: Early Failure로 불필요한 리소스 소비 방지

**단점**:
- Reactive Programming 러닝 커브
- 기존 Blocking 코드를 Non-Blocking으로 전환 필요

**결론**: **가장 근본적이고 확장 가능한 해결책**

---

### 3장: 결정의 근거 (Decision)

#### 3.1 선택: Virtual Threads + Non-Blocking Reactive + 방어적 프로그래밍

MapleExpectation 프로젝트는 **선택지 3: Virtual Threads + Non-Blocking Reactive + 방어적 프로그래밍**을 채택했습니다.

**결정 근거**:
1. **ADR-048 (Java 21 Virtual Threads)**와 **ADR-045 (Virtual Threads Non-Blocking Pipeline)**의 연장선
2. **대규모 트래픽 처리** (Issue #284: 1000+ RPS 목표)
3. **Scale-out 준비** (Issue #283: Stateless 아키텍처)

---

### 4장: 구현의 여정 (Action)

#### 4.1 WebClient Timeout 설정

**파일**: `maple/expectation/config/WebClientConfig.java`

```java
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // TCP 연결 Timeout
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(5))  // 전체 응답 Timeout
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS)));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
```

#### 4.2 Non-Blocking Controller 전환

**Before (Blocking)**:
```java
@Service
@RequiredArgsConstructor
public class CharacterService {
    private final NexonApiClient apiClient;

    public Character getCharacter(String ign) {
        return apiClient.fetchCharacter(ign).block();  // BLOCKING
    }
}
```

**After (Non-Blocking)**:
```java
@Service
@RequiredArgsConstructor
public class CharacterService {
    private final NexonApiClient apiClient;

    public Mono<Character> getCharacter(String ign) {
        return apiClient.fetchCharacter(ign)
            .timeout(Duration.ofSeconds(5))
            .onErrorMap(TimeoutException.class,
                e -> new ServerTimeoutException("Nexon API timeout", e));
    }
}
```

#### 4.3 입력값 검증 강화

**파일**: `maple/expectation/api/dto/CubeCostRequest.java`

```java
package maple.expectation.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigInteger;

public record CubeCostRequest(
    @NotNull(message = "cost는 null일 수 없습니다")
    @Min(value = 0, message = "cost는 0 이상이어야 합니다")
    BigInteger cost,

    @NotNull(message = "count는 null일 수 없습니다")
    @Min(value = 1, message = "count는 1 이상이어야 합니다")
    @Max(value = 30, message = "count는 30 이하이어야 합니다")
    Integer count
) {}
```

**Controller에서 검증 활성화**:
```java
@RestController
@RequiredArgsConstructor
public class CubeController {

    @PostMapping("/api/cube/cost")
    public ResponseEntity<CubeCostResponse> calculateCost(
        @Valid @RequestBody CubeCostRequest request
    ) {
        // @Valid에 의해 자동 검증, 실패 시 MethodArgumentNotValidException 발생
        return ResponseEntity.ok(cubeService.calculateCost(request));
    }
}
```

#### 4.4 Timeout 계층 일치

**계층별 Timeout 통일**:
| 계층 | Timeout | 근거 |
|------|---------|------|
| WebClient | 5초 | Nexon API SLA (3초) + 여유 |
| Redis | 5초 | 로컬 캐시, 네트워크 지연 최소 |
| MySQL | 5초 | Query 최적화로 5초内 충분 |
| Circuit Breaker | 5초 | 하위 계층과 일치 |

**구현**:
```yaml
# application.yml
spring:
  data:
    redis:
      timeout: 5000  # 5초
  jpa:
    properties:
      jakarta.persistence.query.timeout: 5000  # 5초

resilience4j:
  circuitbreaker:
    instances:
      nexonApi:
        timeoutDuration: 5000  # 5초
```

---

### 5장: 결과와 학습 (Result)

#### 5.1 성과

1. **RPS 8.1x 개선**: 89 RPS → 719 RPS (ADR-045 참조)
2. **Thread Pool 고갈 해결**: Virtual Thread는 Blocking I/O 시 Platform Thread 반환
3. **Zombie Request 제거**: Timeout 계층 일치로 불확정 상태 제거
4. **Early Failure**: 입력값 검증으로 불필요한 리소스 소비 방지

#### 5.2 학습한 점

1. **Blocking Call의 숨겨진 비용**: `.block()` 호출은 "전체 시스템의 병목"
2. **Timeout 계층 일치的重要性**: 상위 Timeout > 하위 Timeout이면 Zombie Request 발생
3. **입력값 검증은 최후 방어선**: DB/외부 API 호출 전에 실패를 조기화

#### 5.3 향후 개선 방향

- **R2DBC (Reactive Relational Database Connectivity)** 도입으로 DB Non-Blocking
- **Reactive Redis** (Lettuce) 완전 전환
- **Backpressure** 전략 명세화

---

## Consequences

### 긍정적 영향
- **확장성 확보**: Virtual Threads로 수만 개 동시 요청 처리 가능
- **안정성 향상**: Timeout 일치로 Zombie Request 제거
- **보안 강화**: 입력값 검증으로 무효 요청 조기 거부

### 부정적 영향
- **Reactive 러닝 커브**: 개발자가 Mono/Flux 체이닝에 익숙해져야 함
- **디버깅 어려움**: Async Stack Trace는 복잡함

### 위험 완화
- **MDC Tracing** (ADR-065)으로 요청 추적
- **Reactor Debug Mode**로 Stack Trace 개선

---

## References

- **PR #199**: feat(#195,#196,#197,#198): 방어적 프로그래밍 및 논블로킹 전환
- **Issue #195**: [P0] Blocking Call (.block()/.join()) on Tomcat Thread 제거
- **Issue #196**: [P1] WebClient Timeout 설정 누락
- **Issue #197**: [P1] CubeCostPolicy 입력값 검증 누락
- **Issue #198**: [P1] @Transactional Isolation Level 명시적 지정 필요
- **Issue #225**: [P1][Nightmare-06] 타임아웃 계층 불일치로 인한 Zombie Request 발생
- **ADR-048**: Java 21 Virtual Threads 채택
- **ADR-045**: Virtual Threads와 AbortPolicy를 사용한 비동기 Non-Blocking 파이프라인
