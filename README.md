# MapleExpectation

## Performance
> **RPS 50.8+ | p50 27ms | p95 360ms | p99 640ms | 0% Failure** - Locust Load Test (VUser 100, Warm Cache)
> [View Benchmark Report](docs/PERFORMANCE_260105.md)

**테스트 조건 상세:**
- **Target API:** `GET /api/v3/characters/{ign}/expectation`
- **Warm Cache:** `expectationResult`/`ocid`/`equipment` 캐시가 프라이밍된 상태 (테스트 시작 전 각 캐릭터 1회 호출)
- **Cold Cache:** 첫 요청 기준 (프라임 포함, Nexon API 호출 1회 발생)
- **External API:** Warm에서는 캐시 HIT로 Nexon API 호출 차단, Cold 첫 요청에서만 포함
- **Dataset:** `TEST_CHARACTERS` 12개 라운드로빈 ([`locust/locustfile.py`](locust/locustfile.py) 참조)
- **검증 범위:** 현재 VUser 100 검증 완료 / 설계 목표 1,000명 (수평 확장 시)

---

## ⚠️ Engineering Standards & Operational Reality

### 1. Performance Context (Benchmark Conditions)

| 항목 | 값 |
|------|-----|
| **Environment** | Local Development (MacBook Pro M1, 16GB RAM) |
| **Database** | Docker MySQL 8.0 (Local Container) |
| **Cache** | Docker Redis 7.0 (Local Container) |
| **Workload** | Locust 동시 사용자 100명 (VUser 100), SpawnRate 20/s, Duration 30s |
| **Cache State** | Warm Cache (Warmup 후 측정) |
| **Result** | RPS 50.8, Failure Rate 0%, **p50 27ms, p95 360ms, p99 640ms** |

**캐시 시나리오별 응답 시간:**
| 시나리오 | p50 | p95 | p99 | 설명 |
|----------|-----|-----|-----|------|
| Warm Cache (HIT) | 27ms | 100ms | 200ms | 캐시 적중 (Nexon API 호출 없음) |
| Cold Cache (MISS) | 290ms | 620ms | 690ms | 첫 요청 (Nexon API 1회 포함) |

> ⚠️ **주의**: 위 벤치마크는 **로컬 개발 환경**에서 측정되었습니다.
> 프로덕션 환경(AWS t3.small: 2vCPU, 2GB)에서는 네트워크 지연, 리소스 제약 등으로 인해 성능이 달라질 수 있습니다.
> 실제 프로덕션 SLA 수립 시 별도의 부하 테스트가 필요합니다.

### 2. Admission Control (Backpressure Design)

시스템 과부하 시 **503 Service Unavailable + Retry-After 헤더**로 클라이언트에 재시도를 안내합니다.

```
┌──────────────────────────────────────────────────────────────┐
│                 Backpressure Response Flow                   │
│                                                              │
│   요청 ───▶ ThreadPool Queue ───▶ Queue Full?                │
│                                       │                      │
│                    ┌─────────────────┴───────────────┐       │
│                    │ YES                          NO │       │
│                    ▼                                 ▼       │
│           RejectedExecutionException          정상 처리     │
│                    │                                         │
│                    ▼                                         │
│           GlobalExceptionHandler                             │
│                    │                                         │
│                    ▼                                         │
│   HTTP 503 + Header: Retry-After: 60                        │
└──────────────────────────────────────────────────────────────┘
```

**설정값:**
| 항목 | 값 | 설명 |
|------|-----|------|
| Queue Capacity | 100 | 최대 대기 작업 수 |
| Rejected Policy | AbortPolicy | 큐 포화 시 즉시 거부 |
| Retry-After | 60s | 클라이언트 재시도 권장 시간 |

**구현 코드:**
- Executor 설정: [`src/main/java/maple/expectation/config/ExecutorConfig.java`](src/main/java/maple/expectation/config/ExecutorConfig.java)
- 503 응답 처리: [`src/main/java/maple/expectation/global/error/GlobalExceptionHandler.java`](src/main/java/maple/expectation/global/error/GlobalExceptionHandler.java)

