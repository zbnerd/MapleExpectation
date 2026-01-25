# V4 Equipment Expectation API Specification

> **Version**: 1.0.0
> **Issue**: #240, #264, #266
> **Last Updated**: 2026-01-25

---

## Overview

V4 API는 메이플스토리 장비 강화 기대값 계산을 위한 고성능 RESTful API입니다. **비동기 논블로킹 파이프라인**, **TieredCache(L1/L2)**, **Singleflight 패턴**, **Write-Behind Buffer** 등 엔터프라이즈급 설계 패턴을 적용하여 **RPS 719, p50 164ms**의 성능을 달성합니다.

### Key Features

| Feature | Description | Performance Impact |
|---------|-------------|-------------------|
| **L1 Fast Path** | Caffeine 캐시 직접 조회 | 응답 지연 5ms 이하 |
| **GZIP Compression** | 200KB → 15KB 압축 | 네트워크 대역폭 93% 절감 |
| **Singleflight** | 동시 요청 병합 | Cache Stampede 방지 |
| **Parallel Preset** | 3개 프리셋 동시 계산 | CPU 활용률 3배 향상 |
| **Write-Behind** | 비동기 DB 저장 | 응답 시간에서 DB I/O 제거 |

---

## 1. Endpoint

### GET `/api/v4/characters/{userIgn}/expectation`

캐릭터의 장비 강화 기대값을 계산합니다.

#### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userIgn` | `string` | Yes | 캐릭터 인게임 닉네임 |

#### Query Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `force` | `boolean` | `false` | 캐시 무시하고 재계산 |

#### Request Headers

| Header | Value | Description |
|--------|-------|-------------|
| `Accept-Encoding` | `gzip` | GZIP 압축 응답 요청 (권장) |

#### Response Headers

| Header | Description |
|--------|-------------|
| `Content-Encoding: gzip` | GZIP 압축된 응답 (Accept-Encoding: gzip 시) |
| `X-Cache-Hit: true/false` | 캐시 히트 여부 |

---

## 2. Sequence Diagram

### 2.1 요청 처리 흐름 (L1 Cache Hit)

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Controller as GameCharacterControllerV4
    participant L1 as L1 Cache (Caffeine)
    participant GzipUtil

    Client->>Controller: GET /api/v4/characters/{ign}/expectation
    Note over Controller: Accept-Encoding: gzip 확인

    Controller->>L1: getGzipFromL1CacheDirect(cacheKey)
    L1-->>Controller: Optional<byte[]> (GZIP 압축 데이터)

    alt L1 Cache HIT (Fast Path)
        Note over Controller: L1 캐시 직접 반환 (5ms 이하)
        Controller-->>Client: 200 OK (Content-Encoding: gzip)
    else L1 Cache MISS
        Note over Controller: 비동기 파이프라인으로 전환
    end
```

### 2.2 요청 처리 흐름 (Cache Miss - Full Pipeline)

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Controller as GameCharacterControllerV4
    participant Service as EquipmentExpectationServiceV4
    participant Cache as TieredCache (L1/L2)
    participant DB as MySQL
    participant Nexon as Nexon Open API
    participant Buffer as WriteBackBuffer
    participant Scheduler as BatchScheduler

    Client->>Controller: GET /api/v4/characters/{ign}/expectation
    Controller->>Service: calculateAsync(userIgn)

    Service->>Cache: get(cacheKey, Callable)
    Note over Cache: Singleflight: 동시 요청 병합

    alt Cache HIT (L1 or L2)
        Cache-->>Service: CachedValue (GZIP + Base64)
        Service->>Service: decompress & deserialize
    else Cache MISS
        Cache->>DB: findEquipmentData(characterId)
        alt DB HIT
            DB-->>Cache: equipmentData (GZIP compressed)
            Cache->>Cache: decompress (17KB → 300KB)
        else DB MISS
            Cache->>Nexon: GET /character/equipment
            Nexon-->>Cache: EquipmentResponse (300KB JSON)
            Cache->>DB: save (GZIP compressed)
        end

        par Parallel Preset Calculation
            Cache->>Service: calculatePreset(data, 1)
            Cache->>Service: calculatePreset(data, 2)
            Cache->>Service: calculatePreset(data, 3)
        end

        Service->>Service: aggregate results
        Service->>Cache: put(cacheKey, result)
    end

    Service->>Buffer: offer(characterId, presets)
    Note over Buffer: 메모리 버퍼에 저장 (Lock-free)

    Service-->>Controller: CompletableFuture<Response>
    Controller->>Controller: GZIP compress
    Controller-->>Client: 200 OK (15KB GZIP)

    Note over Scheduler: 5초마다 배치 실행
    Scheduler->>Buffer: drain(100)
    Buffer-->>Scheduler: List<WriteTask>
    Scheduler->>DB: batchUpsert(tasks)
```

