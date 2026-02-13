## Fail If Wrong
1. **[F1]** Scale-out 시 데이터 파편화 발생
2. **[F2]** Rolling Update 시 버퍼 데이터 유실
3. **[F3]** Redis List 성능이 In-Memory 미만으로 저하
4. **[F4]** Kafka 전환 시 OCP 위반 (코드 변경 필요)

---

## Terminology
| 용어 | 정의 |
|------|------|
| **Stateful** | 서버가 In-Memory 상태(버퍼)를 가짐. Scale-out 불가. |
| **Stateless** | 상태가 외부(Redis)에 있음. 서버 추가만으로 확장. |
| **Write-Behind Buffer** | 비동기 버퍼에 쌓았다가 배치로 DB 저장 |
| **Strategy Pattern** | 구현체 교체 가능한 인터페이스 기반 설계 (OCP) |

---

## 맥락 (Context)
### 현재 아키텍처 (V4)의 성과와 한계

**성과 - 단일 노드 극한 최적화:**
- 965 RPS (AWS t3.small, 2 vCPU, 2GB RAM) [P1]
- In-Memory Write-Behind Buffer로 DB 저장 지연 0.1ms [P2]
- L1 Fast Path로 캐시 HIT 시 5ms 응답 [P3]

**한계 - Stateful 구조의 고유 문제:**
- Scale-out 불가: 각 서버가 독립 버퍼 → 데이터 파션화 [E1]
- 배포 위험: Rolling Update 시 버퍼 미플러시 데이터 유실 [E2]
- 장애 전파: 서버 크래시 시 In-Memory 데이터 소멸 [E3]

---

## 대안 분석
### 옵션 A: 현재 유지 (In-Memory Buffer)
```
성능: ★★★★★ (965 RPS)
확장성: ★☆☆☆☆ (수평 확장 불가)
운영 안정성: ★★☆☆☆ (배포/장애 시 데이터 유실 리스크)
비용: ★★★★★ (t3.small 1대로 충분)
```

**비유:** 시속 400km F1 머신
- **장점:** 미친 속도, 비용 효율 최강
- **단점:** 짐을 더 실을 수 없음, 고장 나면 끝
- **결론:** 트래픽이 예측 가능하고 1,000 RPS 이하일 때 최적

### 옵션 B: Redis List (LPUSH/RPOP) 기반 External Queue
```
성능: ★★★☆☆ (500 RPS/대 - 네트워크 RTT 추가)
확장성: ★★★★★ (무한 Scale-out)
운영 안정성: ★★★★☆ (Redis 장애 시 폴백 필요)
비용: ★★★☆☆ (Redis 인스턴스 비용 추가)
```

**비유:** 시속 100km 택시 군단
- **장점:** 손님 늘면 택시 추가, 관리 쉬움
- **단점:** 대당 속도는 F1보다 느림
- **결론:** V5 채택 후보

### 옵션 C: Kafka 기반 Event Streaming
```
성능: ★★★☆☆ (500 RPS/대 - Producer 오버헤드)
확장성: ★★★★★ (파티션 기반 무한 확장)
운영 안정성: ★★★★★ (Replication, Exactly-once)
비용: ★★☆☆☆ (Kafka 클러스터 운영 비용 높음)
```
- **결론:** 현재 규모 대비 과도한 복잡도. 트래픽이 10,000+ RPS 도달 시 재검토.

---

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

---

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

---

## 메시지 큐 추상화 설계 (Strategy Pattern)

### 설계 원칙: OCP (Open-Closed Principle)

추후 Redis List → Kafka, RabbitMQ, AWS SQS 등으로 **무중단 전환**이 가능하도록 전략 패턴으로 추상화합니다.

### 1. 인터페이스 정의
```java
/**
 * Write-Behind Buffer 전략 인터페이스
 * 구현체 교체만으로 In-Memory ↔ Redis ↔ Kafka 전환 가능
 */
public interface WriteBackBufferStrategy {

    /**
     * 버퍼에 작업 추가
     * @return 성공 여부 (Backpressure 시 false)
     */
    boolean offer(ExpectationWriteTask task);

    /**
     * 버퍼에서 배치 단위로 작업 조회 및 제거 (Atomic)
     */
    List<ExpectationWriteTask> poll(int batchSize);

    /**
     * 현재 대기 중인 작업 수
     */
    long size();

    /**
     * 버퍼 타입 식별 (모니터링용)
     */
    BufferType getType();
}

public enum BufferType {
    IN_MEMORY,   // V4: ConcurrentLinkedQueue
    REDIS_LIST,  // V5: Redis LPUSH/RPOP
    KAFKA,       // V6: Kafka Topic
    SQS          // AWS SQS (옵션)
}
```

