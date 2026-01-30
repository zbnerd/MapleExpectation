# Graceful Shutdown P0/P1 리팩토링 리포트

> **PR**: #295
> **기간**: 2026-01-30
> **대상 모듈**: Graceful Shutdown (4단계 순차 종료)
> **작업자**: 5-Agent Council (Blue/Green/Yellow/Purple/Red)

---

## 1. 분석 요약

Graceful Shutdown 모듈 전체를 5-Agent Council이 독립 분석 후 교차 검증하여 **3건의 P0**과 **10건의 P1** 이슈를 확정했습니다.

### 분석 대상 파일 (13개)

| 파일 | 역할 |
|------|------|
| `GracefulShutdownCoordinator` | 4단계 순차 종료 조정자 |
| `ExpectationBatchShutdownHandler` | Expectation 버퍼 Drain 핸들러 |
| `ShutdownDataPersistenceService` | Shutdown 데이터 파일 영속화 |
| `ShutdownDataRecoveryService` | 복구 시 데이터 복원 |
| `EquipmentPersistenceTracker` | Equipment 인메모리 추적기 |
| `RedisEquipmentPersistenceTracker` | Equipment Redis 추적기 |
| `PersistenceTrackerStrategy` | Strategy 패턴 인터페이스 |
| `ExpectationWriteBackBuffer` | Write-Behind 버퍼 |
| `ExecutorConfig` | 스레드 풀 설정 |
| `LikeSyncService` | 좋아요 동기화 서비스 |
| `BufferProperties` | 버퍼 설정 레코드 |
| `ShutdownData` | 불변 DTO 레코드 |
| `FlushResult` | Flush 결과 DTO |

---

## 2. P0 이슈 (CRITICAL — 3건)

### P0-1: flushBatch() 실패 추적 부재 → Shutdown 데이터 유실 위험

**파일**: `ExpectationBatchShutdownHandler.java`
**에이전트**: Green (성능) + Red (SRE)

**문제**:
```java
// BEFORE: 실패 건수 추적 없음
for (ExpectationWriteTask task : batch) {
    repository.upsertExpectationSummary(...); // 실패 시 silent ignore
}
```

10,000건 × 5ms = 50s → `spring.lifecycle.timeout-per-shutdown-phase`(50s) 초과 시 나머지 데이터 유실. 실패한 항목의 추적이 불가능했습니다.

**수정**:
```java
// AFTER: 실패 건수 추적 + 메트릭 기록
int successCount = 0;
int failureCount = 0;
for (ExpectationWriteTask task : batch) {
    boolean success = executor.executeOrDefault(() -> {
        repository.upsertExpectationSummary(...);
        return true;
    }, false, TaskContext.of("ExpectationShutdown", "Upsert", task.key()));

    if (success) successCount++;
    else failureCount++;
}
drainSuccessCounter.increment(successCount);
if (failureCount > 0) drainFailureCounter.increment(failureCount);
```

**영향**: 데이터 유실 추적 가능, Grafana 알림 트리거 가능

---

### P0-2: saveShutdownData() 중첩 LogicExecutor (Section 15 위반)

**파일**: `ShutdownDataPersistenceService.java`
**에이전트**: Blue (아키텍트) + Purple (QA)

**문제**:
```java
// BEFORE: executor.execute() 안에서 executor.executeWithFinally() 중첩 호출
executor.execute(() -> {
    Path tempFile = Files.createTempFile(...);
    return executor.executeWithFinally(  // Section 15 위반: 중첩 실행
        () -> performAtomicWrite(...),
        () -> cleanupTempFile(tempFile),
        context);
}, context);
```

**수정**:
```java
// AFTER: 단일 executeWithTranslation으로 평탄화
return executor.executeWithTranslation(
    () -> performAtomicWrite(data, backupPath, targetFile),
    ExceptionTranslator.forFileIO(),
    context
);

// performAtomicWrite 내부에서 executeWithFinally 단독 사용
private Path performAtomicWrite(...) throws Exception {
    Path tempFile = Files.createTempFile(...);
    return executor.executeWithFinally(
        () -> { /* atomic write */ return targetFile; },
        () -> cleanupTempFile(tempFile),
        TaskContext.of("Persistence", "AtomicWrite")
    );
}
```

---

### P0-3: performAtomicWrite() IOException 직접 전파 (Section 11/12 위반)

**파일**: `ShutdownDataPersistenceService.java`
**에이전트**: Blue + Red

**문제**:
```java
// BEFORE: checked exception 직접 전파
private Path performAtomicWrite(...) throws IOException {
    Files.writeString(tempFile, json, ...);  // IOException 그대로 전파
}
```

**수정**: `executeWithTranslation(ExceptionTranslator.forFileIO())` 래핑으로 도메인 예외 변환

---

## 3. P1 이슈 (HIGH/MEDIUM — 10건)

