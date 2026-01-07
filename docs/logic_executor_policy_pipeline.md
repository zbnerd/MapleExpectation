# LogicExecutor Policy Pipeline 고도화 PRD (Issue #142) — Final

## 0. 문서 목적

1. LogicExecutor의 실행/관측/정리 로직을 **Policy Pipeline**으로 표준화한다.
2. 예외 보존(Primary + suppressed), Error 우선, task-only timing, LIFO after를 **"금융급" 규약**으로 고정한다.
3. 호출부 122개 수정 없이 **내부 구현만 투명하게 교체**한다(Backward Compatibility).

## 1. 목표(Goals)

### ExecutionPolicy 도입
- `before` / `onSuccess` / `onFailure` / `after` 훅으로 정책 조합

### ExecutionPipeline 도입
- 여러 Policy를 순차 실행, 안전 가드 적용

### Executor 이원화
- **LogicExecutor**: 예외를 Runtime으로 번역(서비스/도메인 내부)
- **CheckedLogicExecutor**: 원본 checked 예외를 그대로 전파(IO 경계)

### 면접 공격 포인트 방어
- Error 즉시 rethrow(번역/복구/삼킴 금지)
- System.nanoTime() 기반 정밀 시간
- 스택 트레이스 포함 로깅
- Primary 예외 보존 + suppressed 체인

## 2. 비목표(Non-Goals)

- Redis/Kafka 등 인프라 신규 도입 없음
- 호출부 전체 리라이트 없음(122개 콜사이트 유지)
- 비즈니스 레이어에서 try-catch 허용 없음(단, Pipeline은 인프라 계층으로 예외 격리 허용)

## 3. 용어(Glossary)

- **Policy**: 실행 전후 훅을 제공하는 무상태(Stateless) 컴포넌트
- **Pipeline**: 다수 Policy를 순서대로 실행하는 엔진
- **entered**: before가 "성공적으로 끝난" 정책의 리스트(정리(after) 대상)
- **Primary Exception**: 최종적으로 throw 될 "주 예외"
- **suppressed**: Primary에 부가적으로 붙는 예외(원인 추적)

## 4. 불변 조건(Invariants) — 항상 참이어야 하는 규약

### 4.1 Timing Invariants

1. **elapsedNanos는 task 실행 구간만 포함한다**(Policy 시간 제외).
2. **elapsedNanos는 단 한 번만 확정**되며 onSuccess/onFailure/after에 동일 값으로 전달된다.
3. **task가 시작되지 않은(preempt) 경우** elapsedNanos = 0.

### 4.2 Ordering Invariants

1. **BEFORE**: 등록 순서(0 → N)
2. **AFTER**: 역순(LIFO, N → 0)
3. **entered**: before가 성공한 정책만 포함한다.

### 4.3 Exception Invariants

1. **Error는 번역/복구/삼킴 금지**. primary로 즉시 승격하며, 정리(after) 수행 후 최종 throw한다.
2. **Primary 예외는 덮이지 않는다**
   - 단, Error가 발생하면 Error가 Primary로 승격되고 기존 Primary는 suppressed로 편입된다.
3. **후속 예외는 addSuppressed()로만 보존**한다.
4. **InterruptedException은 발생 지점(task/policy)과 무관하게 전파 전 인터럽트 플래그를 복원**한다.

### 4.4 Policy 원자성(entered pairing 전제)

1. **before()는 성공했을 때만** after 정리가 필요한 상태를 남겨야 한다.
2. **before() 실패 시** policy 내부에서 자체 정리를 완료해야 한다(entered에 포함되지 않기 때문).

### 4.5 관측 훅 Error 중단 규약 (금융급 안정성)

1. **관측 훅 실행 중 Error 발생 시 추가 관측 훅 호출을 즉시 중단**한다.
2. 예시 시나리오:
   - task 성공 → taskOutcome = SUCCESS → entered = [A, B, C]
   - onSuccess(A) 실행 성공
   - **onSuccess(B)에서 Error 발생** → Error를 primary로 설정하고 즉시 after로 이동
   - onSuccess(C)와 모든 onFailure 호출을 스킵
3. **정당성**: Error는 시스템 레벨 장애(OOM, StackOverflow 등)이므로, 추가 관측 훅 재진입은 연쇄 장애를 유발할 수 있다.
4. **호출 보장**:
   - 각 정책의 각 훅 메서드(before/onSuccess/onFailure/after)는 **0회 또는 1회만 호출**된다 (entered 및 경로에 의해 결정)
   - 특히 **onSuccess Error 발생 시 onFailure로의 경로 전이는 허용하지 않는다** (4.5 규약)
5. **task Error 시 onFailure 호출 정책** (선택 A: 현행 유지):
   - **task가 Error로 실패해도 onFailure는 best-effort로 실행**한다.
   - 단, **onFailure 훅에서 Error 발생 시 즉시 중단**(4.5 확장).
   - **정당성**: 관측은 best-effort 원칙 + @Order(LoggingPolicy 선실행) 철학과 일관.
6. **테스트 검증**: onSuccess(B) Error 발생 시 onSuccess(C) 미호출, onFailure 미호출, 최종 throw는 Error임을 보장한다.

## 5. HookType (타입 안전 훅 식별)

**문자열 분기 제거 및 의미 구분 고정.**

```java
public enum HookType {
    BEFORE,
    ON_SUCCESS,
    ON_FAILURE,
    AFTER;

    public boolean isLifecycleHook() {
        return this == BEFORE || this == AFTER;
    }

    public boolean isObservabilityHook() {
        return this == ON_SUCCESS || this == ON_FAILURE;
    }
}
```

## 6. FailureMode (Lifecycle 훅 전용)

**✅ 필수 수정 7: 관측 훅은 non-Error 예외에 한해 항상 SWALLOW한다. Error는 불변 조건 4.3에 따라 즉시 전파한다.**
FailureMode의 영향을 받지 않는다.

```java
public enum FailureMode {
    SWALLOW,    // 로그만 남기고 진행
    PROPAGATE   // 즉시 실패로 전파(단, Error는 언제나 우선)
}
```

### 적용 규칙

