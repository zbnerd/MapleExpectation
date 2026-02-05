# ADR-011: Controller V4 성능 최적화 설계

## 상태
Accepted

## 문서 무결성 체크리스트
✅ All 30 items verified (Date: 2026-01-05, Issue: #266)

---

## Fail If Wrong
1. **[F1]** L1 Fast Path에서 역직렬화 발생하여 5ms 초과
2. **[F2]** 프리셋 병렬 계산 시 Deadlock 발생
3. **[F3]** Write-Behind 버퍼로 데이터 유실 > 0건
4. **[F4]** GZIP 압축률 < 90%

---

## Terminology
| 용어 | 정의 |
|------|------|
| **L1 Fast Path** | 캐시에서 GZIP 데이터 직접 반환하여 역직렬화 스킵 |
| **Parallel Preset** | 3개 프리셋을 병렬로 계산하여 지연 시간 단축 |
| **Write-Behind Buffer** | 메모리 버퍼에 쌓았다가 배치로 DB 저장 |
| **Backpressure** | 버퍼 가득 찼을 때 호출자에게 동기 저장 요청 |

---

## 맥락 (Context)
### 문제 정의
V3 API에서 다음 성능 병목 발생:
- 캐시 히트에도 역직렬화/직렬화 오버헤드 (27ms) [E1]
- 프리셋 3개 순차 계산 (300ms) [E2]
- 응답 경로에서 동기 DB 저장 (15-30ms) [E3]
- 300KB JSON 응답으로 네트워크 대역폭 과다 사용 [E4]

**부하테스트 결과 (#266):**
- RPS 719 (200 connections), 0% Error Rate [P1]
- 프리셋 계산: 순차 300ms → 병렬 110ms (3x 개선) [P2]
- DB 저장: 동기 15-30ms → 버퍼 0.1ms (150-300x 개선) [P3]

---

## 대안 분석
### 옵션 A: 기존 동기 처리 유지
- **장점:** 구현 단순
- **단점:** RPS 200 이하, 프리셋 계산 300ms
- **거절:** [R1] 트래픽 500 RPS에서 응답 지연 2초+ (테스트: 2025-12-20)
- **결론:** 확장성 부족 (기각)

### 옵션 B: 전체 비동기화 (모든 작업)
- **장점:** 최대 처리량
- **단점:** 복잡도 급증, 디버깅 어려움
- **거절:** [R2] CompletableFuture 체인으로 가독성 저하 (POC: 2025-12-22)
- **결론:** 과도한 복잡도 (기각)

### 옵션 C: 선별적 최적화
- **장점:** 핵심 병목만 해결, 점진적 적용
- **단점:** 구현 복잡도 증가
- **채택:** [C1] RPS 719, 0% Error 달성
- **결론:** 채택

---

## 결정 (Decision)
**L1 Fast Path + Parallel Preset Calculation + Write-Behind Buffer + GZIP 압축을 적용합니다.**

### Code Evidence

**[C1] L1 Fast Path**
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

**[C2] Parallel Preset Calculation**
```java
// src/main/java/maple/expectation/service/v4/EquipmentExpectationServiceV4.java
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

**[C3] Write-Behind Buffer**
```java
// src/main/java/maple/expectation/service/v4/ExpectationWriteBackBuffer.java
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
}
```

---

## 결과
| 지표 | Before (V3) | After (V4) | 개선 | Evidence ID |
|------|-------------|------------|------|-------------|
| RPS | ~200 | **719** | 3.6x | [E1] |
| p50 Latency (L1 HIT) | 27ms | **5ms** | 5.4x | [E2] |
| 프리셋 계산 | 300ms | **110ms** | 3x | [E3] |
| DB 저장 지연 | 15-30ms | **0.1ms** | 150-300x | [E4] |
| 응답 크기 | 200KB | **15KB** | 93% 절감 | [E5] |
| DB Round-trips | 3/request | **1/100 batch** | 97% 감소 | [E6] |

**Evidence IDs:**
- [E1] 부하테스트: #266에서 719 RPS 달성
- [E2] L1 Fast Path: 5ms 측정
- [E3] 병렬 계산: 110ms 측정
- [E4] Write-Behind: 0.1ms 측정
- [E5] GZIP: 200KB → 15KB
- [E6] 배치: 100건씩 배치 업서트

---

## 관련 문서
- **코드:** `src/main/java/maple/expectation/controller/GameCharacterControllerV4.java`
- **리포트:** `docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md`

---

# ADR-012: Stateless 아키텍처 전환 로드맵 및 트레이드오프 분석

## 상태
✅ **Accepted & Implemented** (2026-01-27)

> **구현 완료:** Issue #271에서 V5 Stateless Architecture 전환이 완료되었습니다.

## 문서 무결성 체크리스트
✅ All 30 items verified (Date: 2026-01-27, Issue: #271, #283)

---

## Fail If Wrong
