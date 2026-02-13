# Nightmare 16: Self-Invocation Mirage

> **담당 에이전트**: 🔵 Blue (아키텍처) & 🟢 Green (성능)
> **난이도**: P2 (Medium)
> **예상 결과**: PASS

---

## Test Evidence & Reproducibility

### 📋 Test Class
- **Class**: `SelfInvocationNightmareTest`
- **Package**: `maple.expectation.chaos.nightmare`
- **Source**: [`src/test/java/maple/expectation/chaos/nightmare/SelfInvocationNightmareTest.java`](../../../src/test/java/maple/expectation/chaos/nightmare/SelfInvocationNightmareTest.java)

### 🚀 Quick Start
```bash
# Prerequisites: Docker Compose running (MySQL, Redis)
docker-compose up -d

# Run specific Nightmare test
./gradlew test --tests "maple.expectation.chaos.nightmare.SelfInvocationNightmareTest" \
  2>&1 | tee logs/nightmare-16-$(date +%Y%m%d_%H%M%S).log

# Run individual test methods
./gradlew test --tests "*SelfInvocationNightmareTest.shouldNotHaveSelfInvocationInCodebase*"
./gradlew test --tests "*SelfInvocationNightmareTest.shouldUseSeparateBeanForCache*"
./gradlew test --tests "*SelfInvocationNightmareTest.shouldProxyMethodsWork*"
./gradlew test --tests "*SelfInvocationNightmareTest.shouldCacheHitOnExternalCall*"
./gradlew test --tests "*SelfInvocationNightmareTest.shouldTransactionWorkOnExternalCall*"
```

### 📊 Test Results
- **Result File**: [N16-self-invocation-result.md](../Results/N16-self-invocation-result.md) (if exists)
- **Test Date**: 2025-01-20
- **Result**: ✅ PASS (5/5 tests)
- **Test Duration**: ~60 seconds

### 🔧 Test Environment
| Parameter | Value |
|-----------|-------|
| Java Version | 21 |
| Spring Boot | 3.5.4 |
| AOP Proxy Type | CGLIB |
| @EnableAspectJAutoProxy | exposeProxy = true (not used) |

### 💥 Failure Injection
| Method | Details |
|--------|---------|
| **Failure Type** | AOP Bypass |
| **Injection Method** | this.method() internal call |
| **Failure Scope** | @Cacheable, @Transactional annotations |
| **Failure Duration** | N/A (architectural test) |
| **Blast Radius** | Cache misses, transaction boundaries |

### ✅ Pass Criteria
| Criterion | Threshold | Rationale |
|-----------|-----------|-----------|
| Self-Invocation Count | 0 | No proxy bypass |
| Cache Hit Rate | > 0 | @Cacheable works |
| Transaction Boundaries | Correct | @Transactional works |

### ❌ Fail Criteria
| Criterion | Threshold | Action |
|-----------|-----------|--------|
| Self-Invocation Count | > 0 | AOP bypassed |
| Cache Miss on 2nd Call | Yes | @Cacheable not working |
| Transaction Not Applied | Yes | @Transactional not working |

### 🧹 Cleanup Commands
```bash
# No cleanup needed - architectural test
# Verify AOP proxy configuration
curl http://localhost:8080/actuator/beans | grep -A 5 "@EnableAspectJAutoProxy"
```

### 📈 Expected Test Metrics
| Metric | Expected | Actual | Threshold |
|--------|----------|--------|-----------|
| Self-Invocation Patterns | 0 | 0 | = 0 |
| Cache Hit on 2nd Call | Yes | Yes | must hit |
| Transaction Applied | Yes | Yes | must apply |

### 🔗 Evidence Links
- Test Class: [SelfInvocationNightmareTest.java](../../../src/test/java/maple/expectation/chaos/nightmare/SelfInvocationNightmareTest.java)
- AOP Configuration: [AopConfig.java](../../../src/main/java/maple/expectation/config/AopConfig.java)
- Cache Service: Separate bean for caching operations

### ❌ Fail If Wrong
This test is invalid if:
- Test environment uses different AOP configuration
- Proxy type differs (JDK vs CGLIB)
- Spring AOP not properly enabled
- Test doesn't scan all relevant packages

---

## 0. 최신 테스트 결과 (2025-01-20)

### ✅ PASS (5/5 테스트 성공)

| 테스트 메서드 | 결과 | 설명 |
|-------------|------|------|
| `shouldNotHaveSelfInvocationInCodebase()` | ✅ PASS | Self-Invocation 패턴 미발견 |
| `shouldUseSeparateBeanForCache()` | ✅ PASS | 캐시 로직 별도 Bean 분리 |
| `shouldProxyMethodsWork()` | ✅ PASS | Proxy 메서드 정상 동작 |
| `shouldCacheHitOnExternalCall()` | ✅ PASS | 외부 호출 시 캐시 적중 |
| `shouldTransactionWorkOnExternalCall()` | ✅ PASS | 외부 호출 시 트랜잭션 동작 |

