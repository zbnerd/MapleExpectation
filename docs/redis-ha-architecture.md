# Redis HA 아키텍처 및 Redlock 검토

## 1. 현재 구현: Sentinel 기반 HA

### 1.1 아키텍처
- Redis Master 1대 + Slave 1대 (복제)
- Sentinel 3대 (quorum 2)
- Redisson Sentinel 모드

### 1.2 장점
- **SPOF 제거**: Master 장애 시 Slave 자동 승격
- **Failover 시간**: 1초 이내 (down-after-milliseconds 1000ms)
- **운영 비용**: Master/Slave 2대만 유지
- **성능**: Median 응답 시간 0.5s 이내 유지

### 1.3 제약사항
- **Split-brain 시나리오**: 일시적으로 이중 Master 발생 가능
- **네트워크 파티션**: 정합성 보장 불완전

---

## 2. Redlock 알고리즘 검토

### 2.1 Redlock이란?

Redlock은 **3개 이상의 완전 독립된** Redis 인스턴스에서 분산 락을 획득하는 알고리즘입니다.

**핵심 원리**:
- 과반수(N/2+1) 노드에서 락을 획득해야 성공
- 각 Redis 인스턴스는 **Master-Slave 복제가 아닌 독립 노드**여야 함
- 락 획득 시간이 TTL보다 짧아야 함 (Clock Skew 고려)

**예시**:
```java
// Redlock 알고리즘 (Redisson 지원)
RedissonClient redis1 = // 독립 Redis 1
RedissonClient redis2 = // 독립 Redis 2
RedissonClient redis3 = // 독립 Redis 3

RLock lock1 = redis1.getLock("resource");
RLock lock2 = redis2.getLock("resource");
RLock lock3 = redis3.getLock("resource");

// 3개 중 2개 이상에서 락 획득해야 성공
RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2, lock3);
multiLock.lock();
```

---

### 2.2 Redlock이 필요한 경우

**강한 정합성**이 필수적인 경우:
- 금융 거래 (결제, 송금)
- 재고 차감 (초과 판매 방지)
- 멤버십 포인트 차감
- 네트워크 파티션 시에도 **절대적 안전성** 필요

**Redlock의 안전성 보장**:
- Split-brain 시나리오에서도 단일 락 홀더 보장
- 과반수 노드가 동의해야 락 획득
- 네트워크 파티션으로 인한 이중 락 획득 방지

---

### 2.3 Redlock이 불필요한 경우 (현재 프로젝트)

**일시적 불일치 허용** 가능한 경우:
- 좋아요 카운트 (±1 오차 허용)
- 조회수 집계
- 캐시 무효화

**비즈니스 로직에서 보정** 가능한 경우:
- DB에서 최종 정합성 보장 (SSOT: Single Source of Truth)
- 주기적 동기화 (현재 프로젝트: 5초마다 Redis → DB)
- Circuit Breaker로 Redis 장애 시 DB Fallback

**현재 프로젝트의 락 사용 사례**:
- 좋아요 동시성 제어 (src/main/java/maple/expectation/global/lock/RedisDistributedLockStrategy.java)
- 캐릭터 데이터 동기화 (src/main/java/maple/expectation/service/v2/GameCharacterWorker.java)

이러한 경우 **일시적 락 중복**이 발생해도:
1. DB 트랜잭션으로 최종 정합성 보장
2. 5초 주기 동기화로 불일치 최소화
3. 비즈니스 영향도 낮음 (좋아요 ±1 오차)

---

### 2.4 비용 대비 효과 분석

| 항목 | Sentinel 방식 | Redlock 방식 |
|------|-------------|-------------|
| **Redis 인스턴스 수** | 2대 (M/S) | 3대 이상 (독립) |
| **인프라 비용** | 낮음 | 높음 (1.5배) |
| **운영 복잡도** | 낮음 | 높음 |
| **Failover 시간** | 1초 | N/A (항시 가용) |
| **정합성 보장** | 약함 | 강함 |
| **현재 프로젝트 적합성** | ⭐⭐⭐⭐⭐ | ⭐⭐ |

**비용 분석**:
- **Sentinel**: Redis 2대 + Sentinel 3대 (경량 프로세스)
- **Redlock**: 독립 Redis 3대 (각각 메모리 할당 필요)
- **증가 비용**: 약 50% (Redis 인스턴스 1대 추가)

**운영 복잡도**:
- **Sentinel**: 자동 Failover, 설정 간단
- **Redlock**:
  - 각 Redis 인스턴스 독립 관리
  - Clock Skew 모니터링 필요
  - 락 획득 실패 시 재시도 로직 필요

