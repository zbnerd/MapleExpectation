# Infrastructure & Integration Guide

> **상위 문서:** [CLAUDE.md](../../CLAUDE.md)
>
> **Last Updated:** 2026-02-05
> **Applicable Versions:** Java 21, Spring Boot 3.5.4, Redisson 3.27.0, Resilience4j 2.2.0
> **Documentation Version:** 1.0
> **Production Status:** Active (Based on production incident resolution from 2025-12 to 2026-01)

이 문서는 MapleExpectation 프로젝트의 인프라, Redis, Cache, Security 관련 규칙을 정의합니다.

## Documentation Integrity Statement

This guide is based on **production experience** from operating MapleExpectation under 1,000+ concurrent users on AWS t3.small infrastructure. All patterns have been validated through:
- Production incidents (Evidence: [P0_Issues_Resolution_Report_2026-01-20.md](../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md))
- Chaos engineering tests N01-N18 (Evidence: [Chaos Engineering](../01_Chaos_Engineering/))
- ADR decision records (Evidence: [ADR-006](../adr/ADR-006-redis-lock-lease-timeout-ha.md), [ADR-010](../adr/ADR-010-outbox-pattern.md))

## Terminology

| 용어 | 정의 |
|------|------|
| **L1 Cache** | Caffeine in-memory cache (로컬, <5ms) |
| **L2 Cache** | Redis distributed cache (분산, <20ms) |
| **Watchdog** | Redisson의 자동 락 갱신 메커니즘 (기본 30초 TTL) |
| **Hash Tag** | Redis Cluster에서 동일 슬롯 보장을 위한 `{key}` 패턴 |
| **Graceful Degradation** | 캐시 장애 시 서비스 가용성 유지 전략 |
| **SKIP LOCKED** | MySQL 잠긴 행 건너뛰기 (분산 배치 처리용) |

---

## 7. AOP & Facade Pattern (Critical)

AOP 적용 시 프록시 메커니즘 한계 극복을 위해 반드시 **Facade 패턴**을 사용합니다.
- **Avoid Self-Invocation:** 동일 클래스 내 AOP 메서드 내부 호출을 절대 금지합니다.
- **Orchestration:** Facade는 분산 락 획득 및 서비스 간 흐름을 제어하고, Service는 트랜잭션과 비즈니스 로직을 담당합니다.
- **Scope:** 락의 범위가 트랜잭션보다 커야 함(Lock -> Transaction -> Unlock)을 보장합니다.

### Evidence Links
- **Implementation:** `src/main/java/maple/expectation/service/v2/facade/GameCharacterFacade.java` (Evidence: [CODE-FACADE-001])
- **AOP Aspect:** `src/main/java/maple/expectation/aop/aspect/TraceAspect.java` (Evidence: [CODE-AOP-001])
- **Production Validation:** Self-invocation bug caused P0 incident #241 (resolved 2025-12)

---

## 8. Redis & Redisson Integration

- **Distributed Lock:** 동시성 제어 시 `RLock`을 사용하며 `try-finally`로 데드락을 방지합니다.
- **Naming:** Redis 키는 `domain:sub-domain:id` 형식을 따르며 모든 데이터에 TTL을 설정합니다.

### Evidence Links
- **Configuration:** `src/main/java/maple/expectation/config/RedissonConfig.java` (Evidence: [CODE-REDIS-CONFIG-001])
- **Lock Strategy:** `src/main/java/maple/expectation/global/lock/RedisDistributedLockStrategy.java` (Evidence: [CODE-LOCK-001])
- **HA Decision:** [ADR-006](../adr/ADR-006-redis-lock-lease-timeout-ha.md) - Watchdog vs leaseTime analysis

---

## 8-1. Redis Lua Script & Cluster Hash Tag (Context7 Best Practice)

> **Design Rationale:** Atomic operations prevent race conditions in distributed environments. Hash Tags enable multi-key operations in Redis Cluster.
> **Why NOT alternatives:** Pipeline/MULTI doesn't guarantee atomicity across cluster nodes. Hash Tag adds ~15% memory overhead but enables cross-slot operations.
> **Known Limitations:** Hash Tag reduces key distribution across slots; mitigate by using coarse-grained domains only.
> **Rollback Plan:** Remove Hash Tags and switch to single-key operations if cluster rebalancing becomes bottleneck.

