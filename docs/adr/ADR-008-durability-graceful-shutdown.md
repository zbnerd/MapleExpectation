# ADR-008: Durability 및 Graceful Shutdown 전략

## 상태
Accepted

## 문서 무결성 체크리스트
✅ All 30 items verified (Date: 2025-12-05, Issue: #266)

---

## Fail If Wrong
1. **[F1]** Shutdown 시 버퍼 데이터 유실 > 0건
2. **[F2]** Phase 순서 위반으로 DB 커넥션 먼저 해제
3. **[F3]** Rolling Update 시 미동기화 데이터로 불일치
4. **[F4]** SmartLifecycle.stop() 타임아웃 발생

---

## Terminology
| 용어 | 정의 |
|------|------|
| **Graceful Shutdown** | 진행 중 작업 완료 후 안전 종료 |
| **SmartLifecycle** | Spring에서 phase로 순서 제어하는 생명주기 인터페이스 |
| **Write-Behind Buffer** | 비동기 버퍼에 쌓았다가 배치로 DB 저장 |
| **Phase** | Spring 빈 종료 순서 (낮을수록 먼저 종료) |

---

## 맥락 (Context)
### 문제 정의
JVM 종료 시 진행 중인 작업과 버퍼 데이터가 유실되는 문제:
- 배포/재시작 시 Equipment 저장 작업 중단 → 데이터 유실 [E1]
- Like 버퍼 Redis 동기화 미완료 → 좋아요 수 불일치 [E2]
- Write-Behind 버퍼 미플러시 → 기대값 계산 결과 유실 [E3]

**부하테스트 결과 (#266):**
- Write-Behind 버퍼 도입으로 DB 저장 150ms → 0.1ms (1,500x 개선) [P1]
- Graceful Shutdown으로 버퍼 데이터 100% 보존 [P2]

---

## 대안 분석
### 옵션 A: @PreDestroy 단순 사용
- **장점:** 구현 간단
- **단점:** 순서 보장 없음, 타임아웃 제어 불가
- **거절:** [R1] @PreDestroy 실행 순서가 비결정적 (테스트: 2025-12-03)
- **결론:** 신뢰성 부족 (기각)

### 옵션 B: ApplicationListener<ContextClosedEvent>
- **장점:** 이벤트 기반
- **단점:** 순서 제어 어려움, 비동기 작업 대기 불가
- **거절:** [R2] 비동기 작업 완료 대기 불가 (테스트: 2025-12-04)
- **결론:** 복잡한 종료 로직에 부적합 (기각)

### 옵션 C: SmartLifecycle 인터페이스
- **장점:** phase로 순서 제어, isRunning() 상태 관리, 동기/비동기 stop() 지원
- **단점:** 구현 복잡도
- **채택:** [C1] Phase 기반 4단계 종료로 100% 데이터 보존
- **결론:** 채택

---

## 결정 (Decision)
**SmartLifecycle 기반 4단계 순차 종료 + 컴포넌트별 Phase 분리를 적용합니다.**

### Code Evidence

**[C1] GracefulShutdownCoordinator**
```java
// src/main/java/maple/expectation/global/shutdown/GracefulShutdownCoordinator.java
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
```

**[C2] Write-Behind 버퍼 Shutdown Handler**
```java
// src/main/java/maple/expectation/service/v4/ExpectationBatchShutdownHandler.java
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
```

**[C3] Phase 순서 정의**
```
Phase                          | Value              | 역할
-------------------------------|--------------------|-----------------------
ExpectationBatchShutdownHandler| MAX - 500          | Write-Behind 버퍼 drain
GracefulShutdownCoordinator    | MAX - 1000         | Like 버퍼 + DB 동기화
Spring 기본 빈                  | 0 (default)        | 일반 빈 정리
```

---

## 결과
| 지표 | Before | After | Evidence ID |
|------|--------|-------|-------------|
| 종료 시 데이터 유실 | 발생 | **0건** | [E1] |
| 버퍼 플러시 보장 | 불확실 | **100%** | [E2] |
| 종료 순서 제어 | 없음 | **Phase 기반** | [E3] |
| 미완료 작업 추적 | 불가 | **가시화** | [E4] |

**Evidence IDs:**
- [E1] 테스트: GracefulShutdownTest 통과
- [E2] 부하테스트: #266에서 10,000건 드레인 확인
- [E3] 로그: Phase 순서대로 실행됨
- [E4] 메트릭: ShutdownData에 미완료 항목 기록

---
