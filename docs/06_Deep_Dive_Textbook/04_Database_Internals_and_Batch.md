# 04. Database Internals and Batch: 배치 INSERT와 트랜잭션의 심화 학습

> **"데이터베이스는 단순한 저장소가 아닙니다. 그것은 I/O, 네트워크, 파일 시스템, 병행 제어가 만난 가장 복잡한 소프트웨어입니다."**

---

## 1. The Problem (본질: 왜 배치가 필요한가?)

### 1.1 네트워크 왕복 (Round Trip)의 비용

**단건 INSERT의 비용:**

```
Application                    MySQL Server
     │                              │
     │──[INSERT 1]─────────────────>│
     │<──[ACK 1]────────────────────│  1ms (네트워크)
     │                              │  5ms (디스크 쓰기)
     │                              │  1ms (트랜잭션 로그)
     │──[INSERT 2]─────────────────>│
     │<──[ACK 2]────────────────────│  1ms
     │                              │  5ms
     │                              │  1ms
     │──[INSERT 3]─────────────────>│
     │<──[ACK 3]────────────────────│  ...
     │                              │
     │ 총 9건 = 9 × 7ms = 63ms        │
```

**배치 INSERT의 효율:**

```
Application                    MySQL Server
     │                              │
     │──[INSERT 9건 묶음]──────────>│
     │<──[ACK 1]────────────────────│  1ms (네트워크)
     │                              │  5ms (디스크 쓰기)
     │                              │  1ms (트랜잭션 로그)
     │                              │
     │ 총 9건 = 1 × 7ms = 7ms         │ (9배 빠름!)
```

**핵심**: "네트워크 왕복 횟수를 줄이는 것이 성능의 열쇠다"

### 1.2 TCP 패킷 크기와 MSS (Maximum Segment Size)

**TCP/IP 계층 구조:**

```
┌────────────────────────────────────┐
│  Application Data (INSERT 9건)     │
└────────────┬───────────────────────┘
             │ TCP Segmentation
             ▼
┌────────────────────────────────────┐
│  TCP Segment (MSS = 1460 bytes)    │
│  - IP Header: 20 bytes             │
│  - TCP Header: 20 bytes            │
│  - Payload: 1420 bytes             │
└────────────┬───────────────────────┘
             │ IP Fragmentation
             ▼
┌────────────────────────────────────┐
│  IP Packet (MTU = 1500 bytes)     │
│  - Ethernet Frame: 18 bytes        │
│  - IP Packet: 1500 bytes           │
└────────────┬───────────────────────┘
             │
             ▼
         Network
```

**배치의 이점:**

```
단건 INSERT (100 bytes × 9건):
- 9개의 TCP Segment 생성 → 9번의 네트워크 전송
- 각 Segment마다 40 bytes 헤더 오버헤드
- 총 9 × 140 bytes = 1,260 bytes

배치 INSERT (900 bytes × 1건):
- 1개의 TCP Segment 생성 → 1번의 네트워크 전송
- 40 bytes 헤더만 1번
- 총 940 bytes (25% 절약)
```

### 1.3 Undo/Redo Log와 트랜잭션의 원자성

**MySQL InnoDB의 로그 구조:**

```
┌─────────────────────────────────────────────────────┐
│  Buffer Pool (Memory)                               │
│  ┌─────────┬─────────┬─────────┬─────────┐          │
│  │ Page 1  │ Page 2  │ Page 3  │ Page 4  │          │
│  └────┬────┴────┬────┴────┬────┴────┬────┘          │
└───────┼─────────┼─────────┼─────────┼──────────────┘
        │         │         │         │
        │         │         │         │
        ▼         ▼         ▼         ▼
    ┌───────────────────────────────────────────┐
    │  Undo Log (Rollback Segment)              │
    │  [이전 값] [이전 값] [이전 값]            │
    └───────────────────┬───────────────────────┘
                        │
                        ▼
    ┌───────────────────────────────────────────┐
    │  Redo Log (ib_logfile0)                  │
    │  [INSERT] [UPDATE] [DELETE]              │
    └───────────────────┬───────────────────────┘
                        │
                        ▼
                   ┌─────────┐
                   │ Disk    │
                   └─────────┘
```

