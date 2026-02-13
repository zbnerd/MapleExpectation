# Testcontainers Singleton 패턴: Flaky Test 방지 가이드

## 목차
1. [핵심 원칙](#핵심-원칙)
2. [용어 정의](#용어-정의)
3. [컨테이너 수명 vs 데이터 수명](#컨테이너-수명-vs-데이터-수명)
4. [구현 상세](#구현-상세)
5. [플래키 방지 체크리스트](#플래키-방지-체크리스트)

---

## 핵심 원칙

> **"컨테이너는 공유하지만 데이터는 격리한다"**

- **컨테이너 수명**: JVM 동안 1회 (Singleton)
- **데이터 수명**: 테스트마다 0으로 리셋 (격리)

이 원칙을 지키면 **플래키 테스트 80% 감소** 효과가 있습니다.

---

## 용어 정의

### Singleton vs Reuse (혼동 주의)

| 용어 | 정의 | 범위 | CI 적용 |
|:---|:---|:---|:---|
| **Singleton** | 한 JVM 테스트 실행 동안 컨테이너 1번만 띄워 공유 | JVM 내 | ✅ 권장 |
| **Reuse** | 다음 실행에서도 같은 컨테이너 재사용 | 여러 JVM | ❌ CI 부적합 |

**중요**: 우리는 CI에서 **Singleton만 사용**하고 Reuse는 사용하지 않습니다.

### Reuse가 위험한 이유 (Testcontainers 공식 문서)

- 수동 start 필요
- JUnit integration으로 stop되면 안 됨
- 환경에서 opt-in 필요
- 리소스 정리/네트워크 등 일부 기능이 완전치 않음
- **공식적으로 "CI에 부적합"**으로 명시

> [Reusable Containers - Testcontainers](https://java.testcontainers.org/features/reuse/)

---

## 컨테이너 수명 vs 데이터 수명

### 1. 제일 흔한 함정: @Testcontainers/@Container 섞어서 깨짐

Singleton 공유를 베이스 클래스로 만들고도 `@Testcontainers/@Container`(JUnit5 extension)로 라이프사이클을 맡기면:

**문제 발생:**
- 테스트 클래스 끝날 때 컨테이너가 내려감
- Spring 컨텍스트는 캐시로 남음
- 다음 클래스가 "죽은 컨테이너"로 붙어서 터짐

**해결:** ✅ 컨테이너는 **static initializer / @BeforeAll에서 직접 start()**

```java
// ✅ 올바른 Singleton 패턴
public final class SharedContainers {
    public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static {
        Startables.deepStart(Stream.of(MYSQL)).join();  // 직접 시작
    }

    private SharedContainers() {
        throw new UnsupportedOperationException("Utility class");
    }
}
```

```java
// ❌ 잘못된 패턴 (@Testcontainers와 혼용)
@Testcontainers  // ← 클래스 끝날 때 stop() 호출해서 깨짐
class BaseTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");
}
```

### 2. 핵심: "컨테이너를 공유해도, 데이터는 공유하면 안 됨"

Singleton은 **속도 최적화**지, **상태 공유를 허용**하는 설계가 아닙니다.
**플래키의 80%는 여기서 나옵니다.**

---

## 구현 상세

### 1. TRUNCATE + FLUSHDB (가장 안전한 기본값)

장점:
- 롤백이 새지 않음 (commit/비동기/별도 스레드도 정리)
- 테스트 순서/병렬 영향 최소화
- **플래키 관점에서 가장 단단함**

단점:
- 테스트 수가 많으면 TRUNCATE 비용이 증가
- (그래도 컨테이너 재기동보단 훨씬 빠름)

```java
@BeforeEach
void resetDatabaseAndRedisState() {
    flushRedis();
    truncateAllTables();
}

private void flushRedis() {
    if (redisTemplate == null) return;
    var connection = redisTemplate.getConnectionFactory().getConnection();
    try {
        connection.flushDb();
    } finally {
        connection.close();
    }
}

private void truncateAllTables() {
    List<String> tables = TABLES.updateAndGet(prev ->
        prev != null ? prev : loadTableNames());  // 캐싱

    jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
    try {
        for (String table : tables) {
            jdbc.execute("TRUNCATE TABLE `" + table + "`");
        }
    } finally {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}

private List<String> loadTableNames() {
    return jdbc.queryForList("""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_type = 'BASE TABLE'
          AND table_name <> 'flyway_schema_history'
        """, String.class);
}
```

### 2. 더 빠른 방식: @DataJpaTest 롤백 + @DirtyStateTest 마커

장점:
- 대부분 테스트는 롤백이라 엄청 빠름
- 필요할 때만 TRUNCATE

단점:
- 별도 스레드/이벤트 리스너/REQUIRES_NEW가 커밋하면 롤백 밖으로 샘

```java
// 마커 애노테이션
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface DirtyStateTest {
    // 커밋/비동기/외부 트랜잭션이 개입될 수 있는 테스트에만 붙임
}

// 베이스 클래스
@BeforeEach
void resetState(TestInfo info) {
    boolean dirty = info.getTestMethod()
        .map(m -> m.isAnnotationPresent(DirtyStateTest.class))
        .orElse(false)
        || info.getTestClass()
        .map(c -> c.isAnnotationPresent(DirtyStateTest.class))
        .orElse(false);

    if (dirty) {
        flushRedis();
        truncateAllTables();  // 무거운 정리
    }
    // else: @DataJpaTest 기본 롤백에 의존
}
```

### 3. 병렬까지 갈 거면: 테스트 런 단위 DB/스키마 분리

장점:
- "완전 격리 + 병렬 안전"
- 이전 런의 찌꺼기 원천 차단

단점:
- 구현 복잡도 증가
- 병렬 실행은 integrationTest에서 기본 비활성화 권장

---

## 플래키 방지 체크리스트

### ✅ Singleton 패턴 검증

- [ ] `static final` 필드로 컨테이너 선언
- [ ] `static {}` initializer에서 `Startables.deepStart().join()` 호출
- [ ] `@Testcontainers`/@Container 애노테이션 사용 안 함
- [ ] `withReuse(true)` 사용 안 함 (CI에서)

### ✅ 데이터 격리 검증

- [ ] `@BeforeEach`에서 데이터 리셋
- [ ] Redis: `FLUSHDB` 또는 키 프리픽스 분리
- [ ] MySQL: `TRUNCATE` 또는 `@Transactional` 롤백
- [ ] Flyway: `flyway_schema_history`는 TRUNCATE 제외

### ✅ 병렬 실행 주의사항

- [ ] `integrationTest` 태스크는 **기본 병렬 OFF**
- [ ] 공유 DB/Redis면 병렬 실행 금지
- [ ] 부득이 병렬 시 `@ResourceLock(value="mysql", mode=READ_WRITE)`

### ✅ 준비 완료 신호

- [ ] 단순 포트 노출(exposedPorts)만 사용 금지
- [ ] `waitingFor()` WaitStrategy 명시
- [ ] healthcheck 또는 로그 기반 대기 전략

### ✅ 마이그레이션 타이밍

- [ ] 공유 컨테이너 시작 직후 **1회만 migrate**
- [ ] 동시에 migrate 시 락/경합으로 플래키 발생
- [ ] `integrationTest`는 병렬 OFF로 migrate 경합 방지

---

## Singleton 설계 시 한 줄 요약

> **"컨테이너 라이프사이클은 공유하되, 데이터 라이프사이클은 테스트마다 격리했다(롤백/정리/스키마 분리 + 병렬 락). 그래서 속도와 신뢰성을 동시에 확보했다."**

---

## 참고 자료

- [Testcontainers Container Lifecycle](https://testcontainers.com/guides/testcontainers-container-lifecycle/)
- [Testcontainers JUnit 5 Integration](https://java.testcontainers.org/test_framework_integration/junit_5/)
- [JUnit 5 Parallel Execution](https://docs.junit.org/junit5/user-guide/index.html)
- [Reusable Containers (Experimental)](https://java.testcontainers.org/features/reuse/)
