# [Architecture] Event-Driven Architecture(EDA) 전환을 위한 Kafka 도입 설계

> **Type:** Design
> **Priority:** P1 (Future)
> **Status:** Draft (Ready for Review)
> **Date:** 2026-02-07

---

## 개요

현재 **DB 기반 Outbox Polling** 방식으로 이벤트 기반 통신을 구현 중입니다. 트래픽이 5배 이상 증가할 경우 **Kafka + Debezium CDC**로 전환하여 처리량(Throughput)을 개선하는 설계안입니다.

**비즈니스 배경:**
- 현재: DB Outbox Table을 1초마다 폴링하여 이벤트 발행
- 문제: 대량 이벤트 시 DB Table Lock 경합, Polling 주기 trade-off
- 목표: Real-time 이벤트 전송 + 확장 가능한 아키텍처

---

## 현재 상황 (Current State)

### Outbox 패턴 구현
```java
@Transactional
public void saveCharacter(GameCharacter character) {
    repository.save(character);

    // Outbox에 이벤트 기록
    outboxRepository.save(new OutboxEvent(
        "CHARACTER_SAVED",
        character.getOcid(),
        objectMapper.writeValueAsString(character)
    ));
}

// 별도 스레드가 Outbox를 폴링하여 발행
@Scheduled(fixedDelay = 1000)
public void processOutbox() {
    List<OutboxEvent> events = outboxRepository.findPending(100);
    events.forEach(event -> {
        messagePublisher.publish(event.getTopic(), event.getPayload());
        outboxRepository.delete(event);
    });
}
```

### 문제점
1. **DB Lock 경합**: 대량 이벤트 시 `SELECT FOR UPDATE` 경합 발생
2. **Polling 지연**: 1초마다 폴링 → 최대 1초 지연
3. **확장성 한계**: 단일 DB에 의존하여 scale-out 불가

---

## 전환 설계 (Migration Design)

### Phase 1: Debezium + Kafka Connect (CDC 방식)

#### 아키텍처
```
┌─────────────┐    CDC    ┌──────────────┐    Kafka    ┌─────────────┐
│   Service   │──────────>│  Debezium    │────────────>│   Kafka     │
│             │ Binlog   │   Connector  │   Topic     │   Broker    │
│   (Outbox)  │ Capture  │              │             │             │
└─────────────┘          └──────────────┘             └─────────────┘
                                                               │
                                           ┌───────────────────┤
                                           ▼                   ▼
                                  ┌─────────────┐     ┌─────────────┐
                                  │  Consumer 1 │     │  Consumer 2 │
                                  │  (Worker)   │     │  (Notifier) │
                                  └─────────────┘     └─────────────┘
```

#### Debezium Connector 설정
```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "dbz",
    "database.server.id": "184054",
    "database.server.name": "maple_expectation",
    "database.include.list": "maple_expectation",
    "table.include.list": "maple_expectation.nexon_api_outbox",
    "database.history.kafka.bootstrap.servers": "kafka:9092",
    "database.history.kafka.topic": "schema-changes.maple_expectation",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.fields.event.placement": "envelope",
    "transforms.outbox.table.fields.additional.placement": "type:header,key:header",
    "transforms.outbox.route.topic.replacement": "outbox.event.${key}"
  }
}
```

#### Kafka Topic 설계
| Topic Name | Partitions | Replication Factor | Retention | Purpose |
|------------|------------|--------------------|-----------|---------|
| `outbox.event.character` | 3 | 3 | 7 days | 캐릭터 이벤트 |
| `outbox.event.equipment` | 3 | 3 | 7 days | 장비 이벤트 |
| `outbox.event.like` | 3 | 3 | 7 days | 좋아요 이벤트 |
| `outbox.event.donation` | 1 | 3 | 7 days | 후원 이벤트 |

**Partitioning Strategy:**
- `aggregate_id` (예: `ocid`) 기반 해싱
- 동일 캐릭터의 이벤트는 동일 파티션으로 순서 보장

---

### Phase 2: Kafka Consumer 구현

#### Producer 코드 (기존 Outbox 패턴 유지)
```java
// 기존 코드 변경 없음!
@Transactional
public void saveCharacter(GameCharacter character) {
    repository.save(character);
    outboxRepository.save(new OutboxEvent(
        "CHARACTER_SAVED",
        character.getOcid(),
        objectMapper.writeValueAsString(character)
    ));
}
```

#### Consumer 코드 (새로 추가)
```java
@Component
@RequiredArgsConstructor
public class CharacterEventConsumer {

  private final KafkaConsumer<String, String> consumer;
  private final CharacterEventHandler eventHandler;

  @KafkaListener(topics = "outbox.event.character", groupId = "character-worker")
  public void handleCharacterEvent(ConsumerRecord<String, String> record) {
    String eventId = record.key();
    String eventJson = record.value();

    // Idempotent 처리 (중복 방지)
    if (eventHandler.isAlreadyProcessed(eventId)) {
      log.info("Event already processed: {}", eventId);
      return;
    }

    // 이벤트 처리
    CharacterEvent event = objectMapper.readValue(eventJson, CharacterEvent.class);
    eventHandler.handle(event);

    // 처리 완료 기록
    eventHandler.markAsProcessed(eventId);
  }
}
```