**로그 순서 (Write-Ahead Logging):**

1. **Redo Log 기록** (Memory → Disk, 순차 쓰기)
2. **Buffer Pool 수정** (Memory)
3. **ACK 반환** (Application)

**장점**: "Crash Recovery 가능" (커밋 전 컴퓨터 꺼져도 Redo Log로 복구)

---

## 2. The CS Principle (원리: 이 코드는 무엇에 기반하는가?)

### 2.1 B-Tree Index와 페이지 단위 I/O

**InnoDB의 페이지 구조:**

```
InnoDB Page (16 KB)

┌─────────────────────────────────────┐
│  File Header (38 bytes)              │
├─────────────────────────────────────┤
│  Page Header (56 bytes)              │
├─────────────────────────────────────┤
│  Infimum + Supremum (26 bytes)       │
├─────────────────────────────────────┤
│  Records (variable)                  │
│  ┌─────────┬─────────┬─────────┐    │
│  │ Record 1│ Record 2│ Record 3│... │
│  └─────────┴─────────┴─────────┘    │
├─────────────────────────────────────┤
│  Free Space (variable)               │
├─────────────────────────────────────┤
│  Page Directory (variable)           │
├─────────────────────────────────────┤
│  File Trailer (8 bytes)              │
└─────────────────────────────────────┘
```

**배치 INSERT의 이점 (페이지 효율):**

```
단건 INSERT (레코드 크기 100 bytes):
- 1 INSERT = 1/160 Page (16 KB / 100 bytes)
- 9 INSERT = 9 × I/O (9 페이지 접근)

배치 INSERT (레코드 9건 = 900 bytes):
- 9 INSERT = 1/18 Page (16 KB / 900 bytes)
- 1번의 I/O로 9건 저장 (페이지 단위 쓰기)
```

### 2.2 JDBC Rewrite Batched Statements

**MySQL의 `rewriteBatchedStatements` 옵션:**

```properties
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/maple?rewriteBatchedStatements=true
```

**작동 원리:**

```sql
-- Application Code
jdbcTemplate.batchUpdate("INSERT INTO equipment (id, name) VALUES (?, ?)", batchArgs);

-- JDBC가 변환한 결과 (rewriteBatchedStatements=false)
INSERT INTO equipment (id, name) VALUES (1, '검');
INSERT INTO equipment (id, name) VALUES (2, '창');
INSERT INTO equipment (id, name) VALUES (3, '활');
-- 3개의 Statement 전송

-- JDBC가 변환한 결과 (rewriteBatchedStatements=true)
INSERT INTO equipment (id, name) VALUES
  (1, '검'),
  (2, '창'),
  (3, '활');
-- 1개의 Statement 전송 (3배 더 빠름!)
```

### 2.3 Transactional Outbox Pattern

**문제: 분산 트랜잭션의 일관성**

```
┌─────────────┐                    ┌─────────────┐
│  MySQL DB   │                    │  Redis      │
│             │                    │  (Cache)    │
│  Equipment  │                    │             │
└──────┬──────┘                    └──────┬──────┘
       │                                  │
       │ 1. INSERT equipment              │
       │   (COMMIT 완료)                   │
       │                                  │
       │ 2. Cache Invalidate              │
       │   💥 장애 발생!                  │
       │                                  │
       │ 결과: DB는 반영, Cache는 Stale   │
       └──────────────────────────────────┘
```

**해결책: Outbox Table**

```sql
-- Outbox Table
CREATE TABLE nexon_api_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(50),   -- "Equipment"
    aggregate_id BIGINT,          -- 123
    payload JSON,                 -- {"operation": "UPDATE", "data": {...}}
    status ENUM('PENDING', 'PUBLISHED', 'FAILED'),
    created_at TIMESTAMP,
    processed_at TIMESTAMP NULL,
    INDEX idx_pending (status, created_at)
);
```