- **BEFORE(PROPAGATE)**: task 실행을 즉시 중단한다 (진짜 fail-fast)
- **AFTER(PROPAGATE)**: 예외를 SWALLOW하지 않고 primary/suppressed 규칙으로 반영하되, **나머지 after unwind는 계속 수행**한다 (정리 누락 방지)
- **BEFORE/AFTER(SWALLOW)**: 로그만 남기고 진행
- **ON_SUCCESS/ON_FAILURE**: non-Error는 항상 SWALLOW, Error는 즉시 전파
  - **ON_FAILURE**에서 발생한 non-Error 예외는 SWALLOW 하되, **Primary에 suppressed로 보존**한다.

## 7. ExecutionOutcome (task 기준 outcome)

**Outcome은 "task 결과"를 나타낸다.**

after에서 예외가 발생해 최종적으로 메서드가 실패로 끝나더라도, **after 훅에 전달되는 outcome은 task 기준으로 고정**한다.

**중요**: task가 시작되지 않은(preempt) 경우, outcome은 FAILURE로 간주한다(표 8.1 기준).

```java
public enum ExecutionOutcome {
    SUCCESS,
    FAILURE
}
```

## 8. "성공/실패 정의" 및 예외 우선순위 규칙(최종 표)

### 8.1 실행 결과 정의(최종)

| **상황** | **outcome(전달값)** | **최종 반환/예외** | **비고** |
|:---|:---:|:---|:---|
| BEFORE PROPAGATE 실패 | FAILURE | 예외 throw | task 미실행, entered unwind |
| BEFORE SWALLOW 실패 | task에 따름 | task 결과대로 | before 실패 policy는 entered 제외 |
| task 성공 | SUCCESS | 결과 return | onSuccess 실패는 로그만 |
| task 성공 + AFTER PROPAGATE 실패 | SUCCESS(전달) | AFTER 예외 throw | 최종 실패는 after 예외 |
| task 실패(Exception/Runtime) | FAILURE | task 예외 throw | onFailure 정책 예외는 suppressed |
| task 실패(Error) | FAILURE | Error throw | Error 최우선 |

**공통 각주**: 어느 단계에서든 Error 발생 시, 최종 throw는 Error가 되며 Primary 승격 규칙(섹션 8.2)이 적용된다. 기존 Primary(Exception/Runtime)는 Error의 suppressed로 편입된다.

### 8.2 Primary Exception Preservation(최종 규칙)

#### Error 우선

- 어느 단계든 **Error 발생 시 최종 throw는 Error**
- 기존 Primary(Exception/Runtime)는 **Error의 suppressed로 편입**

#### Error가 없으면

- **✅ 필수 수정 5: task / BEFORE(PROPAGATE) / AFTER(PROPAGATE)에서 전파되는 최초 예외가 Primary**
- 후속 예외는 **addSuppressedSafely()로만 추가** (self-suppression 방어)
- **중요**: SWALLOW된 lifecycle 훅 예외는 로그로만 관측되며, primary/suppressed로 편입되지 않음

#### 관측 훅 정책 예외 처리

- **ON_SUCCESS**: 로그만(절대 Primary를 만들거나 outcome을 바꾸지 않음)
- **ON_FAILURE**: 로그 + `addSuppressedSafely(primary, policyEx)` (원인 추적 보존)

## 9. 핵심 API 설계

### 9.1 ExecutionPolicy (Stateless)

```java
public interface ExecutionPolicy {

    default FailureMode failureMode() {
        return FailureMode.SWALLOW;
    }

    default void before(TaskContext context) throws Exception { }

    default <T> void onSuccess(T result, long elapsedNanos, TaskContext context) throws Exception { }

    default void onFailure(Throwable error, long elapsedNanos, TaskContext context) throws Exception { }

    default void after(ExecutionOutcome outcome, long elapsedNanos, TaskContext context) throws Exception { }
}
```

### 정책 설계 원칙

1. **Thread-safe + Immutable (필수)**
   - ExecutionPolicy는 Thread-safe + Immutable 이어야 하며, 싱글톤 빈으로 등록되는 정책은 **호출 간 mutable state를 보관하면 안 된다**
   - FinallyPolicy처럼 실행별로 생성되는 정책은 불변(immutable) 객체로서 **thread-confined 형태로 허용**된다
2. **before 성공 시에만** after 정리가 필요한 상태를 남긴다(entered pairing)

## 10. ExecutionPipeline 설계(금융급 규약 강제)

### 10.1 안전 가드(Safety Guards)

#### SG1 (Raw Throwable 복구 기준 보존)

- `executeWithRecovery`는 `execute()` 재사용 금지(번역된 예외로 복구하면 원본 기반 복구 불가)
- Pipeline의 `executeRaw()`를 직접 호출해 원본 Throwable을 기준으로 복구한다.

#### SG2 (Single Measurement Principle)

- task 실행 완료 직후 elapsedNanos를 한 번만 확정하고 재사용
- after에서 nanoTime 재호출로 "다른 elapsed"가 전달되는 것을 금지
- **예외 허용**: 원칙적으로 task 종료 직후 1회 확정하되, elapsedNanos가 확정되지 못한 비정상 경로(예: 측정 직전 JVM Error)에서는 fallback으로 1회 보정 측정을 허용한다

#### SG3 (Policy Failure Isolation)

- 관측 훅은 non-Error 예외에 한해 항상 best-effort(SWALLOW)하고, Error는 즉시 전파한다
- lifecycle 훅은 FailureMode에 따라 fail-fast 허용
- Pipeline의 핵심 흐름(task 실행/예외 전파)을 정책 예외가 변경하지 못하도록 격리

#### SG4 (Policy List Immutability)

**생성자 규약**:
```java
public ExecutionPipeline(List<ExecutionPolicy> policies) {
    // 불변 스냅샷 생성 (null 요소 방지)
    this.policies = List.copyOf(policies); // NullPointerException if null element
}
```

**효과**:
- Spring 주입 List 변경으로부터 격리
- null 요소 방지 (List.copyOf는 null 요소 시 NPE)
- 금융급 안전성 (외부 변경 불가)

## 11. Pipeline 알고리즘(최종 의사코드)

### 11.1 핵심 포인트(구조로 규약 강제)

- `invokeBefore` / `invokeAfter` = **Lifecycle 훅**(= FailureMode 적용)
- `invokeOnSuccess` / `invokeOnFailure` = **Observability 훅**(= non-Error는 항상 SWALLOW, Error는 즉시 전파)
- **Error 승격/Primary 보존 규칙**은 단일 함수(`promoteError` 등)로 고정

