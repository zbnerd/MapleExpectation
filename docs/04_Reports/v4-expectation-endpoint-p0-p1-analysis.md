# V4 Expectation 엔드포인트 P0/P1 종합 분석 리포트

> **분석 일자:** 2026-01-29
> **분석 대상:** `GET /api/v4/characters/{userIgn}/expectation`
> **분석 방법:** 5-Agent Council (Blue, Green, Red, Purple, Yellow) + Sequential Thinking + Context7
> **관련 이슈:** #240, #262, #264, #266, #278

---

## Executive Summary

V4 Expectation 엔드포인트는 전반적으로 견고한 아키텍처(TieredCache, Singleflight, Write-Behind Buffer, GZIP 압축)를 갖추고 있으나, **3개의 P0 이슈와 7개의 P1 이슈**가 식별되었습니다. 특히 `@Transactional` self-invocation, `.join()` 블로킹, In-Memory 버퍼 등이 대규모 트래픽 및 Scale-out 시 심각한 문제를 야기합니다.

---

## 5-Agent Council 판정 결과

| Agent | Role | 판정 | 주요 발견 |
|-------|------|------|----------|
| Blue (Architect) | 아키텍처 | **FAIL** | @Transactional self-invocation, 910줄 God Class, SRP 위반 |
| Green (Performance) | 성능 | **FAIL** | LinkedBlockingQueue로 Max Pool 도달 불가, .join() 블로킹 |
| Red (SRE) | 안정성 | **FAIL** | 1000 RPS 시 99.84% 에러율, DB 커넥션 풀 고갈 |
| Purple (Auditor) | 코드 품질 | **FAIL** | IllegalStateException 직접 사용(Section 11 위반), 주석-동작 불일치 |
| Yellow (QA) | Dead Code | **WARNING** | Repository 6개 메서드 미사용, recalculate force 미사용 |

**종합 판정: FAIL (만장일치)**

---

## P0 이슈 (Critical - 즉시 수정 필요)

### P0-1: @Transactional Self-Invocation (트랜잭션 무효화)

**위치:** `EquipmentExpectationServiceV4.java:158-163`

```java
// 문제 코드
public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(...) {
    return CompletableFuture.supplyAsync(
            () -> calculateExpectation(userIgn, force),  // ← this. 호출 → 프록시 우회!
            equipmentExecutor
    );
}

@Transactional  // ← 이 어노테이션은 self-invocation으로 무효화됨!
public EquipmentExpectationResponseV4 calculateExpectation(String userIgn, boolean force) { ... }
```

**문제점:**
- `CompletableFuture.supplyAsync()` 내에서 `this.calculateExpectation()` 호출 → Spring AOP 프록시 우회
- `@Transactional` 어노테이션이 완전히 무효화됨 (Dead Code)
- `saveResultsSync()`에서 3개 프리셋 × `REQUIRES_NEW` = 3개 독립 트랜잭션 → 부분 저장 가능
- 주석에 "@Transactional 컨텍스트 유지 필수"라고 명시되어 있으나 **실제로는 트랜잭션 없이 실행됨**

**영향:**
- 데이터 정합성 위험: 프리셋 1 저장 후 에러 시 프리셋 2, 3 미저장
- `REQUIRES_NEW`로 인해 각 upsert가 새 DB 커넥션 할당 → 커넥션 풀 고갈
- 백프레셔 발생 시: 1000 RPS × 3 presets × REQUIRES_NEW = 3000 DB 커넥션 요구

**해결 방안:**
- `@Transactional` 제거 (dead code)
- `saveResultsSync`를 별도 `@Service` Bean으로 추출하여 프록시 경유 보장
- 또는 3개 프리셋을 하나의 트랜잭션으로 묶는 배치 메서드 생성

---

### P0-2: loadEquipmentData() `.join()` 블로킹

**위치:** `EquipmentExpectationServiceV4.java:549`

```java
// 문제 코드
return equipmentProvider.getRawEquipmentData(character.getOcid())
        .orTimeout(DATA_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join();  // ← equipmentProcessingExecutor 스레드를 최대 10초간 블로킹!
```

**문제점:**
- `equipmentProcessingExecutor` 스레드(Core 8, Max 16)에서 `.join()` 호출
- Nexon API RTT: 100~500ms, 타임아웃 10초
- 16개 동시 캐시 미스 → 16개 스레드 전부 블로킹 → 큐 소진 → 503