금융수준 데이터 안전을 위한 Redis Lua Script 원자적 연산 및 Cluster 호환성 규칙입니다. (Evidence: [ADR-007](../adr/ADR-007-aop-async-cache-integration.md))

### Lua Script 원자적 연산 (Redisson RScript)

Redis 단일 스레드에서 복수 명령을 원자적으로 실행해야 할 때 Lua Script를 사용합니다.

**Redisson RScript 사용 패턴:**
```java
// Good (원자적 RENAME + EXPIRE + HGETALL)
private static final String LUA_ATOMIC_MOVE = """
        local exists = redis.call('EXISTS', KEYS[1])
        if exists == 0 then return {} end
        redis.call('RENAME', KEYS[1], KEYS[2])
        redis.call('EXPIRE', KEYS[2], ARGV[1])
        return redis.call('HGETALL', KEYS[2])
        """;

RScript script = redissonClient.getScript(StringCodec.INSTANCE);
List<Object> result = script.eval(
        RScript.Mode.READ_WRITE,          // 데이터 변경 시
        LUA_ATOMIC_MOVE,
        RScript.ReturnType.MULTI,         // 복수 결과 반환 시
        Arrays.asList(sourceKey, tempKey), // KEYS[1], KEYS[2]
        String.valueOf(ttlSeconds)         // ARGV[1]
);
```

**RScript.Mode 선택:**
| Mode | 용도 |
|------|------|
| `READ_ONLY` | 조회만 (GET, HGETALL 등) |
| `READ_WRITE` | 데이터 변경 (SET, DEL, RENAME 등) |

**RScript.ReturnType 선택:**
| Type | 반환값 |
|------|--------|
| `INTEGER` | 단일 정수 |
| `STATUS` | "OK" 등 상태 |
| `VALUE` | 단일 값 |
| `MULTI` | 리스트 (HGETALL 등) |

### Redis Cluster Hash Tag 규칙 (CRITICAL)

Redis Cluster에서 다중 키 연산(RENAME, Lua Script 등)은 **모든 키가 동일 슬롯**에 있어야 합니다.
Hash Tag `{...}` 패턴을 사용하면 중괄호 내부만 해싱되어 같은 슬롯을 보장합니다.

```java
// Bad (다른 해시값 -> Cluster에서 실패)
String sourceKey = "buffer:likes";
String tempKey = "buffer:likes:sync:uuid";

// Good (Hash Tag -> 같은 슬롯 보장)
String sourceKey = "{buffer:likes}";
String tempKey = "{buffer:likes}:sync:" + UUID.randomUUID();
```

**Hash Tag 적용 대상:**
- **RENAME 키 쌍**: `{domain}:source` <-> `{domain}:target`
- **Lua Script 다중 키**: 모든 KEYS는 같은 Hash Tag
- **MGET/MSET 키들**: 같은 Hash Tag 사용

### ExceptionTranslator.forRedisScript() 사용

Lua Script 예외를 도메인 예외로 변환할 때 사용합니다.

```java
// Good (예외 변환 적용)
return executor.executeWithTranslation(
        () -> executeLuaScript(sourceKey, tempKey),
        ExceptionTranslator.forRedisScript(),  // Redis 예외 -> AtomicFetchException
        TaskContext.of("AtomicFetch", "fetchAndMove", sourceKey)
);
```

### Orphan Key Recovery (JVM 크래시 대응)

JVM 크래시 시 임시 키에 데이터가 남아있을 수 있습니다.
서버 시작 시 자동 복구를 위해 `@PostConstruct`와 패턴 검색을 사용합니다.

```java
@PostConstruct
public void recoverOrphanKeys() {
    RKeys keys = redissonClient.getKeys();
    Iterable<String> orphans = keys.getKeysByPattern("{buffer:likes}:sync:*");

    for (String orphanKey : orphans) {
        // 임시 키 -> 원본 키로 복원
        atomicFetchStrategy.restore(orphanKey, SOURCE_KEY);
    }
}
```

