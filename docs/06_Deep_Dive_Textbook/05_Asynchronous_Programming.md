# 05. Asynchronous Programming: 비동기 프로그래밍의 심화 학습

> **"동기는 당신의 코드를 멈추게 하고, 비동기는 당신의 코드를 흐르게 합니다. 하지만 그 흐름을 제어하지 못하면, 재앙이 시작됩니다."**

---

## 1. The Problem (본질: 우리는 무엇과 싸우고 있는가?)

### 1.1 Blocking I/O의 병목

**동기 호출의 문제:**

```
Thread Main (Tomcat Thread 1)
├─ 1. DB 조회: 100ms (Blocking) ⏸
├─ 2. 외부 API 호출: 500ms (Blocking) ⏸
├─ 3. 캐시 업데이트: 50ms (Blocking) ⏸
└─ 4. 응답 반환
총: 650ms

문제: 650ms 동안 스레드는 아무것도 못함 (낭비)
```

**Thread Pool 고갈 시나리오:**

```
Tomcat Thread Pool (max=200)

요청 1-200: 정상 처리 (각 스레드가 1개씩 담당)
요청 201: ⏳ 대기 (스레드 풀 고갈)
요청 202-1000: ⏳ 대기 큐 적재
요청 1001: 💥 Rejected (Too many connections)

결과: 전체 서비스 마비
```

### 1.2 Non-blocking I/O의 해법

**비동기 호출의 이점:**

```
Thread Main
├─ 1. DB 조회 → Future (Non-blocking) ▶
├─ 2. 외부 API 호출 → Future (Non-blocking) ▶
├─ 3. 캐시 업데이트 → Future (Non-blocking) ▶
├─ 4. 다른 요청 처리 가능 ✅
└─ Future 1,2,3이 완료되면 조합해서 응답
```

**Java 21의 Virtual Threads:**

```java
// Platform Thread (OS Thread)
Thread.ofPlatform().start(() -> {
    Thread.sleep(1000);  // 1초 동안 OS Thread 블로킹
});

// Virtual Thread (JVM 관리)
Thread.ofVirtual().start(() -> {
    Thread.sleep(1000);  // 1초 동안만 OS ThreadUnmount (다른 작업 가능)
});
```

### 1.3 Asynchronous Processing Pipeline

**MapleExpectation의 파이프라인:**

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Controller │───>│  @Async     │───>│  External   │
│  (Tomcat)   │    │  Service    │    │  API        │
└─────────────┘    └─────────────┘    └─────────────┘
      │                                      │
      │ Non-blocking                        │ Blocking
      │                                      │
      └──────> [Queue] ──────────────────────┘
                └── Background Worker Pool
```

---

## 2. The CS Principle (원리: 이 코드는 무엇에 기반하는가?)

### 2.1 Context Switching의 비용

**OS의 스레드 스케줄링:**

```
Thread A → Thread B 로 전환 (Context Switch)

1. CPU Register 저장 (Thread A의 상태)
2. Scheduler 실행 (다음 스레드 선택)
3. CPU Register 복원 (Thread B의 상태)
4. TLB Flush (Translation Lookaside Buffer 무효화)
5. CPU Cache Cold Start (L1/L2 Cache Miss)

비용: ~1-10μs (마이크로초)
```

**Virtual Threads의 장점:**

```
Platform Thread Switching:
Thread A (OS Thread) → Thread B (OS Thread)
└─ Context Switching: ~10μs
└─ OS 스케줄러 참여

Virtual Thread Switching:
Virtual Thread A → Virtual Thread B
└─ Carrier Thread 교체만 (JVM 내부)
└─ OS Context Switching 없음
└─ 비용: ~0.1μs (100배 더 빠름!)
```

### 2.2 ForkJoinPool의 Work-Stealing 알고리즘

**Work-Stealing의 핵심:**

```
┌─────────────────────┐     ┌─────────────────────┐
│  Worker Thread 1    │     │  Worker Thread 2    │
├─────────────────────┤     ├─────────────────────┤
│  Queue: [Task 3]    │     │  Queue: []          │
│  🔨 Executing Task 4│     │  💤 Idle            │
└──────────┬──────────┘     └──────────┬──────────┘
           │                            │
           │ Work-Stealing              │
           └────────────────────────────>┘
                Task 3을 훔쳐옴!

