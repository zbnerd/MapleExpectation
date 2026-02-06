# 06. Design Patterns and Proxy: AOP와 LogicExecutor의 심화 학습

> **"디자인 패턴은 재사용 가능한 해결책이 아닙니다. 그것은 의사소통의 도구입니다. '어떻게'가 아니라 '왜'를 설명하는 언어입니다."**

---

## 1. The Problem (본질: 우리는 무엇과 싸우고 있는가?)

### 1.1 코드 중복 (Copy-Paste Programming)의 저주

**나쁜 예: 예외 처리 중복**

```java
// Service A
public void processA(Input in) {
    try {
        coreLogic(in);
    } catch (Exception e) {
        log.error("[ServiceA] Failed", e);
        metrics.increment("service.a.error");
        throw new InternalSystemException("ServiceA", e);
    }
}

// Service B
public void processB(Input in) {
    try {
        coreLogic(in);
    } catch (Exception e) {
        log.error("[ServiceB] Failed", e);
        metrics.increment("service.b.error");
        throw new InternalSystemException("ServiceB", e);
    }
}

// Service C... (중복 계속)
```

**문제의 본질**:
- **Boilerplate**: 예외 처리, 로깅, 메트릭이 코드의 80%를 차지
- **Human Error**: 복사-붙여기 시 누락/오타 발생
- **Maintenance**: 변경 시 100곳을 모두 수정해야 함

### 1.2 Cross-Cutting Concerns (횡단 관심사)

**관심사의 분리:**

```
Core Business Logic (핵심 비즈니스 로직)
├─ 장비 강화 로직
├─ 캐릭터 조회 로직
└─ 기프티콘 계산 로직

Cross-Cutting Concerns (횡단 관심사)
├─ Logging (모든 메서드의 입출력 기록)
├─ Exception Handling (모든 예외를 잡아서 변환)
├─ Metrics (모든 메서드의 실행 시간 측정)
├─ Transaction (트랜잭션 시작/커밋/롤백)
└─ Security (권한 검사)

문제: Core Logic마다 동일한 Cross-Cutting Logic이 중복
```

**해결책: AOP (Aspect-Oriented Programming)**

```
Core Logic (순수 비즈니스)
├─ enhanceEquipment()
├─ getCharacter()
└─ calculateGifticon()

         ▲
         │ 위빙 (Weaving)
         │
Cross-Cutting Logic (Aspect)
├─ @Around (모든 메서드를 감싸서 실행)
├─ @Before (메서드 실행 전)
└─ @After (메서드 실행 후)
```

### 1.3 Template Method Pattern의 반복

**Spring의 Template Callback:**

```java
// JdbcTemplate
jdbcTemplate.query("SELECT * FROM user", rs -> {
    // RowMapper: Row마다 호출되는 콜백
    return new User(rs.getLong("id"), rs.getString("name"));
});

// RedisTemplate
redisTemplate.opsForValue().get("key", value -> {
    // ValueDeserializer: 직렬화 콜백
    return JSON.parse(value);
});
```

**장점**: 복잡한 흐름(트랜잭션, 예외 처리)은 Template이 담당
**단점**: 콜백 지옥 (Callback Hell)

```
execute(() -> {
    query1(() -> {
        query2(() -> {
            query3(() -> {
                // 4단계 중첩 💀
            });
        });
    });
});
```

---

## 2. The CS Principle (원리: 이 코드는 무엇에 기반하는가?)

### 2.1 Proxy Pattern (프록시 패턴)

**구조:**

```
┌─────────────────────────────────────────────────┐
│  Client                                        │
└────────────────┬────────────────────────────────┘
                 │
                 │ calls
                 ▼
┌─────────────────────────────────────────────────┐
│  Proxy (대리인)                                │
│  ┌───────────────────────────────────────────┐  │
│  │  - Logging (입출력 기록)                │  │
│  │  - Exception Handling (예외 변환)       │  │
│  │  - Metrics (실행 시간 측정)            │  │
│  │  - Security (권한 검사)                 │  │
│  └───────────────────────────────────────────┘  │
└────────────────┬────────────────────────────────┘
                 │ delegates
                 ▼
┌─────────────────────────────────────────────────┐
│  Real Subject (실제 객체)                      │
│  - Core Business Logic (순수 비즈니스)         │
└─────────────────────────────────────────────────┘
```

