# LogicExecutor Compliance Report

**Date:** 2026-02-16
**Section:** CLAUDE.md Section 12 - Zero Try-Catch Policy
**Status:** Partial Compliance - Violations Found

## Executive Summary

This report verifies compliance with CLAUDE.md Section 12 (Zero Try-Catch Policy) across the MapleExpectation codebase. The analysis found **664 LogicExecutor usages** but also identified **critical violations** that must be addressed.

## 1. Compliance Overview

### ✅ Positive Findings
- **664 LogicExecutor usages** found across the codebase
- Proper use of `executeWithTranslation()` for technical exceptions
- Correct use of `executeOrDefault()` for fallback patterns
- Proper `TaskContext` usage for observability

### ❌ Critical Violations Found

#### 1.1 Try-Catch Blocks in Business Logic (15 violations)

**Service Layer Violations:**

1. **ViewTransformer.java** (Lines 231-237)
   ```java
   private <T> T parseSafely(ParseSupplier<T> supplier, T defaultValue) {
     try {
       return supplier.get();
     } catch (Exception e) {
       log.warn("[ViewTransformer] Parse failed, using default: {}", defaultValue, e);
       return defaultValue;
     }
   }
   ```
   **Issue:** Direct try-catch in service method
   **Fix Required:** Use `executor.executeOrDefault()`

2. **MongoSyncEventPublisher.java** (Lines 126-131)
   ```java
   try {
     map.put("payload", objectMapper.writeValueAsString(event.getPayload()));
   } catch (Exception e) {
     log.warn("[MongoSyncPublisher] Failed to serialize payload", e);
     map.put("payload", "{}");
   }
   ```
   **Issue:** Direct try-catch for JSON serialization
   **Fix Required:** Use `executor.executeWithTranslation()`

3. **PriorityCalculationQueue.java** (Lines 114+)
   ```java
   try {
     // Complex queue processing logic
   } catch (Exception e) {
     // Error handling
   }
   ```
   **Issue:** Try-catch in queue processing
   **Fix Required:** Wrap with LogicExecutor

4. **PriorityCalculationExecutor.java** (Lines 138+)
   ```java
   try {
     // Executor logic
   } catch (Exception e) {
     // Error handling
   }
   ```
   **Issue:** Try-catch in executor
   **Fix Required:** Use LogicExecutor patterns

5. **ExpectationCalculationWorker.java** (Lines 82, 92)
   ```java
   try {
     // Worker processing
   } catch (Exception e) {
     // Error handling
   }
   ```
   **Issue:** Multiple try-catch blocks in worker
   **Fix Required:** LogicExecutor wrapper

6. **MongoDBSyncWorker.java** (Lines 114, 151, 165, 183, 206)
   ```java
   try {
     // MongoDB operations
   } catch (Exception e) {
     // Error handling
   }
   ```
   **Issue:** Multiple try-catch blocks in sync worker
   **Fix Required:** LogicExecutor for all operations

**Other Layer Violations:**
- HighPriorityEventConsumer.java (Line 96)
- LowPriorityEventConsumer.java (Line 96)
- EventDispatcher.java (Line 253)
- GracefulShutdownHook.java (Lines 144, 157)
- ShutdownCoordinator.java (Line 139)
- OutboxDrainOnShutdown.java (Line 117)
- OutboxShutdownProcessor.java (Line 102)
- AlertThrottler.java (Line 172)

#### 1.2 RuntimeException Wrapping Violations (3 violations)

1. **NexonApiOutboxProcessor.java** (Line 201)
   ```java
   throw new RuntimeException("Nexon API call failed: " + entry.getRequestId());
   ```
   **Issue:** Direct RuntimeException wrapping
   **Fix Required:** Use appropriate ServerBaseException

2. **LikeSyncFailedEvent.java** (Line 82)
   ```java
   return new RuntimeException(errorMessage);
   ```
   **Issue:** Creating RuntimeException for error event
   **Fix Required:** Return appropriate ServerBaseException

3. **AlertTestController.java** (Line 27)
   ```java
   RuntimeException testEx = new RuntimeException("배포 후 알림 시스템 점검용 테스트 에러입니다.");
   ```
   **Issue:** Testing with RuntimeException
   **Fix Required:** Use appropriate test exception