이유: Worker 2는 놀고 있는데, Worker 1의 Task 3를 가져와서 실행
```

**Java 7의 ForkJoinPool 구조:**

```java
ForkJoinPool pool = ForkJoinPool.commonPool();

class RecursiveTask extends CountedTask {
    @Override
    protected Integer compute() {
        if (작업이 충분히 작음) {
            return 직접_계산();
        }

        // 작업 분할
        RecursiveTask left = new RecursiveTask(half);
        RecursiveTask right = new RecursiveTask(half);

        left.fork();  // 비동기 실행 (Worker의 Queue에 추가)
        int rightResult = right.compute();  // 현재 스레드에서 실행
        int leftResult = left.join();  // 완료 대기

        return leftResult + rightResult;
    }
}
```

### 2.3 CompletableFuture의 조합 (Composition)

**CompletableFuture의 체이닝:**

```java
// 1. 비동기 작업 생성
CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> {
    return DB.query("SELECT name FROM user WHERE id = 1");
});

// 2. 변환 (Map)
CompletableFuture<Integer> future2 = future1.thenApply(name -> {
    return name.length();  // String → Integer
});

// 3. 합성 (Compose)
CompletableFuture<String> future3 = future1.thenCompose(name -> {
    return CompletableFuture.supplyAsync(() -> {
        return DB.query("SELECT email FROM user WHERE name = ?", name);
    });
});

// 4. 결합 (Combine)
CompletableFuture<String> combined = future1.thenCombine(
    CompletableFuture.supplyAsync(() -> "World"),
    (hello, world) -> hello + " " + world
);

// 5. 모두 완료 대기
CompletableFuture<Void> allOf = CompletableFuture.allOf(future1, future2, future3);
```

---

## 3. Internal Mechanics (내부: Spring은 어떻게 동작하는가?)

### 3.1 Spring @Async의 Proxy 생성

**Spring AOP 흐름:**

```java
@Async
public void sendNotification(String message) {
    notificationService.send(message);
}

// Spring이 생성한 Proxy
public class AsyncProxy implements NotificationService {
    private final NotificationService target;
    private final Executor executor;

    @Override
    public void sendNotification(String message) {
        // 비동기 실행
        executor.execute(() -> target.sendNotification(message));
        // 즉시 반환 (Caller는 Blocking되지 않음)
    }
}
```

**ThreadPoolTaskExecutor 설정:**

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);      // 기본 스레드 수
        executor.setMaxPoolSize(50);       // 최대 스레드 수
        executor.setQueueCapacity(100);    // 대기 큐 용량
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.CallerRunsPolicy()  // 거부 시 직접 실행
        );
        executor.initialize();
        return executor;
    }
}
```

### 3.2 Virtual Threads와 Project Loom

**Java 21의 Virtual Threads:**

```java
// Traditional Thread (Platform Thread)
Thread platformThread = Thread.ofPlatform()
    .start(() -> blockingTask());

// Virtual Thread (Java 21+)
Thread virtualThread = Thread.ofVirtual()
    .start(() -> blockingTask());

// ExecutorService with Virtual Threads
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
executor.submit(() -> blockingTask());
```

**Virtual Threads의 내부 구조:**

```
┌─────────────────────────────────────────────────────────┐
│  JVM                                                 │
│                                                       │
│  ┌──────────────┐     ┌──────────────┐               │
│  │ Virtual      │     │ Virtual      │               │
│  │ Thread 1     │     │ Thread 2     │  수천 개 가능   │
│  └──────┬───────┘     └──────┬───────┘               │
│         │                    │                         │
│         │ Mounted            │ Mounted               │
│         ▼                    ▼                         │
│  ┌─────────────────────────────────────────────┐     │
│  │  Carrier Thread (ForkJoinPool)              │     │
│  │  ┌─────────┬─────────┬─────────┬─────────┐  │     │
│  │  │ OS T1   │ OS T2   │ OS T3   │ OS T4   │  │     │
│  │  └─────────┴─────────┴─────────┴─────────┘  │     │
│  └─────────────────────────────────────────────┘     │
│                       │                               │
│                       ▼                               │
│                  OS Scheduler                        │
└───────────────────────────────────────────────────────┘
```

