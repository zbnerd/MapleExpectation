# ADR-037: V5 CQRS Command Side Implementation

**Status:** Implemented
**Date:** 2025-02-15
**Context:** Scale-out Phase 7 (Issue #283)

## Context

V5 CQRS(Command Query Responsibility Segregation) 아키텍처의 **Command Side**를 구현하여 쓰기 작업(기대값 계산)을 비동기 큐 기반으로 처리합니다.

### 문제 정의

1. **동기 계산 병목:** V4의 동기식 계산이 API 응답 시간 지연
2. **확장성 제한:** 단일 서버 인스턴스에서만 계산 가능
3. **우선순위 부재:** 사용자 요청과 배치 작업의 구분 없음

## Decision

### 아키텍처 구성도

```
┌─────────────────────────────────────────────────────────────────┐
│                     V5 CQRS Command Side                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────────────┐     │
│  │   Controller     │         │  PriorityCalculation     │     │
│  │   V5 API         │───────▶│  Executor                │     │
│  └──────────────────┘         └──────────┬───────────────┘     │
│                                          │                      │
│                                          ▼                      │
│                          ┌──────────────────────────────┐       │
│                          │  PriorityCalculationQueue   │       │
│                          │  - HIGH: User requests       │       │
│                          │  - LOW: Batch updates        │       │
│                          └──────────────┬───────────────┘       │
│                                         │ poll()                │
│                                         ▼                       │
│                          ┌──────────────────────────────┐       │
│                          │ ExpectationCalculationWorker │       │
│                          │ (Pool: 4 threads)            │       │
│                          └──────────────┬───────────────┘       │
│                                         │                       │
│                  ┌──────────────────────┼──────────────────┐    │
│                  ▼                      ▼                  ▼    │
│          ┌──────────────┐      ┌──────────────┐   ┌──────────┐ │
│          │  V4 Service  │      │  Persistence │   │  Event   │ │
│          │   Reuse      │      │   Service    │   │Publisher │ │
│          └──────────────┘      └──────────────┘   └──────────┘ │
│                  │                      │                  │    │
│                  ▼                      ▼                  ▼    │
│          ┌──────────────┐      ┌──────────────┐   ┌──────────┐ │
│          │    MySQL     │      │    MySQL     │   │  Redis   │ │
│          │   (Character)│      │  (Results)   │   │  Stream  │ │
│          └──────────────┘      └──────────────┘   └──────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 핵심 컴포넌트

#### 1. PriorityCalculationQueue

**역할:** 우선순위 기반 작업 큐

**기능:**
- `addHighPriorityTask(userIgn, force)` - 사용자 요청 (HIGH 우선순위)
- `addLowPriorityTask(userIgn)` - 배치 작업 (LOW 우선순위)
- `poll()` - 작업 조회 (Blocking)
- `poll(timeoutMs)` - 작업 조회 (Timeout)
- `complete(task)` - 작업 완료 처리

**백프레셔 전략:**
- 최대 큐 크기: 10,000
- HIGH 우선순위 capacity: 1,000
- capacity 초과 시 LOW 우선순위 작업만 거부

#### 2. ExpectationCalculationWorker

**역할:** 큐에서 작업을 꺼내어 계산 실행

**V4 서비스 재사용:**
```java
expectationService.calculateExpectation(userIgn, force)
```

**내부 실행 흐름 (V4 위임):**
1. GameCharacterFacade.findCharacterByUserIgn()
2. EquipmentDataProvider.getRawEquipmentData()
3. EquipmentStreamingParser.decompressIfNeeded()
4. EquipmentStreamingParser.parseCubeInputsForPreset() (1,2,3)
5. PresetCalculationHelper.calculatePreset() (병렬)
6. findMaxPreset() (최대 기대값 선택)
7. ExpectationPersistenceService.saveResults()
8. MongoSyncEventPublisher.publishCalculationCompleted()

#### 3. PriorityCalculationExecutor

**역할:** Worker Pool 관리

**기능:**
- `start()` - Worker Pool 시작 (기본 4개 스레드)
- `stop()` - Graceful shutdown (30초 타임아웃)
- `submitHighPriority(userIgn, force)` - HIGH 우선순위 작업 제출
- `submitLowPriority(userIgn)` - LOW 우선순위 작업 제출

**설정:**
```yaml
app:
  v5:
    enabled: true
    worker-pool-size: 4
    shutdown-timeout-seconds: 30
```

### 작업 처리 흐름

```
User Request
    │
    ▼
Controller.submitHighPriority(ign, force)
    │
    ▼
PriorityCalculationQueue.offer(task)
    │
    ├─▶ [HIGH Queue] ──▶ Capacity Check (1,000)
    │                         │
    │                         ├─ OK ──▶ Queued
    │                         └─ Full ─▶ Rejected (Backpressure)
    │
    ▼
Worker.poll()
    │
    ▼
ExpectationCalculationWorker.processNextTask()
    │
    ▼
V4 Service.doCalculateExpectation()
    │
    ├─▶ GameCharacterFacade.findCharacterByUserIgn()
    ├─▶ EquipmentDataProvider.getRawEquipmentData()
    ├─▶ EquipmentStreamingParser.decompressIfNeeded()
    ├─▶ EquipmentStreamingParser.parseCubeInputsForPreset(1,2,3)
    ├─▶ PresetCalculationHelper.calculatePreset() [Parallel]
    ├─▶ findMaxPreset()
    ├─▶ ExpectationPersistenceService.saveResults()
    │
    ▼
MongoSyncEventPublisher.publishCalculationCompleted()
    │
    ▼
Redis Stream ("character-sync")
    │
    ▼
MongoSyncWorker (Query Side) → MongoDB Upsert
```

## Consequences

### 긍정적 효과

1. **비동기 처리:** API 응답 시간 단축 (즉시 큐에 등록 후 반환)
2. **우선순위 제어:** 사용자 요청 우선 처리 (HIGH > LOW)
3. **확장성:** Worker Pool 크기 조절로 부하 분산
4. **V4 재사용:** 기존 비즈니스 로직 100% 재사용 (중복 없음)
5. **백프레셔:** 큐 capacity로 메모리 보호

### 부정적 효과

1. **복잡성 증가:** 비동기 처리로 디버깅 어려움
2. **결과 지연:** 계산 완료까지 지연 발생 (폴링 필요)
3. **분산 환경:** Redis Stream 의존성 추가

### 완화 전략

1. **모니터링:** Micrometer metrics (processed, errors)
2. **폴링 API:** 결과 조회 API 제공 (Query Side)
3. **Eventual Consistency:** CQRS 패턴 수용

## Implementation Files

### Core Components

| File | 역할 |
|------|------|
| `/service/v5/queue/PriorityCalculationQueue.java` | 우선순위 큐 구현 |
| `/service/v5/queue/QueuePriority.java` | HIGH/LOW 우선순위 enum |
| `/service/v5/queue/ExpectationCalculationTask.java` | 작업 DTO |
| `/service/v5/worker/ExpectationCalculationWorker.java` | Worker 구현 (V4 재사용) |
| `/service/v5/executor/PriorityCalculationExecutor.java` | Worker Pool 관리 |
| `/service/v5/V5Config.java` | Spring 설정 및 Lifecycle |

### V4 Reused Services

| File | 재사용 기능 |
|------|------------|
| `/service/v4/EquipmentExpectationServiceV4.java` | 전체 계산 파이프라인 |
| `/service/v2/facade/GameCharacterFacade.java` | 캐릭터 조회 |
| `/provider/EquipmentDataProvider.java` | 장비 데이터 로드 |
| `/parser/EquipmentStreamingParser.java` | 파싱 (GZIP, JSON) |
| `/service/v4/PresetCalculationHelper.java` | 프리셋 계산 |
| `/service/v4/persistence/ExpectationPersistenceService.java` | 결과 저장 |

### Event & Sync

| File | 역할 |
|------|------|
| `/service/v5/event/MongoSyncEventPublisher.java` | Redis Stream 이벤트 발행 |
| `/service/v5/V5MetricsConfig.java` | Micrometer metrics 설정 |

## Testing

### Unit Tests

```bash
./gradlew test --tests "maple.expectation.service.v5.*"
```

### Integration Test

```java
@Test
void testHighPriorityCalculation() {
  // Given
  String userIgn = "testUser";

  // When
  String taskId = executor.submitHighPriority(userIgn, true);

  // Then
  assertThat(taskId).isNotNull();
  await().atMost(30, TimeUnit.SECONDS)
      .until(() -> executor.getQueueSize() == 0);
}
```

## References

- [ADR-036: V5 CQRS Architecture](ADR-036-v5-cqrs-mongodb.md)
- [V4 Service Design](../04_Reports/04_09_Scale_Out/v5-cqrs-implementation-report.md)
- [CLAUDE.md Section 12: LogicExecutor](../../CLAUDE.md)
