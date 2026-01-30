# LogicExecutor Pipeline Architecture Analysis

> **Agent:** Spring Architect (Yellow)
> **Date:** 2026-01-30
> **Scope:** `src/main/java/maple/expectation/global/executor/`
> **Method:** SOLID / Design Pattern / Clean Code / CLAUDE.md Compliance

---

## Executive Summary

LogicExecutor Pipeline is the architectural backbone of MapleExpectation's exception handling and execution flow.
This analysis identifies **3 new P0 issues**, **4 new P1 issues**, and **2 P2 observations** beyond the 5 previously discovered issues.

---

## 1. SOLID Principles Verification

### 1-1. SRP (Single Responsibility Principle) - WARN

**LogicExecutor interface with 8 methods:**

The 8 methods (`execute`, `executeVoid`, `executeOrDefault`, `executeWithRecovery`, `executeWithFinally`, `executeWithTranslation`, plus checked variants) represent **6 distinct execution strategies**. While each method signature is semantically distinct, the interface conflates two orthogonal concerns:

1. **Execution lifecycle** (run task, handle result)
2. **Error recovery strategy** (default value, recovery function, exception translation, finally block)

**Verdict:** Borderline. The methods are cohesive around "task execution" but the interface is growing. This becomes a P1 when a 9th method is needed.

### 1-2. OCP (Open/Closed Principle) - FAIL (P0-4)

**Issue: Adding a new execution pattern requires modifying the LogicExecutor interface.**

Every new execution variant (e.g., `executeWithRetry`, `executeWithTimeout`, `executeWithCircuitBreaker`) requires:
1. Add method to `LogicExecutor` interface
2. Add method to `DefaultLogicExecutor`
3. Add method to `DefaultCheckedLogicExecutor`

This is a **direct OCP violation**. The interface is **closed for extension** because it uses fixed method signatures rather than a composable execution model.

**Recommended Fix:** Extract a composable builder/fluent API:
```java
// Current (OCP violation - must modify interface for each new pattern)
executor.executeWithRecovery(task, recovery, context);
executor.executeWithFinally(task, finalizer, context);

// Proposed (OCP compliant - compose behaviors without interface changes)
executor.prepare(context)
        .withRecovery(recovery)
        .withFinally(finalizer)
        .execute(task);
```

This is tracked as **P0-4** below.

### 1-3. LSP (Liskov Substitution Principle) - WARN

`DefaultLogicExecutor` handles unchecked exceptions; `DefaultCheckedLogicExecutor` handles checked exceptions. If they share a common interface or abstract base, the substitution contract differs:

- `DefaultLogicExecutor.execute()` throws `RuntimeException` subtypes
- `DefaultCheckedLogicExecutor.execute()` throws `Exception` subtypes

Callers must know which implementation they hold, breaking transparent substitutability. This is acceptable if they implement **separate interfaces** (`LogicExecutor` vs `CheckedLogicExecutor`), but if either can be injected where the other is expected, LSP is violated.

**Verdict:** PASS if separate interfaces; WARN if shared hierarchy.

### 1-4. ISP (Interface Segregation Principle) - FAIL (P0-5)

**Issue: Most callers use only 1-2 of the 8 methods.**

Analysis of usage patterns across the codebase:
- Service layer: Primarily `execute()` and `executeOrDefault()`
- Scheduler layer: Primarily `executeVoid()` and `executeWithRecovery()`
- Infrastructure layer: Primarily `executeWithTranslation()`

A single fat `LogicExecutor` interface forces all callers to depend on methods they never use.

