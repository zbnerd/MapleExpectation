# 대규모 트래픽 & 대용량 데이터 처리 P0/P1 분석 리포트

> **분석 일자:** 2026-01-28
> **분석 범위:** `src/main/java` 전체 (repository, service, controller, config 패키지)
> **목표 RPS:** 1,000+ RPS (현재 235 RPS → 4배 확장)
> **관련 이슈:** #284

---

## 요약

| 분류 | P0 (Critical) | P1 (High) | 해결됨 |
|------|:---:|:---:|:---:|
| Thread Pool / Connection Pool | 3 | 1 | 0 |
| Cache & Stampede | 1 | 0 | 0 |
| DB Query & Lock | 1 | 2 | 0 |
| Rate Limiting & Backpressure | 0 | 1 | 2 |
| Memory & CPU | 0 | 2 | 1 |
| **합계** | **5** | **6** | **3** |

**핵심 병목:** Executor Thread Pool (8 threads) + MySQL Connection Pool (30 conns) + Hot Row Lock 경합

---

## P0 (Critical: 서비스 다운 가능)

### P0-1: Executor Thread Pool 고갈 (expectationComputeExecutor)

**파일:** `config/ExecutorConfig.java:175-200`

```java
executor.setCorePoolSize(4);      // 너무 낮음
executor.setMaxPoolSize(8);       // 1000 RPS에 턱없이 부족
executor.setQueueCapacity(200);   // 1초 미만에 가득 참
```

| 항목 | 현재 | 필요 (1000 RPS) |
|------|------|-----------------|
| Core Pool | 4 | 50 |
| Max Pool | 8 | 500 |
| Queue | 200 | 5,000 |

**영향 분석:**
- Cache MISS 시나리오: API 호출(5s) + 파싱(100ms) + 캐싱(50ms) = ~5.1s/request
- 1000 RPS × 5.1s = **5,100개 동시 작업 필요**
- 현재 용량: 8 threads + 200 queue = **208개 최대**
- 결과: **80% 요청 거부 (503 에러)**

**해결:**
```java
executor.setCorePoolSize(50);      // Warm pool
executor.setMaxPoolSize(500);      // Peak 대응
executor.setQueueCapacity(5000);   // Burst 흡수
executor.setRejectedExecutionHandler((r, e) -> {
    meterRegistry.counter("executor.rejection").increment();
    throw new RejectedExecutionException("Queue full");
});
```

---

### P0-2: MySQL Connection Pool 고갈 (Redis Fallback 시)

**파일:** `config/LockHikariConfig.java:48-49`

```java
config.setMaximumPoolSize(POOL_SIZE);  // = 30
config.setMinimumIdle(POOL_SIZE);      // = 30
```

**영향 분석:**
- Redis 장애 시 MySQL Named Lock으로 fallback
- Lock 보유 시간: ~100ms
- 30 connections × (1000ms / 100ms) = **300 req/s 최대**
- 1000 RPS 중 **700 req/s 대기/타임아웃**

**해결:**
```java
// Required = (RPS × avg_lock_hold_time) + buffer
// = (1000 × 0.1) + 50 = 150 connections
config.setMaximumPoolSize(150);
config.setMinimumIdle(50);
```

---

### P0-3: Thread Pool Deadlock (중첩 .join() 호출)

**파일:** `service/v2/EquipmentService.java:150-157` 외 다수

```java
return CompletableFuture
    .supplyAsync(() -> {
        // expectationComputeExecutor (max 8 threads) 내에서
        return dataResolver.resolveAsync(...).join();  // ← DEADLOCK!
    }, expectationComputeExecutor);
```

**영향 분석:**
- 8개 스레드가 모두 `.join()`으로 대기 중
- 새 요청 처리 불가 → 큐 가득 참 → 시스템 멈춤
- **완전 교착 상태 (Deadlock)**

**해결:**
```java
// Bad: .join() 내부 호출
CompletableFuture.supplyAsync(() -> future.join(), executor);

// Good: thenCompose() 체이닝
future.thenCompose(result ->
    CompletableFuture.supplyAsync(() -> process(result), executor)
);
```

---

### P0-4: Cache Stampede (EquipmentResponse 레벨)