### 🟢 성공 원인
- **Bean 분리 패턴**: 캐시/트랜잭션 로직이 별도 서비스로 분리됨
- **IntelliJ Inspection 활성화**: Self-invocation 경고 0건
- **코드 리뷰 체크리스트**: Self-invocation 패턴 확인 항목 포함

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
동일 클래스 내에서 this.method() 호출 시 @Cacheable, @Transactional 등
AOP 어노테이션이 동작하지 않는 Self-Invocation 문제를 검증한다.

### 검증 포인트
- [ ] @Cacheable self-invocation 바이패스
- [ ] @Transactional self-invocation 바이패스
- [ ] AopContext.currentProxy() 해결책

### 성공 기준
- Self-invocation 발생 0건

---

## 2. 문제 상황 (🔴 Red's Analysis)

### Self-Invocation 예시
```java
@Service
public class UserService {
    public UserDto getUser(Long id) {
        // ... 검증 로직 ...
        return this.getCachedUser(id);  // ❌ Self-invocation!
    }

    @Cacheable("users")
    public UserDto getCachedUser(Long id) {
        return userRepository.findById(id);  // 캐시 무시됨!
    }
}
```

### 왜 동작하지 않는가?
```
External call:
Client → Spring Proxy → UserService.getUser()
           ↑ AOP 동작

Internal call (this):
UserService.getUser() → this.getCachedUser()
                         ↑ Proxy 우회! AOP 동작 안 함
```

---

## 3. 해결 방안

### 방법 1: 별도 Bean 분리
```java
@Service
public class UserService {
    private final UserCacheService cacheService;

    public UserDto getUser(Long id) {
        return cacheService.getCachedUser(id);  // ✅ 외부 호출
    }
}

@Service
public class UserCacheService {
    @Cacheable("users")
    public UserDto getCachedUser(Long id) {
        return userRepository.findById(id);
    }
}
```

### 방법 2: AopContext.currentProxy()
```java
@Service
public class UserService {
    public UserDto getUser(Long id) {
        UserService proxy = (UserService) AopContext.currentProxy();
        return proxy.getCachedUser(id);  // ✅ Proxy 통해 호출
    }

    @Cacheable("users")
    public UserDto getCachedUser(Long id) { ... }
}

// 설정 필요
@EnableAspectJAutoProxy(exposeProxy = true)
```

### 방법 3: @Lazy Self-Injection
```java
@Service
public class UserService {
    @Lazy
    @Autowired
    private UserService self;  // Proxy 주입

    public UserDto getUser(Long id) {
        return self.getCachedUser(id);  // ✅ Proxy 통해 호출
    }
}
```

---

## 4. 감지 방법

### IntelliJ Inspection
`Editor → Inspections → Spring → Spring Core → Self-invocation bypasses Spring proxy`

### ArchUnit 규칙
```java
@ArchTest
static final ArchRule no_self_invocation =
    methods().that().areAnnotatedWith(Cacheable.class)
        .or().areAnnotatedWith(Transactional.class)
        .should(not(beCalledByMethod().thatIsDeclaredInSameClass()));
```

---

## 5. 관련 CS 원리

### Proxy Pattern
Spring AOP는 JDK Dynamic Proxy 또는 CGLIB로 프록시 생성.
프록시는 외부 호출만 가로챔.

### this 참조의 의미
Java에서 `this`는 현재 객체의 실제 인스턴스를 참조.
프록시 객체가 아닌 원본 객체를 가리킴.

---

## 6. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **PASS**

프로젝트 코드베이스에서 **Self-Invocation 패턴이 발견되지 않음**.
모든 @Cacheable, @Transactional 메서드가 외부 호출을 통해 사용됨.

### 기술적 인사이트
- **Bean 분리 패턴**: 캐시 로직이 별도 서비스로 분리됨
- **Proxy 우회 없음**: this.method() 형태의 내부 호출 없음
- **AOP 정상 동작**: 모든 어노테이션이 Proxy를 통해 정상 작동
- **IntelliJ Inspection**: Self-invocation 경고 0건 확인

### 권장 유지 사항
1. **Bean 분리 원칙**: 캐시/트랜잭션 로직은 별도 서비스로 분리
2. **코드 리뷰 체크리스트**: Self-invocation 패턴 확인 항목 포함
3. **IntelliJ Inspection 활성화**: Spring Self-invocation 검사 설정
4. **ArchUnit 규칙 추가**: 자동화된 Self-invocation 감지 테스트

---

## Fail If Wrong

This test is invalid if:
- [ ] Test environment uses different AOP configuration
- [ ] Proxy type differs (JDK vs CGLIB)
- [ ] Spring AOP not properly enabled
- [ ] Test doesn't scan all relevant packages
- [ ] AspectJ weaving mode differs

---

*Generated by 5-Agent Council*
