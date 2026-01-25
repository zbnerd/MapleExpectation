# ADR-008: Durability 및 Graceful Shutdown 전략

## 상태
Accepted

## 맥락 (Context)

JVM 종료 시 진행 중인 작업과 버퍼 데이터가 유실되는 문제가 발생했습니다:

**관찰된 문제:**
- 배포/재시작 시 Equipment 저장 작업 중단 → 데이터 유실
- Like 버퍼 Redis 동기화 미완료 → 좋아요 수 불일치
- Write-Behind 버퍼 미플러시 → 기대값 계산 결과 유실

**README 정의:**
> Phase 1: 새 요청 거부 → Phase 2: 진행 중 작업 완료 대기 (30s) → Phase 3: 버퍼 플러시 → Phase 4: 리소스 해제

**부하테스트 결과 (#266):**
- Write-Behind 버퍼 도입으로 DB 저장 150ms → 0.1ms (1,500x 개선)
- Graceful Shutdown으로 버퍼 데이터 100% 보존

## 검토한 대안 (Options Considered)

### 옵션 A: @PreDestroy 단순 사용
```java
@PreDestroy
public void cleanup() {
    buffer.flush();
}
```
- 장점: 구현 간단
- 단점: 순서 보장 없음, 타임아웃 제어 불가
- **결론: 신뢰성 부족**

### 옵션 B: ApplicationListener<ContextClosedEvent>
```java
@EventListener(ContextClosedEvent.class)
public void onShutdown() { ... }
```
- 장점: 이벤트 기반
- 단점: 순서 제어 어려움, 비동기 작업 대기 불가
- **결론: 복잡한 종료 로직에 부적합**

### 옵션 C: SmartLifecycle 인터페이스
- 장점: phase로 순서 제어, isRunning() 상태 관리, 동기/비동기 stop() 지원
- 단점: 구현 복잡도
- **결론: 채택**

## 결정 (Decision)

**SmartLifecycle 기반 4단계 순차 종료 + 컴포넌트별 Phase 분리를 적용합니다.**

### 4단계 순차 종료 흐름
```
Phase 1: 새 요청 거부 (Admission Control)
    ↓
Phase 2: 진행 중 작업 완료 대기 (30s)
    ↓
Phase 3: 버퍼 플러시 (Like Buffer → Redis → DB)
    ↓
Phase 4: 리소스 해제 (Connection Pool, Redis)
```

### GracefulShutdownCoordinator (총괄)
```java
// maple.expectation.global.shutdown.GracefulShutdownCoordinator
@Component
public class GracefulShutdownCoordinator implements SmartLifecycle {

    @Override
    public void stop() {
        executor.executeWithFinally(
            () -> {
                log.warn("========= [System Shutdown] 종료 절차 시작 =========");

                // 1. Equipment 비동기 저장 작업 완료 대기
                ShutdownData backupData = waitForEquipmentPersistence();

                // 2. 로컬 좋아요 버퍼 Flush
                backupData = flushLikeBuffer(backupData);

                // 3. 리더 서버인 경우 DB 최종 동기화
                syncRedisToDatabase();

                // 4. 백업 데이터 최종 저장
                if (backupData != null && !backupData.isEmpty()) {
                    shutdownDataPersistenceService.saveShutdownData(backupData);
                }
                return null;
            },
            () -> {
                this.running = false;
                log.warn("========= [System Shutdown] 종료 완료 =========");
            },
            TaskContext.of("Shutdown", "MainProcess")
        );
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;  // 다른 빈보다 늦게 종료
    }
}
```

### Write-Behind 버퍼 Shutdown Handler (#266)
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

### Phase 순서 정의
```
Phase                          | Value              | 역할
-------------------------------|--------------------|-----------------------
ExpectationBatchShutdownHandler| MAX - 500          | Write-Behind 버퍼 drain
GracefulShutdownCoordinator    | MAX - 1000         | Like 버퍼 + DB 동기화
Spring 기본 빈                  | 0 (default)        | 일반 빈 정리
```

### Spring Boot Graceful Shutdown 설정
```yaml
# application.yml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 50s  # Equipment 대기 20s + Redis sync + 여유

server:
  shutdown: graceful
```

### Equipment 비동기 저장 대기
```java
private ShutdownData waitForEquipmentPersistence() {
    return executor.execute(() -> {
        log.info("▶️ [1/4] Equipment 비동기 저장 작업 완료 대기 중...");
        boolean allCompleted = equipmentPersistenceTracker.awaitAllCompletion(Duration.ofSeconds(20));

        if (!allCompleted) {
            List<String> pendingOcids = equipmentPersistenceTracker.getPendingOcids();
            log.warn("⚠️ Equipment 저장 미완료 항목: {}건", pendingOcids.size());
            return new ShutdownData(LocalDateTime.now(), instanceId, Map.of(), pendingOcids);
        }

        log.info("✅ 모든 Equipment 저장 작업 완료.");
        return ShutdownData.empty(instanceId);
    }, TaskContext.of("Shutdown", "WaitEquipment"));
}
```

### 백업 데이터 영속화
```java
// 종료 시 미완료 데이터를 파일로 백업
@Value("${app.shutdown.backup-directory:/tmp/maple-shutdown}")
private String backupDirectory;

public void saveShutdownData(ShutdownData data) {
    Path file = Paths.get(backupDirectory, "shutdown_" + instanceId + ".json");
    objectMapper.writeValue(file.toFile(), data);
}
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| 종료 시 데이터 유실 | 발생 | **0건** |
| 버퍼 플러시 보장 | 불확실 | **100%** |
| 종료 순서 제어 | 없음 | **Phase 기반** |
| 미완료 작업 추적 | 불가 | **가시화** |

**부하테스트 효과 (#266):**
- Write-Behind 버퍼 10,000건 용량
- Graceful Shutdown 시 100% drain 보장

## 참고 자료
- `maple.expectation.global.shutdown.GracefulShutdownCoordinator`
- `maple.expectation.service.v4.ExpectationBatchShutdownHandler`
- `maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker`
- `maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService`
- `docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md`
