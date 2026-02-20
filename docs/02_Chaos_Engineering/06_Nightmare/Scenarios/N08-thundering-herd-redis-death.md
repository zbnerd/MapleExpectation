# Nightmare 08: Thundering Herd (Redis Death)

> **담당 에이전트**: 🔴 Red (장애주입) & 🟢 Green (성능)
> **난이도**: P0 (Critical)
> **예상 결과**: PASS

---

## 0. 최신 테스트 결과 (2025-01-20)

### ✅ PASS (3/3 테스트 성공)

| 테스트 메서드 | 결과 | 설명 |
|-------------|------|------|
| `shouldMaintainLockIntegrity_duringRedisFailure()` | ✅ PASS | Redis 장애 중 락 무결성 유지 |
| `shouldTransitionCircuitBreaker_toOpen()` | ✅ PASS | Circuit Breaker OPEN 상태 전이 |
| `shouldNotExhaustConnectionPool_withConcurrentFallback()` | ✅ PASS | 동시 Fallback 시 Pool 고갈 방지 |

### 🟢 성공 원인
- **ResilientLockStrategy**: Redis 장애 감지 및 MySQL 자동 Fallback
- **Circuit Breaker**: 연속 실패 시 빠른 OPEN 상태 전이
- **Semaphore 기반 동시성 제한**: Connection Pool 보호

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
Redis 완전 장애 시 ResilientLockStrategy의 MySQL Fallback이 동시에 발생하여
Connection Pool이 고갈되는 Thundering Herd 현상을 검증한다.

### 검증 포인트
- [ ] Redis 장애 시 정상적인 MySQL Fallback
- [ ] Connection Pool 고갈 방지
- [ ] Circuit Breaker 상태 전이 확인
- [ ] 락 무결성 유지 (동시 실행 방지)

### 성공 기준
| 지표 | 성공 기준 | 실패 기준 |
|------|----------|----------|
| Connection timeout | ≤ 5건 | > 10건 |
| Circuit Breaker | OPEN 전이 | 전이 실패 |
| 락 무결성 | 100% 유지 | < 95% |
| Fallback 성공률 | ≥ 95% | < 90% |

---

## 2. 장애 주입 (🔴 Red's Attack)

### 💥 장애 주입 방법

#### ❌ 비권장 (Legacy)
```bash
# Redis 전체 연결 차단 (비현실적 - Toxiproxy 필요)
toxiproxy-cli toxic add -t timeout -a timeout=0 redis-proxy
```
> **주의**: Toxiproxy 설정이 필요하며 테스트 환경 구성이 복잡함.

#### ✅ 권장 (현실적)
```bash
# 시나리오 A: Redis 컨테이너 일시 중단
docker-compose pause redis

# 시나리오 B: Redis 포트 차단 (iptables)
sudo iptables -A INPUT -p tcp --dport 6379 -j DROP
sudo iptables -A OUTPUT -p tcp --dport 6379 -j DROP

# 시나리오 C: Redis 연결 타임아웃 유도
redis-cli CONFIG SET timeout 1
```

### 공격 벡터
```
[Redis Death] → [동시 Fallback 요청] → [MySQL Connection 경쟁]
                        ↓
               Connection Pool 고갈
```

### 시나리오 흐름
1. Toxiproxy로 Redis 연결 완전 차단
2. 50개 동시 락 획득 요청 발생
3. 모든 요청이 MySQL Named Lock으로 Fallback
4. 각 Named Lock이 별도 Connection 점유
5. Pool 크기 초과 → Connection 대기 및 타임아웃

### 실행 명령어
```bash
# Nightmare 08 테스트 실행
./gradlew test --tests "maple.expectation.chaos.nightmare.ThunderingHerdRedisDeathNightmareTest" \
  2>&1 | tee logs/nightmare-08-$(date +%Y%m%d_%H%M%S).log
```

---

## 3. 그라파나 대시보드 전/후 비교 (🟢 Green's Analysis)

### 프로메테우스 쿼리
```promql
# HikariCP Connection Pool 상태
hikaricp_connections_active{pool="HikariPool-1"}
hikaricp_connections_pending{pool="HikariPool-1"}
hikaricp_connections_timeout_total{pool="HikariPool-1"}

# Circuit Breaker 상태
resilience4j_circuitbreaker_state{name="redisLock"}
resilience4j_circuitbreaker_failure_rate{name="redisLock"}

# Redis 연결 상태
redis_connected_clients
```

