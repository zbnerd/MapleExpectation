# ADR-004: LogicExecutor 및 Policy Pipeline 아키텍처

## 상태
Accepted

## 맥락 (Context)

비즈니스 로직 전반에 산재한 try-catch 블록이 다음 문제를 야기했습니다:

**관찰된 문제:**
- 예외 처리 패턴 불일치 (개발자별 스타일 차이)
- 로깅/메트릭 수집 누락
- 체크 예외의 RuntimeException 래핑 난립
- 리소스 해제 로직 누락 (finally 블록 실수)

**부하테스트 결과 (#266):**
- RPS 719 달성 시 일관된 예외 처리가 핵심
- 0% Error Rate 유지를 위한 표준화된 복구 패턴 필요

## 검토한 대안 (Options Considered)

### 옵션 A: AOP 기반 예외 처리
```java
@Around("@annotation(ExceptionHandled)")
public Object handleException(ProceedingJoinPoint pjp) {
    try { return pjp.proceed(); }
    catch (Exception e) { ... }
}
```
- 장점: 비침투적
- 단점: 세밀한 복구 로직 불가, 컨텍스트 전달 어려움
- **결론: 유연성 부족**

### 옵션 B: 직접 try-catch 표준화
```java
// 코딩 컨벤션으로 강제
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("[Domain:FindById] {}", e.getMessage());
    return null;
}
```
- 장점: 즉시 적용 가능
- 단점: 강제력 없음, 보일러플레이트 증가
- **결론: 일관성 보장 불가**

### 옵션 C: LogicExecutor + Policy Pipeline
- 장점: 8가지 표준 패턴, 컴파일 타임 강제, 메트릭 자동 수집
- 단점: 학습 곡선
- **결론: 채택**

## 결정 (Decision)

**LogicExecutor 템플릿과 ExecutionPipeline 기반 Policy 체인을 도입합니다.**

### LogicExecutor 8가지 실행 패턴

| 패턴 | 메서드 | 용도 |
|:---:|--------|------|
| 1 | `execute(task, context)` | 기본 실행, 예외 시 로그 후 전파 |
| 2 | `executeVoid(task, context)` | void 작업 실행 |
| 3 | `executeOrDefault(task, default, context)` | 예외 시 기본값 반환 |
| 4 | `executeOrCatch(task, recovery, context)` | 예외 시 복구 로직 실행 |
| 5 | `executeWithFinally(task, finallyBlock, context)` | 리소스 해제 보장 |
| 6 | `executeWithTranslation(task, translator, context)` | 기술 예외 → 도메인 예외 변환 |
| 7 | `executeCheckedWithHandler(task, handler, context)` | Checked 예외 + 복구 |
| 8 | `executeWithFallback(task, fallback, context)` | Fallback 패턴 |

### TaskContext 기반 메트릭 카디널리티 제어
```java
// maple.expectation.global.executor.TaskContext
public record TaskContext(
    String component,    // 메트릭 태그 (고정)
    String operation,    // 메트릭 태그 (고정)
    String dynamicValue  // 로그에만 기록 (메트릭 제외)
) {
    public static TaskContext of(String component, String operation, String dynamicValue) {
        return new TaskContext(component, operation, dynamicValue);
    }
}
```

### ExecutionPipeline 4단계 라이프사이클
```
1. BEFORE (lifecycle 훅) - 정책 순서대로
2. TASK 실행
3. ON_SUCCESS / ON_FAILURE (observability 훅)
4. AFTER (lifecycle 훅) - 역순 LIFO
```

### 핵심 보장 (PRD v4 준수)
```java
// maple.expectation.global.executor.policy.ExecutionPipeline
- elapsedNanos는 task.get() 구간만 측정 (정책 시간 제외)
- BEFORE는 등록 순서, AFTER는 역순(LIFO)
- before() 성공한 정책만 after() 호출 (entered pairing)
- Error는 최우선 전파 대상
- ThreadLocal depth로 재진입 폭주 fail-fast (MAX_NESTING_DEPTH=32)
```

### 사용 예시
```java
// Bad (Legacy)
try {
    return repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
    return null;
}

// Good (Modern)
return executor.executeOrDefault(
    () -> repository.findById(id),
    null,
    TaskContext.of("Domain", "FindById", String.valueOf(id))
);
```

## 결과 (Consequences)

| 지표 | Before | After |
|------|--------|-------|
| 예외 처리 일관성 | 개인 스타일 | **100% 표준화** |
| 로깅 누락 | 빈번 | **0건** |
| 메트릭 카디널리티 | 폭발 | **제어됨** |
| 코드 가독성 | try-catch 중첩 | **선언적** |

**부하테스트 효과 (#266):**
- 0% Error Rate 달성의 핵심 기반
- 장애 시 일관된 Fallback 보장

## 참고 자료
- `maple.expectation.global.executor.LogicExecutor`
- `maple.expectation.global.executor.DefaultLogicExecutor`
- `maple.expectation.global.executor.TaskContext`
- `maple.expectation.global.executor.policy.ExecutionPipeline`
- `maple.expectation.global.executor.policy.ExecutionPolicy`
- CLAUDE.md 섹션 12: Zero Try-Catch Policy
