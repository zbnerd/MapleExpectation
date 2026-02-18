# MapleExpectation 프로젝트 종합 분석 보고서

## 1. 프로젝트 개요 (0.5페이지)

### 핵심 정체성
**MapleExpectation**은 마플스토리 장비 업비용 계산기라는 도메인을 가진 Spring Boot 애플리케이션이지만, 실질적인 가치는 **"대규모 트래픽 처리를 위한 공통 인프라 모듈 설계 패턴"**에 있습니다.

- **목표**: 1,000+ 동시 사용자 처리 (AWS t3.small, 240 RPS)
- **성과**: RPS 965, p50 95ms, p99 214ms, Error 0%
- **핵심**: 7개 공통 인프라 모듈을 독립적으로 재사용 가능한 구조로 설계

### 기술 스택
- **Java 21**: Virtual Threads, Records, Pattern Matching, Switch Expressions
- **Spring Boot 3.5.4**: 최신 안정 버전
- **데이터 저장소**: MySQL 8.0 (GZIP 압축), Redis (Redisson 3.27.0)
- **캐싱**: 3계층 캐시 (Caffeine L1 + Redis L2 + MySQL)
- **회복탄력성**: Resilience4j 2.2.0 (서킷 브레이커)
- **테스트**: Testcontainers + Docker

---

## 2. 아키텍처 진화 과정 (1페이지)

### 7대 공통 인프라 모듈

#### 1) LogicExecutor — Cross-Cutting 실행 프레임워크
**문제**: 프로젝트 전반에서 try-catch 패턴이 제각각 → 장애 시 에러 추적 불가

**해결**: 모든 실행 흐름을 LogicExecutor에 위임
```java
// After: LogicExecutor — 자동 메트릭/로깅/에러 분류
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", id)
);
```

**6가지 실행 패턴**:
- `execute()`: 일반 실행, 예외 발생 시 로그 후 전파
- `executeVoid()`: 반환값 없는 작업
- `executeOrDefault()`: 예외 시 안전하게 기본값 반환
- `executeWithRecovery()`: 예외 시 복구 로직 실행
- `executeWithFinally()`: finally 블록이 필요한 경우
- `executeWithTranslation()`: 기술적 예외를 도메인 예외로 변환

**설계 철학**: "다른 개발자가 실수 없이 쓸 수 있는 API"
- TaskContext 강제로 구조화된 로그 자동 생성 (디버깅 시간 50% 단축)
- 예외 변환 책임을 명확히 분리

#### 2) ResilientLockStrategy — 장애 격리 락 전략
**문제**: Redis 장애 시 락을 사용하는 모든 서비스가 중단

**해결**: Redis 실패 → MySQL Named Lock 자동 전환
```
정상:     Redis Lock (빠름)
          ↓ Redis 장애 감지
자동 전환: MySQL Named Lock (안전)
          ↓ CircuitBreaker Half-Open
자동 복구: Redis Lock (빠름)
```

**3-tier 예외 분류 정책**:
- **인프라 예외** (RedisException 등) → MySQL fallback
- **비즈니스 예외** (ClientBaseException) → 즉시 전파, fallback 없음
- **알 수 없는 예외** (NPE 등) → 보수적 처리

**Marker Interface 분류**:
- `CircuitBreakerIgnoreMarker`: 비즈니스 예외 (4xx) — 서킷 상태 무영향
- `CircuitBreakerRecordMarker`: 시스템 예외 (5xx) — 실패로 기록

#### 3) TieredCache + Singleflight — 3계층 캐시
**문제**: Cache Stampede로 인한 DB 폭주

**해결**: 3계층 캐시 + Singleflight로 DB 호출 ≤ 10% 달성
- L1: Caffeine (로컬 메모리, 가장 빠름)
- L2: Redis (분산 캐시)
- L3: MySQL (영구 저장소)

**Singleflight**: 동시에 같은 키로 조회 요청이 들어오면, 첫 번째 요청만 DB 조회하고 나머지는 결과 대기