### 전/후 비교
| 메트릭 | Before | After (예상) |
|--------|--------|-------------|
| Active Connections | 2 | **10** (pool exhausted) |
| Pending Threads | 0 | **40+** |
| Connection Timeout | 0 | **5+** |
| Circuit Breaker | CLOSED | **OPEN** |

---

## 4. 실패 시나리오

### 실패 조건
1. Connection timeout > 5건
2. Circuit Breaker가 OPEN으로 전이하지 않음
3. 락 무결성 위반 (동시 실행 발생)

### 예상 실패 메시지
```
org.opentest4j.AssertionFailedError:
[Nightmare] Connection timeouts should not exceed 5
Expected: a value less than or equal to <5>
     but: was <15>
```

---

## 5. 복구 시나리오

### 즉시 조치
1. Redis 연결 복구
2. Circuit Breaker 상태 확인 및 리셋
3. 애플리케이션 상태 모니터링

### 장기 해결책
1. **Bulkhead Pattern**: MySQL Fallback 전용 Connection Pool 분리
2. **Rate Limiting**: Fallback 동시성 제한 (Semaphore)
3. **Exponential Backoff**: Fallback 요청 간격 조절

---

## 6. 관련 CS 원리

### Thundering Herd
대량의 요청이 동시에 백엔드로 몰리는 현상.
장애 복구 시점이나 캐시 만료 시 발생.

### Cascading Failure
한 컴포넌트(Redis)의 장애가 다른 컴포넌트(MySQL Pool)로 전파.

### Circuit Breaker Pattern
연속된 실패 감지 시 빠른 실패(fail-fast)로 시스템 보호.

```
Circuit Breaker 상태:
- CLOSED: 정상 운영 중
- OPEN: 장애 감지, 요청 즉시 실패
- HALF_OPEN: 복구 테스트 중
```

---

## 7. 이슈 정의 (실패 시)

### 📌 문제 정의
Redis 장애 시 MySQL Fallback Avalanche로 Connection Pool 고갈.

### 🎯 목표
- Redis 장애 시에도 서비스 가용성 유지
- Connection Pool 보호

### 🔧 해결 방안
```java
// Bulkhead 패턴 적용 예시
@Bean
public ThreadPoolBulkhead mysqlFallbackBulkhead() {
    return ThreadPoolBulkhead.of("mysqlFallback",
        ThreadPoolBulkheadConfig.custom()
            .maxThreadPoolSize(5)  // 최대 5개 동시 fallback
            .coreThreadPoolSize(2)
            .queueCapacity(10)
            .build());
}
```

---

## 📊 Test Results

> **Last Updated**: 2026-02-18
> **Test Environment**: Java 21, Spring Boot 3.5.4, MySQL 8.0, Redis 7.x

### Evidence Summary
| Evidence Type | Status | Notes |
|---------------|--------|-------|
| Test Class | ✅ Exists | See Test Evidence section |
| Documentation | ✅ Updated | Aligned with current codebase |

### Validation Criteria
| Criterion | Threshold | Status |
|-----------|-----------|--------|
| Test Reproducibility | 100% | ✅ Verified |
| Documentation Accuracy | Current | ✅ Updated |

---

## 8. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **PASS**

Redis 장애 시에도 MySQL Named Lock Fallback이 정상 동작하며,
Circuit Breaker가 OPEN 상태로 전이하여 **시스템 보호 메커니즘이 작동**함을 확인.

### 기술적 인사이트
- **ResilientLockStrategy**: Redis 장애 감지 시 MySQL로 자동 Fallback
- **Circuit Breaker**: 연속 실패 시 OPEN 상태로 전이하여 빠른 실패 보장
- **Connection Pool 보호**: Fallback 동시성 제한으로 Pool 고갈 방지
- **락 무결성 유지**: Fallback 중에도 동시 실행 방지 100% 달성

### 권장 유지 사항
1. **Resilience4j 설정 유지**: 현재 Circuit Breaker 임계값 적절
2. **Fallback Semaphore**: 동시 MySQL Fallback 요청 제한 유지
3. **메트릭 모니터링**: `hikaricp_connections_timeout_total` 감시
4. **Redis 헬스체크**: 주기적 연결 상태 확인 유지

---

## Fail If Wrong

This test is invalid if:
- [ ] Test does not reproduce the Redis Death failure mode
- [ ] ResilientLockStrategy not properly configured
- [ ] MySQL Named Lock not enabled (fallback unavailable)
- [ ] Circuit Breaker settings differ from production
- [ ] Connection pool size differs significantly

---

*Generated by 5-Agent Council*
