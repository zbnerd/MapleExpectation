# ADR-011: Controller V4 성능 최적화 설계

## 상태
Accepted

## 맥락 (Context)

V3 API에서 다음 성능 병목이 관찰되었습니다:

**관찰된 문제:**
- 캐시 히트에도 역직렬화/직렬화 오버헤드 (27ms)
- 프리셋 3개 순차 계산 (300ms)
- 응답 경로에서 동기 DB 저장 (15-30ms)
- 300KB JSON 응답으로 네트워크 대역폭 과다 사용

**부하테스트 결과 (#266):**
- RPS 719 (200 connections), 0% Error Rate
- 프리셋 계산: 순차 300ms → 병렬 110ms (3x 개선)
- DB 저장: 동기 15-30ms → 버퍼 0.1ms (150-300x 개선)
- L1 HIT 응답: 27ms → 5ms (5.4x 개선)
- 10만 RPS급 등가 처리량 달성

**README 문제 정의:**
> 300KB JSON 파싱 시 OOM 위험 → Streaming Parser + GZIP 압축

## 검토한 대안 (Options Considered)

### 옵션 A: 기존 동기 처리 유지
- 장점: 구현 단순
- 단점: RPS 200 이하, 프리셋 계산 300ms
- **결론: 확장성 부족**

### 옵션 B: 전체 비동기화 (모든 작업)
- 장점: 최대 처리량
- 단점: 복잡도 급증, 디버깅 어려움
- **결론: 과도한 복잡도**

### 옵션 C: 선별적 최적화 (L1 Fast Path + Parallel Preset + Write-Behind)
- 장점: 핵심 병목만 해결, 점진적 적용
- 단점: 구현 복잡도 증가
- **결론: 채택**

## 결정 (Decision)

**L1 Fast Path + Parallel Preset Calculation + Write-Behind Buffer + GZIP 압축을 적용합니다.**

### 1. L1 Fast Path (#264)
```java
// Controller Level - GZIP 데이터 직접 반환
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<?>> getExpectation(
        @PathVariable String userIgn,
        @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {

    // Fast Path: L1 캐시에서 GZIP 직접 반환 (역직렬화 스킵)
    if (isGzipAccepted(acceptEncoding)) {
        Optional<byte[]> gzipData = service.getGzipFromL1CacheDirect(cacheKey);
        if (gzipData.isPresent()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .body(gzipData.get())  // 역직렬화 없이 즉시 반환
            );
        }
    }

    // Full Path: 비동기 파이프라인
    return service.calculateAsync(userIgn).thenApply(this::toResponse);
}
```

**기존 흐름 vs Fast Path:**
```
기존 (27ms):
  Controller → Service → TieredCache → L1 → 역직렬화 → 직렬화 → GZIP → Response

Fast Path (5ms):
  Controller → L1 캐시 직접 조회 → GZIP 데이터 즉시 반환
```

### 2. Parallel Preset Calculation (#266)
```java
// maple.expectation.service.v4.EquipmentExpectationServiceV4
private List<PresetExpectation> calculateAllPresetsParallel(byte[] equipmentData) {
    List<CompletableFuture<PresetExpectation>> futures = IntStream.rangeClosed(1, 3)
        .mapToObj(presetNo -> CompletableFuture.supplyAsync(
            () -> calculatePreset(equipmentData, presetNo),
            presetExecutor  // 별도 Executor로 Deadlock 방지
        ))
        .toList();

    return futures.stream()
        .map(CompletableFuture::join)
        .filter(preset -> !preset.getItems().isEmpty())
        .toList();
}
```

**Deadlock 방지 설계 (스레드 풀 격리):**
```java
// maple.expectation.config.ExecutorConfig
@Bean("presetCalculationExecutor")
public Executor presetCalculationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(12);   // 3 프리셋 × 4 동시 요청
    executor.setMaxPoolSize(24);    // 피크 시 확장
    executor.setQueueCapacity(100);
    executor.setRejectedExecutionHandler(new CallerRunsPolicy()); // Deadlock 방지
    return executor;
}
```

**성능 개선:**
```
순차 처리:
  Preset 1 (100ms) → Preset 2 (100ms) → Preset 3 (100ms) = 300ms

병렬 처리:
  ┌─ Preset 1 (100ms) ─┐
  ├─ Preset 2 (100ms) ─┼─ Join ─ 110ms (오버헤드 포함)
  └─ Preset 3 (100ms) ─┘
```

### 3. Write-Behind Buffer Pattern (#266)
```java
// maple.expectation.service.v4.ExpectationWriteBackBuffer
@Component
public class ExpectationWriteBackBuffer {

    private static final int MAX_QUEUE_SIZE = 10_000;
    private final ConcurrentLinkedQueue<ExpectationWriteTask> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger(0);

    /**
     * Lock-free O(1) 삽입
     * Backpressure: 10,000건 초과 시 동기 폴백 트리거
     */
    public boolean offer(Long characterId, List<PresetExpectation> presets) {
        if (pendingCount.get() >= MAX_QUEUE_SIZE) {
            meterRegistry.counter("expectation.buffer.rejected").increment();
            return false;  // 호출자에게 동기 저장 요청
        }

        buffer.offer(new ExpectationWriteTask(characterId, presets, LocalDateTime.now()));
        pendingCount.incrementAndGet();
        return true;
    }

    public List<ExpectationWriteTask> drain(int maxSize) {
        List<ExpectationWriteTask> batch = new ArrayList<>(maxSize);
        ExpectationWriteTask task;
        while (batch.size() < maxSize && (task = buffer.poll()) != null) {
            batch.add(task);
            pendingCount.decrementAndGet();
        }
        return batch;
    }
}
```

**Write-Behind Scheduler:**
```java
// maple.expectation.service.v4.ExpectationBatchWriteScheduler
@Scheduled(fixedDelay = 5000)  // 5초마다
public void flush() {
    List<ExpectationWriteTask> batch = buffer.drain(100);
    if (!batch.isEmpty()) {
        repository.batchUpsert(batch);  // 100건 배치 저장
        meterRegistry.counter("expectation.buffer.flushed").increment(batch.size());
    }
}
```

**성능 개선:**
```
동기 저장:
  계산 완료 → DB 저장 (15-30ms) → 응답 반환

Write-Behind:
  계산 완료 → 메모리 버퍼 (0.1ms) → 응답 반환
                    ↓
              [5초마다 배치]
                    ↓
              DB 저장 (100건씩)
```

### 4. GZIP 압축 전략
```java
// maple.expectation.service.v4.EquipmentExpectationServiceV4
private void saveToGzipCache(String userIgn, EquipmentExpectationResponseV4 response) {
    String json = objectMapper.writeValueAsString(response);
    byte[] compressed = GzipUtils.compress(json);

    expectationCache.put(userIgn, compressed);  // L1/L2에 압축 저장

    log.debug("[V4] GZIP 캐시 저장: {} (원본: {}KB → 압축: {}KB)",
            userIgn, json.length() / 1024, compressed.length / 1024);
}

// 압축률: 200KB JSON → 15KB GZIP (93% 절감)
```

### 5. 비동기 타임아웃 보호 (SRE)
```java
// maple.expectation.service.v4.EquipmentExpectationServiceV4
@TraceLog
public CompletableFuture<EquipmentExpectationResponseV4> calculateExpectationAsync(String userIgn) {
    return CompletableFuture.supplyAsync(
            () -> calculateExpectation(userIgn, false),
            equipmentExecutor
    )
    .orTimeout(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);  // 30초 타임아웃
}

// 장비 데이터 로드 타임아웃
private byte[] loadEquipmentData(GameCharacter character) {
    return equipmentProvider.getRawEquipmentData(character.getOcid())
            .orTimeout(DATA_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)  // 10초 타임아웃
            .join();
}
```

### 6. Graceful Shutdown (Write-Behind 버퍼 드레인)
```java
// maple.expectation.service.v4.ExpectationBatchShutdownHandler
@Component
public class ExpectationBatchShutdownHandler implements SmartLifecycle {

    @Override
    public void stop() {
        // JVM 종료 전 버퍼 완전 드레인
        while (!buffer.isEmpty()) {
            List<ExpectationWriteTask> batch = buffer.drain(100);
            repository.batchUpsert(batch);
        }
        log.info("Buffer drained completely before shutdown");
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 500;  // GracefulShutdownCoordinator보다 먼저 실행
    }
}
```

## 결과 (Consequences)

| 지표 | Before (V3) | After (V4) | 개선 |
|------|-------------|------------|------|
| RPS | ~200 | **719** | 3.6x |
| p50 Latency (L1 HIT) | 27ms | **5ms** | 5.4x |
| 프리셋 계산 | 300ms | **110ms** | 3x |
| DB 저장 지연 | 15-30ms | **0.1ms** | 150-300x |
| 응답 크기 | 200KB | **15KB** | 93% 절감 |
| DB Round-trips | 3/request | **1/100 batch** | 97% 감소 |

**부하테스트 결과 (#266):**
- 10만 RPS급 등가 처리량 (300KB 응답 × 719 RPS = 150 표준 요청 × 719)
- 모든 Circuit Breaker CLOSED 유지
- 0% Error Rate

## 참고 자료
- `maple.expectation.controller.GameCharacterControllerV4`
- `maple.expectation.service.v4.EquipmentExpectationServiceV4`
- `maple.expectation.service.v4.ExpectationWriteBackBuffer`
- `maple.expectation.service.v4.ExpectationBatchWriteScheduler`
- `maple.expectation.service.v4.ExpectationBatchShutdownHandler`
- `maple.expectation.config.ExecutorConfig`
- `docs/api/v4_specification.md`
- `docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md`