### 11.2 의사코드 (phase 분리: 4.5 규약 적용)

```java
public <T> T executeRaw(ThrowingSupplier<T> task, TaskContext ctx) throws Throwable {

    List<ExecutionPolicy> entered = new ArrayList<>();
    ExecutionOutcome taskOutcome = ExecutionOutcome.FAILURE;

    boolean taskStarted = false;
    long taskStartNanos = 0L;
    Long elapsedNanos = null;

    Throwable primary = null; // 최종 throw 후보(필요시 Error로 승격)
    T result = null;

    // ========== PHASE 1: BEFORE (lifecycle 훅) ==========
    try {
        for (ExecutionPolicy p : policies) {
            boolean ok = invokeBefore(p, ctx); // FailureMode 적용
            if (ok) entered.add(p);            // before 성공한 policy만 entered
        }
    } catch (Throwable t) {
        restoreInterruptIfNeeded(t);
        primary = t; // BEFORE PROPAGATE 실패 시 task 미실행
        // onFailure는 표 8.1에서 BEFORE PROPAGATE 실패 시 호출하지 않음
    }

    // ========== PHASE 2: TASK + ON_FAILURE ==========
    if (primary == null) {
        try {
            taskStarted = true;
            taskStartNanos = System.nanoTime();
            result = task.get();
            elapsedNanos = System.nanoTime() - taskStartNanos;

            // ✅ task 성공 직후 outcome 확정 (ON_SUCCESS 전)
            taskOutcome = ExecutionOutcome.SUCCESS;

        } catch (Throwable t) {
            restoreInterruptIfNeeded(t);

            // elapsed 계산 (task 실패/Error 포함)
            if (taskStarted && elapsedNanos == null) {
                elapsedNanos = System.nanoTime() - taskStartNanos;
            }
            long e = (elapsedNanos != null) ? elapsedNanos : 0L;

            primary = t; // task 예외를 primary로 설정

            // ON_FAILURE: non-Error swallow + suppressed, Error면 즉시 중단
            for (ExecutionPolicy p : entered) {
                try {
                    invokeOnFailure(p, primary, e, ctx);
                } catch (Error err) {
                    primary = promoteError(primary, err);
                    break; // ✅ 4.5 확장: onFailure Error도 즉시 중단
                }
            }
        }
    }

    // ========== PHASE 3: ON_SUCCESS (task 성공 시에만) ==========
    if (primary == null && taskOutcome == ExecutionOutcome.SUCCESS) {
        long e = (elapsedNanos != null) ? elapsedNanos : 0L; // 방어 패턴(PHASE 4와 일관성)
        for (ExecutionPolicy p : entered) {
            try {
                invokeOnSuccess(p, result, e, ctx);
            } catch (Error err) {
                primary = promoteError(primary, err);
                break; // ✅ 4.5 규약: Error 발생 시 onFailure 스킵, 즉시 after로
            }
        }
    }

    // ========== PHASE 4: AFTER LIFO (무조건 끝까지 unwind) ==========
    // elapsed 최종 확정 (SG2: 동일 값 전달)
    long e;
    if (elapsedNanos != null) e = elapsedNanos;
    else if (taskStarted) e = System.nanoTime() - taskStartNanos;
    else e = 0L;

    // AFTER: N -> 0 (LIFO, loop는 break하지 않음 = 무조건 끝까지 unwind)
    for (int i = entered.size() - 1; i >= 0; i--) {
        ExecutionPolicy p = entered.get(i);
        try {
            invokeAfter(p, taskOutcome, e, ctx); // FailureMode 적용
        } catch (Error err) {
            primary = promoteError(primary, err);
        } catch (Throwable afterEx) {
            restoreInterruptIfNeeded(afterEx);

            if (primary != null) {
                // 실패 경로(primary 존재): after 실패는 suppressed로만 보존
                addSuppressedSafely(primary, afterEx); // ✅ self-suppression 방어
            } else {
                // 성공 경로(primary 없음): after 실패가 새로운 Primary
                primary = afterEx;
            }
        }
    }

    // ========== 단일 throw 지점 (메서드 말미, 예외 마스킹 없음) ==========
    if (primary != null) {
        throw primary;
    }
    return result;
}
```

### 11.3 Lifecycle / Observability 훅 호출 규약(최종)

```java
private boolean invokeBefore(ExecutionPolicy p, TaskContext ctx) throws Throwable {
    try {
        p.before(ctx);
        return true;
    } catch (Error e) {
        throw e;
    } catch (Throwable t) {
        restoreInterruptIfNeeded(t);
        log.warn("⚠️ [Policy:BEFORE] failed. policy={}, context={}",
                 p.getClass().getName(), ctx.toTaskName(), t);

        if (p.failureMode() == FailureMode.PROPAGATE) {
            throw t; // fail-fast
        }
        return false; // SWALLOW => entered 제외
    }
}

private <T> void invokeOnSuccess(ExecutionPolicy p, T result, long e, TaskContext ctx) {
    try {
        p.onSuccess(result, e, ctx);
    } catch (Error err) {
        throw err;
    } catch (Throwable t) {
        restoreInterruptIfNeeded(t);
        log.warn("⚠️ [Policy:ON_SUCCESS] failed. policy={}, context={}",
                 p.getClass().getName(), ctx.toTaskName(), t);
        // non-Error SWALLOW (Error는 위에서 즉시 throw)
    }
}

private void invokeOnFailure(ExecutionPolicy p, Throwable primary, long e, TaskContext ctx) {
    try {
        p.onFailure(primary, e, ctx);
    } catch (Error err) {
        throw err;
    } catch (Throwable t) {
        restoreInterruptIfNeeded(t);
        log.warn("⚠️ [Policy:ON_FAILURE] failed. policy={}, context={}",
                 p.getClass().getName(), ctx.toTaskName(), t);
        // non-Error SWALLOW + 금융급 보존 (Error는 위에서 즉시 throw)
        addSuppressedSafely(primary, t); // ✅ self-suppression 방어
    }
}

private void invokeAfter(ExecutionPolicy p, ExecutionOutcome outcome, long e, TaskContext ctx) throws Throwable {
    try {
        p.after(outcome, e, ctx);
    } catch (Error err) {
        throw err;
    } catch (Throwable t) {
        restoreInterruptIfNeeded(t);
        log.warn("⚠️ [Policy:AFTER] failed. policy={}, context={}",
                 p.getClass().getName(), ctx.toTaskName(), t);

        if (p.failureMode() == FailureMode.PROPAGATE) {
            throw t; // propagate
        }
        // SWALLOW
    }
}
```