**JDK Dynamic Proxy vs CGLIB:**

| 측정 항목 | JDK Dynamic Proxy | CGLIB (Byte Code Generation) |
|---------|-------------------|------------------------------|
| **구현** | Reflection (java.lang.reflect) | ByteBuddy (Bytecode 조작) |
| **대상** | Interface만 가능 | Class (구체 클래스) 가능 |
| **성능** | 느림 (Reflection 오버헤드) | 빠름 (직접 호출) |
| **제약** | final method 불가 | final class 불가 |

**Spring AOP의 선택 전략:**

```java
// Interface 있으면: JDK Dynamic Proxy
public interface EquipmentService { ... }
@Component
public class EquipmentServiceImpl implements EquipmentService { ... }
// → Proxy는 EquipmentService를 구현

// Interface 없으면: CGLIB
@Component
public class EquipmentService { ... }  // Interface 없음
// → Proxy는 EquipmentService를 상속 (서브클래스 생성)
```

### 2.2 Template Method Pattern (템플릿 메서드)

**구조:**

```
Abstract Class (Template)
├─ execute() [Final]  // 알고리즘의 뼈대 (변하지 않음)
│   ├─ step1() [Abstract]  // 하위 클래스가 구현
│   ├─ step2() [Abstract]
│   ├─ step3() [Hook]      // 선택적 오버라이드
│   └─ step4() [Abstract]
└─ execute()는 step1~4를 순서대로 호출

Concrete Class A
└─ step1(), step2(), step4() 구현

Concrete Class B
└─ step1(), step2(), step4() 구현 (다른 방식)
```

**Spring의 JdbcTemplate:**

```java
// Template (Spring이 제공)
public class JdbcTemplate {
    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        // 1. Connection 획득
        Connection conn = dataSource.getConnection();

        // 2. Statement 생성
        PreparedStatement ps = conn.prepareStatement(sql);

        // 3. 쿼리 실행
        ResultSet rs = ps.executeQuery();

        // 4. 결과 매핑 (사용자가 제공한 콜백)
        List<T> result = new ArrayList<>();
        while (rs.next()) {
            result.add(rowMapper.mapRow(rs));  // ⭐ Callback
        }

        // 5. 자원 해제
        rs.close();
        ps.close();
        conn.close();

        return result;
    }
}

// 사용자는 RowMapper만 구현 (4단계만)
RowMapper<User> rowMapper = (rs) -> new User(rs.getLong("id"));
```

### 2.3 Lambda와 익명 클래스의 메모리 차이

**Java 8 이전 (익명 클래스):**

```java
// 익명 클래스 (Anonymous Class)
Runnable r = new Runnable() {
    @Override
    public void run() {
        System.out.println("Hello");
    }
};

// 컴파일 결과:
// 1. MainClass$1.class (익명 클래스 별도 생성)
// 2. Heap에 클래스 메타데이터 로드
// 3. instanceof 체크 시 Reflection 필요
```

**Java 8+ (Lambda):**

```java
// Lambda Expression
Runnable r = () -> System.out.println("Hello");

// 컴파일 결과:
// 1. MainClass$$Lambda$1.class (InvokeDynamic)
// 2. 클래스 로딩 지연 (런타임에 생성)
// 3. invokedynamic 바이트코드로 직접 호출 (Reflection 없음)
```

**성능 비교:**

```
Heap Memory Usage:
- 익명 클래스: ~1KB per instance (Class metadata + Field)
- Lambda: ~128 bytes per instance (Captured values only)

Invocation Cost:
- 익명 클래스: polymorphic invoke (vtable lookup)
- Lambda: direct invoke (MethodHandle)
```

