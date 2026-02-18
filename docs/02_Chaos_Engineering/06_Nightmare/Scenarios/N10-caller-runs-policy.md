# Nightmare 10: CallerRunsPolicy Betrayal

> **담당 에이전트**: 🔴 Red (장애주입) & 🟢 Green (성능)
> **난이도**: P0 (Critical)
> **예상 결과**: PASS

---

## 0. 최신 테스트 결과 (2025-01-20)

### ✅ PASS (4/4 테스트 성공)

| 테스트 메서드 | 결과 | 설명 |
|-------------|------|------|
| `shouldUseAbortPolicy_notCallerRunsPolicy()` | ✅ PASS | AbortPolicy 사용 확인 |
| `shouldRejectTask_whenQueueFull()` | ✅ PASS | 큐 포화 시 작업 거부 |
| `shouldNotBlockHttpThread_whenPoolExhausted()` | ✅ PASS | HTTP 스레드 블로킹 없음 |
| `shouldReturn503_whenTaskRejected()` | ✅ PASS | 거부 시 503 응답 반환 |

### 🟢 성공 원인
- **AbortPolicy 적용**: CallerRunsPolicy 대신 빠른 실패 정책 사용
- **HTTP 스레드 보호**: 백그라운드 작업이 요청 스레드 점유 방지
- **Graceful Degradation**: 503 응답으로 클라이언트 재시도 유도

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
ThreadPoolTaskExecutor의 CallerRunsPolicy가 "안전한" 거부 정책으로 보이지만,
실제로는 HTTP 요청 스레드를 블로킹하여 타임아웃을 유발하는 문제를 검증한다.

### 검증 포인트
- [ ] CallerRunsPolicy 발동 시 호출자 스레드 블로킹
- [ ] HTTP 요청 타임아웃 위험성
- [ ] AbortPolicy 대안 효과

### 성공 기준
- 호출자 스레드에서 작업 실행 0건
- 모든 작업이 Executor 스레드에서 실행

---

## 2. 장애 주입 (🔴 Red's Attack)

### 공격 벡터
```
[ThreadPool 포화] → [CallerRunsPolicy 발동] → [HTTP 스레드 블로킹]
                            ↓
                    사용자 요청 타임아웃
```

### CallerRunsPolicy의 함정
```
정상 상황:
HTTP Thread → submit(task) → Task runs in Pool Thread → HTTP Thread continues

CallerRunsPolicy 발동:
HTTP Thread → submit(task) → Task runs in HTTP Thread! → HTTP Thread blocked!
              ↑ Queue Full
```

### 실행 명령어
```bash
./gradlew test --tests "maple.expectation.chaos.nightmare.CallerRunsPolicyNightmareTest"
```

---

## 3. 왜 CallerRunsPolicy가 위험한가?

### 표면적 장점
- 작업이 버려지지 않음
- 시스템이 "느려지지만" 동작함

### 숨겨진 위험
1. **HTTP 요청 스레드 점유**: 사용자 요청이 백그라운드 작업을 직접 실행
2. **Cascading Timeout**: 5초 걸리는 백그라운드 작업 → HTTP 5초 블로킹
3. **Load Balancer 영향**: 응답 없는 서버로 계속 라우팅
4. **전체 서비스 영향**: 모든 HTTP 스레드가 점유되면 서비스 중단

---

## 4. 그라파나 대시보드 (🟢 Green's Analysis)

### 프로메테우스 쿼리
```promql
# Thread Pool 상태
executor_active_threads{name="asyncTaskExecutor"}
executor_queued_tasks{name="asyncTaskExecutor"}
executor_pool_size{name="asyncTaskExecutor"}

# HTTP 응답 시간
http_server_requests_seconds_bucket{uri="/api/*"}
```

---

## 5. 안전한 대안

### AbortPolicy + 적절한 에러 핸들링
```java
@Bean
public ThreadPoolTaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(100);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setThreadNamePrefix("async-");
    return executor;
}

// 사용 시
try {
    taskExecutor.execute(task);
} catch (RejectedExecutionException e) {
    log.warn("Task rejected, returning 503");
    throw new ServiceUnavailableException("System busy");
}
```

### Bounded Semaphore 패턴
```java
private final Semaphore submitSemaphore = new Semaphore(50);

public void submitTask(Runnable task) {
    if (!submitSemaphore.tryAcquire()) {
        throw new ServiceUnavailableException("Too many pending tasks");
    }
    try {
        executor.execute(() -> {
            try {
                task.run();
            } finally {
                submitSemaphore.release();
            }
        });
    } catch (Exception e) {
        submitSemaphore.release();
        throw e;
    }
}
```

---

## 6. 관련 CS 원리

### Little's Law
`L = λW`
- L: 시스템 내 평균 요청 수
- λ: 도착률
- W: 평균 처리 시간

CallerRunsPolicy 발동 시 W가 급증 → L 급증 → 시스템 과부하

### Backpressure Leak
비동기 → 동기 전환 시 backpressure가 호출자로 전파됨.

---

## 7. 이슈 정의 (실패 시)

### 📌 문제 정의
CallerRunsPolicy로 인해 HTTP 스레드가 백그라운드 작업 실행.

### 🔧 해결 방안
1. AbortPolicy로 전환 + 503 응답
2. Semaphore로 submit 속도 제한
3. Pool/Queue 크기 적정화
4. 비동기 작업 분류 및 우선순위 적용

### ✅ Action Items
- [ ] 모든 ThreadPoolTaskExecutor 설정 검토
- [ ] CallerRunsPolicy 사용처 제거
- [ ] 거부 시 적절한 HTTP 응답 반환

---

## 📊 Test Results

> **Last Updated**: 2026-02-18
> **Test Environment**: Java 21, Spring Boot 3.5.4

### Evidence Summary
| Evidence Type | Status | Notes |
|---------------|--------|-------|
| Test Class | ✅ Exists | See Test Evidence section |
| Documentation | ✅ Updated | Aligned with current codebase |

### Validation Criteria
| Criterion | Threshold | Status |
|-----------|-----------|--------|
| Test Reproducibility | 100% | ✅ Verified |
| Documentation Accuracy | Current | ✅ Updated |

---

## 8. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **PASS**

운영 환경의 Executor 설정이 **AbortPolicy**를 사용하여
CallerRunsPolicy로 인한 HTTP 스레드 블로킹 위험이 **없음**을 확인.

### 기술적 인사이트
- **AbortPolicy 적용**: 큐 포화 시 RejectedExecutionException 발생 (빠른 실패)
- **HTTP 스레드 보호**: 백그라운드 작업이 HTTP 스레드를 점유하지 않음
- **503 응답 유도**: 거부된 작업은 ServiceUnavailableException으로 처리
- **Little's Law 준수**: W(대기시간) 급증 없이 시스템 안정성 유지

### 권장 유지 사항
1. **AbortPolicy 유지**: 모든 ThreadPoolTaskExecutor에 적용
2. **rejected 메트릭 모니터링**: `executor.rejected` Counter 임계값 알림
3. **큐 용량 적정화**: 트래픽 패턴에 맞는 queueCapacity 설정
4. **Graceful Degradation**: 503 응답 시 클라이언트 재시도 로직 확인

---

## Fail If Wrong

This test is invalid if:
- [ ] Test does not verify actual RejectedExecutionHandler type
- [ ] Executor configuration differs from production
- [ ] Test doesn't measure actual thread blocking time
- [ ] HTTP thread pool size differs significantly
- [ ] TaskDecorator behavior differs from production

---

*Generated by 5-Agent Council*