### 2. 구현체별 전략
```java
// V4: In-Memory (현재)
@Component
@ConditionalOnProperty(name = "buffer.strategy", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryBufferStrategy implements WriteBackBufferStrategy {
    private final ConcurrentLinkedQueue<ExpectationWriteTask> queue = new ConcurrentLinkedQueue<>();

    @Override
    public boolean offer(ExpectationWriteTask task) {
        return queue.offer(task);
    }

    @Override
    public List<ExpectationWriteTask> poll(int batchSize) {
        // 드레인 로직
    }

    @Override
    public BufferType getType() { return BufferType.IN_MEMORY; }
}

// V5: Redis List
@Component
@ConditionalOnProperty(name = "buffer.strategy", havingValue = "redis")
public class RedisBufferStrategy implements WriteBackBufferStrategy {
    private final RedissonClient redisson;

    @Override
    public boolean offer(ExpectationWriteTask task) {
        RList<String> list = redisson.getList(BUFFER_KEY);
        return list.add(serialize(task));
    }

    @Override
    public List<ExpectationWriteTask> poll(int batchSize) {
        // Lua Script로 Atomic하게 조회 + 삭제
        return redisson.getScript().eval(...);
    }

    @Override
    public BufferType getType() { return BufferType.REDIS_LIST; }
}
```

### 3. 설정 기반 전환
```yaml
# application.yml - 설정값 하나로 전략 교체
buffer:
  strategy: in-memory  # in-memory | redis | kafka

---
# application-prod.yml (V5 전환 시)
buffer:
  strategy: redis
```

---

## 결과 (Consequences)

### 개선 효과

| 지표 | Before (V4) | After (V5 예상) | Evidence ID |
|------|-------------|----------------|-------------|
| Scale-out 가능성 | 불가 | **가능** | [E1] |
| 배포 안정성 | 데이터 유실 위험 | **안전** | [E2] |
| 처리량 | 965 RPS (single) | 500+ RPS (horizontal) | [E3] |
| 운영 비용 | t3.small 1대 | t3.small N대 + Redis | [E4] |

### Evidence IDs

| ID | 타입 | 설명 | 검증 |
|----|------|------|------|
| [E1] | 테스트 | Redis List로 수평 확장 테스트 | Load Test V5 |
| [E2] | Chaos Test | Rolling Update 시 버퍼 유실 검증 | Chaos Test |
| [E3] | 부하테스트 | 500+ RPS/대 처리량 달성 | Performance Test |
| [E4] | 비용 분석 | 인프라 비용 증가율 계산 | Cost Analysis |
| [C1] | 코드 | WriteBackBufferStrategy 인터페이스 | BufferStrategy.java |
| [C2] | 코드 | Redis List 구현체 | RedisBufferStrategy.java |

---

## 재현성 및 검증

### 테스트 실행

```bash
# V5 Redis Buffer 테스트
./gradlew test --tests "maple.expectation.strategy.v5.RedisBufferStrategyTest"

# Scale-out 테스트
./gradlew test --tests "maple.expectation.strategy.v5.ScaleOutTest"

# 성능 비교 테스트
./gradlew test --tests "maple.expectation.strategy.PerformanceComparisonTest"
```

### 메트릭 확인

```promql
# 버퍼 크기
buffer_size{buffer="redis"}

# 처리량 (RPS)
rate(processed_tasks_total[5m])

# 에러율
rate(processed_tasks_failed_total[5m])
```

---

## Verification Commands (검증 명령어)

### 1. V5 전환 전 검사

```bash
# 트래픽 모니터링 (현재 대비 80% 이상인지 확인)
./gradlew loadTest --args="--rps 800"

# 버퍼 크기 모니터링
./gradlew monitor --args="--buffer-size"

# Redis 연결 테스트
./gradlew test --tests "maple.expectation.infrastructure.redis.RedisConnectionTest"
```