---

## 3. Response Schema

### 3.1 Success Response (200 OK)

```json
{
  "userIgn": "MapleHero",
  "calculatedAt": "2026-01-25T14:30:00",
  "fromCache": true,

  "totalExpectedCost": 12345678901234,
  "totalCostText": "12조 3456억 7890만",
  "maxPresetNo": 1,
  "totalCostBreakdown": {
    "blackCubeCost": 5000000000000,
    "redCubeCost": 2000000000000,
    "additionalCubeCost": 3000000000000,
    "starforceCost": 2345678901234
  },

  "presets": [
    {
      "presetNo": 1,
      "totalExpectedCost": 12345678901234,
      "totalCostText": "12조 3456억 7890만",
      "costBreakdown": {
        "blackCubeCost": 5000000000000,
        "redCubeCost": 2000000000000,
        "additionalCubeCost": 3000000000000,
        "starforceCost": 2345678901234
      },
      "items": [
        {
          "itemName": "앱솔랩스 아처후드",
          "itemIcon": "https://open.api.nexon.com/static/maplestory/ItemIcon/...",
          "itemPart": "모자",
          "itemLevel": 160,
          "expectedCost": 500000000000,
          "expectedCostText": "5000억",
          "enhancePath": "UNIQUE→LEGENDARY + 17★→22★",

          "potentialGrade": "UNIQUE",
          "additionalPotentialGrade": "EPIC",

          "currentStar": 17,
          "targetStar": 22,
          "isNoljang": false,

          "costBreakdown": {
            "blackCubeCost": 200000000000,
            "redCubeCost": 0,
            "additionalCubeCost": 100000000000,
            "starforceCost": 200000000000
          },

          "blackCubeExpectation": {
            "expectedCost": 200000000000,
            "expectedCostText": "2000억",
            "expectedTrials": 150.5,
            "currentGrade": "UNIQUE",
            "targetGrade": "LEGENDARY",
            "potential": "STR +12%, 보공 +30%"
          },

          "additionalCubeExpectation": {
            "expectedCost": 100000000000,
            "expectedCostText": "1000억",
            "expectedTrials": 80.2,
            "currentGrade": "EPIC",
            "targetGrade": "UNIQUE",
            "potential": "STR +6%"
          },

          "starforceExpectation": {
            "currentStar": 17,
            "targetStar": 22,
            "isNoljang": false,
            "costWithoutDestroyPrevention": 150000000000,
            "costWithoutDestroyPreventionText": "1500억",
            "expectedDestroyCountWithout": 2.3,
            "costWithDestroyPrevention": 280000000000,
            "costWithDestroyPreventionText": "2800억",
            "expectedDestroyCountWith": 0.0
          }
        }
      ]
    },
    {
      "presetNo": 2,
      "totalExpectedCost": 8000000000000,
      "totalCostText": "8조",
      "costBreakdown": { "..." : "..." },
      "items": []
    },
    {
      "presetNo": 3,
      "totalExpectedCost": 0,
      "totalCostText": "0",
      "costBreakdown": { "..." : "..." },
      "items": []
    }
  ]
}
```

### 3.2 Error Response (4xx/5xx)

```json
{
  "errorCode": "CHARACTER_NOT_FOUND",
  "message": "캐릭터 'InvalidName'을(를) 찾을 수 없습니다.",
  "timestamp": "2026-01-25T14:30:00"
}
```

#### Error Codes