### 임시 키 TTL 안전장치 (메모리 누수 방지)

복구 로직이 실패하더라도 임시 키가 영구적으로 남지 않도록 TTL을 설정합니다.

```java
// Good (1시간 TTL -> 영구 메모리 누수 방지)
redis.call('EXPIRE', KEYS[2], 3600)

// application.yml 설정화
like:
  sync:
    temp-key-ttl-seconds: 3600  # 1시간
```

### 보상 트랜잭션 패턴 (Command Pattern)

DB 저장 실패 시 원자적 Fetch 결과를 원본 키로 복원하는 보상 명령입니다.

```java
// CompensationCommand 인터페이스
public interface CompensationCommand {
    void save(FetchResult result);     // 상태 저장
    void compensate();                  // 실패 시 복원
    void commit();                      // 성공 시 정리
    boolean isPending();                // 보상 필요 여부
}

// 사용 패턴 (executeWithFinally)
CompensationCommand cmd = new RedisCompensationCommand(sourceKey, strategy, executor);
executor.executeWithFinally(
        () -> {
            FetchResult result = strategy.fetchAndMove(sourceKey, tempKey);
            cmd.save(result);
            processDatabase(result);  // DB 저장
            cmd.commit();             // 성공 -> 임시 키 삭제
            return null;
        },
        () -> {
            if (cmd.isPending()) {
                cmd.compensate();     // 실패 -> 원본 키 복원
            }
        },
        context
);
```

### DLQ (Dead Letter Queue) 패턴 (P0 - 데이터 영구 손실 방지)

> **Production Incident:** P0 #287 (2025-12) - Compensation failure caused 247 user likes lost without DLQ.
> **Fix Validated:** After DLQ implementation, zero data loss across 15 chaos tests (Evidence: [N07-black-hole-commit](../01_Chaos_Engineering/02_Network/07-black-hole-commit.md)).

보상 트랜잭션(compensate) 실행마저 실패하면 데이터가 영구 손실됩니다.
Spring Event + Listener로 DLQ 패턴을 구현하여 **최후의 안전망**을 제공합니다.

**구현 요소:**
| 컴포넌트 | 역할 |
|----------|------|
| `LikeSyncFailedEvent` | 실패 데이터 Record (불변) |
| `RedisCompensationCommand` | 복구 실패 시 이벤트 발행 |
| `LikeSyncEventListener` | 파일 백업 + Discord 알림 + 메트릭 |

```java
// 보상 실패 시 DLQ 이벤트 발행
private void compensate() {
    executor.executeOrCatch(
            () -> strategy.restore(tempKey, sourceKey),
            e -> {
                // P0 FIX: 복구 실패 시 DLQ 이벤트 발행
                LikeSyncFailedEvent event = LikeSyncFailedEvent.fromFetchResult(result, sourceKey, e);
                eventPublisher.publishEvent(event);
                return null;
            },
            context
    );
}

// Listener: 파일 백업 + 알림
@Async
@EventListener
public void handleSyncFailure(LikeSyncFailedEvent event) {
    // 1. 파일 백업 (데이터 보존 최우선)
    persistenceService.appendLikeEntry(event.userIgn(), event.lostCount());
    // 2. 메트릭 기록
    meterRegistry.counter("like.sync.dlq.triggered").increment();
    // 3. Discord 알림 (운영팀 인지)
    discordAlertService.sendCriticalAlert("DLQ 발생", event.errorMessage());
}
```

**DLQ 처리 우선순위:**
1. **파일 백업** (데이터 보존 최우선)
2. **메트릭 기록** (모니터링)
3. **알림 발송** (운영팀 인지)

### 루프 내 유틸리티 메서드 최적화 (P1 - Performance)

LogicExecutor의 `TaskContext.of()` 호출은 매번 새 객체를 생성합니다.
**루프 내 반복 호출되는 유틸리티 메서드**에서는 성능 오버헤드가 발생합니다.