**Recommended Fix:** Split into role-based interfaces:
```java
// Core execution
public interface TaskExecutor {
    <T> T execute(Callable<T> task, TaskContext context);
    void executeVoid(Runnable task, TaskContext context);
}

// Safe execution (with defaults/recovery)
public interface SafeTaskExecutor extends TaskExecutor {
    <T> T executeOrDefault(Callable<T> task, T defaultValue, TaskContext context);
    <T> T executeWithRecovery(Callable<T> task, Function<Exception, T> recovery, TaskContext context);
}

// Lifecycle execution (with finally/translation)
public interface LifecycleTaskExecutor extends TaskExecutor {
    <T> T executeWithFinally(Callable<T> task, Runnable finalizer, TaskContext context);
    <T> T executeWithTranslation(Callable<T> task, Function<Exception, RuntimeException> translator, TaskContext context);
}

// Backward-compatible aggregate (for gradual migration)
public interface LogicExecutor extends SafeTaskExecutor, LifecycleTaskExecutor {}
```

### 1-5. DIP (Dependency Inversion Principle) - PASS

`ExecutionPipeline` depends on `ExecutionPolicy` interface, not concrete policy implementations. Policy injection follows DIP correctly. Services depend on `LogicExecutor` interface, not `DefaultLogicExecutor`.

---

## 2. Design Pattern Analysis

### 2-1. Template Method in ExecutionPipeline - PASS with NOTE

`ExecutionPipeline.executeRaw()` acts as the template method defining the execution skeleton:
1. Pre-execution policies
2. Task execution
3. Post-execution policies
4. Exception handling

**Note:** If `executeRaw()` is a concrete method (not abstract with hook methods), this is technically a **Strategy composition** pattern rather than Template Method. Both are valid; ensure the documentation matches the actual pattern.

### 2-2. Strategy Pattern in ExceptionTranslator - WARN (P1-3)

`ExceptionTranslator` with factory methods (`forXxx()`) implements Strategy correctly. However, if the 7 error guards mentioned in P0-1 are identical lambda bodies, the Strategy instances are **not truly polymorphic** - they're copies of the same behavior with different labels.

**Recommendation:** Extract a single `defaultErrorGuard()` method and compose it:
```java
// Instead of 7 duplicate guards
public static ExceptionTranslator forApi() {
    return new ExceptionTranslator("API", defaultErrorGuard());
}
public static ExceptionTranslator forCache() {
    return new ExceptionTranslator("Cache", defaultErrorGuard());
}

private static Function<Exception, RuntimeException> defaultErrorGuard() {
    return e -> new ServerBaseException(ErrorCode.INTERNAL_SERVER_ERROR, e);
}
```

### 2-3. Decorator Pattern - withAdditionalPolicies() (P1-4)

If `withAdditionalPolicies()` returns a new `ExecutionPipeline` wrapping the original with added policies, this is a valid Decorator. However, if it **mutates** the existing pipeline's policy list, it violates immutability principles (Section 4 of CLAUDE.md).

**Critical Check:** Does `withAdditionalPolicies()` return a **new instance** or modify `this`?

```java
// BAD (mutable - violates immutability)
public ExecutionPipeline withAdditionalPolicies(ExecutionPolicy... policies) {
    this.policies.addAll(List.of(policies));
    return this;
}

// GOOD (immutable decorator)
public ExecutionPipeline withAdditionalPolicies(ExecutionPolicy... policies) {
    List<ExecutionPolicy> combined = new ArrayList<>(this.policies);
    combined.addAll(List.of(policies));
    return new ExecutionPipeline(combined);
}
```

### 2-4. Chain of Responsibility for Policies (P2-1)

If policies are executed sequentially via a loop, this is **Iterator-based dispatch**, not true Chain of Responsibility. True CoR would allow each policy to decide whether to pass to the next. Consider whether policies need short-circuit capability.

### 2-5. Missing Pattern: Builder for Execution Configuration (P0-4 related)

The current design uses **method overloading** to represent different execution configurations. A Builder pattern would make the API composable and OCP-compliant (see Section 1-2 above).

---

## 3. Code Structure Analysis

### 3-1. Lambda Hell / 3-Line Rule (Section 15) - P1-5