### 11.4 suppressed 안전 추가 헬퍼 (금융급 필수)

```java
// ✅ 필수 수정 4 + 6: self-suppression 방어 + suppression disabled 대응
private void addSuppressedSafely(Throwable primary, Throwable suppressed) {
    if (primary == null || suppressed == null) return;
    if (primary == suppressed) return; // self-suppression 방지
    try {
        primary.addSuppressed(suppressed);
    } catch (RuntimeException ignored) {
        // IllegalArgumentException(self-suppression) / IllegalStateException(suppression disabled) 방어
        // primary 불변이 더 중요
    }
}
```

### 11.5 Error 승격(Primary 규칙 단일화)

```java
// ✅ 필수 수정 2: "첫 Error 우선" 규약 반영 + self-suppression 방어
private Throwable promoteError(Throwable currentPrimary, Error newError) {
    if (currentPrimary == null) return newError;
    if (currentPrimary == newError) return currentPrimary; // 동일 객체 가드

    if (currentPrimary instanceof Error) {
        // 첫 Error를 유지, 후속 Error는 suppressed
        addSuppressedSafely(currentPrimary, newError);
        return currentPrimary;
    }

    // 기존 primary가 Error가 아니면 newError가 primary, 기존은 suppressed
    addSuppressedSafely(newError, currentPrimary);
    return newError;
}
```

### 11.6 Interrupted 복원(cause chain 순회)

```java
// ✅ 권장 수정 A: cause chain 순회 + InterruptedIOException 감지
private void restoreInterruptIfNeeded(Throwable t) {
    Throwable cur = t;
    int depth = 0;
    final int MAX_DEPTH = 32; // 무한 루프 방지

    while (cur != null && depth < MAX_DEPTH) {
        if (cur instanceof InterruptedException
            || cur instanceof java.io.InterruptedIOException) {
            Thread.currentThread().interrupt();
            return;
        }
        cur = cur.getCause();
        depth++;
    }
}
```

## 12. 기본 정책(Policies)

### 12.1 LoggingPolicy (Stateless)

- nanoTime 기반 Duration 출력(나노초→밀리초 변환)
- 성공/실패/복구 로그 포맷 통일
- 스택 트레이스 포함
- Duration format: `%.3fms` (권장)

### 12.2 FinallyPolicy (Stateless)

- Runnable을 받아 `after()`에서 실행
- 성공/실패 모두 실행되며, 실패 시에도 SG 규약에 따라 suppressed/primary 보존을 따른다.

### 12.3 RecoveryPolicy 삭제

- Stateful 설계 위험(복구값 저장)으로 제거
- 복구는 `executeWithRecovery()`에서 직접 처리(SG1)

### 12.4 @Order 최우선 실행 (운영 필수, 4.5 규약 적용 후)

**필요성**: 4.5 규약상 관측 훅은 Error 시 중단되므로, **필수 관측 정책(LoggingPolicy 등)은 @Order로 최우선 실행되도록 고정**한다.

**중요**: 4.5 규약으로 인해 관측 훅은 Error 시 중단될 수 있으므로, 필수 관측 정책의 순서 보장은 **정합성 요건**이며 단순 권장 사항이 아니다(@Order + 정렬 필수).

**권장 설정**:
- **LoggingPolicy**: `@Order(100)` - 최우선 실행 (Error 발생 전 로그 보장)
- **FinallyPolicy**: `@Order(200)` - after 정리 목적이므로 뒤여도 무방
- **기타 정책**: `@Order(300+)` - 필요에 따라 추가

**효과**: onSuccess/onFailure Error 시 중단되더라도 LoggingPolicy는 이미 실행되어 관측성 확보

## 13. Executor 이원화

### 13.1 LogicExecutor (서비스/도메인)

- Pipeline `executeRaw()` 호출
- Throwable을 translator로 RuntimeException 변환하여 던짐
- Error는 그대로 rethrow

### 13.2 CheckedLogicExecutor (IO 경계)

- Pipeline `executeRaw()` 호출
- checked 예외를 계약 타입으로 "그대로" 던짐
- 계약 위반(다른 checked 발생)은 IllegalStateException으로 명확히 실패
- InterruptedException 플래그 복원 필수

*(CheckedLogicExecutor/DefaultCheckedLogicExecutor 계약은 기존 초안 그대로 유지 가능하며, 본 PRD의 Pipeline 규약과 충돌 없음)*

## 14. DefaultLogicExecutor 마이그레이션 전략(호출부 0 수정)

- **기존 메서드 시그니처 유지**
- **내부 구현만 Pipeline 기반으로 교체**
- `executeWithRecovery`는 반드시 `execute()` 재사용 금지(SG1)

## 15. 테스트 전략(필수)

### 15.1 ExecutionPipelineTest (금융급 검증)

#### 순서 보장 테스트

- **BEFORE: 0 → N**: Policy A, B, C 등록 순서대로 before() 호출되는지
- **AFTER: N → 0**: after()는 역순(LIFO)으로 C, B, A 순서로 호출되는지
- **entered pairing**: before() 성공한 정책만 after() 호출되는지 (before 실패 시 entered 제외)

#### Timing task-only

- before에서 sleep(50ms) + task 즉시 반환
- elapsed가 50ms 근처면 실패(정책 시간이 포함되면 안 됨)

#### Single measurement

- onSuccess/onFailure/after가 받은 elapsed가 동일한지

#### Error 경로 elapsed 보존

- task가 Error 던짐 → onFailure/after에 elapsed가 0이 아닌지

#### after(PROPAGATE) 예외 보존 테스트

- task 성공 후 after(PROPAGATE)에서 예외 발생 시 새로운 primary가 되는지
- task 실패 후 after(PROPAGATE)에서 예외 발생 시 기존 primary를 덮지 않고 suppressed로만 붙는지
- **Primary 불변 규칙**: 실패 경로에서는 after 예외가 primary를 덮지 않음을 보장