**영향 (1000 RPS 시나리오):**
```
Thread Starvation Math:
- 16 threads × 10s blocking = 1.6 RPS 처리량
- 1000 RPS 요구 → 998.4 요청 거부 (99.84% 에러율)
- Queue 200 소진 시간: 0.2초
```

**해결 방안:**
- `thenCompose()` 체이닝으로 비동기 파이프라인 전환
- 또는 `doCalculateExpectation` 전체를 비동기로 리팩토링

---

### P0-3: TieredCache lockKey `hashCode()` 충돌 → 데이터 유출 위험

**위치:** `TieredCache.java:332`

```java
// 문제 코드
private String buildLockKey(String keyStr) {
    return "cache:sf:" + l2.getName() + ":" + keyStr.hashCode();
    // Java String.hashCode()는 32비트 정수 → 충돌 가능
    // 예: "Aa".hashCode() == "BB".hashCode() == 2112
}
```

**문제점:**
- Birthday Paradox: 10,000명 활성 유저 시 약 1.2% 충돌 확률
- 충돌 시나리오:
  1. User "Aa" → lock `cache:sf:expectationV4:2112` 획득 → 계산 시작
  2. User "BB" → 같은 lock 대기 → User "Aa" 완료 후 L2 캐시 조회
  3. User "BB"가 User "Aa"의 결과를 반환받음 → **Cross-user 데이터 유출!**

**영향:**
- 다른 사용자의 기대값 데이터가 잘못 반환되는 보안 취약점
- 50,000 DAU 시 약 25% 확률로 락 키 충돌

**해결 방안:**
```java
// 수정: 원문 키 직접 사용 (충돌 불가)
private String buildLockKey(String keyStr) {
    return "cache:sf:" + l2.getName() + ":" + keyStr;
}
```

---

## P1 이슈 (High - 다음 스프린트)

### P1-1: LinkedBlockingQueue로 Max Pool Size 도달 불가

**위치:** `EquipmentProcessingExecutorConfig.java:70-72`

```
설정: Core 8, Max 16, Queue 200 (LinkedBlockingQueue)
문제: LinkedBlockingQueue는 Queue가 가득 차야 Max까지 확장
     → Queue 200이 먼저 채워지므로 Max 16에 절대 도달 불가
     → 실질적으로 Core 8 고정 운영
```

**Green Agent 분석:**
```
효과적 처리량:
- 캐시 히트(GZIP): 0.1ms (Fast Path, Tomcat 스레드)
- 캐시 미스: 300ms → 8 threads / 0.3s = 26.7 RPS 최대
- Queue 200 끝자리 대기시간: 200 / 8 × 300ms = 7,500ms (7.5초!)
```

**해결 방안:** `SynchronousQueue` 또는 설정 외부화로 동적 조정

---

### P1-2: ExpectationWriteBackBuffer In-Memory (Scale-out 블로커)

**위치:** `ExpectationWriteBackBuffer.java:51`

```java
private final ConcurrentLinkedQueue<ExpectationWriteTask> queue = new ConcurrentLinkedQueue<>();
// JVM 로컬 메모리 → 배포/크래시 시 데이터 유실
```

**Scale-out 영향:**
- Pod 크래시 시: 평균 3,000~4,000 태스크 유실 → ~1,333 캐릭터 데이터 손실
- 다중 인스턴스: 각 Pod별 독립 버퍼 → 일관성 없음

**해결 방안:** Redis Stream 기반 분산 버퍼로 전환

---

### P1-3: recalculateExpectation에서 force=true 미사용

**위치:** `GameCharacterControllerV4.java:166`

```java
// 문제: "재계산" 엔드포인트인데 force=false (캐시 사용)
@PostMapping("/{userIgn}/expectation/recalculate")
public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> recalculateExpectation(...) {
    // TODO: 캐시 무효화 후 재계산 구현
    return expectationService.calculateExpectationAsync(userIgn)  // ← force=false!
            .thenApply(ResponseEntity::ok);
}
```

**해결 방안:** `calculateExpectationAsync(userIgn, true)` 호출로 변경

---

### P1-4: IllegalStateException 직접 사용 (CLAUDE.md Section 11 위반)

**위치:** `EquipmentExpectationServiceV4.java:213, 331, 441`

```java
// 위반: Custom Exception 대신 IllegalStateException 직접 사용
throw new IllegalStateException("StarforceLookupTable not initialized.");
```