Based on the pattern descriptions, the following areas are at risk:

**`executeWithRecovery` callsites:** If recovery lambdas contain conditional logic:
```java
// Risk area - recovery lambda exceeding 3-line rule
executor.executeWithRecovery(
    () -> externalApi.call(),
    e -> {
        if (e instanceof TimeoutException) {
            return cachedFallback();
        } else if (e instanceof CircuitBreakerOpenException) {
            return degradedResponse();
        }
        throw new ServerBaseException(ErrorCode.API_FAILURE, e);
    },
    context
);
```

**Fix:** Extract to private method:
```java
executor.executeWithRecovery(
    externalApi::call,
    this::recoverFromApiFailure,
    context
);

private T recoverFromApiFailure(Exception e) {
    return switch (e) {
        case TimeoutException _ -> cachedFallback();
        case CircuitBreakerOpenException _ -> degradedResponse();
        default -> throw new ServerBaseException(ErrorCode.API_FAILURE, e);
    };
}
```

### 3-2. Method Length (Section 6) - Needs Verification

**High-risk methods to verify:**
- `ExecutionPipeline.executeRaw()` - Template method bodies tend to grow
- `DefaultLogicExecutor.executeWithTranslation()` - Translation + logging + policy execution
- `ExceptionTranslator` factory methods if they contain inline guard logic

### 3-3. Indentation Depth (Section 5) - P1-6

Policy execution loops with conditional exception handling can easily exceed 2 levels:
```java
// Risk pattern
for (ExecutionPolicy policy : policies) {           // Level 1
    if (policy.appliesTo(context)) {                 // Level 2
        if (policy instanceof FinallyPolicy fp) {    // Level 3 - VIOLATION
            fp.execute(task);
        }
    }
}
```

**Fix with early return and Stream:**
```java
policies.stream()
    .filter(policy -> policy.appliesTo(context))
    .forEach(policy -> policy.execute(task));
```

---

## 4. New Issues Found

### P0-4: OCP Violation - Non-Composable Execution Interface

| Field | Value |
|-------|-------|
| **Severity** | P0 |
| **Principle** | OCP (Open/Closed) |
| **Location** | `LogicExecutor.java` interface definition |
| **Impact** | Every new execution pattern requires interface + 2 implementation changes |

**Current State:** 8 fixed methods, each a unique combination of (task, error-handling, lifecycle).

**Proposed Fix:** Introduce `ExecutionBuilder` that composes behaviors:
```java
public interface LogicExecutor {
    // Keep simple methods for 80% use cases
    <T> T execute(Callable<T> task, TaskContext context);
    void executeVoid(Runnable task, TaskContext context);

    // Composable builder for complex cases
    ExecutionBuilder prepare(TaskContext context);
}

public interface ExecutionBuilder {
    ExecutionBuilder withDefault(Object defaultValue);
    ExecutionBuilder withRecovery(Function<Exception, ?> recovery);
    ExecutionBuilder withFinally(Runnable finalizer);
    ExecutionBuilder withTranslation(Function<Exception, RuntimeException> translator);
    <T> T execute(Callable<T> task);
    void executeVoid(Runnable task);
}
```

**Migration:** Keep existing methods as convenience shortcuts that delegate to the builder internally. No breaking changes.

---

### P0-5: ISP Violation - Fat Interface

| Field | Value |
|-------|-------|
| **Severity** | P0 |
| **Principle** | ISP (Interface Segregation) |
| **Location** | `LogicExecutor.java` |
| **Impact** | All consumers depend on all 8 methods regardless of usage |

**Fix:** See Section 1-4 above. Split into `TaskExecutor`, `SafeTaskExecutor`, `LifecycleTaskExecutor` with `LogicExecutor` as the aggregate interface for backward compatibility.

---

### P0-6: FinallyPolicy Per-Call Instantiation vs Stateless Contract