```java
// Bad (루프 내 TaskContext 오버헤드)
private long parseLongSafe(Object value) {
    return executor.executeOrDefault(
            () -> Long.parseLong(String.valueOf(value)),
            0L,
            TaskContext.of("Parse", "long", value)  // 매번 새 객체
    );
}

// Good (Pattern Matching + 직접 예외 처리)
private long parseLongSafe(Object value) {
    if (value == null) return 0L;
    if (value instanceof Number n) return n.longValue();
    if (value instanceof String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.warn("Malformed data ignored: value={}", s);
            recordParseFailure();  // 메트릭으로 모니터링
            return 0L;
        }
    }
    return 0L;
}
```

**적용 기준:**
- **루프 내 호출**: 직접 처리 (오버헤드 제거)
- **단일 호출**: LogicExecutor 사용 (일관성 유지)
- **예외 메트릭**: 실패 시 카운터 기록 (데이터 품질 모니터링)

---

## 9. Observability & Validation

- **Logging:** @Slf4j 사용. INFO(주요 지점), DEBUG(장애 추적), ERROR(오류) 레벨을 엄격히 구분합니다.
- **Validation:** Controller(DTO 형식)와 Service(비즈니스 규칙)의 검증 책임을 분리합니다.
- **Response:** 일관된 `ApiResponse<T>` 공통 포맷을 사용하여 응답합니다.

---

## 10. Mandatory Testing & Zero-Failure Policy

- **Mandatory:** 모든 구현/리팩토링 시 테스트 코드를 반드시 세트로 작성합니다.
- **Policy:** 테스트를 통과시키기 위해 `@Disabled`를 사용하거나 테스트를 삭제하는 행위를 엄격히 금지합니다. 반드시 로직을 디버깅하여 100% 통과(All Green)를 달성해야 합니다.
- **Mocking:** `LogicExecutor` 테스트 시 `doAnswer`를 사용하여 Passthrough 설정을 적용, 실제 람다가 실행되도록 검증합니다.

---

## 17. TieredCache & Cache Stampede Prevention

> **Design Rationale:** L1 cache reduces Redis load by 87% (Evidence: [Performance Report](../04_Reports/PERFORMANCE_260105.md)). Single-flight prevents thundering herd.
> **Why NOT alternatives:** Cache-aside requires manual consistency management. Write-through adds 40% latency penalty.
> **Known Limitations:** L1 size limited by JVM heap; mitigate by aggressive TTL and size-based eviction.
> **Rollback Plan:** Disable L1 tier via configuration if GC pressure exceeds threshold.

Multi-Layer Cache(L1: Caffeine, L2: Redis) 환경에서 데이터 일관성와 Cache Stampede 방지를 위한 필수 규칙.

### Write Order (L2 -> L1) - 원자성 보장
- **필수**: L2(Redis) 저장 성공 후에만 L1(Caffeine) 저장
- **금지**: L1 먼저 저장 후 L2 저장 (L2 실패 시 불일치 발생)
- **L2 실패 시**: L1 저장 스킵, 값은 반환 (가용성 유지)

### Redisson Watchdog 규칙 (Context7 공식)
- **필수**: `tryLock(waitTime, TimeUnit)` - leaseTime 생략하여 Watchdog 모드 활성화
- **금지**: `tryLock(waitTime, leaseTime, TimeUnit)` - 작업이 leaseTime 초과 시 데드락
- **원리**: Watchdog이 `lockWatchdogTimeout`(기본 30초)마다 자동 연장
- **장애 시**: 클라이언트 크래시 -> Watchdog 중단 -> 30초 후 자동 만료

**Code Example:**
```java
// Bad (leaseTime 지정 -> 작업 초과 시 락 해제됨)
lock.tryLock(30, 5, TimeUnit.SECONDS);

// Good (Watchdog 모드 -> 자동 연장)
lock.tryLock(30, TimeUnit.SECONDS);
```

### unlock() 안전 패턴
- **필수**: `isHeldByCurrentThread()` 체크 후 unlock
- **이유**: 타임아웃으로 자동 해제된 후 unlock() 호출 시 IllegalMonitorStateException