#### 관측 훅 격리 테스트

- **onSuccess 격리**: RuntimeException 등 non-Error는 swallow되어 결과가 성공으로 유지되는지
- **onFailure 격리**: RuntimeException은 swallow + addSuppressedSafely()로 보존되는지
- **Error 승격**: onSuccess/onFailure에서 Error 발생 시 promote되어 최종 throw의 primary가 되는지

#### ✅ 4.5 규약 검증 (onSuccess/onFailure Error 중단)

- **onSuccess 중간 Error 시 관측 훅 중단 + onFailure 스킵** (핵심 시나리오):
  - entered=[A,B,C], task 성공, onSuccess(A) 성공
  - onSuccess(B)에서 Error 발생
  - 기대: onSuccess(C) 미호출, onFailure(A/B/C) 전부 미호출, primary=Error, after LIFO 호출

- **onFailure 중간 Error 시 onFailure 훅 즉시 중단**:
  - task RuntimeException(primary), entered=[A,B,C]
  - onFailure(A) 성공, onFailure(B)에서 Error 발생
  - 기대: primary=첫 Error, 기존 RuntimeException suppressed, onFailure(C) 미호출, after LIFO 호출

- **BEFORE(PROPAGATE) 실패 시 after unwind 검증**:
  - entered=[A], BEFORE(B) 실패(PROPAGATE), C는 entered 미포함
  - 기대: task 미실행, after(A) 호출 (entered된 정책만), after(B/C) 미호출, primary=BEFORE(B) 예외

- **4.5 onSuccess Error 시 outcome=SUCCESS 고정 검증**:
  - task 성공 → taskOutcome=SUCCESS 확정, onSuccess(A)에서 Error 발생
  - 기대: after(A)에 전달되는 outcome=SUCCESS (task 기준 고정), 최종 throw는 Error

#### Error 우선순위

- task RuntimeException + after Error 발생 시 최종 throw가 Error인지, RuntimeException이 suppressed인지
- 첫 Error가 primary로 유지되고, 후속 Error는 suppressed로 붙는지 ("첫 Error 우선" 규칙)

#### InterruptedException 복원

- task 또는 policy에서 InterruptedException 발생 시 interrupt flag가 복원되는지
- cause chain 순회로 InterruptedIOException도 감지되는지

#### @Order 정렬 적용 검증 (통합 테스트 성격)

- ExecutorConfig에서 정렬한 리스트가 실제 pipeline에 전달되어, LoggingPolicy가 항상 선행하는지
- **정당성**: 4.5 규약 + @Order 정합성 요건 검증
- **시나리오**: 여러 정책 등록 후 before 호출 순서가 @Order 값 오름차순인지 확인

#### primary=Error 경로에서의 onFailure 호출 검증

- **4.5-5 정책 검증**: task가 Error로 실패해도 onFailure는 best-effort로 실행되는지
- **시나리오**:
  - task Error(primary), entered=[A,B,C]
  - onFailure(A) 성공, onFailure(B) 성공, onFailure(C) 성공 → 기대: onFailure 3회 호출, 최종 throw는 task Error
  - onFailure(A) 성공, onFailure(B)에서 Error → 기대: onFailure(C) 미호출, primary=첫 Error(onFailure B), task Error suppressed

### 15.2 PolicyTest

- **LoggingPolicy** 포맷(`%.3fms`), 스택 트레이스 포함 확인
- **FinallyPolicy** 성공/실패 모두 실행 확인

### 15.3 DefaultLogicExecutorTest(SG1)

- `executeWithRecovery`가 원본 Throwable 기반으로 recoveryFunction을 수행하는지
- recoveryFunction 실패 시 translator.translate로 번역되는지
- Error는 복구 금지(즉시 rethrow)인지

## 16. 완료 조건(Definition of Done) — Final

### 산출물

- [ ] ExecutionOutcome(enum)
- [ ] HookType(enum)
- [ ] FailureMode(enum)
- [ ] ExecutionPolicy(Stateless)
- [ ] ExecutionPipeline(본 PRD 알고리즘 반영)
- [ ] LoggingPolicy, FinallyPolicy(Stateless)
- [ ] CheckedSupplier/CheckedRunnable
- [ ] CheckedLogicExecutor + DefaultCheckedLogicExecutor
- [ ] DefaultLogicExecutor 내부 Pipeline 전환(호출부 변경 없음)

### 금융급 규약 검증

- [ ] task-only timing + single measurement 통과
- [ ] after LIFO 통과
- [ ] entered pairing 통과
- [ ] Observability 훅: non-Error SWALLOW + Error propagate 통과
- [ ] onFailure 정책 예외 suppressed 보존 통과
- [ ] Error 우선순위 통과
- [ ] InterruptedException 복원 통과

### 품질

- [ ] SOLID 준수(SRP/OCP/ISP/DIP)
- [ ] System.nanoTime() 사용
- [ ] 스택 트레이스 포함 로깅
- [ ] Zero try-catch in business layer(단 Pipeline은 인프라 계층 예외)

## 17. 패키지/파일 구조(최종)

```
global/executor/
├── policy/
│   ├── ExecutionOutcome.java
│   ├── HookType.java
│   ├── FailureMode.java
│   ├── ExecutionPolicy.java
│   ├── ExecutionPipeline.java
│   ├── LoggingPolicy.java
│   └── FinallyPolicy.java
├── function/
│   ├── CheckedSupplier.java
│   └── CheckedRunnable.java
├── CheckedLogicExecutor.java
├── DefaultCheckedLogicExecutor.java
└── DefaultLogicExecutor.java   // 내부 구현 Pipeline 전환
config/
└── ExecutorConfig.java
test/
└── ... (Pipeline/Policy/Executor tests)
```

## 18. 핵심 파일(Critical Files)