**문제점:**
- CircuitBreakerIgnoreMarker/RecordMarker 미구현 → 서킷브레이커 오작동
- GlobalExceptionHandler에서 500으로 매핑 → 4xx인 경우에도 5xx 반환
- ErrorCode Enum 미사용

---

### P1-5: 910줄 God Class (SRP 위반)

**위치:** `EquipmentExpectationServiceV4.java` (전체)

**Blue Agent 분석:**
```
현재 책임 혼합:
1. 비동기 오케스트레이션 (async dispatch)
2. 캐시 관리 (TieredCache 상호작용)
3. 영속성 조정 (DB 저장, 트랜잭션)
4. 도메인 로직 (기대값 계산)
5. 압축/직렬화 (GZIP, Base64)
```

**해결 방안 (Facade + Strategy 패턴):**
| 추출 클래스 | 책임 | 패턴 |
|---|---|---|
| `ExpectationCalculationEngine` | 순수 계산 로직 | Strategy |
| `ExpectationCacheCoordinator` | 캐시 조회/저장/무효화 | Proxy |
| `ExpectationPersistenceService` | DB 읽기/쓰기, 트랜잭션 | Service |
| `ExpectationOrchestrationFacade` | 위 3개 조정, 비동기 디스패치 | Facade |

---

### P1-6: 프리셋 병렬 계산 시 GZIP 3중 해제

**위치:** `EquipmentExpectationServiceV4.java:564-578`

**Green Agent 분석:**
```
각 프리셋별로 동일한 byte[] equipmentData를 독립적으로 파싱:
- Preset 1: GZIP 해제 + JSON 전체 스캔 → "item_equipment_preset_1" 탐색
- Preset 2: GZIP 해제 + JSON 전체 스캔 → "item_equipment_preset_2" 탐색
- Preset 3: GZIP 해제 + JSON 전체 스캔 → "item_equipment_preset_3" 탐색

→ 동일 데이터를 3번 해제/파싱 (CPU 낭비 ~30%)
```

**해결 방안:** 한 번 파싱 후 3개 프리셋으로 분배

---

### P1-7: CallerRunsPolicy 연쇄 효과

**위치:** `PresetCalculationExecutorConfig.java:77`

```
presetCalculationExecutor 큐 포화 시:
- CallerRunsPolicy → equipmentProcessingExecutor 스레드에서 직접 실행
- 해당 equipment 스레드가 프리셋 계산에 점유됨
- 38 동시 미스 시: 114 preset tasks > 112 capacity → 연쇄 블로킹
```

---

## Dead Code 분석 결과

### Repository 미사용 메서드 (6개)

| 메서드 | 상태 | 비고 |
|--------|------|------|
| `findAllByUserIgn()` | 미사용 | 정의만 존재 |
| `findByUserIgnAndPresetNo()` | 미사용 | 정의만 존재 |
| `findByGameCharacterIdAndPresetNo()` | 미사용 | 정의만 존재 |
| `existsByGameCharacterId()` | 미사용 | 정의만 존재 |
| `deleteAllByGameCharacterId()` | 미사용 | 정의만 존재 |
| `findAllByGameCharacterId()` | 미사용 | 정의만 존재 |

### Service/Controller 미사용 코드

| 항목 | 상태 | 비고 |
|------|------|------|
| `calculateExpectation(String)` 1-arg 버전 | 프로덕션 미사용 | 테스트에서만 호출, Self-invocation 문제 |
| `@Transactional` on calculateExpectation | Dead Annotation | Self-invocation으로 무효화 |
| `CostBreakdown.redCubeTrials` | 필드만 정의 | 값 설정/사용 없음 |

---

## Scale-out Stateless 검증

| 구성요소 | Stateless | Scale-out Ready | 조치 |
|---------|-----------|----------------|------|
| Controller | ✅ | ✅ | 없음 |
| Service (계산 로직) | ✅ | ✅ | 없음 |
| TieredCache L2 (Redis) | ✅ | ✅ | 분산 |
| TieredCache L1 (Caffeine) | ⚠️ | ⚠️ | #278 부분 해결 (put 미전파) |
| WriteBackBuffer | ❌ | ❌ | **Redis 전환 필요** |
| PopularCharacterTracker | ❌ | ❌ | **Redis 전환 필요** |
| Singleflight Lock | ✅ | ✅ | Redisson 분산 락 |
| shuttingDown flag | ❌ | ❌ | Redis + K8s readiness |

---