**핵심**: Virtual Thread는 Carrier Thread에 "Mounted"되어서만 실행
- I/O Blocking 시: "Unmount" → Carrier Thread는 다른 Virtual Thread 실행
- 완료 시: "Mount" → 다시 실행

### 3.3 Spring WebFlux의 Reactive Streams

**Reactive Programming vs Async:**

```java
// Spring MVC (Async)
@GetMapping("/equipment/{id}")
public CompletableFuture<Equipment> getEquipment(@PathVariable Long id) {
    return CompletableFuture.supplyAsync(() -> {
        return equipmentRepository.findById(id);
    });
}

// Spring WebFlux (Reactive)
@GetMapping("/equipment/{id}")
public Mono<Equipment> getEquipment(@PathVariable Long id) {
    return Mono.fromCallable(() -> equipmentRepository.findById(id))
        .subscribeOn(Schedulers.boundedElastic());
}
```

**Reactive Streams의 Backpressure:**

```
Publisher (데이터 생산) → Subscriber (데이터 소비)

onNext(1) ───────────────────> request(1) ──────> onNext(2) ──────>
          ↓                                         ↓
        Buffer (크기 제한)                         request(1)

장점: Subscriber가 처리 가능한 만큼만 요청 (Overflow 방지)
```

---

## 4. Alternative & Trade-off (비판: 왜 이 방법을 선택했는가?)

### 4.1 @Async vs WebFlux

| 측정 항목 | @Async (Spring MVC) | WebFlux (Reactive) |
|---------|---------------------|---------------------|
| **학습 곡선** | 낮음 (익숙한 스타일) | 높음 (Mono/Flux 학습) |
| **디버깅 난이도** | 쉬움 (스레드 추적) | 어려움 (Reactor Trace) |
| **성능** | 좋음 (ThreadPool) | 최상 (Non-blocking) |
| **DB 지원** | 모두 (JDBC, JPA) | 제한적 (R2DBC, Reactive Redis) |
| **생태계** | 성숙 | 성장 중 |

**선택 이유**: MapleExpectation은 Spring MVC 선택
- 이미 JPA/MyBatis 사용 중 (R2DBC 미사용)
- 팀 생산성 우선
- @Async로 충분한 성능

### 4.2 Platform Thread vs Virtual Thread

| 측정 항목 | Platform Thread | Virtual Thread |
|---------|-----------------|----------------|
| **생성 비용** | 높음 (~1MB stack) | 낮음 (~KB) |
| **최대 개수** | 수천 개 (OS 제한) | 수백만 개 (JVM 제한) |
| **Context Switch** | 느림 (~10μs) | 빠름 (~0.1μs) |
| **Debugging** | 쉬움 | 어려움 (스레드 덤프 복잡) |
| **JDK 버전** | 모두 | 21+만 |

**선택 이유**: Virtual Thread는 아직 검증 단계
- Pinning 문제 (synchronized/native call로 인한 Carrier Thread 점유)
- 툴링 미흡 (VisualVM, JProfiler 지원 부족)
- **하지만 미래**: Java 25+에서는 표준이 될 것

### 4.3 ForkJoinPool vs ThreadPoolExecutor

| 측정 항목 | ForkJoinPool | ThreadPoolExecutor |
|---------|--------------|-------------------|
| **Work-Stealing** | ✅ 지원 | ❌ 미지원 |
| **용도** | CPU 작업 (연산) | I/O 작업 (DB, HTTP) |
| **Task 타입** | ForkJoinTask | Runnable/Callable |
| **Complexity** | 높음 (RecursiveTask) | 낮음 |

**선택 가이드:**

- **CPU 연산 (DP Calculator)**: ForkJoinPool (Work-Stealing 효과)
- **I/O 작업 (DB, HTTP)**: ThreadPoolExecutor (Blocking tolerated)

```java
// I/O 작업: ThreadPoolExecutor
@Async("taskExecutor")  // ThreadPoolTaskExecutor
public Equipment fetchFromDB(Long id) {
    return equipmentRepository.findById(id);  // Blocking I/O
}

// CPU 연산: ForkJoinPool
public long calculateCost(Equipment eq) {
    return ForkJoinPool.commonPool().invoke(
        new CostCalculationTask(eq)
    );
}
```

---