### 신규 생성
1. `src/main/java/maple/expectation/global/executor/policy/ExecutionOutcome.java` (enum: SUCCESS/FAILURE)
2. `src/main/java/maple/expectation/global/executor/policy/HookType.java` (enum: BEFORE/ON_SUCCESS/ON_FAILURE/AFTER)
3. `src/main/java/maple/expectation/global/executor/policy/FailureMode.java` (enum: SWALLOW/PROPAGATE)
4. `src/main/java/maple/expectation/global/executor/policy/ExecutionPolicy.java` (Stateless)
5. `src/main/java/maple/expectation/global/executor/policy/ExecutionPipeline.java` (3대 Safety Guards 적용)
6. `src/main/java/maple/expectation/global/executor/policy/LoggingPolicy.java` (Stateless, Duration 통일)
7. `src/main/java/maple/expectation/global/executor/policy/FinallyPolicy.java` (Stateless)
8. `src/main/java/maple/expectation/global/executor/function/CheckedSupplier.java` (금융급 문서)
9. `src/main/java/maple/expectation/global/executor/function/CheckedRunnable.java` (금융급 문서)
10. `src/main/java/maple/expectation/global/executor/CheckedLogicExecutor.java` (금융급 계약 명세)
11. `src/main/java/maple/expectation/global/executor/DefaultCheckedLogicExecutor.java` (금융급 구현)
12. `src/main/java/maple/expectation/config/ExecutorConfig.java` (ExecutionPipeline Bean 설정)

### 수정 대상
1. `src/main/java/maple/expectation/global/executor/DefaultLogicExecutor.java` (Safety Guard 1 적용)

### 테스트 파일
1. `src/test/java/maple/expectation/global/executor/policy/ExecutionPipelineTest.java` (Safety Guards 2&3 + 금융급 검증)
2. `src/test/java/maple/expectation/global/executor/policy/LoggingPolicyTest.java`
3. `src/test/java/maple/expectation/global/executor/policy/FinallyPolicyTest.java`
4. `src/test/java/maple/expectation/global/executor/DefaultLogicExecutorTest.java` (Safety Guard 1 검증)

## 19. 참조 문서

- Issue #142: LogicExecutor 아키텍처 고도화
- ChatGPT 2번 고도화안: Policy Pipeline + Executor 이원화
- Context7: Java Design Patterns (/iluwatar/java-design-patterns)
- Context7: Spring Framework AOP (/websites/spring_io_spring-framework_reference_6_2)
- CLAUDE.md: 프로젝트 가이드라인 (SOLID, Zero Try-Catch)

---

## ✅ PRD 품질 보장 (모순 0 상태)

이 PRD는 다음을 보장합니다:

1. **구현 일관성**: 해석의 여지 없이 구현 가능
2. **테스트 = 규약**: 각 테이블 행이 테스트 케이스
3. **면접 방어**: 표 하나로 "왜 관측 실패가 비즈니스를 죽이지 않나", "언제 죽이나", "예외는 왜 보존되나" 설명 가능
4. **금융급 완결성**: Primary 예외 보존, outcome 타이밍 정확성, entered pairing 모두 명시
5. **규약의 구조 강제**: Lifecycle/Observability 훅 분리로 FailureMode 적용 범위를 코드 구조로 고정

---

## 📋 PR 4 리뷰 완료 및 최종 승인 (2026-01-06)

### 리뷰 대상 파일
1. **CheckedSupplier.java** ✅
2. **CheckedRunnable.java** ✅
3. **CheckedLogicExecutor.java** ✅
4. **DefaultCheckedLogicExecutor.java** ✅

### 최종 적용 개선사항

#### 1. CheckedSupplier/CheckedRunnable (공통)
- @param <E> 설명에 RuntimeException 금지 경고 추가
- "IO 경계 전용" 톤 강화 (제목에 명시)
- 문서 표현: "권장: checked 예외 + 금지: RuntimeException" 조합
- @see 상호 참조 추가

#### 2. CheckedLogicExecutor (인터페이스)
- 금융급 계약에 "RuntimeException 투명 전파" 항목 추가
- "checked 예외 계약 타입 보존"으로 표현 정확화
- expectedExceptionType 파라미터에 계약 위반 동작 명시
- @throws IllegalArgumentException 추가 (RuntimeException 지정 시)
- null 불가 일관성 (task/expectedExceptionType/context 모두 명시)
- SQLException 예시를 의사코드로 수정 (컴파일 불가 문제 해결)
- @param 내 `<p>` 태그 제거 (Javadoc 렌더링 안정성)
- RuntimeException 링크 표준화 (`{@link RuntimeException}`)

#### 3. DefaultCheckedLogicExecutor (구현체)
- 금융급 보장사항 문서를 인터페이스와 통일
  - "checked 예외 계약 타입 보존" 명시
  - "RuntimeException 투명 전파" 추가
  - "계약 타입으로 RuntimeException 지정 금지" 추가
- 계약 위반 메시지 null 안전 처리
  - `e.getMessage()` null 체크 추가
  - `getSimpleName()` → `getName()` (nested 클래스 식별성)

### 리뷰 주요 피드백 반영
1. **Javadoc 표준 준수**: `<p>` 태그 사용 최소화, `{@link}` 표준화
2. **구현-문서 정합성**: 인터페이스와 구현체의 금융급 계약 표현 통일
3. **null 안전성**: 모든 getMessage() 호출에 null 체크
4. **타입 식별성**: getSimpleName() 대신 getName() 사용 (운영 관측성)
5. **오용 방지**: RuntimeException 금지 경고를 모든 관련 파일에 일관되게 추가

### 선택적 개선사항 (추후 적용 가능)
- Throwable catch에 ERROR 로그 추가 (운영 관측성)
- taskName 메시지 포함 (디버깅 강화)
- 인터럽트 감지 범위 확장 (InterruptedIOException 등)
- 계약 테스트 케이스 작성 (6개 시나리오)

---

## 🔧 PRD 필수 수정사항 (2026-01-06 반영 완료)

### 필수 수정 1: executeRaw() 예외 보존 구조 변경 ✅
**문제:** catch 블록에서 `throw primary;` 사용 시, finally에서 throw 발생 시 원래 예외 유실
**해결:**
- catch에서 throw 금지, primary 변수에 저장만
- try-catch-finally 밖에서 단일 throw 지점 생성
- `T result` 변수 추가하여 성공 경로 처리

### 필수 수정 2: promoteError() "첫 Error 우선" 규약 반영 ✅
**문제:** 새 Error가 항상 Primary가 되어 "첫 Error 우선" 테스트 전략과 충돌
**해결:**
```java
if (currentPrimary instanceof Error) {
    // 첫 Error 유지, 후속 Error는 suppressed
    addSuppressedSafely(currentPrimary, newError);
    return currentPrimary;
}
```