### 3. Timeout Layering (장애 격리 전략)

외부 API 호출 시 **3단계 타임아웃 레이어링**으로 장애를 격리합니다.

| Layer | Timeout | 용도 |
|-------|---------|------|
| TCP Connect | 3s | 네트워크 연결 실패 조기 탐지 |
| HTTP Response | 5s | 느린 응답 차단 |
| TimeLimiter | 28s | 전체 작업 상한 (3회 재시도 포함) |

**타임아웃 예산 계산:**
```
maxAttempts × (connect + response) + (maxAttempts-1) × waitDuration + margin
= 3 × (3s + 5s) + 2 × 0.5s + 3s = 28s
```

**설정 위치:** [`src/main/resources/application.yml`](src/main/resources/application.yml) (resilience4j 섹션)

### 4. SLA/SLO 명세 (목표치)

> 아래는 **설계 목표치**이며, 프로덕션 모니터링 데이터 축적 후 조정 예정입니다.

| 지표 | 목표 (SLO) | 임계치 (Alert) |
|------|-----------|----------------|
| API 응답 시간 (p95) | < 500ms | > 1,000ms |
| 캐시 HIT 비율 | > 80% | < 60% |
| 서킷브레이커 OPEN | 0회/일 | > 3회/일 |
| 에러율 | < 0.1% | > 1% |

### 5. Benchmark 재현 방법

```bash
# 1. 인프라 구동
docker-compose up -d

# 2. 애플리케이션 시작
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Locust 부하 테스트 실행 (V3 API)
locust -f locust/locustfile.py --tags v3 --headless -u 100 -r 20 -t 30s --host=http://localhost:8080

# 4. 좋아요 API 테스트 (인증 필요)
export NEXON_API_KEY="your_api_key"
export LOGIN_IGN="your_character_name"
locust -f locust/locustfile.py --tags like_sync_test --headless -u 50 -r 10 -t 60s --host=http://localhost:8080
```

> 테스트 파일: [`locust/locustfile.py`](locust/locustfile.py)

**보안:**
- API Key는 환경변수(`NEXON_API_KEY`)로만 주입되며 코드에 하드코딩 금지
- `LoginRequest.toString()`에서 마스킹 처리 ([`dto/LoginRequest.java`](src/main/java/maple/expectation/controller/auth/dto/LoginRequest.java) 참조)
- 테스트 중 로그에 API Key 평문 노출 없음

---

## Professional Summary
> **"시스템의 가용성과 확장성을 숫자로 증명하고, 장애 대응 시나리오를 설계하는 엔지니어"**
>
> 단순히 기능을 구현하는 것을 넘어, **수평적 확장(Scale-out)**이 가능한 분산 환경을 설계합니다.
> 외부 의존성 장애가 전체 시스템으로 전이되지 않도록 **회복 탄력성(Resilience)**을 확보하고,
> 데이터 정합성과 가용성의 최적점을 찾는 엔지니어링 결정에 강점이 있습니다.

---

## 1. 프로젝트 소개

**넥슨 Open API**를 활용하여 유저 장비 데이터를 수집하고, 확률형 아이템(큐브)의 기댓값을 계산하여 **"스펙 완성 비용"을 시뮬레이션해주는 서비스**입니다.

저사양 서버 환경(AWS t3.small: 2vCPU, 2GB RAM)을 **목표 인프라**로 설정하고, 고부하 상황에서도 안정적으로 동작하도록 성능 병목을 수치화하고 아키텍처를 고도화했습니다.

**설계 목표:**
- 동시 사용자 1,000명 이상 수용 가능한 확장성
- 캐시 HIT 시 p95 < 500ms 응답 시간
- 외부 API 장애 격리 (Circuit Breaker)

---

## 2. 핵심 모듈 아키텍처

### 2.1 LogicExecutor/Policy Pipeline