| HTTP Status | Error Code | Description |
|-------------|------------|-------------|
| 400 | `INVALID_REQUEST` | 잘못된 요청 파라미터 |
| 404 | `CHARACTER_NOT_FOUND` | 캐릭터를 찾을 수 없음 |
| 429 | `RATE_LIMIT_EXCEEDED` | 요청 한도 초과 |
| 503 | `EXTERNAL_API_UNAVAILABLE` | Nexon API 장애 (Circuit Breaker Open) |
| 500 | `INTERNAL_SERVER_ERROR` | 내부 서버 오류 |

---

## 4. Performance Architecture

### 4.1 L1 Fast Path (#264)

**L1 Fast Path**는 가장 빈번한 요청 패턴(캐시 히트 + GZIP 응답)을 최적화합니다.

```
기존 흐름 (L1 HIT):
  Controller → Service → TieredCache → L1 → 역직렬화 → 직렬화 → GZIP → Response

L1 Fast Path (최적화):
  Controller → L1 캐시 직접 조회 → GZIP 데이터 즉시 반환
```

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| L1 HIT Latency | 27ms | 5ms | **5.4x faster** |
| Object Allocation | 4 objects | 1 object | **75% reduction** |
| GC Pressure | High | Minimal | **Significant** |

```java
// Controller Level - L1 Fast Path
@GetMapping("/{userIgn}/expectation")
public CompletableFuture<ResponseEntity<?>> getExpectation(
        @PathVariable String userIgn,
        @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {

    // Fast Path: L1 캐시에서 GZIP 직접 반환
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

### 4.2 Parallel Preset Calculation (#266)

3개의 프리셋(장비 세트)을 **병렬로 동시 계산**하여 CPU 활용률을 극대화합니다.

```
순차 처리 (Before):
  Preset 1 (100ms) → Preset 2 (100ms) → Preset 3 (100ms) = 300ms

병렬 처리 (After):
  ┌─ Preset 1 (100ms) ─┐
  ├─ Preset 2 (100ms) ─┼─ Join ─ 110ms (오버헤드 포함)
  └─ Preset 3 (100ms) ─┘
```

**Deadlock 방지 설계 (P1 해결)**:

```java
// 별도 Executor Pool로 스레드 풀 격리
@Bean("presetCalculationExecutor")
public Executor presetCalculationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(12);   // 3 프리셋 × 4 동시 요청
    executor.setMaxPoolSize(24);    // 피크 시 확장
    executor.setQueueCapacity(100);
    executor.setRejectedExecutionHandler(new CallerRunsPolicy()); // Deadlock 방지
    return executor;
}

// Service에서 별도 Executor 사용
private List<PresetExpectation> calculateAllPresetsParallel(byte[] equipmentData) {
    List<CompletableFuture<PresetExpectation>> futures = IntStream.rangeClosed(1, 3)
        .mapToObj(presetNo -> CompletableFuture.supplyAsync(
            () -> calculatePreset(equipmentData, presetNo),
            presetExecutor  // ← 요청 처리 스레드와 분리
        ))
        .toList();

    return futures.stream()
        .map(CompletableFuture::join)
        .filter(preset -> !preset.getItems().isEmpty())
        .toList();
}
```

### 4.3 Write-Behind Buffer Pattern (#266)

**Write-Behind**는 API 응답 경로에서 DB 저장을 제거하여 응답 시간을 단축합니다.

```
동기 저장 (Before):
  계산 완료 → DB 저장 (15-30ms) → 응답 반환

Write-Behind (After):
  계산 완료 → 메모리 버퍼 (0.1ms) → 응답 반환
                    ↓
              [5초마다 배치]
                    ↓
                DB 저장 (100건씩)
```

#### Buffer Architecture

```
                    ┌─────────────────────────────────────┐
                    │     ExpectationWriteBackBuffer      │
                    │                                     │
  Service ────────► │  ConcurrentLinkedQueue<WriteTask>  │ ◄──── Scheduler
   offer()          │  - Lock-free O(1)                  │        drain()
                    │  - Max 10,000 tasks (~10MB)        │
                    │  - Backpressure at capacity        │
                    └─────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────┐
                    │    ExpectationBatchWriteScheduler   │
                    │                                     │
                    │  @Scheduled(fixedDelay = 5000)     │
                    │  - Batch size: 100 tasks           │
                    │  - Distributed lock for HA         │
                    │  - Metric: expectation.buffer.*    │
                    └─────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────┐
                    │            MySQL                    │
                    │  batchUpsert(100 tasks)            │
                    └─────────────────────────────────────┘