### 필수 수정 3: taskOutcome = SUCCESS 시점 변경 ✅
**문제:** onSuccess 훅 실행 후 outcome 확정 시, 훅에서 Error 발생 시 outcome이 틀어짐
**해결:** task.get() 성공 직후 즉시 `taskOutcome = SUCCESS;` 확정

### 필수 수정 4: addSuppressedSafely() 헬퍼 도입 (금융급 필수) ✅
**문제:** `primary.addSuppressed(primary)` self-suppression 시 IllegalArgumentException 발생
**해결:**
```java
private void addSuppressedSafely(Throwable primary, Throwable suppressed) {
    if (primary == null || suppressed == null) return;
    if (primary == suppressed) return; // self-suppression 방지
    try {
        primary.addSuppressed(suppressed);
    } catch (RuntimeException ignored) {
        // self-suppression/suppression disabled - primary 불변이 더 중요
    }
}
```
**적용 범위:**
- invokeOnFailure 내부
- finally after 루프 내부
- promoteError 내부 + 동일 객체 가드

### 필수 수정 5: "최초 예외" 문구 정확화 (FailureMode 정합성) ✅
**문제:** "task/before/after에서 발생한 최초 예외"는 SWALLOW와 모순
**해결:** "task / BEFORE(PROPAGATE) / AFTER(PROPAGATE)에서 **전파되는** 최초 예외가 Primary"
**추가 명시:** SWALLOW된 lifecycle 훅 예외는 로그로만 관측, primary/suppressed 미편입

### 필수 수정 6: addSuppressed catch 범위 확장 (suppression disabled 대응) ✅
**문제:** `IllegalArgumentException`만 잡으면 `suppression disabled` Throwable에서 IllegalStateException 발생
**해결:** catch 범위를 `RuntimeException`으로 확장
**효과:** suppression disabled / self-suppression 모두 방어

### 필수 수정 7: "관측 훅 SWALLOW" 문구에 non-Error 한정 명시 ✅
**문제:** "관측 훅은 무조건 SWALLOW"는 Error 즉시 전파 규약과 충돌
**해결:** "관측 훅은 non-Error 예외에 한해 SWALLOW, Error는 즉시 전파"
**효과:** 섹션 6 (FailureMode) 와 4.3 (Error 우선) 정합성 확보

### 권장 수정 A: 인터럽트 복원 cause chain 순회 ✅
**개선:**
- cause chain 순회 (MAX_DEPTH=32)
- InterruptedIOException 감지 추가
- DefaultCheckedLogicExecutor와 일관성 확보

### 권장 수정 B: ExecutorConfig에서 Policy 정렬
**필요성:** Spring의 `List<ExecutionPolicy>` 주입 순서 불안정 → 테스트 플래키
**해결 방안:**
1. 각 Policy에 @Order 부여 (LoggingPolicy=100, FinallyPolicy=200 등)
2. ExecutorConfig에서 `AnnotationAwareOrderComparator.sort(policies)` 명시적 정렬
3. ExecutionPipeline 생성자에서 정렬 수행

**권장 코드:**
```java
@Configuration
public class ExecutorConfig {
    @Bean
    public ExecutionPipeline executionPipeline(List<ExecutionPolicy> policies) {
        // @Order 기반 정렬 (BEFORE는 등록 순서 보장)
        AnnotationAwareOrderComparator.sort(policies);
        return new ExecutionPipeline(policies);
    }
}
```

---

## 🎯 모순 0 마감 완료 (2026-01-06 Final v4)

### 1차 필수 마감 완료 ✅ (이전 버전)

**A. "항상 SWALLOW" 문구 통일**
- ✅ 섹션 10.1 SG3 / 11.1 / DoD

**B. 섹션 번호 중복 해결**
- ✅ 11.6 Interrupted 복원

**C. Error 발생 시 관측 훅 중단 규약 (옵션 1 선택)**
- ✅ 섹션 4.5 추가

**D-F. 권장 개선 (표 각주, 테스트 문구, getName 통일)**
- ✅ 모두 적용

### 2차 치명 이슈 수정 완료 ✅ (최종 버전)

**1. 11.2 의사코드 phase 분리 (4.5 규약 적용)**
- ✅ PHASE 1: BEFORE (lifecycle 훅)
- ✅ PHASE 2: TASK + ON_FAILURE (onFailure Error도 break 적용)
- ✅ PHASE 3: ON_SUCCESS (task 성공 시에만, Error 발생 시 onFailure 스킵)
- ✅ PHASE 4: FINALLY - AFTER LIFO
- **효과**: onSuccess Error → onFailure 호출 금지 (4.5 규약 구조로 강제)

**2. 11.3 주석 문구 정정**
- ✅ "ALWAYS SWALLOW" → "non-Error SWALLOW (Error는 위에서 즉시 throw)"

**3. 섹션 6 FailureMode 명문화**
- ✅ BEFORE(PROPAGATE): task 실행 즉시 중단 (진짜 fail-fast)
- ✅ AFTER(PROPAGATE): primary 반영 + **나머지 after unwind 계속 수행** (정리 누락 방지)

**4. 4.5-4 문구 정밀화**
- ✅ "각 훅 메서드는 **0회 또는 1회만 호출**"
- ✅ "onSuccess Error → onFailure 전이 금지" 명시

**5. 섹션 9 Stateless 정의 정밀화**
- ✅ Thread-safe + Immutable (싱글톤 빈은 mutable state 금지)
- ✅ FinallyPolicy는 **thread-confined 형태로 허용**

**6. 테스트 전략 추가 (✅ 4.5 규약 검증)**
- ✅ onSuccess 중간 Error → onSuccess(C) 미호출, onFailure 전체 스킵
- ✅ onFailure 중간 Error → onFailure(C) 미호출, primary=Error

### 3차 구조적 위험 제거 완료 ✅ (Final v3)

**1. PHASE 4 finally throw 예외 마스킹 제거**
- ✅ 문제: `try { after loop } finally { throw primary; }` 구조에서 예상치 못한 예외가 finally의 throw로 마스킹될 수 있음
- ✅ 해결: finally 제거, 메서드 말미 단일 throw 지점으로 변경 (`if (primary != null) throw primary;`)
- **효과**: 예외 마스킹 리스크 완전 제거, 모든 예외가 primary 규칙에 따라 보존됨

