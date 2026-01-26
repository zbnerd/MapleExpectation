# ADR-012: Stateless 아키텍처 전환 로드맵 및 트레이드오프 분석

## 상태
Proposed (V5 로드맵)

## 맥락 (Context)

### 현재 아키텍처 (V4)의 성과와 한계

**성과 - 단일 노드 극한 최적화:**
- 965 RPS (AWS t3.small, 2 vCPU, 2GB RAM)
- In-Memory Write-Behind Buffer로 DB 저장 지연 0.1ms 달성
- L1 Fast Path로 캐시 HIT 시 5ms 응답

**한계 - Stateful 구조의 고유 문제:**
```
┌─────────────────────────────────────────────────────────┐
│                    현재 아키텍처 (V4)                     │
├─────────────────────────────────────────────────────────┤
│  [Server A - Stateful]                                  │
│  ┌─────────────────────┐                               │
│  │  In-Memory Buffer   │  ← 이 데이터가 서버에 종속      │
│  │  (ConcurrentQueue)  │                               │
│  └─────────────────────┘                               │
│           │                                             │
│           ▼                                             │
│  ┌─────────────────────┐                               │
│  │  5초마다 Flush      │                               │
│  │  (Scheduler)        │                               │
│  └─────────────────────┘                               │
└─────────────────────────────────────────────────────────┘

문제점:
1. Scale-out 불가: 각 서버가 독립 버퍼 → 데이터 파편화
2. 배포 위험: Rolling Update 시 버퍼 미플러시 데이터 유실
3. 장애 전파: 서버 크래시 시 In-Memory 데이터 소멸
4. 오토스케일링 제약: 트래픽 감소 시 Scale-in 불가
```

### 비즈니스 성장 시나리오

| 트래픽 | 현재 (V4) | 목표 (V5) |
|--------|-----------|-----------|
| 일반 | 100 RPS | 100 RPS (서버 1대) |
| 피크 | 500 RPS | 500 RPS (서버 1대) |
| 이벤트 | 1,000+ RPS | **서버 2-3대 Scale-out** |
| 대형 이벤트 | **처리 불가** | **서버 N대 무한 확장** |

## 검토한 대안 (Options Considered)

### 옵션 A: 현재 유지 (In-Memory Buffer)
```
성능: ★★★★★ (965 RPS)
확장성: ★☆☆☆☆ (수평 확장 불가)
운영 안정성: ★★☆☆☆ (배포/장애 시 데이터 유실 리스크)
비용: ★★★★★ (t3.small 1대로 충분)
```

**비유:** 시속 400km F1 머신
- 장점: 미친 속도, 비용 효율 최강
- 단점: 짐을 더 실을 수 없음, 고장 나면 끝

**결론:** 트래픽이 예측 가능하고 1,000 RPS 이하일 때 최적

---

### 옵션 B: Redis List (LPUSH/RPOP) 기반 External Queue
```
성능: ★★★☆☆ (500 RPS/대 - 네트워크 RTT 추가)
확장성: ★★★★★ (무한 Scale-out)
운영 안정성: ★★★★☆ (Redis 장애 시 폴백 필요)
비용: ★★★☆☆ (Redis 인스턴스 비용 추가)
```

```
┌─────────────────────────────────────────────────────────┐
│                    목표 아키텍처 (V5)                     │
├─────────────────────────────────────────────────────────┤
│  [Server A]    [Server B]    [Server C]                │
│      │              │              │                    │
│      └──────────────┼──────────────┘                    │
│                     ▼                                   │
│           ┌─────────────────┐                          │
│           │   Redis List    │  ← Stateless 분리        │
│           │  (External)     │                          │
│           └─────────────────┘                          │
│                     │                                   │
│                     ▼                                   │
│           ┌─────────────────┐                          │
│           │  Batch Consumer │                          │
│           │  (별도 Worker)   │                          │
│           └─────────────────┘                          │
└─────────────────────────────────────────────────────────┘

장점:
1. 서버 추가만으로 선형 확장 (5대 = 2,500 RPS)
2. 무중단 배포 (버퍼가 외부에 있어 데이터 유실 없음)
3. 오토스케일링 자유로움 (Scale-in도 안전)
```

**비유:** 시속 100km 택시 군단
- 장점: 손님 늘면 택시 추가, 관리 쉬움
- 단점: 대당 속도는 F1보다 느림

**결론: V5 채택 후보**

---

### 옵션 C: Kafka 기반 Event Streaming
```
성능: ★★★☆☆ (500 RPS/대 - Producer 오버헤드)
확장성: ★★★★★ (파티션 기반 무한 확장)
운영 안정성: ★★★★★ (Replication, Exactly-once)
비용: ★★☆☆☆ (Kafka 클러스터 운영 비용 높음)
```

**결론:** 현재 규모 대비 과도한 복잡도. 트래픽이 10,000+ RPS 도달 시 재검토.

## 결정 (Decision)

### 단기 (현재): V4 유지
```
조건: 트래픽 < 1,000 RPS
전략: 단일 노드 극한 최적화 (965 RPS)
이유: 인프라 비용 최소화, 운영 단순성
```

