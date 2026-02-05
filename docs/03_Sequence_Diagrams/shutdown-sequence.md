# Graceful Shutdown 시퀀스 다이어그램

> **Last Updated:** 2026-02-05
> **Code Version:** MapleExpectation v1.x
> **Diagram Version:** 1.0

## 개요

4단계 순차 종료로 진행 중인 작업과 데이터를 안전하게 보존합니다.

## Terminology

| 용어 | 정의 |
|------|------|
| **SmartLifecycle** | Spring 생명주기 콜백 인터페이스 |
| **Graceful Shutdown** | 진행 중 작업 완료 후 종료 |
| **DLQ** | Dead Letter Queue (최후 안전망) |
| **Compensation Command** | 실패 시 복구 명령 |

## 전체 종료 시퀀스

```mermaid
sequenceDiagram
    participant OS as OS (SIGTERM)
    participant SL as SmartLifecycle
    participant GS as GracefulShutdownCoordinator
    participant EX as Executor
    participant BF as LikeBufferStorage
    participant CA as TieredCache
    participant DB as MySQL
    participant RD as Redis

    OS->>SL: SIGTERM
    SL->>GS: stop(callback)

    rect rgb(255, 240, 240)
        Note over GS: Phase 1: 새 요청 거부 (즉시)
        GS->>EX: shutdown()
        Note over EX: 새 작업 제출 거부
    end

    rect rgb(240, 255, 240)
        Note over GS: Phase 2: 진행 중 작업 완료 대기 (20초)
        GS->>EX: awaitTermination(20, SECONDS)
        EX-->>GS: 완료 또는 타임아웃
    end

    rect rgb(240, 240, 255)
        Note over GS: Phase 3: 캐시/버퍼 플러시 (10초)
        GS->>BF: flushAllToDb()
        BF->>DB: batchInsert(likes)
        DB-->>BF: OK
        GS->>CA: flushToRedis()
        CA->>RD: MSET(...)
        RD-->>CA: OK
    end

    rect rgb(255, 255, 240)
        Note over GS: Phase 4: 커넥션 종료 (10초)
        GS->>DB: close()
        GS->>RD: shutdown()
    end

    GS->>SL: callback.run()
    Note over SL: 총 타임아웃: 50초
```

## Phase별 상세

### Phase 1: 새 요청 거부

```java
@Override
public void stop(Runnable callback) {
    // 즉시 실행
    executorService.shutdown();
    log.info("[Shutdown] Phase 1: Executor 새 작업 제출 중단");
    ...
}
```

### Phase 2: 진행 중 작업 완료 대기

```java
boolean terminated = executorService.awaitTermination(20, TimeUnit.SECONDS);
if (!terminated) {
    log.warn("[Shutdown] Phase 2: 일부 작업 강제 종료");
    executorService.shutdownNow();
}
```

### Phase 3: 캐시/버퍼 플러시

```mermaid
sequenceDiagram
    participant GS as Coordinator
    participant BF as LikeBufferStorage
    participant AF as AtomicFetchStrategy
    participant DB as MySQL
    participant RD as Redis

    GS->>BF: flushAllToDb()
    BF->>AF: fetchAndMove(sourceKey, tempKey)
    Note over AF: Lua Script 원자적 이동

    AF->>RD: EVAL(lua_script, keys, args)
    RD-->>AF: entries[]

    BF->>DB: batchInsert(entries)

    alt 성공
        DB-->>BF: OK
        BF->>AF: commit()
        AF->>RD: DEL tempKey
    else 실패
        DB--xBF: Exception
        BF->>AF: compensate()
        AF->>RD: RENAME tempKey sourceKey
        Note over BF: DLQ 이벤트 발행
    end
```

### Phase 4: 커넥션 종료

```java
hikariDataSource.close();
redissonClient.shutdown();
log.info("[Shutdown] Phase 4: 모든 커넥션 종료 완료");
```

## DLQ (Dead Letter Queue) 처리

```mermaid
sequenceDiagram
    participant CC as CompensationCommand
    participant EP as EventPublisher
    participant LN as LikeSyncEventListener
    participant FS as FileSystem
    participant DC as Discord

    CC->>CC: compensate()

    alt 복구 실패
        CC--xCC: Exception
        CC->>EP: publishEvent(LikeSyncFailedEvent)
        EP->>LN: handleSyncFailure(event)

        LN->>FS: appendLikeEntry(userIgn, count)
        Note over FS: 파일 백업 (최우선)

        LN->>LN: counter("like.sync.dlq").increment()
        Note over LN: 메트릭 기록

        LN->>DC: sendCriticalAlert("DLQ 발생", message)
        Note over DC: 운영팀 알림
    end
```

## 파일 백업 구조

```
backup/
├── shutdown-{serverId}/
│   ├── likes-{timestamp}.json
│   └── equipment-{timestamp}.json
```

## SmartLifecycle 설정

```java
@Override
public int getPhase() {
    return Integer.MAX_VALUE;  // 가장 마지막에 종료
}

@Override
public boolean isAutoStartup() {
    return true;
}

// 총 타임아웃: 50초
private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(50);
```

## 관련 파일

- `src/main/java/maple/expectation/global/shutdown/GracefulShutdownCoordinator.java`
- `src/main/java/maple/expectation/service/v2/shutdown/ShutdownDataPersistenceService.java`
- `src/main/java/maple/expectation/service/v2/shutdown/ShutdownDataRecoveryService.java`

## Fail If Wrong

이 다이어그램이 부정확한 경우:
- **데이터 유실 발생**: Shutdown 시 flush 동작 확인
- **종료 타임아웃**: lifecycle.timeout-per-shutdown-phase 설정 확인
- **DLQ 미동작**: LikeSyncEventListener 확인

### Verification Commands
```bash
# Graceful Shutdown Coordinator 확인
find src/main/java -name "*ShutdownCoordinator.java"

# SmartLifecycle getPhase 확인
grep -A 5 "getPhase" src/main/java/maple/expectation/global/shutdown/

# shutdown 타임아웃 설정 확인
grep "timeout-per-shutdown-phase" src/main/resources/application.yml
```