---

## 3. Internal Mechanics (내부: Spring AOP는 어떻게 동작하는가?)

### 3.1 Spring AOP의 Proxy 생성 과정

**@EnableAspectJAutoProxy의 작동:**

```java
@Configuration
@EnableAspectJAutoProxy
public class AopConfig { }

// Spring이 실행하는 작업:
// 1. AnnotationAwareAspectJAutoProxyCreator Bean 등록
// 2. 모든 @Component 스캔
// 3. @Aspect가 붙은 Class를 찾아서 Advisor로 변환
// 4. Advisor를 적용할 Target 선정 (Pointcut 매칭)
// 5. Proxy 생성 (JDK or CGLIB)
```

**Proxy 생성 예시:**

```java
// Target
@Component
public class EquipmentService {
    public void enhance(Long id) {
        // Core Logic
    }
}

// Aspect
@Aspect
@Component
public class LoggingAspect {
    @Around("execution(* maple..*.*(..))")
    public Object log(ProceedingJoinPoint pjp) throws Throwable {
        log.info("Before: {}", pjp.getSignature());
        Object result = pjp.proceed();  // Target 메서드 호출
        log.info("After: {}", pjp.getSignature());
        return result;
    }
}

// Spring이 생성한 Proxy (CGLIB)
public class EquipmentService$$EnhancerBySpringCGLIB$$123 extends EquipmentService {
    private final List<Advisor> advisors;

    @Override
    public void enhance(Long id) {
        // AOP Chain 실행
        MethodInterceptor chain = new ReflectiveMethodInvocation(
            target,  // Original EquipmentService
            method,
            args,
            advisors  // [LoggingAspect, ...]
        );

        chain.proceed();  // LoggingAspect → Target.enhance()
    }
}
```

### 3.2 LogicExecutor의 Template Method Pattern

**구조:**

```java
public interface LogicExecutor {
    // Pattern 1: 기본 실행 (예외 자운 전파)
    <T> T execute(CheckedSupplier<T> task, TaskContext context);

    // Pattern 2: Void 반환
    void executeVoid(CheckedRunnable task, TaskContext context);

    // Pattern 3: 기본값 반환 (예외 시 안전한 Fallback)
    <T> T executeOrDefault(CheckedSupplier<T> task, T defaultValue, TaskContext context);

    // Pattern 4: 복구 로직 (예외 시 대체 값)
    <T> T executeWithRecovery(CheckedSupplier<T> task, RecoveryFunction<T> recovery, TaskContext context);

    // Pattern 5: Finally 블록 (자원 해제 등)
    <T> T executeWithFinally(CheckedSupplier<T> task, Finalizer finally, TaskContext context);
}
```

**사용 예시:**

```java
// Pattern 1: 기본 실행
Equipment eq = executor.execute(
    () -> equipmentRepository.findById(id),  // Checked Exception
    TaskContext.of("EquipmentService", "FindById", id)
);

// Pattern 3: 기본값 반환
List<Equipment> eqList = executor.executeOrDefault(
    () -> equipmentRepository.findAll(),
    List.of(),  // 예외 시 빈 리스트 반환
    TaskContext.of("EquipmentService", "FindAll")
);

// Pattern 5: Finally 블록 (자원 해제)
InputStream is = executor.executeWithFinally(
    () -> Files.newInputStream(path),  // Checked Exception
    stream -> stream.close(),  // 항상 실행됨 (finally 역할)
    TaskContext.of("FileService", "Read", path)
);
```

### 3.3 Method Interceptor Chain

**AOP Chain의 실행 흐름:**