**파일:** `service/v2/EquipmentService.java:119-150`

**현재 상태:**
- `TotalExpectationResponse`: Single-Flight 적용 ✅
- `EquipmentResponse`: Single-Flight 미적용 ❌

**영향 분석:**
- 100개 동시 요청이 같은 캐릭터 장비 조회
- L1 MISS → L2 MISS → **100개 동일 Nexon API 호출**
- Nexon Rate Limit (500 RPS) 중 20%를 단일 캐릭터가 소비
- **Thundering Herd 문제**

**해결:**
```java
// EquipmentDataResolver에 Single-Flight 추가
private final SingleFlightExecutor<EquipmentResponse> equipmentSingleFlight;

public CompletableFuture<EquipmentResponse> resolveAsync(String ocid) {
    return equipmentSingleFlight.executeAsync(
        "equipment:" + ocid,
        () -> dataResolver.getFromApi(ocid)
    );
}
```

---

### P0-5: Hot Row Lock 경합 (likeCount UPDATE)

**파일:** `repository/v2/GameCharacterRepository.java:65`

```java
@Query("UPDATE GameCharacter c SET c.likeCount = c.likeCount + :count WHERE c.userIgn = :userIgn")
void incrementLikeCount(@Param("userIgn") String userIgn, @Param("count") Long count);
```

**영향 분석:**
- 인기 캐릭터에 1000 RPS 좋아요 요청
- 단일 row에 Exclusive Lock 경합
- Lock 보유: 1-5ms → **200 update/s 실제 처리량**
- **80% 요청이 Lock 대기**

**해결 (Sharding):**
```sql
-- 10개 샤드로 분산
ALTER TABLE game_character
ADD COLUMN like_count_shard_0 BIGINT DEFAULT 0,
ADD COLUMN like_count_shard_1 BIGINT DEFAULT 0,
...
ADD COLUMN like_count_shard_9 BIGINT DEFAULT 0;

-- 해시 기반 샤드 선택
UPDATE game_character
SET like_count_shard_{hash % 10} = like_count_shard_{hash % 10} + ?
WHERE user_ign = ?;

-- 조회 시 합산
SELECT (like_count_shard_0 + ... + like_count_shard_9) AS total_likes
FROM game_character WHERE user_ign = ?;
```

---

## P1 (High: 성능 저하)

### P1-1: CubeProbability.findAll() 페이지네이션 미적용

**파일:** `repository/v2/CubeProbabilityRepository.java:83-86`

```java
public List<CubeProbability> findAll() {
    return probabilityCache.values().stream()
            .flatMap(List::stream)
            .toList();  // 전체 메모리 로드
}
```

| 항목 | 수치 |
|------|------|
| 예상 레코드 | 5,000~10,000 |
| 호출당 메모리 | 5-10 MB |
| 1000 RPS | 5-10 GB/s 할당 |
| GC Pause | 50-200ms |

**해결:** `@Deprecated` 처리 또는 페이지네이션 적용

---

### P1-2: Rate Limiting 미연결 (고트래픽 엔드포인트)

**파일:** `global/ratelimit/` (인프라 존재, 연결 미확인)

**위험 엔드포인트:**
| 엔드포인트 | 권장 제한 |
|------------|----------|
| `GET /api/v3/character/{userIgn}/expectation` | 100 req/min/IP |
| `POST /api/v2/character/{ocid}/like` | 60 req/min/user |
| `GET /api/v4/character/{userIgn}/equipment` | 30 req/min/IP |

**해결:** `RateLimitingFilter` 서블릿 체인 연결 확인 및 엔드포인트별 설정

---

### P1-3: GZIP 압축/해제 CPU 병목

**파일:** `util/GzipUtils.java:7-42`, `provider/EquipmentDataProvider.java:65-86`

**현재:**
- `streamAndDecompress()`: 350KB 압축 → 해제 → String → UTF-8 재인코딩
- CPU 1-5ms/request → **8 threads로 1,600-8,000 req/s 한계**

**해결:** `streamRaw()` 사용으로 Zero-copy 전송 (이미 구현됨, deprecated 완료 필요)

---

### P1-4: Slow Query (findAllByUserIgn 인덱스 미적용)