---

## 3. 실무적 권장사항

### 3.1 현재 프로젝트: Sentinel만으로 충분

**권장 이유**:

1. **비즈니스 요구사항 부합**
   - 좋아요/조회수는 강한 정합성 불필요
   - ±1 오차는 사용자 경험에 영향 없음

2. **DB가 최종 정합성 보장 (SSOT)**
   ```java
   // DB 트랜잭션으로 최종 정합성 보장
   @Transactional
   public void syncLikesToDB() {
       // Redis → DB 동기화
       // DB가 Single Source of Truth
   }
   ```

3. **계층형 버퍼 전략**
   - L1 Caffeine: 로컬 캐시 (초당 수천 건 처리)
   - L2 Redis: 분산 버퍼 (5초 주기 동기화)
   - L3 DB: 최종 정합성 보장

4. **Circuit Breaker로 장애 격리**
   ```yaml
   resilience4j:
     circuitbreaker:
       instances:
         redisLock:
           failureRateThreshold: 60
           waitDurationInOpenState: 30s
   ```

5. **비용 효율성**
   - Redis 2대 vs 3대 (33% 비용 증가)
   - 운영 복잡도 감소

---

### 3.2 Redlock 도입 고려 시나리오

향후 다음 기능 추가 시 재검토:

1. **유료 결제 시스템**
   - 중복 결제 방지
   - 결제 원자성 보장

2. **제한된 수량 상품 판매**
   - 재고 차감 정합성
   - 초과 판매 방지

3. **포인트/크레딧 차감 로직**
   - 마이너스 잔액 방지
   - 트랜잭션 무결성

**도입 시 고려사항**:
- Redisson의 `RedissonMultiLock` 사용
- 3개 독립 Redis 인스턴스 구성
- NTP 동기화 (Clock Skew 최소화)
- 락 획득 실패 시 재시도 정책

---

### 3.3 대안: 애플리케이션 레벨 보정

현재 구현된 전략으로 충분:

**1. 계층형 버퍼 (src/main/java/maple/expectation/service/v2/LikeSyncService.java)**
```
사용자 요청
  ↓
L1 Caffeine (메모리)
  ↓ 5초 주기
L2 Redis (분산)
  ↓ 5초 주기
L3 DB (SSOT)
```

**2. Graceful Shutdown 데이터 복구**
```java
// src/main/java/maple/expectation/service/v2/shutdown/ShutdownDataRecoveryService.java
- 서버 종료 시 Redis 버퍼 → 파일 백업
- 재시작 시 파일 → DB 자동 복구
```

**3. Circuit Breaker Fallback**
```java
// Redis 장애 시 DB 직접 쓰기
if (circuitBreaker.isOpen()) {
    writeDirectlyToDB();
}
```

---

## 4. Martin Kleppmann의 Redlock 비판 고려

[Martin Kleppmann](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)의 Redlock 비판:

1. **Clock Skew 문제**: 시스템 시간 불일치 시 안전성 보장 불가
2. **Garbage Collection Pause**: JVM GC로 인한 락 유효기간 초과
3. **복잡도 대비 실익**: 강한 정합성이 필요하면 **합의 알고리즘**(Raft, Paxos) 사용 권장

**실무적 결론**:
- **Efficiency 목적** (성능): Sentinel 충분
- **Safety 목적** (안전성): Redlock도 불완전, 합의 알고리즘 필요
- **현재 프로젝트**: Efficiency 목적이므로 Sentinel이 최적

---

## 5. 결론

**✅ Sentinel 기반 HA만 구현하고, Redlock은 향후 필요 시 검토**

**근거**:
1. **실무적 비용 효율성**: Redis 2대 vs 3대
2. **비즈니스 요구사항 부합**: 강한 정합성 불필요
3. **운영 복잡도 최소화**: 자동 Failover
4. **안전성 대안**: Circuit Breaker + DB Fallback

**향후 검토 조건**:
- 금융 거래, 재고 관리, 포인트 차감 기능 추가 시
- 하지만 그 경우에도 **합의 알고리즘**(Raft/Paxos) 검토 우선

---

## 참고 자료

- [Redlock 공식 문서](https://redis.io/docs/manual/patterns/distributed-locks/)
- [Martin Kleppmann의 Redlock 비판](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)
- [Redisson 문서](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)
- 현재 프로젝트 참고 파일:
  - `src/main/java/maple/expectation/global/lock/RedisDistributedLockStrategy.java`
  - `src/main/java/maple/expectation/service/v2/LikeSyncService.java`
  - `docs/resilience.md`