실행 흐름 추상화를 통해 **try-catch 제거** 및 **일관된 예외 처리**를 제공합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                    LogicExecutor                             │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐   │
│  │ BEFORE  │───▶│  TASK   │───▶│ON_SUCCESS│───▶│  AFTER  │   │
│  └─────────┘    └────┬────┘    └─────────┘    └─────────┘   │
│                      │                                       │
│                      ▼ (예외 발생 시)                         │
│                ┌──────────┐                                  │
│                │ON_FAILURE│───▶ ExceptionTranslator          │
│                └──────────┘                                  │
└─────────────────────────────────────────────────────────────┘
```

**핵심 메서드:**
| 메서드 | 용도 |
|--------|------|
| `execute()` | 일반 실행, 예외 시 상위 전파 |
| `executeVoid()` | 반환값 없는 작업 (Runnable) |
| `executeOrDefault()` | 예외 시 기본값 반환 |
| `executeWithFinally()` | finally 블록 보장 |
| `executeWithTranslation()` | 기술 예외 → 도메인 예외 변환 |

**시퀀스 다이어그램:** [docs/logic-executor-sequence.md](docs/logic-executor-sequence.md)

---

### 2.2 Resilience4j 회복 탄력성

외부 API 장애가 내부로 전파되지 않도록 **서킷브레이커** 패턴을 적용합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                 CircuitBreaker 상태 전이                      │
│                                                              │
│    ┌────────┐    실패율 50% 초과    ┌────────┐               │
│    │ CLOSED │ ───────────────────▶ │  OPEN  │               │
│    └────────┘                      └───┬────┘               │
│         ▲                              │                    │
│         │ 성공 3회                      │ 10초 대기          │
│    ┌────┴─────┐                        ▼                    │
│    │HALF_OPEN │◀──────────────────────┘                    │
│    └──────────┘                                             │
└─────────────────────────────────────────────────────────────┘
```

**설정값:**
| CircuitBreaker | 실패율 임계치 | 대기 시간 | Half-Open 허용 |
|----------------|--------------|----------|---------------|
| nexonApi | 50% | 10s | 3회 |
| redisLock | 60% | 30s | 3회 |

**Marker Interface:**
- `CircuitBreakerIgnoreMarker`: 비즈니스 예외 (4xx) - 서킷 상태에 영향 없음
- `CircuitBreakerRecordMarker`: 시스템 예외 (5xx) - 실패로 기록

**시퀀스 다이어그램:** [docs/resilience-sequence.md](docs/resilience-sequence.md)

---

### 2.3 TieredCache (L1/L2)

**Multi-Layer 캐시**와 **분산 Single-flight** 패턴으로 Cache Stampede를 방지합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                     TieredCache 흐름                         │
│                                                              │
│   요청 ───▶ L1 (Caffeine) ─── HIT ───▶ 반환                  │
│                │                                             │
│               MISS                                           │
│                ▼                                             │
│            L2 (Redis) ─── HIT ───▶ L1 Backfill ───▶ 반환     │
│                │                                             │
│               MISS                                           │
│                ▼                                             │
│         분산 락 획득 (Redisson)                               │
│                │                                             │
│                ▼                                             │
│     ┌─── Leader ───┐    ┌─── Follower ───┐                  │
│     │ API 호출     │    │ L2 대기 후     │                  │
│     │ L2 저장      │    │ L2 읽기        │                  │
│     │ L1 저장      │    │ L1 Backfill    │                  │
│     └──────────────┘    └────────────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

**핵심 규칙:**
- **Write Order:** L2 → L1 (원자성 보장)
- **TTL 규칙:** L1 TTL ≤ L2 TTL
- **Watchdog 모드:** leaseTime 생략으로 자동 갱신

**시퀀스 다이어그램:** [docs/cache-sequence.md](docs/cache-sequence.md)

---

### 2.4 AOP+Async 비동기 파이프라인

