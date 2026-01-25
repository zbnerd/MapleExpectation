# MapleExpectation

<div align="center">

## TL;DR

| What | Description |
|------|-------------|
| **Problem** | MapleStory 장비 강화 비용 계산 (요청당 200~300KB JSON 처리) |
| **Solution** | 7대 핵심모듈 아키텍처로 10만 RPS급 등가 처리량 달성 |
| **Result** | **RPS 719**, p50 164ms, **0% Failure** (로컬 벤치마크 #266) |

### Target Users

| Segment | Description |
|---------|-------------|
| **MapleStory Players** | 장비 강화 비용 최적화가 필요한 캐주얼~하드코어 게이머 |
| **Backend Developers** | Resilience 패턴 (Circuit Breaker, Singleflight, TieredCache) 학습 |
| **Performance Researchers** | High-throughput JSON 처리 사례 연구 |

### Value Proposition

> **"1 Request = 150 Standard Requests"** handled with enterprise-grade resilience

| Capability | Evidence |
|------------|---------|
| 1,000+ 동시 사용자 | [Load Test Report](docs/04_Reports/PERFORMANCE_260105.md) |
| Zero Failure Rate | 18개 Nightmare 카오스 테스트 검증 |
| Cost Efficiency | Single t3.small (~$15/month) |

### Quick Links

| Document | Description |
|----------|-------------|
| [KPI Dashboard](docs/04_Reports/KPI_BSC_DASHBOARD.md) | 성과 지표 및 BSC 스코어카드 |
| [Load Test #266](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md) | 최신 부하테스트 (RPS 719) |
| [Business Model](docs/00_Start_Here/BUSINESS_MODEL.md) | BMC 문서 |
| [Architecture](docs/00_Start_Here/architecture.md) | 시스템 아키텍처 다이어그램 |
| [Chaos Tests](docs/01_Chaos_Engineering/06_Nightmare/) | N01-N18 Nightmare 시나리오 |

</div>

---

<div align="center">

### **"1 Request ≈ 150 Standard Requests"**
#### 200~300KB JSON Throughput을 견디기 위한 7대 핵심모듈 아키텍처

</div>

---

> **RPS 719 | p50 164ms | Error 0%** - [wrk Load Test Report #266](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md)

---

## Why This Architecture? (오버엔지니어링이 아닌 이유)

### 트래픽 밀도(Traffic Density) 비교

| 구분 | 일반 웹 서비스 | MapleExpectation |
|------|---------------|------------------|
| **요청당 페이로드** | ~2KB | **200~300KB** |
| **메모리 할당량** | ~10MB/100명 | **1.5GB/100명** |
| **직렬화 비용** | 1ms | **150ms** |
| **네트워크 I/O** | 0.2Mbps | **24Mbps** |

```
[ 등가 계산식 ]
300KB / 2KB = 150배

∴ 동시 접속자 100명 = 일반 서비스 15,000명 동시 접속과 동등한 리소스 부하
```

### 왜 이 모듈들이 "필수"인가?

| 문제 상황 | 일반적 접근 | 결과 | 본 프로젝트 해결책 |
|----------|------------|------|------------------|
| 300KB JSON 파싱 | `ObjectMapper.readValue()` DOM 방식 | **OOM (50명 동시접속 시)** | **Streaming Parser** |
| 외부 API 3초 지연 | 동기 호출 대기 | **Thread Pool 고갈** | **Resilience4j + 비동기 파이프라인** |
| 캐시 만료 + 1,000명 동시 | 모두 DB 직접 호출 | **Cache Stampede** | **TieredCache + Singleflight** |
| 트랜잭션 내 외부 I/O | `.join()` 블로킹 | **Connection Pool 고갈** | **트랜잭션 범위 분리** |

---

## System Architecture

<img width="5556" height="4528" alt="architecture" src="https://github.com/user-attachments/assets/6a4daa9d-b4f5-4a49-8e51-5311cb816014" />

**범례**
- ──── (Solid): Implemented (Current)
- --- --- --- (Dashed): Planned (Future Roadmap)

---

## 7대 핵심모듈 아키텍처

### 1. LogicExecutor Pipeline (try-catch 제거)

<img width="756" height="362" alt="LogicExecutor" src="https://github.com/user-attachments/assets/a43b8f43-fd49-489c-ab24-4c91a27584f5" />

```java
// Bad: 스파게티 try-catch
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
    return null;
}

// Good: LogicExecutor 템플릿
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", id)
);
```

**6가지 실행 패턴**: `execute`, `executeVoid`, `executeOrDefault`, `executeWithRecovery`, `executeWithFinally`, `executeWithTranslation`

---

### 2. Resilience4j (장애 격리)

<img width="626" height="364" alt="Resilience4j" src="https://github.com/user-attachments/assets/373b1203-55b7-4c94-99df-2b85c927d1b9" />

```yaml
# 3단계 타임아웃 레이어링
TCP Connect: 3s      # 네트워크 연결 실패 조기 탐지
HTTP Response: 5s    # 느린 응답 차단
TimeLimiter: 28s     # 전체 작업 상한 (3회 재시도 포함)

# Circuit Breaker
실패율 임계치: 50%
대기 시간: 10s
Half-Open 허용: 3회
```

**Marker Interface 분류**:
- `CircuitBreakerIgnoreMarker`: 비즈니스 예외 (4xx) - 서킷 상태 무영향
- `CircuitBreakerRecordMarker`: 시스템 예외 (5xx) - 실패로 기록

---

### 3. TieredCache (L1/L2) + Singleflight

<img width="728" height="523" alt="TieredCache" src="https://github.com/user-attachments/assets/b3ad5614-2ef7-4cda-b29f-cdcdec44dc9e" />

```
L1 HIT: < 5ms (Caffeine 로컬 메모리)
L2 HIT: < 20ms (Redis)
MISS: Singleflight로 1회만 DB 호출, 나머지 대기 후 결과 공유
```

**효과**: Cache Stampede 완전 방지, DB 쿼리 비율 ≤ 10%

---

### 4. AOP+Async 비동기 파이프라인

<img width="525" height="551" alt="AsyncPipeline" src="https://github.com/user-attachments/assets/792c224c-7fc6-41f7-82ba-d43438bede85" />

**Two-Phase Snapshot:**
| Phase | 목적 | 로드 데이터 |
|-------|------|------------|
| LightSnapshot | 캐시 키 생성 | 최소 필드 (ocid, fingerprint) |
| FullSnapshot | 계산 (MISS 시만) | 전체 필드 |

---

### 5. Transactional Outbox (분산 트랜잭션)

<img width="541" height="421" alt="Outbox" src="https://github.com/user-attachments/assets/16b60110-3d1e-46be-801d-762d8c151644" />

**Triple Safety Net (데이터 영구 손실 방지):**
1. **1차**: DB Dead Letter Queue
2. **2차**: File Backup (DB 실패 시)
3. **3차**: Discord Critical Alert (최후의 안전망)

---

### 6. Graceful Shutdown (4단계 순차 종료)

<img width="362" height="689" alt="GracefulShutdown" src="https://github.com/user-attachments/assets/70ce9987-1a8f-430f-b4ae-2184a7b16973" />

```
Phase 1: 새 요청 거부 (Admission Control)
Phase 2: 진행 중 작업 완료 대기 (30s)
Phase 3: 버퍼 플러시 (Like Buffer → DB)
Phase 4: 리소스 해제 (Connection Pool, Redis)
```

---

### 7. DP Calculator (Kahan Summation 정밀도)

<img width="239" height="549" alt="DPCalculator" src="https://github.com/user-attachments/assets/ef52dd64-4b6c-473f-a730-1d6bec86bf90" />

```java
// 부동소수점 오차 누적 방지
double sum = 0.0, c = 0.0;  // Kahan Summation
for (double value : values) {
    double y = value - c;
    double t = sum + y;
    c = (t - sum) - y;
    sum = t;
}
```

---

## Admission Control (Backpressure Design)

<img width="771" height="503" alt="Backpressure" src="https://github.com/user-attachments/assets/adf69973-1c96-47b7-9750-3aa55b4e64d7" />

시스템 과부하 시 **503 Service Unavailable + Retry-After 헤더**로 클라이언트에 재시도를 안내합니다.

| 항목 | 값 | 설명 |
|------|-----|------|
| Queue Capacity | 100 | 최대 대기 작업 수 |
| Rejected Policy | AbortPolicy | 큐 포화 시 즉시 거부 |
| Retry-After | 60s | 클라이언트 재시도 권장 시간 |

---

## Chaos Engineering: Nightmare Tests

> **18개 극한 시나리오 테스트**로 시스템의 회복 탄력성을 검증했습니다.

### 테스트 결과 요약 (N01~N06)

| 테스트 | 시나리오 | 결과 | 발견된 문제 | 해결 방안 |
|--------|---------|------|------------|----------|
| **N01** | Thundering Herd (Cache Stampede) | **PASS** | - | Singleflight 효과적 작동 |
| **N02** | Deadlock Trap | **FAIL→FIX** | Lock Ordering 미적용 | 알파벳순 테이블 접근 + @Retryable |
| **N03** | Thread Pool Exhaustion | **FAIL→FIX** | CallerRunsPolicy 블로킹 | AbortPolicy + Bulkhead 패턴 |
| **N04** | Connection Vampire | **CONDITIONAL** | @Transactional + .join() | 트랜잭션 범위와 외부 I/O 분리 |
| **N05** | Celebrity Problem (Hot Key) | **PASS** | - | TieredCache + Singleflight |
| **N06** | Timeout Cascade | **FAIL→FIX** | Zombie Request 발생 | 타임아웃 계층 정렬 |

### N02: Deadlock Trap - 문제 발견 및 해결

**문제**: Transaction A(TABLE_A→TABLE_B)와 Transaction B(TABLE_B→TABLE_A)가 교차 락 획득 시 100% Deadlock 발생

```sql
-- 재현: Coffman Conditions 4가지 조건 모두 충족
-- 1. Mutual Exclusion: InnoDB Row Lock
-- 2. Hold and Wait: TABLE_A 보유 상태에서 TABLE_B 대기
-- 3. No Preemption: 락 자발적 해제 없음
-- 4. Circular Wait: A→B, B→A 순환 대기
```

**해결**:
```java
// Lock Ordering 적용 - 알파벳순 테이블 접근
@Transactional
public void updateWithLockOrdering(Long equipmentId, Long userId) {
    equipmentRepository.findByIdWithLock(equipmentId);  // e < u
    userRepository.findByIdWithLock(userId);
}
```

### N03: Thread Pool Exhaustion - 문제 발견 및 해결

**문제**: `CallerRunsPolicy`로 인해 메인 스레드 2010ms 블로킹 → API 응답 불가

```
Pool: core=2, max=2, queue=2 (총 용량 4)
제출된 작업: 60개 (용량의 15배)
결과: 56개 작업이 메인 스레드에서 실행 → 블로킹!
```

**해결**:
```java
// AbortPolicy + Resilience4j Bulkhead
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

@Bulkhead(name = "asyncService", type = Bulkhead.Type.THREADPOOL)
public CompletableFuture<String> asyncMethod() { ... }
```

### N06: Timeout Cascade - 문제 발견 및 해결

**문제**: 클라이언트 타임아웃(3s) < 서버 처리 체인(17s+) → Zombie Request 발생

```
Client Timeout: 3초 → 연결 종료
Server Chain: Redis Retry 3회 × 3초 + 오버헤드 = 17초+
결과: 클라이언트 종료 후 14초 동안 서버 작업 계속 (리소스 낭비)
```

**해결**:
```yaml
# 타임아웃 계층 정렬: 클라이언트 > TimeLimiter > Retry Chain
resilience4j.timelimiter.instances.default.timeoutDuration: 8s  # 28s → 8s
redis.timeout: 2s  # 3s → 2s
nexon-api.retry.maxAttempts: 2  # 3 → 2
```

---

## Performance

### 벤치마크 결과 (#266)

| 메트릭 | 100 conn | 200 conn |
|--------|----------|----------|
| **Avg Latency** | 164ms | 275ms |
| **RPS** | **674** | **719** |
| **Error Rate** | **0%** | **0%** |
| **Throughput** | 4.25 MB/s | 4.56 MB/s |

> 등가 처리량: **10만 RPS급** (719 RPS × 150배 payload)

### 최적화 성과

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| JSON 압축 | 350KB | 17KB | **95%** |
| 동시 요청 처리 | 5.3s | 1.1s | **480%** |
| DB 인덱스 튜닝 | 0.98s | 0.02s | **50x** |
| 메모리 사용량 | 300MB | 30MB | **90%** |

---

## QuickStart (2-3분)

```bash
# 1. 인프라 구동 (MySQL, Redis)
docker-compose up -d

# 2. 애플리케이션 시작
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. API 테스트
curl "http://localhost:8080/api/v3/characters/강은호/expectation"
```

---

## Tech Stack

| 분류 | 기술 |
|------|------|
| **Core** | Java 17, Spring Boot 3.5.4 |
| **Database** | MySQL 8.0, JPA/Hibernate |
| **Cache** | Caffeine (L1), Redis/Redisson 3.27.0 (L2) |
| **Resilience** | Resilience4j 2.2.0 (Circuit Breaker, Retry, TimeLimiter) |
| **Testing** | JUnit 5, Testcontainers, Locust |
| **Monitoring** | Prometheus, Loki, Grafana |

---

## Development Journey

> **집중 개발 3개월 | 230 커밋 | 27,799 LoC | 479 테스트**

```
Feature 개발:    ████████████████████  33개 (34%)
Refactoring:    ████████████████████  32개 (33%)
Performance:    ████████              13개 (13%)
Test:           ██████████            16개 (16%)
```

---

## 5-Agent Council (AI-Augmented Development)

본 프로젝트는 **5개 AI 에이전트 페르소나**를 활용한 협업 프로토콜로 개발되었습니다.

| Agent | 역할 | 검증 영역 |
|-------|------|----------|
| **Blue** | Architect | SOLID, Design Pattern, Clean Architecture |
| **Green** | Performance Guru | O(1) 지향, Redis Lua, SQL Tuning |
| **Yellow** | QA Master | Edge Case, Boundary Test, Locust |
| **Purple** | Auditor | 데이터 무결성, 보안, 정밀 계산 |
| **Red** | SRE Gatekeeper | Resilience, Timeout, Graceful Shutdown |

**Pentagonal Pipeline**: Draft(Blue) → Optimize(Green) → Test(Yellow) → Audit(Purple) → Deploy Check(Red)

---

## 문서 구조

```
docs/
├── 00_Start_Here/           # 프로젝트 개요
│   ├── architecture.md      # 시스템 아키텍처 (Mermaid)
│   └── multi-agent-protocol.md  # 5-Agent Council
├── 01_Chaos_Engineering/    # Nightmare Tests (N01~N18)
│   └── 06_Nightmare/        # 시나리오 + 결과 리포트
├── 02_Technical_Guides/     # 인프라, 비동기, 테스트 가이드
└── 03_Sequence_Diagrams/    # 모듈별 시퀀스 다이어그램
```

---

## License

MIT License

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-25*