#### 4) Transactional Outbox — 데이터 생존
**문제**: 메시지 전송 실패로 데이터 유실

**해결**: 3중 안전망
- DB → File → Alert 순서로 fallback
- Outbox Replay로 실패 메시지 자동 재전송

#### 5) Async Non-Blocking Pipeline
- Virtual Threads로 동시성 처리
- Backpressure 있는 Thread Pool 설정
- CallerRunsPolicy 금지 (실제 장애 경험)

#### 6) Facade Pattern
- 15개 V2 모듈: root, alert, auth, cache, calculator, cube, donation, facade, impl, like, mapper, policy, shutdown, starforce, worker
- 7개 V4 모듈: root, buffer, cache, fallback, persistence, warmup, calculator

#### 7) Resilience4j Circuit Breaker
- 자동 장애 감지 및 격리
- Half-Open 상태에서 자동 복구

---

## 3. 카오스 엔지니어링과 회복 탄력성 (1페이지)

### Nightmare Tests N01-N18
**목적**: 시스템 장애 시나리오를 강제 주입하여 회복 탄력성 검증

### 주요 시나리오 카테고리

#### Core (N01-N03)
- **N01 Thundering Herd**: 캐시 만료 시 동시 요청 폭주
- **N02 Deadlock Trap**: 순환 락으로 교착 상태 유발
- **N03 Thread Pool Exhaustion**: 스레드 풀 고갈

#### Network (N04-N07)
- **N04 Connection Vampire**: 커넥션 누수
- **N05 Celebrity Problem**: 특정 키에만 집중 트래픽
- **N06 Timeout Cascade**: 타임아웃 연쇄 전파
- **N07 Metadata Lock Freeze**: DB 메타데이터 락

#### Resource (N08-N11)
- **N08 Thundering Herd + Redis Death**: 캐시 장애 동시 발생
- **N09 Circular Lock Deadlock**: 원형 락
- **N10 Caller Runs Policy**: 잘못된 rejection policy
- **N11 Lock Fallback Avalanche**: 락 fallback 연쇄 실패

#### Advanced (N12-N19)
- **N12 Async Context Loss**: 비동기 컨텍스트 유실
- **N13 Zombie Outbox**: 좀비 Outbox 메시지
- **N14 Pipeline Exception**: 파이프라인 예외 전파
- **N15 AOP Order Problem**: AOP 실행 순서 문제
- **N16 Self Invocation**: @AOP 내부호출 실패
- **N17 Poison Pill**: 잘못된 메시지 처리
- **N19 Compound Failures**: 복합 장애

### 검증된 회복 탄력성
1. **Redis 장애** → MySQL Named Lock 자동 전환 ✅
2. **DB 장애** → File fallback ✅
3. **Thread Pool 고갈** → Backpressure + Queue ✅
4. **Cache Stampede** → Singleflight로 1회 DB 조회 ✅

---

## 4. 성능 최적화와 Scale-out (1페이지)

### 비용 vs 성능 최적점 분석 (N23)
**발견**: t3.small → t3.large로 스케일업 시 비용 대비 성능 향상이 둔화
- **t3.small**: 저렴하지만 RPS 240 한계
- **t3.medium**: 1.5x 비용, 2x 성능
- **t3.large**: 2x 비용, 2.5x 성능 ← 최적점

**결론**: t3.large가 비용 대비 성능 최적점

### Scale-out 방해 요소 분석
**P0 Stateful 컴포넌트** (22개 전수 분석):
1. **In-Memory 상태**: 캐시, 세션, 버퍼
2. **Lock 경합**: Redis Lock, MySQL Lock
3. **Thread Pool 고갈**: 고정된 스레드 수

**해결 방안**:
- Stateful → Stateless 리팩토링
- Distributed Lock → Local Cache + Singleflight
- Thread Pool → Virtual Threads

### Load Test 결과
**최신 성능** (2026-01-26 기준):
- RPS: 965
- p50: 95ms
- p99: 214ms
- Error: 0%

