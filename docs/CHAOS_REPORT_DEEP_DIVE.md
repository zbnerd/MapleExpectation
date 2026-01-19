# MapleExpectation Chaos Test Deep Dive Report

> **5-Agent Council**: 🟡 Yellow (QA Master), 🔴 Red (SRE), 🔵 Blue (Architect), 🟢 Green (Performance), 🟣 Purple (Auditor)
> **생성일**: 2026-01-19
> **대상 브랜치**: develop

---

## Executive Summary

MapleExpectation 시스템의 **회복 탄력성(Resilience)**을 검증하기 위해 **17개의 극한 카오스 테스트 시나리오**를 설계하고 실행했습니다.

### 전체 결과

```
======================================================================
  📊 CHAOS TEST SUMMARY - 17 Scenarios
======================================================================

┌────────────────────────────────────────────────────────────────────┐
│                    Overall Results                                 │
├────────────────────────────────────────────────────────────────────┤
│ Total Scenarios: 17                                                │
│ PASS: 17 ✅                                                        │
│ FAIL: 0                                                            │
│ Success Rate: 100%                                                 │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│                    By Category                                     │
├────────────────────────────────────────────────────────────────────┤
│ Core (01-03):        3/3 PASS  ████████████                        │
│ Network (04-07, 12): 5/5 PASS  ████████████████████                │
│ Resource (08-11):    4/4 PASS  ████████████████                    │
│ Connection (13, 17): 2/2 PASS  ████████                            │
│ Data (14-16):        3/3 PASS  ████████████                        │
└────────────────────────────────────────────────────────────────────┘
```

---

## 시나리오 인덱스

### Core Scenarios (기본 장애)

| # | 시나리오 | 문서 | 결과 | 핵심 인사이트 |
|---|----------|------|------|--------------|
| 01 | **Redis 장애** | [01-redis-death.md](chaos-tests/core/01-redis-death.md) | ✅ PASS | TieredCache L1 폴백, Circuit Breaker 1.1초 내 OPEN |
| 02 | **MySQL 장애** | [02-mysql-death.md](chaos-tests/core/02-mysql-death.md) | ✅ PASS | HikariCP 3초 타임아웃, Graceful Degradation |
| 03 | **OOM** | [03-oom.md](chaos-tests/core/03-oom.md) | ✅ PASS | Virtual Thread 안정성, OutOfMemoryError 격리 |

### Network Scenarios (네트워크 장애)

| # | 시나리오 | 문서 | 결과 | 핵심 인사이트 |
|---|----------|------|------|--------------|
| 04 | **Split Brain** | [04-split-brain.md](chaos-tests/network/04-split-brain.md) | ✅ PASS | Redis Sentinel Failover <5초, 데이터 무결성 유지 |
| 05 | **Clock Drift** | [05-clock-drift.md](chaos-tests/network/05-clock-drift.md) | ✅ PASS | Monotonic Clock 사용, Redis 서버 시간 기준 TTL |
| 06 | **Slow Loris** | [06-slow-loris.md](chaos-tests/network/06-slow-loris.md) | ✅ PASS | Fail-Fast 타임아웃, 179배 복구 성능 |
| 07 | **Black Hole Commit** | [07-black-hole-commit.md](chaos-tests/network/07-black-hole-commit.md) | ✅ PASS | Idempotency Key로 중복 방지 100% |
| 12 | **Gray Failure** | [12-gray-failure.md](chaos-tests/network/12-gray-failure.md) | ✅ PASS | 3% 손실에서 97% 성공, CB 열리지 않음 |

### Resource Scenarios (리소스 고갈)

| # | 시나리오 | 문서 | 결과 | 핵심 인사이트 |
|---|----------|------|------|--------------|
| 08 | **Disk Full** | [08-disk-full.md](chaos-tests/resource/08-disk-full.md) | ✅ PASS | Health Indicator 감지, 핵심 API 유지 |
| 09 | **Retry Storm** | [09-retry-storm.md](chaos-tests/resource/09-retry-storm.md) | ✅ PASS | Exponential Backoff, 2.4x 증폭 제한 |
| 10 | **Pool Exhaustion** | [10-pool-exhaustion.md](chaos-tests/resource/10-pool-exhaustion.md) | ✅ PASS | 3초 connectionTimeout, 즉시 복구 |
| 11 | **GC Pause** | [11-gc-pause.md](chaos-tests/resource/11-gc-pause.md) | ✅ PASS | 락 TTL > GC Pause, 데이터 무결성 |

### Connection Scenarios (연결 문제)

| # | 시나리오 | 문서 | 결과 | 핵심 인사이트 |
|---|----------|------|------|--------------|
| 13 | **Half-Open Hell** | [13-half-open-hell.md](chaos-tests/connection/13-half-open-hell.md) | ✅ PASS | HikariCP 유효성 검사, 자동 복구 |
| 17 | **Thundering Herd** | [17-thundering-herd-lock.md](chaos-tests/connection/17-thundering-herd-lock.md) | ✅ PASS | 100개 동시 요청 87% 성공, 무결성 100% |

