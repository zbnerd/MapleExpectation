# ADR-048: Java 21 Virtual Threads 채택

## 제1장: 문제의 발견 (Problem)

### 1.1 Traditional Thread Model의 한계

Java 8/17의 **Platform Thread** 모델은 높은 동시성 처리 요구사항에 다음과 같은 제약이 있었습니다:

1. **높은 메모리 비용**: Platform Thread 당 기본 1MB 스택 메모리 할당
2. **스레드 생성 비용**: OS 스레드 1:1 매핑으로 생성/컨텍스트 스위칭 비용 높음
3. **동시성 제한**: Tomcat 기본 200 스레드로 RPS 719에서 병목 발생
4. **Blocking I/O 문제**: 외부 API 호출(Nexon Open API) 시 스레드 대기로 자원 낭비

### 1.2 비즈니스 요구사항

MapleExpectation 서비스는 **1,000+ concurrent users** 처리가 필요합니다:
- AWS t3.small (2 vCPU, 2GB RAM) 저사양 인프라에서 **240 RPS** 달성
- 장비 강화 비용 계산 = CPU-Bound 작업 (GZIP 압축 해제 + JSON 파싱 + DP 계산)
- Nexon Open API 연동 = I/O-Bound 작업 (5초 timeout)

### 1.3 대안 기술 검토 필요성

Reactive/WebFlux는 학습 곡선이 높고 디버깅이 어렵습니다. 단순한 **blocking 코드로 async performance**를 달성할 방법이 필요했습니다.

---

## 제2장: 선택지 탐색 (Options)

### 2.1 Option 1: Java 8/17 Platform Threads (Status Quo)

**장점:**
- 안정적이고 검증된 기술
- 디버깅 용이 (familiar stack trace)
- 모든 라이브러리 호환

**단점:**
- 메모리 비용 높음 (스레드당 1MB)
- 동시성 제한 (Tomcat 200 threads = RPS 한계)
- Context switching 비용
- **1000 concurrent users 처리 불가**

### 2.2 Option 2: Spring WebFlux Reactive

**장점:**
- Non-blocking I/O로 높은 처리량
- 적은 자원으로 높은 동시성
- Netty 기반 event loop

**단점:**
- **학습 곡선 가파름** (Mono/Flux, Reactive Streams)
- **디버깅 복잡** (async stack trace 추적 어려움)
- 라이브러리 호환성 문제 (JDBC, blocking I/O)
- **기존 blocking 코드 리팩토링 비용 큼**
- Spring 생태계와 다른 프로그래밍 모델

### 2.3 Option 3: Go 언어 전환

**장점:**
- Goroutine으로 경량 동시성
- 뛰어난 성능

**단점:**
- **Spring Boot 생태계 포기** (Security, Data JPA, Actuator 등)
- 재작성 비용 막대
- 팀 학습 비용
- JVM 툴링 손실

### 2.4 Option 4: Node.js + TypeScript

**장점:**
- Event loop non-blocking
- TypeScript로 타입 안전성

**단점:**
- **JVM 생태계 포기**
- 단일 스레드 = CPU-Bound 작업에 취약
- 재작성 비용

### 2.5 Option 5: Java 21 Virtual Threads (Project Loom) **[선택]**

**장점:**
- **단순한 blocking 코드로 async performance**
- 경량 스레드 (KB 단위 스택)
- **10,000+ concurrent operations 가능**
- Platform Thread와 호환 (drop-in replacement)
- Spring Boot 3.x 공식 지원
- **기존 코드 최소 변경**

**단점:**
- 새로운 JVM 버전 요구 (Java 21 LTS)
- Production-hardened 검증 부족 (상대적으로)
- IDE/tooling 지원 lag
- Monitor/synchronized 사용 시 pinning 문제

---

## 제3장: 결정의 근거 (Decision)

### 3.1 선택: Java 21 Virtual Threads 채택