**톰캣 스레드 즉시 반환**(0ms 목표)으로 고처리량 API를 구현합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                Async Pipeline 흐름                           │
│                                                              │
│   HTTP 요청                                                  │
│       │                                                      │
│       ▼                                                      │
│   ┌─────────────────────────────────────┐                   │
│   │  Controller (http-nio-*)            │ ◀── 즉시 반환 0ms  │
│   │  return CompletableFuture           │                   │
│   └──────────────┬──────────────────────┘                   │
│                  │ supplyAsync()                            │
│                  ▼                                          │
│   ┌─────────────────────────────────────┐                   │
│   │  expectation-* 스레드               │                   │
│   │  LightSnapshot → 캐시 확인          │                   │
│   │  FullSnapshot (MISS 시)             │                   │
│   │  계산 → 캐시 저장                    │                   │
│   └──────────────┬──────────────────────┘                   │
│                  │ thenApply()                              │
│                  ▼                                          │
│   HTTP 응답 (비동기 완료)                                    │
└─────────────────────────────────────────────────────────────┘
```

**Two-Phase Snapshot:**
| Phase | 목적 | 로드 데이터 |
|-------|------|------------|
| LightSnapshot | 캐시 키 생성 | 최소 필드 (ocid, fingerprint) |
| FullSnapshot | 계산 (MISS 시만) | 전체 필드 |

**시퀀스 다이어그램:** [docs/async-pipeline-sequence.md](docs/async-pipeline-sequence.md)

---

### 2.5 Graceful Shutdown

**4단계 순차 종료**로 진행 중인 작업과 데이터를 안전하게 보존합니다.

```
┌─────────────────────────────────────────────────────────────┐
│              Graceful Shutdown 4단계                         │
│                                                              │
│   SIGTERM 수신                                               │
│       │                                                      │
│       ▼                                                      │
│   ┌─────────────────────────────────────┐                   │
│   │ Phase 1: 새 요청 거부              │ (즉시)             │
│   │ - Executor 새 작업 제출 중단        │                   │
│   └──────────────┬──────────────────────┘                   │
│                  ▼                                          │
│   ┌─────────────────────────────────────┐                   │
│   │ Phase 2: 진행 중 작업 완료 대기     │ (최대 20초)       │
│   │ - awaitTermination()               │                   │
│   └──────────────┬──────────────────────┘                   │
│                  ▼                                          │
│   ┌─────────────────────────────────────┐                   │
│   │ Phase 3: 캐시 및 버퍼 플러시        │ (최대 10초)       │
│   │ - Redis 캐시 동기화                 │                   │
│   │ - 좋아요 버퍼 DB 반영               │                   │
│   └──────────────┬──────────────────────┘                   │
│                  ▼                                          │
│   ┌─────────────────────────────────────┐                   │
│   │ Phase 4: 커넥션 종료               │ (최대 10초)       │
│   │ - DB 커넥션 풀 정리                 │                   │
│   │ - Redis 연결 종료                   │                   │
│   └─────────────────────────────────────┘                   │
│                  │                                          │
│                  ▼                                          │
│   총 타임아웃: 50초 (SmartLifecycle)                        │
└─────────────────────────────────────────────────────────────┘
```

**DLQ (Dead Letter Queue):**
- 복구 실패 시 `LikeSyncFailedEvent` 발행
- 파일 백업 → 메트릭 기록 → Discord 알림

**시퀀스 다이어그램:** [docs/shutdown-sequence.md](docs/shutdown-sequence.md)

---

### 2.6 Expectation Calculator (DP)

**동적 프로그래밍(DP)**으로 큐브 기대값을 계산합니다.

```
┌─────────────────────────────────────────────────────────────┐
│              CubeDpCalculator 흐름                           │
│                                                              │
│   입력: 장비 옵션 목록, 큐브 타입                             │
│       │                                                      │
│       ▼                                                      │
│   ┌─────────────────────────────────────┐                   │
│   │ DpModeInferrer                      │                   │
│   │ - 연산 모드 결정 (Normal/Weighted)   │                   │
│   │ - 필드 자동 설정                     │                   │
│   └──────────────┬──────────────────────┘                   │
│                  ▼                                          │
│   ┌─────────────────────────────────────┐                   │
│   │ CubeRateCalculator                  │                   │
│   │ - 옵션별 확률 조회                   │                   │
│   │ - CubeProbabilityRepository 참조    │                   │
│   └──────────────┬──────────────────────┘                   │
│                  ▼                                          │
│   ┌─────────────────────────────────────┐                   │
│   │ ProbabilityConvolver                │                   │
│   │ - 3줄 확률 합산 (Convolution)        │                   │
│   │ - O(slots × target × K) 복잡도      │                   │
│   └──────────────┬──────────────────────┘                   │
│                  ▼                                          │
│   출력: 기대 횟수, 기대 비용                                 │
└─────────────────────────────────────────────────────────────┘
```

**정밀도 보장:**
- BigDecimal 연산 (double 변환 금지)
- Kahan Summation Algorithm

**시퀀스 다이어그램:** [docs/dp-calculator-sequence.md](docs/dp-calculator-sequence.md)

---

## 3. 핵심 기술적 성과

### 장애 격리 및 회복 탄력성 (Resilience)
- **문제:** 외부 API 장애 시 연쇄 장애(Cascading Failure) 발생 위험
- **해결:** Resilience4j Circuit Breaker 도입 및 Scenario A/B/C 명세화
- **결과:** 외부 장애 상황에서도 시스템 가용성 유지

### 분산 환경 동시성 제어 (Scalability)
- **문제:** 서버 수평 확장 시 단일 서버 락 사용 불가
- **해결:** Redisson 분산 락 + Watchdog 모드 (자동 갱신)
- **결과:** 동시 요청 처리 성능 **480% 향상** (5.3s → 1.1s)

### 데이터 최적화 (Efficiency)
- **GZIP 압축:** 350KB JSON → 17KB (95% 절감)
- **스트리밍 직렬화:** RPS 11배 향상
- **인덱스 튜닝:** 조회 성능 50배 개선 (0.98s → 0.02s)

---

## 4. 기술 스택

| 분류 | 기술 |
|------|------|
| Backend | Java 17, Spring Boot 3.5.4 |
| Database | MySQL 8.0, JPA/Hibernate |
| Cache | Redis 7.0 (Redisson), Caffeine |
| Resilience | Resilience4j 2.2.0 |
| Testing | JUnit 5, Testcontainers, Locust |
| Monitoring | Micrometer, Actuator |
| Infra | AWS EC2, GitHub Actions |

---

## 5. 모니터링 지표

| 분류 | 지표 | 임계치 | 의미 |
|------|------|--------|------|
| Redis | `redis.connection.error` | > 0 | 분산 락/캐시 불능 |
| Resilience | `circuitbreaker.state` | "OPEN" | 외부 API 장애 |
| App | `like.buffer.total_pending` | > 1,000 | 버퍼 포화 |
| DB | `hikaricp.connections.pending` | > 0 | 커넥션 풀 고갈 |
| Executor | `executor.rejected` | > 0 | 스레드 풀 포화 |

---

## 6. 시퀀스 다이어그램 목록

| 모듈 | 파일 |
|------|------|
| LogicExecutor Pipeline | [docs/logic-executor-sequence.md](docs/logic-executor-sequence.md) |
| CircuitBreaker 상태 전이 | [docs/resilience-sequence.md](docs/resilience-sequence.md) |
| TieredCache Single-flight | [docs/cache-sequence.md](docs/cache-sequence.md) |
| Graceful Shutdown 4단계 | [docs/shutdown-sequence.md](docs/shutdown-sequence.md) |
| Async Pipeline (V2 API) | [docs/async-pipeline-sequence.md](docs/async-pipeline-sequence.md) |
| DP Calculator | [docs/dp-calculator-sequence.md](docs/dp-calculator-sequence.md) |

---

## 7. 협업 프로세스

- **Issue-Driven Development:** 모든 작업은 이슈 발행 후 시작
- **Rationale in PR:** PR마다 기술적 선택의 근거와 트레이드오프 기록
- **Git Flow:** develop → feature → PR → main

---

## License

MIT License