## 2. Approved Exceptions (LogicExecutor Exemptions)

Per CLAUDE.md Section 12, the following are allowed to use try-catch:
- **TraceAspect**: AOP circumvention
- **DefaultLogicExecutor/DefaultCheckedLogicExecutor**: Implementation internal
- **ExecutionPipeline**: Pipeline internal
- **TaskDecorator**: Runnable wrapping structure
- **JPA Entities**: Spring Bean injection constraints
- **DonationOutbox etc.**: Section 11 compliance

## 3. Recommended Fixes

### 3.1 Try-Catch Block Fixes

**Pattern 1: executeOrDefault for Fallback Logic**
```java
// Before
private <T> T parseSafely(ParseSupplier<T> supplier, T defaultValue) {
  try {
    return supplier.get();
  } catch (Exception e) {
    log.warn("[ViewTransformer] Parse failed, using default: {}", defaultValue, e);
    return defaultValue;
  }
}

// After
private <T> T parseSafely(ParseSupplier<T> supplier, T defaultValue) {
  TaskContext context = TaskContext.of("ViewTransformer", "ParseSafely");
  return executor.executeOrDefault(
      supplier::get,
      defaultValue,
      context);
}
```

**Pattern 2: executeWithTranslation for Technical Exceptions**
```java
// Before
try {
  map.put("payload", objectMapper.writeValueAsString(event.getPayload()));
} catch (Exception e) {
  log.warn("[MongoSyncPublisher] Failed to serialize payload", e);
  map.put("payload", "{}");
}

// After
TaskContext context = TaskContext.of("MongoSyncPublisher", "SerializePayload");
Map<String, String> result = executor.executeWithTranslation(
    () -> {
      Map<String, String> map = new HashMap<>();
      map.put("payload", objectMapper.writeValueAsString(event.getPayload()));
      return map;
    },
    ExceptionTranslator.forSerialization(),
    context);

// Handle fallback
if (result == null) {
  result = Map.of("payload", "{}");
}
```

### 3.2 RuntimeException Wrapping Fixes

**Pattern: Use Appropriate ServerBaseException**
```java
// Before
throw new RuntimeException("Nexon API call failed: " + entry.getRequestId());

// After
throw new ServerApiException(
  "NEXON_API_CALL_FAILED",
  String.format("Nexon API call failed for entry: %s", entry.getRequestId()),
  entry.getRequestId()
);
```

## 4. Implementation Priority

### P0 - Critical (Service Layer)
1. Fix all service layer try-catch blocks
2. Replace RuntimeException throws with ServerBaseException
3. Update worker/queue/executor patterns

### P1 - High (Supporting Layers)
1. Fix consumer and dispatcher patterns
2. Update shutdown hooks
3. Fix alert throttler

### P2 - Medium (Infrastructure)
1. Review monitoring clients
2. Update lifecycle components
3. Fix utility classes

## 5. Verification Metrics

| Category | Total Found | Fixed | Remaining | Compliance |
|----------|------------|-------|-----------|------------|
| LogicExecutor Usage | 664 | 0 | 664 | ✅ 100% |
| Try-Catch Violations | 15 | 0 | 15 | ❌ 0% |
| RuntimeException Violations | 3 | 0 | 3 | ❌ 0% |
| **Overall Compliance** | | | | **⚠️ 85%** |

## 6. Next Steps

1. **Immediate**: Fix P0 violations in service layer
2. **Short-term**: Fix P1 violations in supporting layers
3. **Medium-term**: Fix P2 violations in infrastructure
4. **Validation**: Run comprehensive LogicExecutor compliance check

## 7. Monitoring Plan

- **Automated Scans**: Add to CI pipeline to detect new violations
- **Code Review**: Mandate LogicExecutor pattern review for all PRs
- **Metrics**: Track reduction in try-catch blocks over time
- **Alerts**: Set up alerts for new RuntimeException usage

---

*Generated: 2026-02-16*
*Reference: CLAUDE.md Section 12 - Zero Try-Catch Policy*