**구현 흐름:**

```java
@Transactional
public void updateEquipment(Long id, Equipment data) {
    // 1. Main Table 업데이트
    equipmentRepository.update(id, data);

    // 2. Outbox Table에 이벤트 저장 (같은 트랜잭션)
    outboxRepository.insert(
        OutboxEvent.of("Equipment", id, "UPDATE", data)
    );
    // ✅ 둘 다 COMMIT or 둘 다 ROLLBACK (원자성 보장)
}

// Background Worker (Spring Batch)
@Scheduled(fixedRate = 1000)
public void processOutbox() {
    List<OutboxEvent> pending = outboxRepository.findPending(100);

    for (OutboxEvent event : pending) {
        try {
            // 3. Redis Pub/Sub 발행
            redisson.getTopic("equipment:updated").publish(event);

            // 4. 상태 업데이트
            outboxRepository.markAsPublished(event.getId());
        } catch (Exception e) {
            outboxRepository.markAsFailed(event.getId());
        }
    }
}
```

---

## 3. Internal Mechanics (내부: JDBC & MySQL은 어떻게 동작하는가?)

### 3.1 JDBC Batch Update의 내부 구조

**Spring JdbcTemplate의 Batch 흐름:**

```java
// Application Code
List<Object[]> batchArgs = List.of(
    new Object[]{1, "검"},
    new Object[]{2, "창"},
    new Object[]{3, "활"}
);

jdbcTemplate.batchUpdate(
    "INSERT INTO equipment (id, name) VALUES (?, ?)",
    batchArgs
);

// JdbcTemplate 내부 처리
public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
    // 1. PreparedStatement 생성
    PreparedStatement ps = connection.prepareStatement(sql);

    // 2. 파라미터 바인딩 + Batch 추가
    for (Object[] args : batchArgs) {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        ps.addBatch();  // ⭐ 내부 버퍼에 추가 (미전송)
    }

    // 3. 한 번에 전송 (rewriteBatchedStatements=true)
    return ps.executeBatch();  // ✅ 1번의 네트워크 왕복
}
```

### 3.2 MySQL InnoDB의 Buffer Pool 관리

**Buffer Pool의 LRU List:**

```
┌─────────────────────────────────────────────────────────┐
│  Buffer Pool (1 GB)                                   │
│                                                        │
│  ┌──────────────┬──────────────┬──────────────┐       │
│  │ Young Block  │ Old Block    │              │       │
│  │ (37%)        │ (63%)        │              │       │
│  ├──────────────┼──────────────┤              │       │
│  │ [NEW 1]      │ [HOT 1]      │              │       │
│  │ [NEW 2]      │ [HOT 2]      │              │       │
│  │ ...          │ ...          │              │       │
│  └──────────────┴──────────────┴──────────────┘       │
│         ▲                              ▲              │
│         │                              │              │
│    LRU Tail                       LRU Head          │
│  (오래된 데이터)              (최근 데이터)           │
└─────────────────────────────────────────────────────────┘
```

**배치 INSERT의 이점 (Buffer Pool 활용):**

```
단건 INSERT (9건):
- Page 1에 Record 1 기록 → Dirty Page
- Page 2에 Record 2 기록 → Dirty Page
- ...
- Page 9에 Record 9 기록 → Dirty Page
→ 9개의 Dirty Page → 9번의 Flush I/O

배치 INSERT (9건):
- Page 1에 Record 1-9 기록 → Dirty Page 1개
→ 1개의 Dirty Page → 1번의 Flush I/O (9배 절약)
```

### 3.3 WAL (Write-Ahead Logging)의 성능

**Redo Log의 순차 쓰기 (Sequential Write):**

```
Disk I/O 유형별 성능 (HDD 기준):

Sequential Write (Redo Log):   100 MB/s
Random Write (Data File):       1 MB/s  (100배 느림!)
Random Read (Data File):        0.5 MB/s
```

**InnoDB의 Redo Log 구조:**