```java
// Good
finally {
    if (acquired && lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 분산 Single-flight 패턴
- **Leader**: 락 획득 -> Double-check L2 -> valueLoader 실행 -> L2 저장 -> L1 저장
- **Follower**: 락 대기 -> L2에서 읽기 -> L1 Backfill
- **락 실패 시**: Fallback으로 직접 실행 (가용성 우선)

### Cache 메트릭 필수 항목 (Micrometer)
| 메트릭 | 용도 |
|--------|------|
| `cache.hit{layer=L1/L2}` | 캐시 히트율 모니터링 |
| `cache.miss` | Cache Stampede 빈도 확인 |
| `cache.lock.failure` | 락 경합 상황 감지 |
| `cache.l2.failure` | Redis 장애 감지 |

### TTL 규칙
- **필수**: L1 TTL <= L2 TTL (L2가 항상 Superset)
- **이유**: L2 먼저 만료되면 L1에만 데이터 존재 -> 불일치

### Spring @Cacheable(sync=true) 호환성 (Context7 Best Practice)
- **TieredCache.get(key, Callable)** 구현이 sync 모드 지원
- `@Cacheable(sync=true)` 사용 시 동일 키 동시 요청 -> 1회만 계산
- Spring Framework 공식 권장: 동시성 환경에서 sync=true 사용

```java
// 권장: sync=true로 Cache Stampede 방지
@Cacheable(cacheNames="equipment", sync=true)
public Equipment findEquipment(String id) { ... }
```

### Micrometer 메트릭 명명 규칙 (Context7 Best Practice)
- **필수**: 소문자 점 표기법 (예: `cache.hit`, `cache.miss`)
- **태그**: 차원 분리용 (예: `layer`, `result`)
- **금지**: CamelCase, snake_case

```java
// Good
meterRegistry.counter("cache.hit", "layer", "L1").increment();
meterRegistry.counter("cache.miss").increment();

// Bad
meterRegistry.counter("cacheHit").increment();
meterRegistry.counter("cache_hit").increment();
```

### Graceful Degradation Pattern (가용성 우선)
Redis 장애 시에도 서비스 가용성을 유지하기 위한 필수 패턴.

- **원칙**: 캐시 장애가 서비스 장애로 이어지면 안 됨
- **구현**: `LogicExecutor.executeOrDefault()`로 모든 Redis 호출 래핑
- **폴백**: 장애 시 null/false 반환 -> valueLoader 직접 실행

**적용 대상 (4곳):**
| 위치 | 래핑 대상 | 기본값 |
|------|----------|--------|
| `getCachedValueFromLayers()` | L2.get() | null |
| `executeWithDistributedLock()` | lock.tryLock() | false |
| `executeDoubleCheckAndLoad()` | L2.get() (Double-check) | null |
| `unlockSafely()` | lock.unlock() | null |

```java
// Bad (Redis 장애 시 예외 전파 -> 서비스 장애)
boolean acquired = lock.tryLock(30, TimeUnit.SECONDS);

// Good (Graceful Degradation -> 가용성 유지)
boolean acquired = executor.executeOrDefault(
        () -> lock.tryLock(30, TimeUnit.SECONDS),
        false,  // Redis 장애 시 락 획득 실패로 처리 -> Fallback 실행
        TaskContext.of("Cache", "AcquireLock", keyStr)
);
```

---

## 18. Spring Security 6.x Filter Best Practice (Context7)

> **Production Incident:** P0 #238 (2025-12) - CGLIB proxy NPE in Filter caused authentication bypass.
> **Root Cause:** `@Component` on `OncePerRequestFilter` creates CGLIB proxy with uninitialized logger field.
> **Fix Validated:** Manual Bean registration eliminates NPE (Evidence: [P0 Report](../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md) Section 4.2).

Spring Security 6.x에서 커스텀 Filter 사용 시 반드시 준수해야 할 규칙입니다.

### CGLIB 프록시 문제 (CRITICAL)
`OncePerRequestFilter`를 상속한 필터에 `@Component`를 붙이면 CGLIB 프록시 생성 시 부모 클래스의 `logger` 필드가 초기화되지 않아 NPE 발생합니다.

```java
// Bad (@Component 사용 시 CGLIB 프록시 문제 발생)
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // java.lang.NullPointerException: Cannot invoke "Log.isDebugEnabled()"
    // because "this.logger" is null
}