| ID | 이슈 | 심각도 | 수정 내용 |
|----|------|--------|-----------|
| P1-1 | `@Value` 필드 주입 | HIGH | `ShutdownProperties` 생성자 주입 (Section 6) |
| P1-2 | Coordinator 타임아웃/락 하드코딩 | HIGH | `properties.getEquipmentAwaitTimeout()` 등 외부화 |
| P1-3 | BatchHandler 상수 하드코딩 | HIGH | `properties.getBatchSize()` 등 외부화 |
| P1-4 | `running=false` executeWithFinally 외부 | MEDIUM | finally 블록 내부로 이동 |
| P1-5 | `resolveInstanceId()` DNS 블로킹 | MEDIUM | `properties.getInstanceId()` 대체 |
| P1-6 | Shutdown 메트릭 미존재 | MEDIUM | Timer + Counter 추가 (4개 메트릭) |
| P1-7 | 미사용 `deleteFiles()` 메서드 | LOW | 제거 |
| P1-8 | GracefulShutdownIntegrationTest PersistenceTracker 불일치 | LOW | 기존 이슈 (이번 리팩토링 범위 외) |
| P1-9 | ConcurrentHashMap 불필요 사용 | LOW | HashMap 변경 (순차 처리) |
| P1-10 | flushBatch 실패 건수 미추적 | MEDIUM | P0-1 연계 수정 |

---

## 4. 수정 파일 요약

| 파일 | 구분 | 이슈 |
|------|------|------|
| `config/ShutdownProperties.java` | **신규** | P1-1, P1-2, P1-3, P1-5 |
| `application.yml` | 수정 | shutdown 블록 추가 |
| `GracefulShutdownCoordinator.java` | 리팩토링 | P1-2, P1-6 |
| `ExpectationBatchShutdownHandler.java` | 리팩토링 | P0-1, P1-3, P1-4, P1-6, P1-10 |
| `ShutdownDataPersistenceService.java` | 리팩토링 | P0-2, P0-3, P1-1, P1-5, P1-7 |
| `ShutdownDataRecoveryService.java` | 패치 | P1-9 |
| `ShutdownDataPersistenceServiceTest.java` | 수정 | 생성자 시그니처 반영 |

---

## 5. 5-Agent Council 교차 검증 결과

### Blue (아키텍트) — PASS

- ShutdownProperties `@ConfigurationProperties` + `@Validated` 패턴은 OutboxProperties/BufferProperties와 일관성 확보
- 생성자 주입 전환으로 Section 6 (DIP) 준수 확인
- Section 15 중첩 LogicExecutor 위반 해소 확인

### Green (성능) — PASS

- P0-1 flushBatch 실패 추적으로 데이터 유실 가시성 확보
- P1-9 HashMap 변경은 정당 (forEach → 순차, executeOrCatch → 동기)
- 메트릭 pre-registration 패턴 적용 (hot-path 할당 제거)

### Yellow (DBA) — PASS

- DB 동기화 lockWaitSeconds(3) / lockLeaseSeconds(10) 외부화로 운영 유연성 확보
- Scale-out 시 락 경합 완화 가능

### Purple (QA) — PASS

- ShutdownDataPersistenceServiceTest 생성자 업데이트 확인
- 단위 테스트 전체 통과 (40 tests, 39 passed, 1 pre-existing integration test failure)
- 기존 통합 테스트 실패는 PersistenceTracker 라우팅 불일치 (이번 변경 범위 외)

### Red (SRE) — PASS

- `executeWithFinally`로 `running=false` 보장 → Shutdown 완료 시 lifecycle 정합성 확보
- Timer/Counter 메트릭으로 Shutdown 모니터링 가능
- 인스턴스 식별자 외부화로 DNS 블로킹 제거 → Scale-out 안전

**결론: 5-Agent 만장일치 PASS**

---

## 6. 교차 검증에서 기각된 False Positive (2건)

| 제안 | 기각 사유 |
|------|-----------|
| Phase 순서 오류 (ExpectationBatch MAX_VALUE-500 > Coordinator MAX_VALUE-1000) | SmartLifecycle은 높은 Phase가 먼저 stop — 설계 의도대로 |
| stop(Runnable callback) 미구현 | SmartLifecycle 기본 구현이 stop() → stop(Runnable) 위임 처리 |

---

## 7. 메트릭 & 모니터링

### 새로 추가된 메트릭 (4건)

| 메트릭 | 타입 | 태그 | 설명 |
|--------|------|------|------|
| `shutdown.coordinator.duration` | Timer | — | Graceful Shutdown 총 소요 시간 |
| `shutdown.coordinator.result` | Counter | status={success,failure} | Shutdown 성공/실패 횟수 |
| `shutdown.buffer.drain.duration` | Timer | — | Expectation 버퍼 Drain 소요 시간 |
| `shutdown.buffer.drain.tasks` | Counter | status={success,failure} | Drain 성공/실패 건수 |