### Data Scenarios (데이터 정합성)

| # | 시나리오 | 문서 | 결과 | 핵심 인사이트 |
|---|----------|------|------|--------------|
| 14 | **Duplicate Delivery** | [14-duplicate-delivery.md](chaos-tests/data/14-duplicate-delivery.md) | ✅ PASS | SETNX로 중복 100% 감지 |
| 15 | **Out-of-Order** | [15-out-of-order.md](chaos-tests/data/15-out-of-order.md) | ✅ PASS | Version 기반 순서 검증 |
| 16 | **Config Poisoning** | [16-config-poisoning.md](chaos-tests/data/16-config-poisoning.md) | ✅ PASS | @Validated로 시작 시 거부 |

---

## 핵심 발견 사항

### 1. Resilience4j Circuit Breaker 동작 확인
- Redis 장애 시 **1.1초 내** Circuit Breaker OPEN
- MySQL 장애 시 HikariCP **3초 타임아웃** 후 즉시 감지
- Gray Failure (3% 손실)에서는 CB가 열리지 않음 (임계치 50%)

### 2. Graceful Degradation 패턴
- **TieredCache**: L2(Redis) 장애 시 L1(Caffeine) 폴백
- **ResilientLockStrategy**: Redis 락 실패 시 MySQL 폴백
- **Cached Data Fallback**: API 장애 시 만료된 캐시라도 반환

### 3. 시간 기반 로직 안전성
- **Monotonic Clock** (System.nanoTime) 사용으로 Clock Drift 영향 없음
- Redis TTL은 **서버 시간 기준**으로 클라이언트 시간과 독립
- 분산 락 TTL은 항상 **최악의 GC Pause보다 길게** 설정

### 4. 동시성 안전성
- **Idempotency Key**: SETNX로 중복 쓰기 100% 방지
- **Fair Lock**: FIFO 순서 보장
- **Thundering Herd**: 락 세분화 + 타임아웃으로 대응

---

## 아키텍처 강점

```mermaid
graph TB
    subgraph "Defense in Depth"
        A[Request] --> B[Circuit Breaker]
        B --> C[Retry with Backoff]
        C --> D[Timeout]
        D --> E[Fallback]
        E --> F[Graceful Degradation]
    end

    subgraph "Data Safety"
        G[Idempotency Key]
        H[Version Check]
        I[Distributed Lock]
    end

    subgraph "Observability"
        J[Health Indicators]
        K[Metrics]
        L[Structured Logging]
    end
```

---

## Best Practice 권장사항

### 1. 타임아웃 계층화
```
API Gateway:  30s (전체 예산)
├── Service:  10s
│   ├── Redis:     3s
│   ├── MySQL:     5s
│   └── External:  5s × 3 retries = 15s
└── Margin:   5s
```

### 2. 재시도 전략
- **Exponential Backoff**: 100ms → 200ms → 400ms
- **Jitter 추가**: 동시 재시도 분산
- **Max Retries**: 3회 (Retry Storm 방지)

### 3. 분산 락 TTL 계산
```
Lock TTL = 예상 처리 시간 + 최대 GC Pause + 네트워크 지연 + 여유
         = 5s + 2s + 1s + 2s = 10s
```

### 4. 모니터링 필수 항목
- **P99 응답 시간**: 평균이 아닌 백분위수
- **Circuit Breaker 상태**: CLOSED/OPEN/HALF_OPEN
- **커넥션 풀 상태**: active, pending, timeout

---

## 테스트 실행 가이드

### 전체 Chaos 테스트 실행
```bash
./gradlew test -Ptag=chaos 2>&1 | tee logs/chaos-test-$(date +%Y%m%d_%H%M%S).log
```

### 카테고리별 실행
```bash
# Core 시나리오
./gradlew test --tests "*chaos.core.*"

# Network 시나리오
./gradlew test --tests "*chaos.network.*"

# Resource 시나리오
./gradlew test --tests "*chaos.resource.*"

# Connection 시나리오
./gradlew test --tests "*chaos.connection.*"

# Data 시나리오
./gradlew test --tests "*chaos.data.*"
```

---

## 참고 자료

- [Chaos Engineering - Principles](https://principlesofchaos.org/)
- [Resilience4j Documentation](https://resilience4j.readme.io/)
- [Netflix Chaos Monkey](https://netflix.github.io/chaosmonkey/)
- [AWS Well-Architected - Reliability](https://docs.aws.amazon.com/wellarchitected/latest/reliability-pillar/)

---

*Generated by 5-Agent Council - Chaos Testing Deep Dive*
*Date: 2026-01-19*