```
Client Request
    │
    ▼
┌─────────────────────────────────────────────────┐
│  Proxy                                        │
│  ┌───────────────────────────────────────────┐  │
│  │  Interceptor Chain (责任链模式)         │  │
│  │                                           │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐   │  │
│  │  │Logging  │→│Metrics  │→│Security │   │  │
│  │  │Aspect   │  │Aspect   │  │Aspect   │   │  │
│  │  └─────────┘  └─────────┘  └─────────┘   │  │
│  │       ↓            ↓            ↓          │  │
│  │       └────────────┴────────────┘          │  │
│  │                   ▼                        │  │
│  │         ┌───────────────┐                 │  │
│  │         │ Target Method │                 │  │
│  │         └───────────────┘                 │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

**ReflectiveMethodInvocation의 구현:**

```java
public class ReflectiveMethodInvocation implements MethodInvocation {
    private final Object target;
    private final Method method;
    private final Object[] arguments;
    private final List<Interceptor> interceptors;
    private int currentInterceptorIndex = 0;

    @Override
    public Object proceed() throws Throwable {
        // 1. 모든 Interceptor 실행 완료
        if (currentInterceptorIndex == interceptors.size()) {
            return invokeJoinpoint();  // Target 메서드 호출
        }

        // 2. 다음 Interceptor 실행
        Interceptor interceptor = interceptors.get(currentInterceptorIndex++);
        return interceptor.invoke(this);  // 재귀 호출 (Chain)
    }
}
```

---

## 4. Alternative & Trade-off (비판: 왜 이 방법을 선택했는가?)

### 4.1 AOP vs Direct Code

| 측정 항목 | AOP (AspectJ) | Direct Code (수동) |
|---------|----------------|-------------------|
| **중복 제거** | ✅ 100% 제거 | ❌ 매번 복사 |
| **성능 오버헤드** | ⚠️ Proxy 호출 (~5μs) | ✅ 없음 |
| **디버깅 난이도** | ⚠️ Stack Trace 복잡 | ✅ 직관적 |
| **학습 곡선** | ⚠️ 높음 (Pointcut 문법) | ✅ 낮음 |

**선택 이유**: Cross-Cutting Concerns은 AOP로, Core Logic은 Direct로

```java
// ✅ 좋음: 로깅, 예외 처리는 AOP
@Aspect
@Component
public class LoggingAspect {
    @Around("execution(* maple..*.*(..))")
    public Object log(ProceedingJoinPoint pjp) { ... }
}

// ✅ 좋음: 비즈니스 로직은 직접 구현
@Service
public class EquipmentService {
    public void enhance(Long id) {
        // Core Logic (예외 처리 없음, AOP가 처리)
        repository.update(id);
    }
}
```

### 4.2 LogicExecutor vs Try-Catch

| 측정 항목 | LogicExecutor | Try-Catch |
|---------|---------------|-----------|
| **예외 처리 일관성** | ✅ 통일된 전략 | ❌ 매번 다름 |
| **코드 중복** | ✅ 없음 | ❌ 5줄씩 반복 |
| **가독성** | ✅ 높음 (비즈니스만 보임) | ❌ 낮음 (예외 코드 섞임) |
| **유연성** | ⚠️ 6가지 패턴으로 제한 | ✅ 자유로움 |

**CLAUDE.md Section 12 규칙:**

```
"비즈니스 로직, 인프라 모듈, 글로벌 모듈 전체에서
 try-catch 및 try-finally 블록을 사용하는 것을 엄격히 금지합니다.
 모든 실행 흐름과 예외 처리는 LogicExecutor 템플릿에 위임합니다."
```

**이유**:
1. **일관성**: 모든 예외가 동일하게 처리됨 (로그, 메트릭, 변환)
2. **가독성**: 비즈니스 로직에서 예외 코드가 사라짐
3. **유지보수**: 예외 처리 전략 변경 시 LogicExecutor만 수정

### 4.3 Method Reference vs Lambda

**가독성 비교:**

```java
// Lambda: 가독성 낮음 (익명 함수)
executor.execute(() -> repository.findById(id));