### Prometheus 쿼리

```promql
# Shutdown 소요 시간 (P99)
histogram_quantile(0.99, rate(shutdown_coordinator_duration_seconds_bucket[5m]))

# 버퍼 Drain 소요 시간
histogram_quantile(0.99, rate(shutdown_buffer_drain_duration_seconds_bucket[5m]))

# Drain 실패율
rate(shutdown_buffer_drain_tasks_total{status="failure"}[5m])
/ rate(shutdown_buffer_drain_tasks_total[5m])

# Shutdown 성공률
rate(shutdown_coordinator_result_total{status="success"}[5m])
/ rate(shutdown_coordinator_result_total[5m])
```

### Grafana 대시보드 패널

| Row | 패널 | 쿼리 |
|-----|------|------|
| 1 | Shutdown Duration (Heatmap) | `shutdown_coordinator_duration_seconds_bucket` |
| 2 | Buffer Drain Duration (Heatmap) | `shutdown_buffer_drain_duration_seconds_bucket` |
| 3 | Drain Success/Failure (Stacked Bar) | `shutdown_buffer_drain_tasks_total{status=~".+"}` |
| 4 | Coordinator Result (Pie Chart) | `shutdown_coordinator_result_total{status=~".+"}` |

### 개선 전/후 비교

| 항목 | 개선 전 | 개선 후 |
|------|---------|---------|
| **Shutdown 소요 시간 가시성** | 없음 (로그만 존재) | Timer 메트릭 + P99 대시보드 |
| **Drain 실패 추적** | 없음 (silent failure) | Counter 메트릭 + 알림 가능 |
| **설정 변경** | 코드 배포 필요 | YAML/환경변수 변경만으로 적용 |
| **Scale-out 안전성** | DNS 블로킹 위험 | `instanceId` 외부 주입 |
| **코드 품질 (Section 15)** | 중첩 LogicExecutor 존재 | 평탄화 완료 |
| **코드 품질 (Section 12)** | IOException 직접 전파 | ExceptionTranslator 래핑 |
| **finally 보장** | `running=false` 미보장 위험 | `executeWithFinally` 패턴 |

---

## 8. 외부 설정 (application.yml)

```yaml
# ========== Graceful Shutdown 설정 (P1-1, P1-2, P1-3, P1-5) ==========
shutdown:
  equipment-await-timeout: 20s     # Phase 1: Equipment 비동기 저장 대기 (P1-2)
  lock-wait-seconds: 3             # Phase 3: 분산 락 대기 (P1-2)
  lock-lease-seconds: 10           # Phase 3: 분산 락 점유 (P1-2)
  batch-size: 200                  # Drain 배치 크기 (P1-3)
  empty-batch-retry-count: 3       # 빈 배치 재시도 (P1-3)
  empty-batch-wait-ms: 100         # 빈 배치 대기 ms (P1-3)
  backup-directory: /tmp/maple-shutdown           # 백업 디렉토리 (P1-1)
  archive-directory: /tmp/maple-shutdown/processed # 아카이브 디렉토리 (P1-1)
  instance-id: ${app.instance-id}  # 인스턴스 ID (P1-5)
```

---

## 9. 빌드 & 테스트 결과

```
BUILD SUCCESSFUL in 3m 50s
40 tests completed, 39 passed
1 failed (GracefulShutdownIntegrationTest - pre-existing PersistenceTracker routing issue)
```

기존 통합 테스트 실패는 `EquipmentPersistenceTracker`(인메모리)와 `PersistenceTrackerStrategy`(Redis) 간 라우팅 불일치로 인한 기존 결함이며, 이번 리팩토링 범위 외입니다.

---

## 10. 아키텍처 다이어그램

```
[Shutdown 시작]
    │
    ├── ExpectationBatchShutdownHandler (Phase: MAX_VALUE - 500) ← 먼저 실행
    │   ├── Phase 1: buffer.prepareShutdown() (신규 offer 차단)
    │   ├── Phase 2: buffer.awaitPendingOffers() (진행 중 완료 대기)
    │   └── Phase 3: drainBuffer() → flushBatch() [P0-1: 실패 추적 + 메트릭]
    │
    └── GracefulShutdownCoordinator (Phase: MAX_VALUE - 1000) ← 나중 실행
        ├── [1/4] waitForEquipmentPersistence() [P1-2: 타임아웃 외부화]
        ├── [2/4] flushLikeBuffer()
        ├── [3/4] syncRedisToDatabase() [P1-2: 락 설정 외부화]
        └── [4/4] saveShutdownData() [P0-2, P0-3: 중첩 제거 + 예외 변환]
                    └── ShutdownDataPersistenceService [P1-1, P1-5: Properties 주입]
```

---

*Generated by 5-Agent Council — 2026-01-30*