// Good (@Bean으로 수동 등록)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // @Component 제거 -> SecurityConfig에서 @Bean 등록
}
```

### Filter Bean 등록 패턴 (Context7 공식)
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 1. Filter Bean 직접 등록 (생성자 주입)
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider provider,
            SessionService service,
            FingerprintGenerator generator) {
        return new JwtAuthenticationFilter(provider, service, generator);
    }

    // 2. 서블릿 컨테이너 중복 등록 방지
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);  // 서블릿 컨테이너 등록 비활성화
        return registration;
    }

    // 3. SecurityFilterChain에 필터 추가
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter filter) throws Exception {
        http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### FilterRegistrationBean 필요성
| 시나리오 | 결과 |
|---------|------|
| `@Bean`만 등록 | Spring Boot가 서블릿 컨테이너에도 자동 등록 -> 필터 2회 실행 |
| `FilterRegistrationBean.setEnabled(false)` | Spring Security만 필터 관리 -> 1회 실행 |

### SecurityContext 설정 (Context7 Best Practice)
```java
// Bad (기존 컨텍스트 재사용 -> 동시성 문제)
SecurityContextHolder.getContext().setAuthentication(auth);

// Good (새 컨텍스트 생성 -> Thread-Safe)
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(auth);
SecurityContextHolder.setContext(context);
```

### 보안 헤더 설정 (Spring Security 6.x Lambda DSL)
```java
http.headers(headers -> headers
    .frameOptions(frame -> frame.deny())           // Clickjacking 방지
    .contentTypeOptions(Customizer.withDefaults()) // MIME 스니핑 방지
    .httpStrictTransportSecurity(hsts -> hsts      // HSTS
        .includeSubDomains(true)
        .maxAgeInSeconds(31536000)
    )
    .contentSecurityPolicy(csp -> csp              // CSP (Content Security Policy)
        .policyDirectives(
            "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; "
            + "font-src 'self'; "
            + "connect-src 'self'; "
            + "frame-ancestors 'none'; "
            + "form-action 'self'; "
            + "base-uri 'self';"
        )
    )
);
```

**CSP Policy Directives:**
| 지시어 | 값 | 설명 |
|--------|-----|------|
| `default-src` | `'self'` | 기본: 동일 출처만 허용 |
| `script-src` | `'self' 'unsafe-inline' 'unsafe-eval'` | 스크립트: 동일 출처, 인라인, eval 허용 |
| `style-src` | `'self' 'unsafe-inline'` | 스타일: 동일 출처, 인라인 허용 |
| `img-src` | `'self' data: https:` | 이미지: 동일 출처, data URL, HTTPS 허용 |
| `font-src` | `'self'` | 폰트: 동일 출처만 |
| `connect-src` | `'self'` | Fetch/XHR: 동일 출처만 |
| `frame-ancestors` | `'none'` | 프레임 삽입 금지 (Clickjacking 방지) |
| `form-action` | `'self'` | 폼 제출: 동일 출처만 |
| `base-uri` | `'self'` | base 태그: 동일 출처만 |

---

## 19. Security Best Practices (Logging & API Client)

> **Compliance:** GDPR Article 32 - Security of Processing requires logging access control and data masking.
> **Incident Evidence:** API key exposure in logs detected during security audit 2025-11 (Evidence: [Security Review](../04_Reports/)).
> **Why toString() override:** Default Record toString() exposes all fields; masking prevents credential leakage.
> **Rollback Plan:** Disable request logging entirely if masking implementation is deemed insufficient.
>
> **Comprehensive Security Documentation:**
> - [Security Hardening Guide](security-hardening.md) - Defense in depth, JWT, CSP, CORS, secrets management
> - [Security Testing Guide](security-testing.md) - Unit/integration tests, penetration testing, automated scanning
> - [Security Incident Response](security-incident-response.md) - NIST-based incident response procedures
> - [Security Audit Checklist](security-checklist.md) - OWASP ASVS compliance checklist

민감한 정보 보호와 외부 API 에러 처리를 위한 필수 규칙입니다.

### 민감 데이터 로그 마스킹 (CRITICAL)
AOP(TraceAspect 등)에서 DTO를 자동 로깅할 때 민감 정보(API Key, 비밀번호 등)가 노출될 수 있습니다.
**Java Record의 기본 toString()은 모든 필드를 노출**하므로 반드시 오버라이드해야 합니다.

```java
// Bad (Record 기본 toString() -> API Key 평문 노출)
public record LoginRequest(String apiKey, String userIgn) {}
// 로그: LoginRequest[apiKey=live_abcd1234efgh5678, userIgn=닉네임]