```
ib_logfile0 (512 MB)
┌────────────────────────────────────────────────────────┐
│  [Block 1] [Block 2] [Block 3] ... [Block N]          │
│   512 bytes  512 bytes  512 bytes                     │
│                                                        │
│  Write Pointer ─────────────────────────────────────>  │
│  (순차적으로 기록, 순환 사용)                           │
└────────────────────────────────────────────────────────┘

Checkpoint:
- Redo Log의 재사용을 위해 Old Data를 Disk에 Flush
- LSN (Log Sequence Number)로 진행 상황 추적
```

---

## 4. Alternative & Trade-off (비판: 왜 이 방법을 선택했는가?)

### 4.1 JDBC Batch vs JPA Batch

| 측정 항목 | JDBC Batch | JPA Batch (`spring.jpa.properties.hibernate.jdbc.batch_size`) |
|---------|-----------|---------------------------------------------------------------|
| **성능** | 최상 (Native SQL) | 좋음 (하지만 1차 캐시 플러시 오버헤드) |
| **편의성** | 낮음 (SQL 직접 작성) | 높음 (Entity 기반) |
| **유연성** | 높음 (Dynamic SQL 가능) | 낮음 (EntityManager 제약) |
| **복잡도** | 낮음 | 높음 (Flush, Clear 전략 필요) |

**선택 이유**: MapleExpectation은 성능 최적화를 위해 JDBC Batch 선택

**JPA Batch 설정 (참고):**

```properties
# application.yml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100  # 100건 단위로 배치
        order_inserts: true  # INSERT 순서 최적화
          order_updates: true  # UPDATE 순서 최적화
```

### 4.2 단건 트랜잭션 vs 배치 트랜잭션

**단건 트랜잭션 (Atomicity 보장 but 느림):**

```java
@Transactional
public void insertOne(Equipment eq) {
    equipmentRepository.insert(eq);
    outboxRepository.insert(OutboxEvent.of(eq));
    // 1 INSERT + 1 INSERT + COMMIT = 3 I/O
}
// 1,000건 = 1,000 × 3 I/O = 3,000 I/O
```

**배치 트랜잭션 (Fast but 위험):**

```java
@Transactional
public void insertBatch(List<Equipment> eqList) {
    for (Equipment eq : eqList) {
        equipmentRepository.insert(eq);
        outboxRepository.insert(OutboxEvent.of(eq));
    }
    // 2,000 INSERT + 1 COMMIT = 2,001 I/O (3,000 → 2,001, 33% 절약)
}
```

**Trade-off**:
- **장점**: I/O 33% 감소, Latency 50% 감소
- **단점**: 롤백 시 전체 배치 취소 (부분 성공 불가)

**해결책**: Chunk-based Transaction (Spring Batch)

```java
// 1,000건을 100건씩 10개의 트랜잭션으로 분리
public void insertAll(List<Equipment> all) {
    Lists.partition(all, 100).forEach(chunk -> {
        insertBatch(chunk);  // 100건씩 트랜잭션
    });
}
```

### 4.3 Outbox Pattern vs CDC (Change Data Capture)

| 측정 항목 | Outbox Pattern | CDC (Debezium) |
|---------|----------------|-----------------|
| **구현 복잡도** | 낮음 (Application 코드) | 높음 (Kafka Connect) |
| **Latency** | 높음 (Polling 주기) | 낮음 (Binlog 실시간) |
| **DB 부하** | 높음 (Outbox 테이블 조회) | 낮음 (Binlog만 읽음) |
| **운영 오버헤드** | 높음 (Worker 관리) | 낮음 (Debezium 자동화) |

**선택 이유**: MapleExpectation은 Spring Batch 기반 Outbox로 충분
- 이미 Spring Batch 사용 중 (DonaionScheduler)
- Redis Pub/Sub로 충분한 실시간성 (1초 이내)

---

## 5. The Interview Defense (방어: 100배 트래픽에서 어디가 먼저 터지는가?)

### 5.1 "트래픽이 100배 증가하면?"