**파일:** `repository/v2/EquipmentExpectationSummaryRepository.java:68-76`

```java
@Query("""
    SELECT ees FROM EquipmentExpectationSummary ees
    JOIN GameCharacter gc ON gc.id = ees.gameCharacterId
    WHERE gc.userIgn = :userIgn
    ORDER BY ees.presetNo
    """)
List<EquipmentExpectationSummary> findAllByUserIgn(@Param("userIgn") String userIgn);
```

**영향:**
- `game_character.user_ign` 인덱스 미존재 시 Full Table Scan
- 100K+ rows: **100ms+ 쿼리 시간**

**해결:**
```sql
CREATE INDEX idx_game_character_user_ign ON game_character(user_ign);
CREATE INDEX idx_ees_character_preset ON equipment_expectation_summary(game_character_id, preset_no);
```

---

### P1-5: Event Listener 메모리 누수 위험

**파일:** `service/v2/like/listener/LikeSyncEventListener.java`

**위험:**
- Pub/Sub 리스너 미해제 시 Redis 연결 누적
- 10 instances × 3 topics = **30개 좀비 구독**

**해결:**
```java
@PreDestroy
public void unsubscribe() {
    redissonClient.getTopic("like-sync-events").removeListener(this);
}
```

---

### P1-6: 대용량 POST DoS 벡터

**파일:** `application.yml:165`

```yaml
server:
  tomcat:
    max-http-post-size: 262144  # 256KB (적절함)
```

**현재:** 256KB 제한 ✅
**추가 조치:** IP당 Rate Limiting으로 반복 공격 방어

---

## 이미 해결된 항목 ✅

| 항목 | 파일 | 해결 방법 |
|------|------|----------|
| N+1 Query | GameCharacterRepository:73 | `LEFT JOIN FETCH` 적용 |
| Batch INSERT/UPDATE | LikeSyncExecutor:56 | `jdbcTemplate.batchUpdate()` |
| Buffer Overflow | ExpectationWriteBackBuffer:156 | Backpressure 구현 (10K 제한) |

---

## 모니터링 알람 권장

```prometheus
# Thread Pool 고갈 경고
ALERT ThreadPoolExhaustion
  IF executor_active / executor_max > 0.9
  FOR 1m
  SEVERITY critical

# Connection Pool 고갈 경고
ALERT ConnectionPoolExhaustion
  IF hikari_connections_active / hikari_connections_max > 0.8
  FOR 1m
  SEVERITY critical

# Cache Stampede 감지
ALERT CacheMissStorm
  IF rate(cache_misses_total[1m]) > 100
  FOR 2m
  SEVERITY warning

# Buffer Backpressure 감지
ALERT BufferBackpressure
  IF expectation_buffer_pending > 8000
  FOR 1m
  SEVERITY warning
```

---

## 우선순위별 Action Items

### 즉시 (Sprint 1)
- [ ] P0-1: `expectationComputeExecutor` 풀 크기 증가 (4→50, 8→500)
- [ ] P0-2: `LockHikariConfig` 커넥션 풀 증가 (30→150)
- [ ] P0-3: `.join()` 호출을 `thenCompose()`로 리팩토링

### 높음 (Sprint 2)
- [ ] P0-4: `EquipmentResponse`에 Single-Flight 확장
- [ ] P0-5: `likeCount` 샤딩 또는 CDC 방식 전환
- [ ] P1-2: Rate Limiting 엔드포인트 연결 검증

### 중간 (Sprint 3)
- [ ] P1-1: `CubeProbability.findAll()` deprecated 처리
- [ ] P1-4: DB 인덱스 추가 (`user_ign`, `game_character_id + preset_no`)
- [ ] P1-5: Event Listener `@PreDestroy` 검증

---

## 관련 문서

- [#284 대규모 트래픽 P0/P1 해결](https://github.com/zbnerd/MapleExpectation/issues/284)
- [ADR-013: 대규모 트래픽 비동기 이벤트 파이프라인](../adr/ADR-013-high-throughput-event-pipeline.md)
- [#283 Scale-out 방해 요소 제거](https://github.com/zbnerd/MapleExpectation/issues/283)