### 2. V5 전환 후 검사

```bash
# 수평 확장 테스트
kubectl scale deployment maplexpectation-v5 --replicas=3

# 처리량 확인
./gradlew loadTest --args="--rps 1500"

# 버퍼 분산 확인
./gradlew monitor --args="--buffer-distribution"
```

### 3. 장애 시나리오 테스트

```bash
# Redis 장애 테스트
./gradlew chaos --scenario="redis-failure"

# Rolling Update 테스트
./gradlew chaos --scenario="rolling-update"

# Auto Scaling 테스트
./gradlew chaos --scenario="auto-scaling"
```

## 트레이드오프 분석

### 성능 vs 확장성 매트릭스

| 옵션 | 성능 | 확장성 | 운영 안정성 | 비용 | 전환 비용 |
|------|------|--------|-------------|------|------------|
| V4: In-Memory | ★★★★★ | ★☆☆☆☆ | ★★☆☆☆ | ★★★★★ | - |
| V5: Redis List | ★★★☆☆ | ★★★★★ | ★★★★☆ | ★★★☆☆ | 중간 |
| V6: Kafka | ★★★☆☆ | ★★★★★ | ★★★★★ | ★★☆☆☆ | 높음 |

**비용 계산:**
- V4: t3.small 1대 ($0.052/h)
- V5: t3.small N대 + Redis (~$0.15/h)
- V6: Kafka Cluster (~$0.5/h)

**전략 선택 기준:**
- **현재 (V4):** 트래픽 < 1,000 RPS → 비용 효율 최적
- **V5 전환:** 트래픽 > 800 RPS 또는 Scale-out 요구
- **V6 도입:** 트래픽 > 10,000 RPS → 복잡성 대비 효율화

**비유로 이해하는 전략 선택:**
- **V4 (F1 머신):** 코스팩에 최적. 빠르지만 짐을 실을 수 없음
- **V5 (택시 군단):** 8명까지는 1대로 충분. 9명 되면 2대로 전환
- **V6 (지하철 시스템):** 10만 명 이상에서 효율. 10명까지는 과잉

---

## 관련 문서

### 연결된 ADR
- **[ADR-003](ADR-003-tiered-cache-singleflight.md)** - Cache Stampede 방지
- **[ADR-004](ADR-004-logicexecutor-policy-pipeline.md)** - LogicExecutor 실행 파이프라인
- **[ADR-011](ADR-011-controller-v4-optimization.md)** - V4 Controller 최적화

### 코드 참조
- **Strategy Interface:** `src/main/java/maple/expectation/strategy/WriteBackBufferStrategy.java`
- **In-Memory Implementation:** `src/main/java/maple/expectation/strategy/v4/InMemoryBufferStrategy.java`
- **Redis Implementation:** `src/main/java/maple/expectation/strategy/v5/RedisBufferStrategy.java`
- **Configuration:** `src/main/resources/application.yml`

### 이슈
- **[Issue #126](https://github.com/zbnerd/MapleExpectation/issues/126)** - Stateless 아키텍처 검토
- **[Issue #282](https://github.com/zbnerd/MapleExpectation/issues/282)** - 수평 확장 요구사항

---

## 부록

### V5 전환 체크리스트

#### 사전 준비
- [ ] Redis 인프라 프로비저닝
- [ ] BufferStrategy 구현체 개발 완료
- [ ] 모니터링 메트릭 정의
- [ ] 롤백 계획 수립

#### 전환 절차
- [ ] 1단계: Staging 환경에서 V5 테스트
- [ ] 2단계: Production 10% 트래픽 전환
- [ ] 3단계: 성능/안정성 모니터링
- [ ] 4단계: 단계적 확장 (50% → 100%)

#### 사후 검증
- [ ] 처리량 목표 달성 (500+ RPS/대)
- [ ] 버퍼 분산 확인
- [ ] 장애 복구 시간 검증 (< 5분)
- [ ] 비용 증가율 확인 (< 200%)

**참고:** 모든 전환 작업은 트래픽이低谷 시간대에 진행해야 합니다.