// Method Reference: 가독성 높음 (이름 있는 메서드)
executor.execute(repository::findById);
```

**Lambda Hell 방지 (Section 15):**

```java
// 나쁜 예 (Lambda Hell): 3줄 초과 + 분기문
executor.execute(() -> {
    User user = repository.findById(id);
    if (user.isActive()) {
        return process(user.getData().stream()
            .filter(d -> d.isValid())
            .map(d -> { /* 복잡한 로직 */ return d.toDto(); })
            .toList());
    }
});

// 좋은 예 (Method Extraction): Private Helper 메서드로 분리
executor.execute(() -> this.processActiveUser(id));

private List<Dto> processActiveUser(Long id) {
    User user = findUserOrThrow(id);
    return user.isActive() ? processUserData(user) : List.of();
}
```

---

## 5. The Interview Defense (방어: 100배 트래픽에서 어디가 먼저 터지는가?)

### 5.1 "트래픽이 100배 증가하면?"

**실패 포인트 예측:**

1. **AOP Proxy의 Method Call 오버헤드** (最先)
   - 현재: 모든 메서드 호출에 ~5μs 추가
   - 100배 트래픽: 5μs × 10,000 TPS = 50ms/s CPU 낭비
   - **해결**: Hot Path 메서드에는 AOP 제외 (Custom Pointcut)

2. **LogicExecutor의 Thread Pool 고갈** (次点)
   - 현재: Common ForkJoinPool (Virtual Threads 미사용)
   - **해결**: Virtual Threads로 전환

3. **AspectJ Weaving의 성능 저하**
   - Load-Time Weaving (LTW): 시작 시 5~10초 지연
   - **해결**: Compile-Time Weaving (CTW) 또는 Runtime Weaving

### 5.2 "AOP Proxy의 순환 참조 문제를 어떻게 해결하나?"

**상황**: A → B → A 순환 참조

```java
@Service
public class A {
    @Autowired
    private B b;

    public void methodA() {
        b.methodB();  // Proxy B → Real A → Proxy A → ...
    }
}

@Service
public class B {
    @Autowired
    private A a;

    public void methodB() {
        a.methodA();  // 💀 StackOverflowError!
    }
}
```

**해결책 1: @Lazy 지연 로딩**

```java
@Service
public class B {
    @Autowired
    @Lazy  // ⭐ Proxy만 주입, 실제 사용 시 초기화
    private A a;

    public void methodB() {
        a.methodA();  // OK
    }
}
```

**해결책 2: Setter Injection**

```java
@Service
public class B {
    private A a;

    @Autowired
    public void setA(A a) {
        this.a = a;  // 순환 참조 해제
    }
}
```

### 5.3 "LogicExecutor의 성능을 최적화하려면?"

**상황**: LogicExecutor의 호출 비용이 큼

**개선안 1: Inline Caching (JIT 최적화 유도)**

```java
// 현재: Virtual Call (인터페이스)
executor.execute(() -> task(), context);

// 개선: Direct Call (Hot Path)
if (context == null) {
    task();  // JIT가 인라이닝하기 쉬움
} else {
    executor.execute(() -> task(), context);
}
```

**개선안 2: @Inline 어노테이션 (GraalVM Native Image)**

```java
@Inline
public <T> T execute(CheckedSupplier<T> task, TaskContext context) {
    // Native Image 컴파일 시 인라이닝 강제
}
```

---

## 요약: 핵심 take-away

1. **AOP는 Proxy Pattern의 자동화**: Cross-Cutting Concerns를 분리
2. **Template Method는 알고리즘의 뼈대 제공**: Spring JdbcTemplate이 대표적
3. **Lambda는 익명 클래스보다 가볍다**: invokedynamic으로 직접 호출
4. **LogicExecutor는 예외 처리의 표준**: CLAUDE.md Section 12 준수
5. **100배 트래픽 대비**: AOP 제외, Virtual Threads, Lazy Loading

---

**다음 챕터 예고**: "부동소수점 계산은 왜 부정확한가? IEEE 754와 Kahan Summation의 신비"