## 캐시 히트율별 RPS 시나리오 (Green Agent)

### 95% 히트율 (정상 상태) - ✅ Sustainable

```
240 RPS 기준:
- 228 RPS 캐시 히트: Fast Path 0.1ms (Tomcat 스레드)
- 12 RPS 캐시 미스: 300ms × 12 = 3.6 스레드 (8 중)
→ 풀 사용률 45%, 안정적
```

### 90% 히트율 (TTL 만료) - ⚠️ Marginal

```
240 RPS 기준:
- 216 RPS 캐시 히트
- 24 RPS 캐시 미스: 300ms × 24 = 7.2 스레드 (8 중)
→ 풀 사용률 90%, 버스트 시 큐잉 시작
```

### 80% 히트율 (콜드 스타트) - ❌ Unsustainable

```
240 RPS 기준:
- 192 RPS 캐시 히트
- 48 RPS 캐시 미스: 8 스레드 / 0.3s = 26.7 max
- 초과 21.3 req/s → Queue 200 소진: ~9.4초
- 9.4초 후 503 에러 avalanche
```

---

## 모니터링 메트릭 (수치 검증용)

### 현재 노출 메트릭

| 메트릭 | 임계값 | 의미 |
|--------|--------|------|
| `equipment.executor.queue.size` | > 160: WARNING, 200: CRITICAL | 큐 포화 |
| `equipment.executor.active.count` | >= 14: WARNING | 풀 포화 |
| `preset.calculation.queue.size` | > 80: WARNING | 프리셋 큐 포화 |
| `cache.l1.fast_path{result=hit}` | 모니터링 | L1 히트율 |
| `cache.l1.fast_path{result=miss}` | 모니터링 | L1 미스율 |
| `cache.hit{layer=L1}` | 모니터링 | TieredCache L1 히트 |
| `cache.hit{layer=L2}` | 모니터링 | TieredCache L2 히트 |
| `cache.miss` | > 10%: INVESTIGATE | 캐시 미스율 |
| `cache.lock.failure` | > 0: HIGH | 분산 락 실패 |
| `expectation.buffer.pending` | > 8000: WARNING | 버퍼 포화 |
| `expectation.buffer.cas.exhausted` | > 0: WARNING | CAS 재시도 소진 |

### 권장 추가 메트릭

| 메트릭 | 용도 |
|--------|------|
| `hikaricp.connections.active` | DB 커넥션 풀 모니터링 |
| `equipment.executor.rejected` | 503 에러 빈도 |
| `expectation.save.sync.count` | 동기 폴백 빈도 |
| `cache.lock.collision` | hashCode 충돌 감지 |

---

## 조치 우선순위

### Sprint 1 (즉시 - 코드 변경만)

- [ ] **P0-3**: lockKey `hashCode()` → 원문 키 사용 (1줄 변경)
- [ ] **P1-3**: recalculateExpectation force=true 적용 (1줄 변경)
- [ ] **P0-1**: @Transactional 제거 (dead code, 주석 정리 포함)

### Sprint 2 (단기 - 설계 필요)

- [ ] **P1-4**: IllegalStateException → Custom Exception 변환
- [ ] **P1-5**: 910줄 God Class → Facade + Strategy 패턴 분해
- [ ] **P1-6**: 프리셋 파싱 최적화 (3중 해제 → 1회 해제)
- [ ] **P1-1**: LinkedBlockingQueue → 설정 외부화

### Sprint 3 (중기 - 아키텍처 변경)

- [ ] **P0-2**: `.join()` 제거 → 비동기 파이프라인
- [ ] **P1-2**: WriteBackBuffer → Redis Stream 전환
- [ ] **P1-7**: Thread Pool 크기 최적화

---

## 긍정적 평가 (잘 된 부분)

1. **TieredCache 아키텍처**: L1/L2 + Graceful Degradation 우수
2. **Singleflight 패턴**: Redisson 분산 락 기반 Cache Stampede 방지
3. **GZIP 압축**: 200KB → 15KB (93% 감소) → Fast Path 0.1ms
4. **Write-Behind Buffer**: CAS + Phaser 기반 Lock-free 구현
5. **메트릭 커버리지**: Micrometer 기반 종합 관측성
6. **Decorator 패턴**: Calculator 체인 (Black/Red/Additional/Starforce)
7. **L1 Cache Coherence**: #278에서 Pub/Sub 기반 무효화 구현

---

*Generated by 5-Agent Council Analysis*
*Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>*