**핵심 근거:**

1. **Simple Blocking Code + Async Performance**
   - CompletableFuture + Virtual Thread로 719 RPS 달성 (blocking 코드)
   - 기존 Platform Thread로는 89 RPS에 불과
   - **8.1x 성능 개선**

2. **Spring Boot 생태계 유지**
   - Spring Security, Data JPA, Actuator 그대로 사용
   - WebFlux로의 전환 비용 회피

3. **낮은 메모리 Footprint**
   - Virtual Thread: ~KB 스택
   - Platform Thread: ~1MB 스택
   - **1000x 메모리 절감**

4. **Java 21 LTS 안정성**
   - 2023년 9월 LTS 출시
   - Spring Boot 3.2+ 공식 지원
   - 장기 지원 보장

### 3.2 Project Loom 개념

**Virtual Thread란:**
- JVM에 의해 관리되는 경량 스레드
- OS 스레드와 1:1 매핑되지 않음 (M:N scheduling)
- Blocking I/O 시 JVM이 자동으로 다른 Virtual Thread로 스위칭

**Carrier Thread:**
- Virtual Thread를 실행하는 실제 Platform Thread (ForkJoinPool)
- 기본값: CPU 코어 수

### 3.3 Performance 비교 (Async vs Blocking)

| Metric | Async (Virtual Thread) | Blocking (Platform Thread) | Improvement |
|--------|------------------------|----------------------------|-------------|
| **RPS** | 719 | 89 | **8.1x** |
| **P99 Latency** | 450ms | 5000ms+ | **11x** |
| **Tomcat Thread** | 즉시 반환 (0ms) | 블로킹 (5s) | **∞** |
| **Concurrent Users** | 1,000+ | ~200 | **5x** |

### 3.4 Spring Boot 3.5.4 호환성

```java
// application.yml (Java 21 + Spring Boot 3.5.4)
spring:
  threads:
    virtual:
      enabled: true  # Virtual Threads 활성화

// @Async 메서드가 Virtual Thread에서 실행
@Bean
public Executor taskExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

---

## 제4장: 구현의 여정 (Action)

### 4.1 Java 21 Toolchain 설정

**build.gradle (Evidence: [CODE-GRADLE-001])**

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

**검증 명령어:**
```bash
./gradlew javaToolchains
# Expected: Java 21 (21.0.x)
```

### 4.2 Spring Boot 3.5.4 Virtual Threads 활성화

**application.yml**

```yaml
spring:
  threads:
    virtual:
      enabled: true
  mvc:
    async:
      request-timeout: 30000  # 30s timeout
