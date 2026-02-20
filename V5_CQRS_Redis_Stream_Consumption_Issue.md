# V5 CQRS: Redis Stream 메시지 소비 문제

## 📋 문제 개요

Redis Stream에 발행된 메시지를 MongoDBSyncWorker가 소비하지 못하여 MongoDB에 데이터가写入되지 않음

## 🔍 증상

### 관찰된 현상

- ✅ **Redis Stream**: 메시지 정상 발행됨 (4개 확인)
- ✅ **Consumer Group 상태**: `entries-read: 4`, `lag: 0` (메시지 "전달됨" 표시)
- ❌ **Worker 로그**: `readGroup returned messages=Map(size=0)` - 항상 빈 결과
- ❌ **MongoDB**: `db.character_valuation_views.count() = 0` - 데이터 없음

### Worker 로그 예시

```log
[V5-MongoDBSyncWorker] DEBUG: readGroup returned messages=Map(size=0), isEmpty=true
[V5-MongoDBSyncWorker] DEBUG: messages empty/null, returning
```

**기대했지만 나오지 않는 로그:**
- `Processing message: 177158XXXX-0` 
- `Synced to MongoDB: userIgn=아델, ocid=...`
- `deserializeAndSync` 관련 로그

## 🐛 원인 분석

### 1. Redisson `readGroup(neverDelivered())` 의미론

```java
// MongoDBSyncWorker.java:207
stream.readGroup(
    CONSUMER_GROUP,
    CONSUMER_NAME,
    StreamReadGroupArgs.neverDelivered().count(1).timeout(POLL_TIMEOUT)
);
```

**문제점:**
- `neverDelivered()`는 "이 Consumer Group에 **아직 전달되지 않은** 메시지"만 반환
- **Consumer Group이 생성된 시점에 이미 Stream에 있던 메시지는 자동으로 "전달됨" 처리됨**
- 따라서 Worker가 시작된 **이후에 발행된 새 메시지만** 읽을 수 있음

### 2. Consumer Group 초기화 타이밍

```java
// MongoDBSyncWorker.java:204
stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP).makeStream());
```

**문제점:**
- `makeStream()` 플래그는 기존 Stream이 있을 때 예상치 않은 동작을 할 수 있음
- Redisson이 Group을 생성하면서 `last-delivered-id`를 Stream의 현재 위치로 설정
- 이전 메시지들은 "이미 전달됨"으로 표시되어 `neverDelivered()`로 읽히지 않음

### 3. Redis Stream Consumer Group 상태 의미

```
# XINFO GROUPS character-sync
name: mongodb-sync-group
consumers: 1
pending: 0
last-delivered-id: 1771584558437-0
entries-read: 4
lag: 0
```

**해석:**
- `entries-read: 4` = Consumer Group이 4개 메시지를 "전달받음" (표시만)
- `lag: 0` = 처리되지 않은 메시지 없음
- **중요**: 이 상태는 "메시지가 전달되었다는 표시"일 뿐, **실제로 Worker가 받아서 처리했다는 뜻이 아님**

### 4. Redisson 내부 동작 추정

가능한 시나리오:
1. `readGroup()` 호출 시 Redis에서 메시지를 가져옴
2. Consumer Group의 `last-delivered-id`를 업데이트 (`entries-read` 증가)
3. **하지만 반환값이 Java Map으로 변환되는 과정에서 문제 발생**
   - Codec 불일치? (하지만 StringCodec 확인됨)
   - Timeout 동작으로 빈 결과 반환?
   - 메시지 형식 불일치?

## 💡 제안 해결 방안

### 해결책 1: Stream 초기화 전략 개선 (권장)

**목표:** 기존 Stream과 Consumer Group을 정리하고 깨끗한 상태로 시작

```java
private void initializeStream() {
    RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);
    
    if (!stream.isExists()) {
        // 새 Stream + 새 Group 생성
        stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP).makeStream());
        log.info("[MongoDBSyncWorker] Created new stream and consumer group");
        return;
    }
    
    // 기존 Stream이 있으면 복구 로직
    log.warn("[MongoDBSyncWorker] Stream already exists with {} messages", stream.size());
    
    try {
        // 옵션 1: Stream 전체 삭제 (개발 환경)
        stream.delete();
        stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP).makeStream());
        log.info("[MongoDBSyncWorker] Reset stream and created new consumer group");
        
    } catch (Exception e) {
        log.error("[MongoDBSyncWorker] Failed to reset stream", e);
        // 옵션 2: 기존 Stream 유지 (프로덕션 환경)
        // 새 메시지만 소비하도록 설정
    }
}
```

**장점:**
- 깨끗한 상태로 시작하여 타이밍 문제 해결
- 개발 환경에서 재현 가능한 일관된 동작

**단점:**
- 기존 메시지 손실 (개발 환경에서는 허용 가능)

### 해결책 2: 명시적 ID 지정 (복구 시나리오)

```java
// ID "0"부터 명시적으로 읽기 (기존 메시지 포함)
Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
    CONSUMER_GROUP,
    CONSUMER_NAME,
    StreamReadGroupArgs.get(StreamMessageId.ALL).count(10)
);
```