## 5. The Interview Defense (방어: 100배 트래픽에서 어디가 먼저 터지는가?)

### 5.1 "트래픽이 100배 증가하면?"

**실패 포인트 예측:**

1. **ThreadPoolExecutor의 Queue Overflow** (最先)
   - 현재: Queue 100, Max 50
   - 100배 트래픽: Queue 찼 → RejectedExecutionException
   - **해결**:
     - Queue Capacity 증설 (100 → 1,000)
     - 또는 Virtual Threads로 전환 (Queue 불필요)

2. **Virtual Threads Pinning** (次点)
   - `synchronized` 블록에서 Carrier Thread 점유
   - **해결**: `ReentrantLock`으로 교체 (Pinning 방지)

3. **Async Method의 Exception 전파**
   - `@Async` 메서드의 예외가 부모 스레드로 전파 안 됨
   - **해결**: `CompletableFuture.exceptionally()`로 명시적 처리

### 5.2 "Virtual Thread에서 Blocking 호출 하면?"

**상황**: Virtual Thread에서 JDBC 사용 (Blocking)

```java
Thread.ofVirtual().start(() -> {
    // JDBC는 Non-blocking을 지원하지 않음
    Connection conn = dataSource.getConnection();  // ⚠️ Pinning 발생!
    PreparedStatement ps = conn.prepareStatement("SELECT ...");
    ResultSet rs = ps.executeQuery();
    // Virtual Thread가 Carrier Thread를 점유한 채 대기
});
```

**Pinning 문제:**

```
Virtual Thread ──[Mounted]──> Carrier Thread (OS Thread 1)
    │
    │ synchronized(dataSource.getConnection())  // Pinning!
    │
    └─ 💀 Carrier Thread를 1,000ms 동안 점유 (다른 VT 못 먹음)
```

**해결책:**

1. **Non-blocking Driver 사용** (R2DBC)
2. **최대한 짧게 Lock 유지**
3. **Platform Thread로 대체** (Blocking 작업인 경우)

```java
// 나쁜 예: Virtual Thread에서 JDBC
Thread.ofVirtual().start(() -> {
    db.query("SELECT ...");  // Pinning!
});

// 좋은 예: Platform Thread에서 JDBC
Thread.ofPlatform().start(() -> {
    db.query("SELECT ...");  // OK (Blocking 허용)
});
```

### 5.3 "@Async 메서드의 예외를 어떻게 잡나?"

**상황**: `@Async` 메서드에서 예외 발생 시

```java
@Async
public void processAsync() {
    throw new RuntimeException("Async Error");
}

// Caller
public void caller() {
    processAsync();  // 예외가 잡히지 않음! 💀
    System.out.println("This will print");
}
```

**문제**: `@Async`는 별도 스레드에서 실행되므로, 예외가 부모로 전파 안 됨

**해결책 1: CompletableFuture 사용**

```java
@Async
public CompletableFuture<Void> processAsync() {
    try {
        doSomething();
        return CompletableFuture.completedFuture(null);
    } catch (Exception e) {
        return CompletableFuture.failedFuture(e);
    }
}

// Caller
public void caller() {
    processAsync()
        .exceptionally(e -> {
            log.error("Async failed", e);
            return null;
        });
}
```

**해결책 2: AsyncResult (Spring 4.3 이전)**

```java
@Async
public Future<String> processAsync() {
    try {
        return new AsyncResult<>("Success");
    } catch (Exception e) {
        return new AsyncResult<>(null);  // 예외 저장
    }
}
```

---

## 요약: 핵심 take-away

1. **Blocking I/O는 Thread를 낭비한다**: Non-blocking으로 교체하면 100배 더 처리 가능
2. **Virtual Thread는 "Lightweight Green Thread"**: 수백만 개 생성 가능, Context Switch 100倍 빠름
3. **Work-Stealing은 Idle Worker를 활용**: ForkJoinPool의 핵심 알고리즘
4. **@Async는 AOP Proxy로 구현**: Executor에 위임해서 비동기 실행
5. **100배 트래픽 대비**: Virtual Threads, Queue Capacity 증설, Exception Handling 명시화

---

**다음 챕터 예고**: "LogicExecutor와 AOP는 예외 처리를 어떻게 우아하게 만드는가? 템플릿 메서드의 미학"