**실패 포인트 예측:**

1. **Outbox Table Explosion** (最先)
   - 현재: 1,000 TPS → Outbox 100만건/일
   - 100배 트래픽: Outbox 1억건/일 → 디스크 Full
   - **해결**:
     - Outbox Archived Table로 이관 (Partitioning)
     - Processed At이 7일 지난 데이터 삭제

2. **Buffer Pool Saturation** (次点)
   - Dirty Page가 너무 많아 Flush 불가
   - **해결**: innodb_max_dirty_pages_pct=75 (기본값 낮춰)

3. **Redo Log Full**
   - 쓰기 속도 > Flush 속도 → Log Full → 전체 멈춤
   - **해결**: innodb_log_file_size=2GB (기본값 증설)

### 5.2 "배치 INSERT 중간에 장애 발생하면?"

**상황**: 1,000건 배치 INSERT 중 500건째에서 장애

**현재 시스템의 취약점:**

```java
@Transactional
public void insertBatch(List<Equipment> eqList) {
    for (Equipment eq : eqList) {
        equipmentRepository.insert(eq);  // 500건째에서 DB 연결 끊김
    }
    // ✅ 자동 ROLLBACK (트랜잭션 원자성)
}
```

**문제**: 500건 낭비, 재시도 시 처음부터 다시 시작

**개선안: Checkpoint + Resume**

```java
public void insertBatchWithCheckpoint(List<Equipment> all) {
    int lastSuccessIndex = 0;

    while (lastSuccessIndex < all.size()) {
        List<Equipment> chunk = all.subList(
            lastSuccessIndex,
            Math.min(lastSuccessIndex + 100, all.size())
        );

        try {
            insertBatch(chunk);  // 100건씩 트랜잭션
            lastSuccessIndex += 100;

            // Checkpoint 저장 (장애 복구용)
            checkpointRepository.save("equipment_batch", lastSuccessIndex);
        } catch (Exception e) {
            log.error("Failed at index {}, retrying...", lastSuccessIndex);
            // lastSuccessIndex 이후부터 재시도
        }
    }
}
```

### 5.3 "Outbox Worker가 느려서 메시지가 쌓이면?"

**상황**: Outbox 테이블에 1,000만건의 PENDING 메시지 적재

**현재 시스템의 취약점:**

```java
@Scheduled(fixedRate = 1000)  // 1초마다
public void processOutbox() {
    List<OutboxEvent> pending = outboxRepository.findPending(100);
    // 문제: 1초에 100건만 처리 → 1,000만건 처리에 27시간 소요
}
```

**개선안 1: Parallel Processing**

```java
@Scheduled(fixedRate = 1000)
public void processOutbox() {
    // Outbox를 10개의 Shard로 분리
    IntStream.range(0, 10).parallel().forEach(shardId -> {
        List<OutboxEvent> pending = outboxRepository.findPendingByShard(shardId, 100);
        pending.forEach(this::publishEvent);
    });
}
```

**개선안 2: Kafka로 전환 (대안)**

```java
// Outbox 제거, 직접 Kafka 발행
public void updateEquipment(Long id, Equipment data) {
    equipmentRepository.update(id, data);
    kafkaTemplate.send("equipment-updated", data);  // 비동기 발행
}
```

---

## 요약: 핵심 take-away

1. **배치 INSERT는 네트워크 왕복을 줄인다**: 9건 → 1건 (9배 빠름)
2. **TCP 패킷 크기(MSS)를 활용하라**: 1,460 bytes 안에 최대한 많이 담기
3. **Undo/Redo Log는 Crash Recovery의 보험**: Write-Ahead Logging
4. **Outbox Pattern은 분산 트랜잭션의 해결사**: DB + 메시징 큐의 일관성
5. **100배 트래픽 대비**: Partitioning, Parallel Worker, Kafka 전환

---

**다음 챕터 예고**: "@Async는 어떻게 스레드 풀을 관리하는가? ForkJoinPool의 Work-Stealing 알고리즘"
