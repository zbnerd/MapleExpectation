# Stateful 리팩토링 대상 목록 (V5 전환용)

> **목적:** Stateless 아키텍처 전환 시 리팩토링이 필요한 Stateful 요소들을 추적합니다.
> **관련 Issue:** #271, ADR-012

---

## 1. maple.expectation.aop 패키지 분석 결과

### 1.1 Critical - ThreadLocal 사용 (Scale-out 시 문제)

| 파일 | Stateful 요소 | 위험도 | 설명 |
|------|---------------|--------|------|
| `TraceAspect.java` | `ThreadLocal<Integer> depthHolder` | **HIGH** | 호출 깊이 추적용. 스레드풀 재사용 시 값 잔존 가능 |
| `SkipEquipmentL2CacheContext.java` | `static ThreadLocal<Boolean> FLAG` | **HIGH** | L2 캐시 스킵 플래그. 비동기 전파 시 컨텍스트 유실 위험 |

#### TraceAspect.java 상세
```java
// 위치: src/main/java/maple/expectation/aop/aspect/TraceAspect.java:36
private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);
```

**문제점:**
- Scale-out 환경에서 각 인스턴스가 독립적인 ThreadLocal 보유
- 분산 추적(Distributed Tracing)과 호환 불가
- 스레드풀 재사용 시 이전 요청의 depth 값 잔존 가능

**리팩토링 방향:**
```java
// AS-IS: ThreadLocal
private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);

// TO-BE: MDC (Mapped Diagnostic Context) + OpenTelemetry Span
// MDC는 로그 프레임워크 표준이며, 분산 환경에서도 Trace ID로 연결 가능
import org.slf4j.MDC;

private void setDepth(int depth) {
    MDC.put("trace.depth", String.valueOf(depth));
}
```

---

#### SkipEquipmentL2CacheContext.java 상세
```java
// 위치: src/main/java/maple/expectation/aop/context/SkipEquipmentL2CacheContext.java:29
private static final ThreadLocal<Boolean> FLAG = new ThreadLocal<>();
```

**문제점:**
- 비동기 처리 시 컨텍스트 전파 필요 (snapshot/restore 패턴 이미 구현됨)
- CompletableFuture 체인에서 수동 전파 필수
- Scale-out 시 서버 간 컨텍스트 공유 불가

**리팩토링 방향:**
```java
// 현재: 수동 snapshot/restore
Boolean snap = SkipEquipmentL2CacheContext.snapshot();
// 워커 스레드에서
SkipEquipmentL2CacheContext.restore(snap);

// TO-BE 옵션 1: Request Scope Bean
@RequestScope
public class CacheContext {
    private boolean skipL2 = false;
}

// TO-BE 옵션 2: Context Propagation (Micrometer/OpenTelemetry)
// Spring Boot 3.x의 ContextPropagation 활용
```

---

### 1.2 Medium - 인스턴스 변수 (설정값)

| 파일 | Stateful 요소 | 위험도 | 설명 |
|------|---------------|--------|------|
| `TraceAspect.java` | `@Value isTraceEnabled` | **LOW** | 설정값. 런타임 변경 불가하므로 실질적 Stateless |

```java
// 위치: src/main/java/maple/expectation/aop/aspect/TraceAspect.java:31
@Value("${app.aop.trace.enabled:false}")
private boolean isTraceEnabled;
```

**평가:** 설정값은 애플리케이션 시작 시 주입되고 변경되지 않으므로 **실질적으로 Stateless**. 리팩토링 불필요.

---

### 1.3 Safe - Stateless 컴포넌트

| 파일 | 평가 | 비고 |
|------|------|------|
| `LoggingAspect.java` | **Stateless** | Micrometer Registry에 위임 (외부 저장소) |
| `BufferedLikeAspect.java` | **Stateless** | LikeBufferStorage에 위임 (별도 분석 필요) |
| `LockAspect.java` | **Stateless** | LockStrategy에 위임 (Redis 분산 락) |
| `ObservabilityAspect.java` | **Stateless** | MeterRegistry에 위임 |
| `NexonDataCacheAspect.java` | **Stateless** | Redis/Cache에 위임 |
| `SimpleLogAspect.java` | **Stateless** | 로그만 출력 |
| `PerformanceStatisticsCollector.java` | **Stateless** | Micrometer Timer에 위임 |

---

## 2. 의존성 체인 분석 (추가 확인 필요)

`BufferedLikeAspect`가 의존하는 `LikeBufferStorage`는 별도 분석 필요:

```java
// BufferedLikeAspect.java:17
private final LikeBufferStorage likeBufferStorage;

// 사용 패턴
likeBufferStorage.getCounter(userIgn).incrementAndGet();
```

**확인 사항:**
- [ ] `LikeBufferStorage`가 In-Memory 버퍼 사용 여부
- [ ] `LikeBufferStorage`가 V5 전환 대상 여부

---

## 3. 리팩토링 우선순위

| 순위 | 대상 | 작업 | 예상 난이도 |
|------|------|------|-------------|
| **P0** | `SkipEquipmentL2CacheContext` | Request Scope 또는 Context Propagation 전환 | Medium |
| **P1** | `TraceAspect.depthHolder` | MDC + OpenTelemetry Span 전환 | Medium |
| **P2** | `LikeBufferStorage` 분석 | Stateful 여부 확인 후 결정 | TBD |

---

## 4. V5 전환 시 액션 아이템

### Phase 1: ThreadLocal 제거
- [ ] `SkipEquipmentL2CacheContext` → `@RequestScope` Bean 또는 Micrometer Context Propagation
- [ ] `TraceAspect.depthHolder` → MDC 기반으로 변경, OpenTelemetry Span Depth 활용 검토

### Phase 2: 의존성 분석
- [ ] `LikeBufferStorage` 코드 분석
- [ ] `ExpectationWriteBackBuffer` 코드 분석 (ADR-012 대상)

### Phase 3: 테스트
- [ ] Scale-out 환경 (2+ 인스턴스)에서 ThreadLocal 제거 후 동작 검증
- [ ] 비동기 처리 시 컨텍스트 전파 테스트

---

## 5. 참고 자료

- `docs/adr/ADR-012-stateless-scalability-roadmap.md` - V5 아키텍처 로드맵
- [Spring Context Propagation](https://docs.spring.io/spring-framework/reference/integration/observability.html)
- [Micrometer Context Propagation](https://micrometer.io/docs/contextPropagation)

---

*Last Updated: 2026-01-26*
*Author: 5-Agent Council*