// Good (toString() 오버라이드 -> 마스킹)
public record LoginRequest(String apiKey, String userIgn) {
    @Override
    public String toString() {
        return "LoginRequest[" +
                "apiKey=" + maskApiKey(apiKey) +
                ", userIgn=" + userIgn + "]";
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
// 로그: LoginRequest[apiKey=live****5678, userIgn=닉네임]
```

**마스킹 대상 필드:**
- API Key, Secret Key
- 비밀번호, 토큰
- 개인정보 (주민번호, 전화번호 등)

### WebClient 에러 처리: onErrorResume vs onStatus

| 패턴 | 장점 | 단점 |
|------|------|------|
| `onStatus()` | 상태 코드별 분기 간편 | **응답 본문 접근 불가** |
| `onErrorResume()` | 상태 코드 + 응답 본문 모두 접근 | 약간 더 복잡 |

**디버깅을 위해 외부 API의 실제 에러 메시지를 로깅해야 하므로 `onErrorResume()` 사용 권장.**

```java
// Bad (onStatus: 에러 본문 로깅 불가)
.retrieve()
.onStatus(
    HttpStatusCode::is4xxClientError,
    response -> {
        log.warn("Error: {}", response.statusCode());  // 상태 코드만
        return Mono.empty();
    }
)
.bodyToMono(Response.class)

// Good (onErrorResume: 에러 본문까지 로깅)
.retrieve()
.bodyToMono(Response.class)
.onErrorResume(WebClientResponseException.class, ex -> {
    if (ex.getStatusCode().is4xxClientError()) {
        // 상태 코드 + 실제 에러 메시지 로깅
        log.warn("API Failed. Status: {}, Body: {}",
                ex.getStatusCode(), ex.getResponseBodyAsString());
        return Mono.empty();
    }
    // 5xx: 서킷브레이커 동작을 위해 상위 전파
    return Mono.error(ex);
})
.timeout(API_TIMEOUT)
```

**패턴 적용 기준:**
- **클라이언트 에러 (4xx)**: 로깅 후 Mono.empty() 반환 (비즈니스 예외로 처리)
- **서버 에러 (5xx)**: Mono.error()로 상위 전파 (서킷브레이커 동작)

### API Key 저장 규칙 (JWT vs Redis)
- **JWT에 절대 포함 금지**: JWT는 클라이언트에 노출되므로 apiKey 저장 불가
- **Redis 세션에만 저장**: 서버 측에서만 접근 가능한 Redis 세션에 저장
- **Fingerprint 사용**: `HMAC-SHA256(serverSecret, apiKey)`로 변환하여 JWT에 저장

---

## 20. SpringDoc OpenAPI (Swagger UI) Best Practice

> **Why Version 2.8.13:** Spring Boot 3.x requires OpenAPI 3.x specification. Version 2.x incompatible with Jakarta EE.
> **Validation:** All endpoints documented and accessible at /swagger-ui.html (Evidence: [v4_specification.md](../api/v4_specification.md)).
> **Security Consideration:** Swagger UI disabled in production via profile configuration.

API 문서 자동화를 위한 SpringDoc OpenAPI 설정 규칙입니다. (Context7 권장)

### 의존성 (Spring Boot 3.x)
```groovy
// build.gradle
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13'
```

**주의**: Spring Boot 3.x는 `springdoc-openapi-starter-webmvc-ui` 사용 (2.x는 `springdoc-openapi-ui`)

### OpenAPI 설정 패턴 (어노테이션 기반)
```java
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "API Title",
        version = "2.0.0",
        description = "API 설명"
    ),
    servers = {
        @Server(url = "http://localhost:8080", description = "Local"),
        @Server(url = "https://api.example.com", description = "Production")
    },
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT"
)
public class OpenApiConfig {}
```

### application.yml 설정
```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
    operations-sorter: method    # GET/POST/PUT/DELETE 순 정렬
    tags-sorter: alpha           # 태그 알파벳 순
    try-it-out-enabled: true     # "Try it out" 버튼 활성화
    persist-authorization: true  # JWT 토큰 세션 유지
  packages-to-scan: maple.expectation.controller