### 중기 (트리거 발동 시): V5 전환 (Redis List)
```
트리거 조건:
  - 피크 트래픽 > 800 RPS (현재 한계의 80%)
  - 또는 수평 확장 필수 요구사항 발생

전환 작업:
  1. ExpectationWriteBackBuffer → Redis LPUSH로 대체
  2. ExpectationBatchWriteScheduler → 별도 Consumer Worker로 분리
  3. 오토스케일링 정책 설정 (CPU 70% 초과 시 Scale-out)
```

### 장기 (10,000+ RPS): V6 검토 (Kafka)
```
조건: 트래픽 > 10,000 RPS 또는 다중 Consumer 요구
전략: Kafka 기반 Event-Driven Architecture
```

## V5 전환 설계 (Preview)

### 1. Redis List 기반 Buffer
```java
// AS-IS (V4): In-Memory
public class ExpectationWriteBackBuffer {
    private final ConcurrentLinkedQueue<ExpectationWriteTask> buffer;

    public boolean offer(Long characterId, List<PresetExpectation> presets) {
        buffer.offer(new ExpectationWriteTask(...));
        return true;
    }
}

// TO-BE (V5): Redis External
public class ExpectationRedisBuffer {
    private final RedissonClient redisson;
    private static final String BUFFER_KEY = "expectation:write:buffer";

    public boolean offer(Long characterId, List<PresetExpectation> presets) {
        RList<String> buffer = redisson.getList(BUFFER_KEY);
        buffer.add(serialize(new ExpectationWriteTask(...)));
        return true;
    }
}
```

### 2. Consumer Worker 분리
```java
// 별도 프로세스 또는 @Async Worker
@Scheduled(fixedDelay = 5000)
public void consume() {
    RList<String> buffer = redisson.getList(BUFFER_KEY);
    List<String> batch = buffer.range(0, 99);  // 100건 조회

    if (!batch.isEmpty()) {
        repository.batchUpsert(deserialize(batch));
        buffer.trim(100, -1);  // 처리된 항목 제거
    }
}
```

### 3. Graceful Shutdown 단순화
```java
// V4: 복잡한 버퍼 드레인 로직 필요
// V5: 외부 Redis에 데이터 있으므로 즉시 종료 가능

@Override
public void stop() {
    // 버퍼가 Redis에 있으므로 데이터 유실 없음
    log.info("Server shutting down - Redis buffer intact");
}
```

## 트레이드오프 분석

### 성능 vs 확장성 매트릭스

| 지표 | V4 (In-Memory) | V5 (Redis List) | 비고 |
|------|----------------|-----------------|------|
| **단일 노드 RPS** | 965 | ~500 | 네트워크 RTT 추가 |
| **확장성** | 1x (Hard Limit) | Nx (Linear) | **V5 승** |
| **최대 처리량** | 965 RPS | **무제한** | **V5 승** |
| **배포 안전성** | 위험 | 안전 | **V5 승** |
| **인프라 비용** | 낮음 | 중간 | V4 승 |
| **운영 복잡도** | 높음 | 낮음 | **V5 승** |

### 면접 답변 시나리오

> **Q: "RPS가 965에서 500으로 떨어지는데 왜 바꿔요?"**
>
> **A:** "단일 노드의 RPS보다 중요한 건 **'시스템 전체의 총 처리량(Total Throughput)'**입니다.
>
> 현재 구조는 965 RPS가 천장(Ceiling)입니다.
> 하지만 Stateless로 바꾸면 대당 500 RPS라도:
> - 서버 2대 → 1,000 RPS
> - 서버 10대 → 5,000 RPS
> - 서버 100대 → 50,000 RPS
>
> **'확장 가능한 500 RPS'**가 **'고립된 965 RPS'**보다 비즈니스 성장 관점에서 훨씬 가치 있는 아키텍처입니다.
>
> 이것이 **'슈퍼카 한 대'**에서 **'택시 군단'**으로의 전환입니다."

## 결과 (Consequences)

### V5 전환 시 예상 지표

| 지표 | V4 (현재) | V5 (예상) | 변화 |
|------|-----------|-----------|------|
| 단일 노드 RPS | 965 | 500 | -48% |
| 최대 확장 | 1대 | N대 | **무제한** |
| 배포 다운타임 | 10초 (버퍼 드레인) | 0초 | **무중단** |
| Scale-in 안전성 | 위험 | 안전 | **개선** |
| 운영 복잡도 | Graceful Shutdown 필수 | 불필요 | **단순화** |

### 전환 트리거 모니터링

```yaml
# Prometheus Alert Rule
- alert: ScaleOutTrigger
  expr: avg(rate(http_requests_total[5m])) > 800
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "V5 전환 검토 필요 - 트래픽 800 RPS 초과"
```

## 참고 자료

- `docs/adr/ADR-011-controller-v4-optimization.md` - V4 최적화 설계
- `docs/adr/ADR-008-durability-graceful-shutdown.md` - Graceful Shutdown
- `docs/04_Reports/Load_Tests/` - 부하테스트 결과
- `maple.expectation.service.v4.ExpectationWriteBackBuffer` - 현재 In-Memory Buffer