### Write-Behind Cache 성과
DB 저장 150ms → 0.1ms (1,500x 개선)

---

## 5. 부록: 주요 결정 사항 요약 (1페이지)

### ADR 주요 결정사항 (001-034)

#### 초반기 (001-010): 기초 인프라 구축
- ADR-001: Streaming Parser로 대용량 JSON 처리
- ADR-003: TieredCache + Singleflight 도입
- ADR-004: LogicExecutor로 표준화된 예외 처리
- ADR-010: Transactional Outbox로 데이터 생존

#### 중반기 (011-020): 장애 격리와 최적화
- ADR-011: Controller V4 최적화
- ADR-012: Stateless 설계 로드맵
- ADR-016: Nexon API Outbox 패턴
- ADR-017: Domain Extraction Clean Architecture
- ADR-018: ACL Strategy Pattern
- ADR-020: Flaky Test SOLID 기반 리팩토링

#### 후반기 (021-034): 안정화와 마이그레이션
- ADR-025: Chaos Test Module Separation
- ADR-034: Scheduler Thread Pool Configuration
- ADR-0345: Stateless Alert System

#### 최신 ADR (035+): CQRS/마이크로서비스
- ADR-037: V5 CQRS Command Side
- ADR-038: V5 CQRS Implementation
- ADR-039: Current Architecture Assessment

### 핵심 개발 원칙

#### SOLID 원칙 엄격 준수
- **SRP**: 클래스/메서드 단일 책임
- **OCP**: 확장에는 개방, 수정에는 닫힘
- **LSP**: 인터페이스 계약 준수
- **ISP**: 인터페이스 분리
- **DIP**: 상위 모듈이 하위 모듈 의존하지 않음

#### 예외 처리 전략
**Zero Try-Catch Policy**:
- 비즈니스 로직에서 try-catch 금지
- 모든 예외 처리를 LogicExecutor에 위임
- Checked Exception은 구조적 분리

**예외 계층 구조**:
- ClientBaseException (4xx): 비즈니스 예외
- ServerBaseException (5xx): 시스템 예외
- Circuit Breaker Marker Interface로 분류

#### 클린 코드 강제 규칙
- 람다 3줄 초과 시 Private Method 추출
- 중첩 깊이 2단계 제한
- Magic Number 금지
- Early Return 지향

#### 테스트 전략
**Flaky Test 방지**:
- Testcontainers로 통합 테스트 환경 구축
- DatabaseCleaner + `@BeforeEach`로 테스트 격리
- 경량 테스트 강제 규칙

### Git 전략
- **Branch**: develop 기반 feature/{기능}, release-{버전}, hotfix-{버전}
- **Commit**: 타입(영어): 제목(한글) 형식
- **PR**: develop을 base (hotfix 제외), 이슈 100% 완료 후 제출

---

## 결론

MapleExpectation은 단순한 도메인 애플리케이션을 넘어, **"대규모 시스템을 위한 공통 인프라 설계 패턴"**을 제시하는 아키텍처 참조 모델입니다.

**핵심 가치**:
1. 7개 공통 인프라 모듈을 독립적으로 재사용 가능
2. Nightmare Tests로 검증된 회복 탄력성
3. 비용 대비 성능 최적점 발견
4. SOLID 원칙에 기반한 클린 코드
5. ADR로 체계적인 아키텍처 진화

**면접 시 어필 포인트**:
- "프로젝트 전반에 LogicExecutor 패턴을 적용하여 에러 추적 가능하게 만듦"
- "Nightmare Tests N01-N18으로 장애 상황에서의 시스템 안정성 검증"
- "t3.large가 비용 대비 성능 최적점임을 데이터로 증명"
- "ADR 기반으로 체계적인 아키텍처 진화 관리"

이 프로젝트는 토스증권과 같은 대규모 트래픽을 처리하는 핀테크 회사에서의 백엔드 설계 역량을 증명하는 강력한 포트폴리오입니다.