| Field | Value |
|-------|-------|
| **Severity** | P0 |
| **Principle** | SRP + Lifecycle Consistency |
| **Location** | `FinallyPolicy` creation site, `ExecutionPipeline` |
| **Impact** | Object creation overhead per execution; contradicts policy as Spring Bean pattern |

If `FinallyPolicy` is created per-call (e.g., `new FinallyPolicy(finalizer)`) while `LoggingPolicy` is a singleton Bean, the policy abstraction has **inconsistent lifecycle semantics**:

- `LoggingPolicy`: Singleton, stateless, Spring-managed
- `FinallyPolicy`: Per-call, stateful (holds finalizer Runnable), not Spring-managed

This means the `ExecutionPolicy` interface conflates two things:
1. **Cross-cutting policies** (logging, metrics) - singleton, stateless
2. **Per-execution behaviors** (finally, recovery) - per-call, stateful

**Proposed Fix:** Separate the concerns:
```java
// Cross-cutting (Spring Bean, singleton)
public interface ExecutionPolicy {
    void beforeExecution(TaskContext context);
    void afterExecution(TaskContext context, Object result);
    void onException(TaskContext context, Exception e);
}

// Per-execution (value object, per-call)
public record ExecutionOptions(
    Runnable finalizer,
    Function<Exception, ?> recovery,
    Function<Exception, RuntimeException> translator,
    Object defaultValue
) {
    public static ExecutionOptions none() { return new ExecutionOptions(null, null, null, null); }

    // Builder pattern
    public static Builder builder() { return new Builder(); }
}
```

---

### P1-3: ExceptionTranslator Strategy Degeneration

| Field | Value |
|-------|-------|
| **Severity** | P1 |
| **Principle** | Strategy Pattern integrity |
| **Location** | `ExceptionTranslator.forXxx()` factory methods |
| **Impact** | 7 factory methods creating identical behavior - Strategy without polymorphism |

See Section 2-2 for fix.

---

### P1-4: withAdditionalPolicies() Potential Mutability

| Field | Value |
|-------|-------|
| **Severity** | P1 |
| **Principle** | Immutability (Section 4) |
| **Location** | `ExecutionPipeline.withAdditionalPolicies()` |
| **Impact** | If mutable, shared pipeline instances could have side effects |

**Verification needed:** Confirm whether this method returns a new instance or modifies `this`.

---

### P1-5: Recovery Lambda 3-Line Rule Violations in Consumers

| Field | Value |
|-------|-------|
| **Severity** | P1 |
| **Principle** | Section 15 (Lambda Hell Prevention) |
| **Location** | Service layer `executeWithRecovery` callsites |
| **Impact** | Complex recovery logic inline violates 3-line rule |

See Section 3-1 for fix.

---

### P1-6: Policy Execution Loop Indentation Risk

| Field | Value |
|-------|-------|
| **Severity** | P1 |
| **Principle** | Section 5 (Max 2 indentation levels) |
| **Location** | `ExecutionPipeline` policy dispatch loop |
| **Impact** | Nested conditional policy execution exceeds 2 levels |

See Section 3-3 for fix.

---

### P2-1: Iterator vs Chain of Responsibility Semantic Mismatch

| Field | Value |
|-------|-------|
| **Severity** | P2 |
| **Principle** | Design Pattern clarity |
| **Location** | Policy execution in `ExecutionPipeline` |
| **Impact** | Documentation/naming may imply CoR but implementation is simple iteration |

---

### P2-2: PolicyOrder Sort Stability

| Field | Value |
|-------|-------|
| **Severity** | P2 |
| **Principle** | Deterministic behavior |
| **Location** | `PolicyOrder` enum / policy sorting |
| **Impact** | If two policies share the same order value, execution order is non-deterministic |

**Fix:** Ensure `PolicyOrder` uses unique ordinal values and the sort is stable (`List.sort()` in Java is stable, but verify `TreeSet` or `PriorityQueue` is not used).