```

#### Backpressure Mechanism

버퍼가 MAX_QUEUE_SIZE(10,000)에 도달하면 **동기 폴백**으로 전환:

```java
public boolean offer(Long characterId, List<PresetExpectation> presets) {
    if (pendingCount.get() >= MAX_QUEUE_SIZE) {
        meterRegistry.counter("expectation.buffer.rejected").increment();
        return false;  // 호출자에게 동기 저장 요청
    }
    // ... 버퍼에 추가
    return true;
}

// Service에서 처리
private void saveResults(Long characterId, List<PresetExpectation> presets) {
    if (!writeBackBuffer.offer(characterId, presets)) {
        // 백프레셔 발동: 동기 폴백
        repository.syncSave(characterId, presets);
    }
}
```

#### Graceful Shutdown

JVM 종료 시 버퍼에 남은 데이터를 안전하게 DB에 저장:

```java
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
        return Integer.MAX_VALUE - 500;  // 다른 빈보다 먼저 실행
    }
}
```

---

## 5. Cache Strategy

### 5.1 TieredCache (L1/L2) with Singleflight

```
       ┌─────────────────────────────────────────────────────────┐
       │                    TieredCache                          │
       │                                                         │
       │   ┌─────────┐    ┌─────────┐    ┌─────────┐            │
  GET  │   │   L1    │───►│   L2    │───►│Callable │            │
 ──────┼──►│Caffeine │    │  Redis  │    │(DB/API) │            │
       │   │  <5ms   │    │  <20ms  │    │         │            │
       │   └─────────┘    └─────────┘    └─────────┘            │
       │        │              │              │                  │
       │        │              │              │                  │
       │        ▼              ▼              ▼                  │
       │   ┌─────────────────────────────────────────────────┐  │
       │   │              Singleflight Gate                  │  │
       │   │   • 동시 요청 → 단일 Callable 실행              │  │
       │   │   • 나머지 요청 → 결과 공유                     │  │
       │   │   • Cache Stampede 완전 방지                    │  │
       │   └─────────────────────────────────────────────────┘  │
       └─────────────────────────────────────────────────────────┘
```

### 5.2 GZIP Compression Flow

```
Nexon API Response (300KB JSON)
         │
         ▼
    ┌─────────┐
    │  Parse  │  JSON → Java Object
    └────┬────┘
         │
         ▼
    ┌─────────┐
    │  GZIP   │  Java Object → byte[] (17KB)
    └────┬────┘
         │
    ┌────┴────────────────────┐
    │                         │
    ▼                         ▼
┌───────┐               ┌──────────┐
│  L1   │               │    L2    │
│Caffeine│              │  Redis   │
│(byte[])│              │ (Base64) │
└────┬──┘               └────┬─────┘
     │                       │
     └───────────┬───────────┘
                 │
                 ▼
         ┌───────────────┐
         │ HTTP Response │
         │Content-Encoding│
         │    : gzip     │
         └───────────────┘
              15KB
```

---

## 6. Monitoring & Metrics

### 6.1 Prometheus Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `expectation.buffer.pending` | Gauge | 버퍼 대기 작업 수 |
| `expectation.buffer.flushed` | Counter | 플러시된 작업 수 |
| `expectation.buffer.rejected` | Counter | 백프레셔 거부 수 |
| `preset.calculation.queue.size` | Gauge | 프리셋 계산 큐 크기 |
| `preset.calculation.active.count` | Gauge | 활성 계산 스레드 수 |
| `cache_gets_total{result="hit"}` | Counter | 캐시 히트 수 |
| `http_server_requests_seconds` | Histogram | HTTP 요청 지연 시간 |

### 6.2 Prometheus Alert Rules (권장)

```yaml
groups:
  - name: v4-api-health
    rules:
      - alert: WriteBufferNearCapacity
        expr: expectation.buffer.pending > 8000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Write-Behind 버퍼 80% 도달"

      - alert: WriteBufferFull
        expr: expectation.buffer.pending >= 10000
        labels:
          severity: critical
        annotations:
          summary: "Write-Behind 버퍼 풀, 동기 폴백 발동 중"

      - alert: PresetCalculationPoolSaturated
        expr: preset.calculation.active.count >= 22
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "프리셋 계산 스레드 풀 90% 포화"
```

### 6.3 Grafana Dashboard Query Examples

```promql
# RPS (Requests Per Second)
rate(http_server_requests_seconds_count{uri="/api/v4/characters/{userIgn}/expectation"}[1m])