**주의사항:**
- 이미 처리된 메시지도 다시 읽힘 → **중복 처리 위험**
- 멱등성(idempotency)이 보장된 경우에만 사용

### 해결책 3: Redis CLI 직접 테스트 (진단)

```bash
# Redis CLI로 직접 읽어서 Redisson 문제인지 확인
docker exec redis-master redis-cli XREADGROUP \
  GROUP mongodb-sync-group mongodb-sync-worker \
  COUNT 1 BLOCK 2000 STREAMS character-sync >
```

## 🧪 테스트 시나리오

### 테스트 1: 깨끗한 상태에서 시작 (검증)

```bash
# 1. Stream 삭제 (깨끗한 상태)
docker exec redis-master redis-cli DEL character-sync

# 2. 애플리케이션 재시작
./gradlew bootRun

# 3. API 호출 (새 메시지 발행)
curl "http://localhost:8080/api/v5/characters/아델/expectation"

# 4. Worker 로그 확인
tail -f logs/application.log | grep "MongoDBSyncWorker.*Processing"

# 5. MongoDB 확인
docker exec maple-mongodb mongosh maple_expectation \
  --eval 'db.character_valuation_views.count({"userIgn": "아델"})'
```

**기대 결과:**
```
[MongoDBSyncWorker] Processing message: 177158XXXX-0
[MongoDBSyncWorker] Synced to MongoDB: userIgn=아델, ocid=...
MongoDB count: 1
```

### 테스트 2: 기존 메시지 복구

```bash
# 1. 기존 Stream 유지 (메시지 있음)
# 2. Consumer Group 재생성 (ID "0"부터)
docker exec redis-master redis-cli XGROUP DESTROY character-sync mongodb-sync-group
docker exec redis-master redis-cli XGROUP CREATE character-sync mongodb-sync-group 0

# 3. 애플리케이션 재시작
./gradlew bootRun

# 4. 기존 메시지 소비 확인
# 5. MongoDB에 기존 메시지 데이터가写入되는지 확인
```

## 🔬 추가 조사 항목

### 1. Redisson 버전 확인
- 현재: Redisson 3.48.0
- Stream 관련 known issue 있는지 확인 필요
- [Redisson GitHub Issues](https://github.com/redisson/redisson/issues)

### 2. Codec 설정 검증
```java
// Publisher (MongoSyncEventPublisher.java:73)
RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);

// Consumer (MongoDBSyncWorker.java:203)
RStream<String, String> stream = redissonClient.getStream(STREAM_KEY, StringCodec.INSTANCE);
```
- ✅ 둘 다 `StringCodec.INSTANCE` 사용
- 키 깨짐 문제는 해결됨

### 3. 메시지 형식 검증
```bash
# Stream 메시지 직접 확인
docker exec redis-master redis-cli XRANGE character-sync - + COUNT 1
```

**기대 형식:**
```
1771584081254-0
data
{"taskId":"...","userIgn":"아델",...}
```

### 4. Redisson 버그 가능성
- `readGroup()` 반환값이 Java Map으로 변환되는 과정에서 문제
- Timeout 시 빈 Map 반환하는 동작
- Workaround로 Redis CLI 직접 사용 고려

## 📁 관련 파일

- `module-app/src/main/java/maple/expectation/service/v5/worker/MongoDBSyncWorker.java`
  - Line 151-216: `initializeStream()` 메서드
  - Line 197-235: `processNextBatch()` 메서드
  - Line 258-276: `processMessage()` 메서드
  - Line 278-301: `deserializeAndSync()` 메서드

- `module-app/src/main/java/maple/expectation/service/v5/event/MongoSyncEventPublisher.java`
  - Line 73: RStream 초기화 (Codec 확인)

## 📊 영향도

### 우선순위
- **P0**: Worker가 메시지를 소비하지 못함 (핵심 기능 작동 안 함)

### 비즈니스 영향
- V5 CQRS Query Side가 MongoDB 데이터 없이 **캐시만 의존**
- 캐시 미스 시 매번 실시간 계산 (성능 저하)
- CQRS 패턴의 목적 저하 (Read Side 독립성 실패)

### 사용자 경험
- 첫 요청: 202 Accepted → 백그라운드 계산
- 두번째 요청: 여전히 202 (MongoDB 없으므로 캐시 미스)
- **결과: 무한 루프 또는 지속적인 재계산**

## 📚 참고 자료

- [Redis Streams - XREADGROUP](https://redis.io/commands/xreadgroup/)
- [Redisson RStream Documentation](https://github.com/redisson/redisson/wiki/6.-Redis-Streams/)
- [ADR-079: V5 CQRS Architecture](../docs/01_ADR/ADR-079-v5-cqrs-architecture.md)
- [Redis Streams Best Practices](https://redis.io/topics/streams-intro/)

## 🏷️ 라벨

`bug` `p0` `v5-cqrs` `redis-stream` `mongodb` `critical`