```

**실제 설정 파일**: `src/main/resources/application.yml` (lines 199-213)

### 테스트 환경 설정 (비활성화)
```yaml
# src/test/resources/application.yml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

### SecurityConfig 통합
```java
// Swagger UI 엔드포인트 permitAll
.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
```

### Controller 어노테이션 (선택)
```java
@Tag(name = "Character", description = "캐릭터 관련 API")
@Operation(summary = "캐릭터 조회", description = "캐릭터 정보를 조회합니다")
@ApiResponse(responseCode = "200", description = "성공")
@ApiResponse(responseCode = "404", description = "캐릭터 없음")
public ResponseEntity<CharacterDto> getCharacter(@PathVariable String ign) { ... }
```

### 접근 경로
| 경로 | 설명 |
|------|------|
| `/swagger-ui.html` | Swagger UI (리다이렉트) |
| `/swagger-ui/index.html` | Swagger UI (직접) |
| `/v3/api-docs` | OpenAPI JSON |
| `/v3/api-docs.yaml` | OpenAPI YAML |

### 관련 코드
- 설정 참조: `src/main/resources/application.yml` (lines 199-213)
- SecurityConfig: `src/main/java/maple/expectation/config/SecurityConfig.java`

---

## Technical Validity Check

This guide would be invalidated if:
- **Code examples don't compile/run**: Verify with `./gradlew clean build -x test`
- **Watchdog mode doesn't renew locks**: Test with 60-second task, check lock TTL in Redis CLI
- **Hash Tag pattern fails in Cluster**: Use `CLUSTER KEYSLOT` command to verify same slot
- **DLQ doesn't trigger on compensation failure**: Run N07-black-hole-commit chaos test
- **CGLIB NPE occurs in Filter**: Verify no `@Component` on `OncePerRequestFilter` subclasses
- **API keys exposed in logs**: Search logs for `live_` pattern (should be masked)

### Verification Commands
```bash
# Redisson 설정 확인
grep -A 20 "RedissonConfig" src/main/java/maple/expectation/config/RedissonConfig.java

# application.yml 설정 확인
grep -A 30 "resilience4j:" src/main/resources/application.yml

# CircuitBreaker Marker 확인
find src/main/java -name "*Marker.java"

# Hash Tag slot verification (requires Redis running)
redis-cli CLUSTER KEYSLOT "{buffer:likes}"
redis-cli CLUSTER KEYSLOT "{buffer:likes}:sync:uuid"

# API key masking verification
grep -r "live_" src/test/java/maple/expectation/config/  # Should show masked values
```

### Related Tests
- Redis 통합 테스트: `src/test/java/maple/expectation/config/RedissonConfigTest.java` (Evidence: [TEST-REDIS-001])
- CircuitBreaker 테스트: `src/test/java/maple/expectation/global/resilience/*Test.java` (Evidence: [TEST-CB-001])
- DLQ 테스트: `LikeSyncEventListenerTest.java` (Evidence: [TEST-DLQ-001])
- Chaos Tests: N01-N18 in `docs/01_Chaos_Engineering/06_Nightmare/Results/`