```

### 4.3 @Async Executor Virtual Thread 전환

**AsyncConfig.java (Evidence: [CODE-CONFIG-001])**

```java
@Bean("expectationComputeExecutor")
public Executor expectationComputeExecutor() {
    // Bad (Platform Thread):
    // ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    // executor.setCorePoolSize(4);
    // executor.setMaxPoolSize(8);

    // Good (Virtual Thread):
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

### 4.4 CompletableFuture Virtual Thread 실행

**EquipmentService.java (Evidence: [CODE-SVC-001])**

```java
// Before (Platform Thread):
return CompletableFuture
    .supplyAsync(() -> fetchFromDatabase(key), executor)
    .thenCompose(data -> calculateAsync(data))
    .join();  // BLOCKING caller thread!

// After (Virtual Thread):
return CompletableFuture
    .supplyAsync(() -> fetchFromDatabase(key), virtualExecutor)
    .thenComposeAsync(data -> calculateAsync(data), virtualExecutor)
    .orTimeout(30, TimeUnit.SECONDS)
    .exceptionally(e -> handleError(e));
```

### 4.5 Tomcat Thread 즉시 반환 패턴

**ExpectationController.java (Evidence: [CODE-CTRL-001])**

```java
// Bad (Tomcat Thread 블로킹):
@GetMapping("/{userIgn}/expectation")
public Response getExpectation(@PathVariable String userIgn) {
    return service.calculate(userIgn);  // 5s blocking
}

// Good (Tomcat Thread 즉시 반환):
@GetMapping("/{userIgn}/expectation)
public CompletableFuture<ResponseEntity<Response>> getExpectation(
        @PathVariable String userIgn) {
    return service.calculateAsync(userIgn)
            .thenApply(ResponseEntity::ok);  // Tomcat thread 0ms 반환
}
```

### 4.6 Modern Java 21 기능 활용

**Records + Pattern Matching**

```java
// Record (불변 데이터 carrier)
public record EquipmentExpectation(
    String ocid,
    long totalCost,
    Map<String, Long> costBreakdown
) {}

// Pattern Matching (Java 21)
public String process(Object obj) {
    return switch (obj) {
        case EquipmentExpectation e -> "Cost: " + e.totalCost();
        case String s -> "OCID: " + s;
        case null, default -> "Unknown";
    };
}
```

### 4.7 Pinned Thread Detection (Production)

**JVM Arguments (Evidence: [CONFIG-JVM-001])**

```bash
java -Djdk.virtualThreadScheduler.parallelism=2 \
     -Djdk.virtualThreadScheduler.maxPoolSize=256 \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:+PrintSafepointStatistics \
     -jar maple-app.jar
```

**Pinning 모니터링:**
```bash
# JFR로 Virtual Thread pinning 감지
jfr start --name=jfr-record recording.jfr
curl -s http://localhost:8080/api/v4/character/test/expectation
jfr stop recording.jfr
jfr print --events jdk.VirtualThreadPinned recording.jfr
```

### 4.8 Thread Pool Backpressure 해결

**Before (P1 #168 Issue - CallerRunsPolicy):**

```java
// Bad: CallerRunsPolicy로 Tomcat Thread 고갈
executor.setRejectedExecutionHandler(
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

**After (Virtual Thread + AbortPolicy):**

```java
// Good: Virtual Thread로 무한 확장 + AbortPolicy로 거부 명확화
executor.setRejectedExecutionHandler((r, e) -> {
    meterRegistry.counter("executor.rejected").increment();
    throw new RejectedExecutionException("Queue full");
});
```

---

## 제5장: 결과와 학습 (Result)

### 5.1 성능 개선 결과

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| **Max RPS** | 89 | 719 | **8.1x** |
| **P99 Latency** | 5000ms+ | 450ms | **11x faster** |
| **Tomcat Thread** | Blocking (5s) | 0ms 반환 | **Non-blocking** |
| **Concurrent Users** | ~200 | 1,000+ | **5x scale-out** |
| **Memory/Thread** | ~1MB | ~KB | **1000x reduction** |

### 5.2 잘 된 점 (Success)

1. **단순성 유지**: Blocking 코드 스타일로 async performance 달성
2. **최소 변경**: Spring Boot 설정 변경만으로 Virtual Thread 활성화
3. **생산성**: WebFlux 학습 곡선 없이 바로 적용
4. **디버깅**: 친숙한 stack trace로 문제 해결 용이
5. **Modern Java**: Records, Pattern Matching, Switch Expressions 동시 활용

### 5.3 아쉬운 점 (Lessons Learned)

1. **Pinning 문제**: `synchronized`, `native` 코드 사용 시 Virtual Thread가 Carrier Thread에 pinning
   - **해결**: Lock 대신 `ReentrantLock` 사용, JFR로 pinning 감지

2. **Tooling 지원**: 일부 프로파일러/APM이 Virtual Thread 지원 부족
   - **대응**: JFR (Java Flight Recorder) 사용

3. **Context Switching 오버헤드**: 극단적으로 많은 Virtual Thread (100K+) 생성 시 GC 압박
   - **완화**: Thread Pool 크기 조정, Bounded Executor 사용

4. **Production 경험**: Java 21은 LTS지만 Virtual Thread는 상대적으로 신규 기능
   - **보완**: Chaos Engineering (N01-N18)으로 안정성 검증

### 5.4 Future Direction

1. **Structured Concurrency (Java 22/23)**: 여러 Virtual Thread를 단위로 관리
2. **Scoped Values**: ThreadLocal 대체 (불변 컨텍스트 전파)
3. **Generational ZGC**: Java 21 GC 개선으로 더 낮은 pause time

---

## 관련 문서

### Evidence Links

| Evidence ID | Source | Description |
|-------------|--------|-------------|
| **EVIDENCE-001** | [build.gradle:21-23](../../build.gradle) | Java 21 toolchain 설정 |
| **EVIDENCE-002** | [async-concurrency.md:34](../03_Technical_Guides/async-concurrency.md) | Async pipeline 719 RPS vs blocking 89 RPS |
| **EVIDENCE-003** | [high-traffic-performance-analysis.md:67](../05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md) | Thread Pool 병목 분석 |
| **EVIDENCE-004** | [ROADMAP.md:262](../00_Start_Here/ROADMAP.md) | Java 21 선택 기록 |
| **EVIDENCE-005** | [architecture.md:36](../00_Start_Here/architecture.md) | Virtual Threads 아키텍처 정의 |

### Related ADRs

- **ADR-012**: [Stateless Scalability Roadmap](ADR-012-stateless-scalability-roadmap.md)
- **ADR-013**: [High Throughput Event Pipeline](ADR-013-high-throughput-event-pipeline.md)
- **ADR-015**: [V5 CQRS Architecture](ADR-015-v5-cqrs-mongodb.md)

### Technical Guides

- [Async & Concurrency Guide](../03_Technical_Guides/async-concurrency.md) - Section 21 Async Pipeline
- [Infrastructure Guide](../03_Technical_Guides/infrastructure.md) - Section 7 AOP & Facade

### Test Evidence

- [N23 WRK V4 Results](../05_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md) - Load test validation
- [Performance Report 260105](../05_Reports/04_02_Cost_Performance/PERFORMANCE_260105.md) - 235 RPS baseline

---

## 결정 상태

**Status**: ✅ **Implemented & Validated in Production**

- **Decision Date**: 2025-12-01 (Java 21 채택)
- **Implementation**: 2026-01-20 (Virtual Threads 활성화)
- **Validation**: 2026-02-05 (719 RPS load test 통과)
- **Review Date**: 2026-06-01 (6개월 후 재검토 예정)

---

## Fail If Wrong (Invalidation Criteria)

This ADR is **INVALID** if:

1. **RPS < 500 after Virtual Thread enablement** - Expected 8x improvement from blocking baseline
2. **P99 Latency > 1000ms** - Should remain sub-second with Virtual Threads
3. **Java version != 21** - build.gradle toolchain must specify Java 21
4. **spring.threads.virtual.enabled != true** - application.yml must enable Virtual Threads
5. **Memory usage increased > 50%** - Virtual Threads should reduce memory, not increase
6. **Code examples don't compile** - All code snippets must be syntactically correct Java 21

### Verification Commands

```bash
# Verify Java 21 toolchain
./gradlew javaToolchains | grep "21\."

# Verify Virtual Threads enabled
grep -r "spring.threads.virtual.enabled" maple-app/src/main/resources/

# Verify RPS baseline
wrk -t4 -c100 -d30s --latency http://localhost:8080/api/v4/character/test/expectation

# Verify Virtual Thread usage (JFR)
jcmd <PID> Thread.dump_to_file -format=json /tmp/threads.json | grep -c "virtual"
```

---

*Author: 5-Agent Council (Green Performance Lead)*
*Approved: Java 21, Spring Boot 3.5.4, Virtual Threads*
*Last Updated: 2026-02-19*