**2. 섹션 12.4 @Order 최우선 실행 추가**
- ✅ 필요성: 4.5 규약상 관측 훅은 Error 시 중단되므로, LoggingPolicy는 @Order(100)으로 최우선 실행 고정
- ✅ 권장 설정: LoggingPolicy=100, FinallyPolicy=200, 기타=300+
- **효과**: onSuccess/onFailure Error 시 중단되더라도 LoggingPolicy는 이미 실행되어 관측성 확보

**3. SG2 fallback nanoTime 허용 명시**
- ✅ 추가: "elapsedNanos가 확정되지 못한 비정상 경로(예: 측정 직전 JVM Error)에서는 fallback으로 1회 보정 측정을 허용한다"
- **효과**: 예외 경로의 elapsed 보존 정당성 확보, SG2 규약 정밀도 향상

**4. 테스트 시나리오 2개 추가**
- ✅ BEFORE(PROPAGATE) 실패 시 after unwind 검증
- ✅ 4.5 onSuccess Error 시 outcome=SUCCESS 고정 검증 (after 훅에 전달되는 outcome이 task 기준으로 고정되는지)
- **효과**: entered pairing + outcome 고정 규약을 테스트로 직접 검증

### 4차 정밀 보완 완료 ✅ (Final v4 - 금융급 문서 완결)

**1. 문서 레벨 정밀화 (모순/공격 포인트 사전 차단)**

- ✅ **섹션 7 ExecutionOutcome**: "task가 시작되지 않은(preempt) 경우, outcome은 FAILURE로 간주한다" 추가
  - **효과**: "task 결과" 정의와 표 8.1 완전 봉합

- ✅ **섹션 12.4 @Order**: "4.5 규약으로 인해 순서 보장은 정합성 요건이며, 단순 권장 사항이 아니다" 명시
  - **효과**: PR 리뷰 논쟁 원천 차단 (테스트 안정성이 아닌 정합성 요구사항)

- ✅ **섹션 4.3 Exception Invariants**: "Error는 번역/복구/삼킴 금지. primary로 즉시 승격하며, 정리(after) 수행 후 최종 throw"
  - **효과**: "즉시 rethrow" 문구와 실제 알고리즘(after unwind 후 throw) 완전 일치

**2. 알고리즘/의사코드 안전성 강화**

- ✅ **PHASE 3 elapsedNanos null 방어**: `long e = (elapsedNanos != null) ? elapsedNanos : 0L;`
  - **효과**: PHASE 4와 패턴 통일, "이론상 null 가능" 질문 원천 차단

- ✅ **SG4 (Policy List Immutability)**: `this.policies = List.copyOf(policies);` 생성자 규약 추가
  - **효과**: Spring 주입 List 변경 격리 + null 요소 방지 + 금융급 안전성

- ✅ **섹션 4.5-5 task Error 시 onFailure 호출 정책**: "task가 Error로 실패해도 onFailure는 best-effort로 실행"
  - **효과**: "Error 상황에서 관측 훅 처리" 문서 결정 완결 (선택 A: 현행 유지)

**3. 테스트 전략 완전 커버리지**

- ✅ **@Order 정렬 적용 검증**: LoggingPolicy가 항상 선행하는지 통합 테스트
  - **효과**: 4.5 규약 + @Order 정합성 요건을 테스트로 검증

- ✅ **primary=Error 경로 onFailure 호출 검증**: 4.5-5 정책을 테스트로 직접 검증
  - **효과**: "Error 시 관측 훅 처리" 정책 완전 닫힘

### 최종 판정 (Final v4 - 금융급 문서 완결)

✅ **기능/안전성**: 실전 투입 가능 수준 (운영 등급 + 금융급 정밀도)
- Primary 보존, 첫 Error 우선, task-only timing, LIFO after, entered pairing
- Interrupt 복원, suppression disabled 방어, Error 중단 규약 모두 **phase 분리 구조로 강제**
- **예외 마스킹 리스크 완전 제거** (PHASE 4 finally throw 제거)
- **관측성 보장** (@Order 최우선 실행으로 LoggingPolicy Error 시에도 실행 완료)
- **불변 스냅샷 + null 방어** (SG4 + PHASE 3 elapsedNanos 방어 패턴)

✅ **"모순 0 / 금융급 문서"**: 문서=구현 지시서 수준 달성 (v4 정밀 보완 완료)
- **4.5 규약 ↔ 11.2 의사코드 정합성 확보** (phase 분리)
- FailureMode 동작 차이 명문화 (BEFORE/AFTER 각각)
- Stateless 정의 정밀화 (FinallyPolicy 허용 조건)
- SG2 fallback nanoTime 허용 명시 (예외 경로 정당성)
- **ExecutionOutcome preempt 케이스 명시** (표 8.1과 정의 봉합)
- **"Error 즉시 rethrow" 정밀화** (정리 후 최종 throw)
- **task Error 시 onFailure 호출 정책 확정** (선택 A: best-effort)
- 테스트 시나리오 완전 커버리지 (@Order 정렬 + primary=Error onFailure 호출 포함)

✅ **면접/PR 리뷰 방어력**: 모든 공격 포인트 사전 차단 (v4 추가 3개)
- "왜 onSuccess Error가 onFailure를 트리거 안 하나?" → 11.2 phase 분리 구조
- "PROPAGATE인데 왜 계속 실행하나?" → 섹션 6 BEFORE/AFTER 차이 명문화
- "Stateless인데 Runnable을 들고 있냐?" → 섹션 9 thread-confined 허용 조건
- "finally에서 throw하면 예외 마스킹 위험 있지 않나?" → 11.2 메서드 말미 단일 throw 지점으로 해결
- "Error 시 LoggingPolicy 실행 보장은?" → 섹션 12.4 @Order(100) 최우선 실행
- **"task 미실행인데 outcome이 왜 FAILURE냐?"** → 섹션 7 preempt 케이스 명시
- **"@Order는 권장 사항 아닌가?"** → 섹션 12.4 정합성 요건 명문화
- **"task가 Error여도 onFailure를 왜 호출하나?"** → 섹션 4.5-5 best-effort 정책 확정