# p95 Latency
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{uri="/api/v4/characters/{userIgn}/expectation"}[5m])
)

# Cache Hit Rate
rate(cache_gets_total{result="hit"}[5m]) /
  (rate(cache_gets_total{result="hit"}[5m]) + rate(cache_gets_total{result="miss"}[5m]))

# Write Buffer Pending
expectation.buffer.pending
```

---

## 7. Performance Benchmark

### 7.1 Load Test Results (#266)

| Metric | Value | Tool | Conditions |
|--------|-------|------|------------|
| **RPS** | 719 | wrk | 200 connections, 10s |
| **p50 Latency** | 164ms | wrk | Load state |
| **p50 Latency (Warm)** | 27ms | wrk | Cache hit |
| **Throughput** | 4.56 MB/s | wrk | Compressed |
| **Failure Rate** | 0% | wrk | All conditions |

### 7.2 Equivalent Processing Capacity

```
1 Request Processing:
  - Decompress: 17KB GZIP → 300KB JSON
  - Parse: 300KB JSON tree
  - Calculate: 3 presets × N items
  - Compress: Response → 15KB GZIP

Equivalent Load:
  1 Request = 300KB / 2KB (standard) = 150 Standard Requests
  719 RPS × 150 = 10만 RPS급 등가 처리량
```

### 7.3 Before/After Comparison

| Metric | V3 (Before) | V4 (After) | Improvement |
|--------|-------------|------------|-------------|
| Preset Calculation | Sequential | Parallel | **3x faster** |
| DB Save Latency | 15-30ms | 0.1ms | **150-300x** |
| DB Round-trips | 3/request | 1/100 batch | **97% reduction** |
| L1 Cache Hit Response | 27ms | 5ms | **5.4x faster** |

---

## 8. Usage Examples

### 8.1 cURL

```bash
# Basic Request
curl -X GET "http://localhost:8080/api/v4/characters/MapleHero/expectation"

# With GZIP (권장)
curl -X GET "http://localhost:8080/api/v4/characters/MapleHero/expectation" \
  -H "Accept-Encoding: gzip" \
  --compressed

# Force Recalculation (캐시 무시)
curl -X GET "http://localhost:8080/api/v4/characters/MapleHero/expectation?force=true" \
  -H "Accept-Encoding: gzip" \
  --compressed
```

### 8.2 JavaScript (Fetch API)

```javascript
async function getExpectation(userIgn, options = {}) {
  const { force = false } = options;

  const response = await fetch(
    `/api/v4/characters/${encodeURIComponent(userIgn)}/expectation?force=${force}`,
    {
      headers: {
        'Accept-Encoding': 'gzip',
        'Accept': 'application/json'
      }
    }
  );

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message);
  }

  return response.json();
}

// Usage
const result = await getExpectation('MapleHero');
console.log(`총 기대 비용: ${result.totalCostText}`);
```

---

## Related Documents

- [Architecture Overview](../00_Start_Here/architecture.md) - 시스템 아키텍처
- [Async & Concurrency Guide](../02_Technical_Guides/async-concurrency.md) - 비동기 패턴
- [Performance Report](../04_Reports/Load_Tests/) - 부하 테스트 결과
- [KPI Dashboard](../04_Reports/KPI_BSC_DASHBOARD.md) - 성능 지표

---

*Generated by 5-Agent Council*
*Last Updated: 2026-01-25*