#### Idempotent Consumer 구현
```java
@Service
public class IdempotentEventHandler {

  private final RedisTemplate<String, String> redisTemplate;
  private final Duration eventIdTtl = Duration.ofDays(7);

  public boolean isAlreadyProcessed(String eventId) {
    return Boolean.TRUE.equals(
        redisTemplate.hasKey("processed_event:" + eventId)
    );
  }

  public void markAsProcessed(String eventId) {
    redisTemplate.opsForValue().set(
        "processed_event:" + eventId,
        "true",
        eventIdTtl
    );
  }
}
```

---

## Migration 단계 (Step-by-Step)

### Step 1: Kafka 클러스터 구축 (1주)
```bash
# Docker Compose로 로컬 테스트
docker-compose up -d kafka zookeeper

# 검증
kafka-topics.sh --list --bootstrap-server localhost:9092
```

### Step 2: Debezium Connector 배포 (1주)
```bash
# Kafka Connect에 Debezium Connector 등록
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @debezium-config.json

# CDC 시작 확인
curl http://localhost:8083/connectors/outbox-connector/status
```

### Step 3: Dual-Write 기간 (2주)
- **기존:** Outbox Poller 유지
- **신규:** Kafka Consumer도 병렬 운영
- **검증:** 두 시스템의 이벤트 처리 결과 비교

### Step 4: 트래픽 전환 (1주)
- Outbox Poller 점진적 축소 (50% → 0%)
- Kafka Consumer로 트래픽 완전 이동

### Step 5: 기존 Outbox 제거 (1주)
- Outbox Poller 코드 제거
- Kafka Producer로 직접 전환 (선택사항)

---

## 성능 비교 (Expected)

| 메트릭 | 현재 (Polling) | 전환 후 (Kafka) | 개선 폭 |
|--------|----------------|------------------|---------|
| **Throughput** | 1,000 TPS | 5,000+ TPS | **5x** |
| **P99 Latency** | 500ms | 50ms | **10x** |
| **DB Load** | 100% (Lock 경합) | 10% (Binlog only) | **10x 감소** |
| **확장성** | 단일 서버 | N개 Consumer | **Linear Scale** |

---

## 장애 대응 (Failure Scenarios)

### Scenario 1: Kafka Broker 다운
**대응:**
- Producer: Kafka 다운 시 → 로컬 Outbox에 임시 저장 (회귀)
- Consumer: Rebalance 후 자동 재시작

### Scenario 2: Consumer 장애
**대응:**
- Kafka 자동으로 파티션을 다른 Consumer에 재할당
- 장애 Consumer 복구 시 Last Committed Offset부터 재개

### Scenario 3: Debezium Connector 장애
**대응:**
- Binlog Position 유지 → 재시작 시 누락 없이 재개
- CDC Offset는 Kafka Topic에 저장

---

## Rollback Plan

**이상 발생 시 기존 Outbox Poller로 즉시 회귀:**
```bash
# 1. Kafka Consumer 중지
./gradlew stop -Pprofile=kafka-consumer

# 2. Outbox Poller 재시작
./gradlew start -Pprofile=outbox-poller

# 3. Debezium Connector 중지
curl -X DELETE http://localhost:8083/connectors/outbox-connector
```

**데이터 유실 방지:**
- Outbox Table은 삭제하지 않음 (Rollback용)
- Dual-Write 기간 중 양쪽 시스템 모두 동일한 결과 검증

---

## 추후 작업 (Action Items)

- [ ] Kafka 로컬 개발 환경 구축 (Docker Compose)
- [ ] Debezium Connector 설정 파일 작성
- [ ] Kafka Consumer 구현 (Idempotent 포함)
- [ ] 부하 테스트 (Target: 5000 TPS)
- [ ] 장애 주입 테스트 (Kafka 다운, Consumer 장애 등)
- [ ] 운영 메트릭 대시보드 구축 (Grafana)

---

## 관련 문서

- [ROADMAP.md Phase 8](../00_Start_Here/ROADMAP.md#phase-8--event-driven-architecture-eda--msa-전환-planned)
- [Transactional Outbox ADR](../adr/ADR-XXX-outbox-pattern.md) (TBD)
- [Debezium Documentation](https://debezium.io/documentation/reference/stable/)

---

## 첨부

### Debezium outbox transform 설정 상세
```json
{
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.route.topic.replacement": "outbox.event.${key}",
  "transforms.outbox.route.by.field": "aggregateType",
  "transforms.outbox.table.fields.event.placement": "envelope",
  "transforms.outbox.table.fields.additional.placement": "type:header,value:header,eventType:header,eventType:aggregateType:header,aggregateType"
}
```

**설명:**
- `route.topic.replacement`: Topic 이름 동적 생성 (`outbox.event.{aggregateType}`)
- `table.fields.event.placement`: 이벤트 데이터를 message body에 배치
- `additional.placement`: 메타데이터를 header에 추가

---

*Design Issue: Kafka + EDA Migration Plan*
*Date: 2026-02-07*
*Status: Draft - Ready for Architecture Review*