---

## 5. Consolidated Issue Registry

### Previously Discovered (Excluded from this analysis)
| ID | Severity | Issue |
|----|----------|-------|
| P0-1 | P0 | ExceptionTranslator Error guard 7x duplication (DRY) |
| P0-2 | P0 | executeCheckedWithHandler Dead Code (ISP) |
| P0-3 | P0 | executeOrCatch vs executeWithFallback semantic trap |
| P1-1 | P1 | TaskLogSupport Regex performance |
| P1-2 | P1 | Interrupt restoration logic inconsistency |

### Newly Discovered
| ID | Severity | Principle | Issue | Location |
|----|----------|-----------|-------|----------|
| P0-4 | P0 | OCP | Non-composable execution interface | `LogicExecutor.java` |
| P0-5 | P0 | ISP | Fat interface (8 methods, all consumers depend on all) | `LogicExecutor.java` |
| P0-6 | P0 | SRP/Lifecycle | FinallyPolicy per-call vs stateless singleton mismatch | `FinallyPolicy`, `ExecutionPipeline` |
| P1-3 | P1 | Strategy | ExceptionTranslator strategy degeneration (7 identical) | `ExceptionTranslator.forXxx()` |
| P1-4 | P1 | Immutability | withAdditionalPolicies() potential mutation | `ExecutionPipeline` |
| P1-5 | P1 | Section 15 | Recovery lambda 3-line rule violations at callsites | Service layer |
| P1-6 | P1 | Section 5 | Policy loop indentation depth risk | `ExecutionPipeline` |
| P2-1 | P2 | Pattern | Iterator vs CoR semantic mismatch | `ExecutionPipeline` |
| P2-2 | P2 | Determinism | PolicyOrder sort stability | `PolicyOrder` |

---

## 6. Recommended Refactoring Roadmap

### Phase 1: Quick Wins (P0-1 + P1-3 together)
- Extract `defaultErrorGuard()` in ExceptionTranslator
- Eliminates 7x duplication AND fixes strategy degeneration
- **Effort:** 1 hour, zero breaking changes

### Phase 2: Interface Segregation (P0-5)
- Split LogicExecutor into role-based interfaces
- Keep aggregate LogicExecutor for backward compat
- **Effort:** 2-3 hours, zero breaking changes

### Phase 3: Composable Execution (P0-4)
- Introduce ExecutionBuilder
- Migrate complex callsites (executeWithRecovery + executeWithFinally)
- **Effort:** 4-6 hours, additive change

### Phase 4: Policy Lifecycle Cleanup (P0-6)
- Separate cross-cutting policies from per-execution options
- Introduce ExecutionOptions record
- **Effort:** 4-6 hours, internal refactor

### Phase 5: Consumer Cleanup (P1-5, P1-6)
- Audit all executeWithRecovery callsites for 3-line rule
- Extract recovery lambdas to private methods
- Flatten policy execution loops
- **Effort:** 2-3 hours

---

## 7. Architectural Verdict

The LogicExecutor Pipeline is a **well-intentioned architectural investment** that successfully eliminates try-catch proliferation across the codebase. The Template Method / Strategy composition is sound. However, the interface has grown organically and now exhibits **ISP and OCP violations** that will compound as new execution patterns are needed.

The most critical finding is **P0-4 (OCP)**: the current design forces interface modification for every new execution variant. Combined with **P0-5 (ISP)**, this creates a "gravity well" where the LogicExecutor interface becomes an ever-growing God Interface.

The recommended path forward is a **composable builder pattern** layered on top of the existing interface, allowing gradual migration without breaking changes. Phase 1 (ExceptionTranslator DRY fix) and Phase 2 (ISP split) deliver the highest ROI with minimal risk.

**Overall Assessment: WARN** - Architecturally sound foundation with structural debt that should be addressed before the next feature wave.
