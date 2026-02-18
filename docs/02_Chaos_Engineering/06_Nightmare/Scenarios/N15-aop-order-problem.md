# Nightmare 15: AOP Order Problem

> **담당 에이전트**: 🔵 Blue (아키텍처) & 🟣 Purple (감사)
> **난이도**: P2 (Medium)
> **예상 결과**: PASS

---

## 0. 최신 테스트 결과 (2025-01-20)

### ✅ PASS (6/6 테스트 성공)

| 테스트 메서드 | 결과 | 설명 |
|-------------|------|------|
| `shouldHaveExplicitOrderOnAllAspects()` | ✅ PASS | 모든 Aspect에 @Order 지정 |
| `shouldExecuteInCorrectOrder()` | ✅ PASS | AOP 실행 순서 일관성 |
| `shouldMaintainTransactionBoundary()` | ✅ PASS | 트랜잭션 경계 보호 |
| `shouldRollbackWithAuditLog()` | ✅ PASS | 롤백 시 감사 로그 일관성 |
| `shouldUseTransactionalEventListener()` | ✅ PASS | 커밋 후 이벤트 발행 |
| `shouldHaveConsistentOrderValues()` | ✅ PASS | Order 값 일관성 |

### 🟢 성공 원인
- **명시적 @Order 지정**: 모든 Aspect에 Order 어노테이션 적용
- **트랜잭션 이벤트 활용**: @TransactionalEventListener로 데이터 일관성 확보
- **문서화된 Order 값**: 각 Aspect의 순서와 이유 명시

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
@Order 미지정 시 AOP 어드바이스 실행 순서가 비결정적이 되어
@Transactional과 커스텀 AOP 간 예상치 못한 동작이 발생하는 문제를 검증한다.

### 검증 포인트
- [ ] @Order 미지정 시 실행 순서 일관성
- [ ] @Transactional과 커스텀 AOP 순서
- [ ] 트랜잭션 롤백 시 감사 로그 일관성

### 성공 기준
- AOP 실행 순서가 항상 일관됨

---

## 2. 문제 상황 (🔵 Blue's Analysis)

### @Order 기본값
```java
@Aspect
public class AuditAspect {  // Order 없음 → LOWEST_PRECEDENCE
    @Around("@annotation(Audited)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        // 감사 로그 작성
    }
}

@Transactional  // 기본 Order: LOWEST_PRECEDENCE
public void saveOrder(Order order) {
    repository.save(order);
}
```

### 실행 순서 문제
```
AuditAspect(@Order 없음) vs @Transactional(LOWEST_PRECEDENCE)
→ 어떤 것이 먼저 실행될지 불확실!

만약 AuditAspect가 먼저 실행되면:
1. 감사 로그 기록 (트랜잭션 외부!)
2. @Transactional 시작
3. 예외 발생 → 롤백
4. 문제: 감사 로그는 남아있음 (불일치)
```

---

## 3. 해결 방안

### 명시적 @Order 지정
```java
@Aspect
@Order(1)  // 가장 먼저 실행 (outermost)
public class SecurityAspect { }

@Aspect
@Order(2)
public class AuditAspect { }

// @Transactional은 기본적으로 LOWEST_PRECEDENCE
// 따라서 innermost에서 실행됨
```

### 트랜잭션 이벤트 활용
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    auditLog.record("Order created: " + event.getOrderId());
    // 트랜잭션 커밋 후에만 실행 → 일관성 보장
}
```

---

## 4. 관련 CS 원리

### AOP Ordering
숫자가 낮을수록 먼저 실행 (outermost).
@Order(1)이 @Order(2)보다 먼저 실행됨.

### Decorator Pattern
AOP는 데코레이터 패턴의 구현.
각 어드바이스가 타겟 메서드를 래핑.

```
@Order(1) → @Order(2) → @Transactional → Target Method
   ↑                                           ↓
   └───────────── return path ─────────────────┘
```

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

## 5. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **PASS**

프로젝트의 모든 AOP 어드바이스가 **명시적 @Order**를 지정하여
실행 순서가 일관되게 유지됨을 확인.

### 기술적 인사이트
- **명시적 Order 지정**: TraceAspect, SecurityAspect 등 모든 Aspect에 @Order 적용
- **트랜잭션 경계 보호**: @Transactional이 innermost에서 실행되어 롤백 범위 명확
- **감사 로그 일관성**: 트랜잭션 커밋/롤백과 감사 로그 기록 순서 보장
- **TransactionalEventListener 활용**: 커밋 후 이벤트 발행으로 데이터 일관성 확보

### 권장 유지 사항
1. **@Order 필수 적용**: 새로운 Aspect 추가 시 반드시 Order 지정
2. **Order 값 문서화**: 각 Aspect의 Order 값과 이유 주석으로 명시
3. **트랜잭션 이벤트 활용**: 감사 로그는 @TransactionalEventListener 사용 권장
4. **코드 리뷰 체크리스트**: Aspect 추가 시 Order 확인 항목 포함

---

## Fail If Wrong

This test is invalid if:
- [ ] Test environment uses different AOP configuration
- [ ] Proxy type differs (JDK vs CGLIB)
- [ ] Spring AOP not properly enabled
- [ ] Test doesn't scan all relevant packages
- [ ] @Order annotation processing disabled

---

*Generated by 5-Agent Council*
