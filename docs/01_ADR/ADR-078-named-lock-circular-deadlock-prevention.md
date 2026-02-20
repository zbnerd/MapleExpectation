# ADR-078: Named Lock 역순 획득 Circular Deadlock 방지

## Status
**Accepted** (2026-02-20)

## Context

### 1장: 문제의 발견 (Problem)

#### 1.1 Circular Deadlock 발생 사례

**Issue #228 (Nightmare-09)**에서 **Named Lock을 역순으로 획득하여 Circular Deadlock이 발생**하는 P0 장애가 확인되었습니다.

**장애 상황**:
```
Thread-1: Lock("character:A") → Lock("equipment:B")  [대기 중]
Thread-2: Lock("equipment:B") → Lock("character:A")  [대기 중]

결과: 서로가 상대가 가진 Lock을 기다리며 교착 상태 발생
```

**기존 코드의 문제**:
```java
// Anti-Pattern: Lock 획득 순서 불일치
@Service
@RequiredArgsConstructor
public class CharacterService {

    public void updateCharacterWithEquipment(String characterIgn, String equipmentId) {
        // 순서: character → equipment
        lockManager.executeWithLock("character:" + characterIgn, () -> {
            lockManager.executeWithLock("equipment:" + equipmentId, () -> {
                // 비즈니스 로직
            });
        });
    }
}

@Service
@RequiredArgsConstructor
public class EquipmentService {

    public void updateEquipmentWithCharacter(String equipmentId, String characterIgn) {
        // 순서: equipment → character (역순!)
        lockManager.executeWithLock("equipment:" + equipmentId, () -> {
            lockManager.executeWithLock("character:" + characterIgn, () -> {
                // 비즈니스 로직
            });
        });
    }
}
```

**결과**: 두 메서드가 동시에 호출되면 **Deadlock 발생**.

#### 1.2 Deadlock의 영향

1. **요청 타임아웃**: Deadlock에 걸린 모든 요청이 영원히 대기
2. **Thread Pool 고갈**: 대기 Thread가 누적되어 새로운 요청 처리 불가
3. **서비스 중단**: 사용자가 장비/캐릭터 정보를 조회/수정할 수 없음

#### 1.3 근본 원인

**Lock Ordering 미준수**: 다중 Lock을 획득할 때 **전역적으로 일관된 순서**를 정의하지 않았습니다.

---

### 2장: 선택지 탐색 (Options)

#### 2.1 선택지 1: Lock Timeout 설정

**방식**: Redisson Lock에 timeout 설정으로 Deadlock 시 타임아웃

```java
RLock lock1 = redissonClient.getLock("character:" + ign);
RLock lock2 = redissonClient.getLock("equipment:" + id);

if (lock1.tryLock(5, TimeUnit.SECONDS)) {
    if (lock2.tryLock(5, TimeUnit.SECONDS)) {
        // 비즈니스 로직
    }
}
```

**장점**:
- 구현이 간단함

**단점**:
- **Deadlock 해결이 아님**: 타임아웃은 "Deadlock을 감지하고 기다림"이지 "예방"이 아님
- **성능 저하**: 타임아웃 대기 시간 동안 Thread가 낭비됨
- **예측 불가능**: 어느 Thread가 타임아웃될지 알 수 없음

**결론**: **임시 방편일 뿐 근본 해결책 아님**

---

#### 2.2 선택지 2: 단일 Lock으로 통합

**방식**: 모든 관련 리소스를 하나의 Lock으로 보호

```java
// "character:A:equipment:B"와 "equipment:B:character:A"를 모두 "A:B"로 정규화
String globalLockKey = Stream.of(characterIgn, equipmentId)
    .sorted()  // 알파벳 순 정렬
    .collect(Collectors.joining(":"));

lockManager.executeWithLock(globalLockKey, () -> {
    // 비즈니스 로직
});
```

**장점**:
- **Deadlock 불가**: 단일 Lock이므로 순서 문제 없음

**단점**:
- **Lock 경합 증가**: 불필요한 리소스까지 Lock이 공유됨
- **세분성 저하**: 동시에 처리 가능한 작업이 줄어듦
- **확장성 문제**: 3개 이상의 리소스를 Lock해야 할 때 Key 생성이 복잡해짐

**결론**: **동시성 저하로 인해 채택 부적합**

---

#### 2.3 선택지 3: Lock Ordering 전역 규칙 정의 및 강제 (선택)

**방식**:
1. **Lock Key에 접두사(Prefix)를 부여하여 타입별로 그룹화**
2. **전역 순서 규칙 정의**: `character < equipment < cube < donation`
3. **다중 Lock 획득 시 항상 순서대로 획득**

```java
// 전역 Lock 순서 규칙
public enum LockType {
    CHARACTER(1, "character"),
    EQUIPMENT(2, "equipment"),
    CUBE(3, "cube"),
    DONATION(4, "donation");

    private final int order;
    private final String prefix;

    LockType(int order, String prefix) {
        this.order = order;
        this.prefix = prefix;
    }

    public String getLockKey(String id) {
        return String.format("%02d:%s:%s", order, prefix, id);
        // 예: "01:character:A", "02:equipment:B"
    }
}

// Lock Manager에서 자동 정렬
public class OrderedLockManager {

    public void executeWithLocks(List<LockType> types, List<String> ids, Runnable action) {
        // Lock Key를 순서대로 정렬
        List<String> lockKeys = IntStream.range(0, types.size())
            .mapToObj(i -> types.get(i).getLockKey(ids.get(i)))
            .sorted()  // 자동 정렬
            .toList();

        acquireLocksInOrder(lockKeys, action);
    }
}
```

**사용 예시**:
```java
// CharacterService에서
orderedLockManager.executeWithLocks(
    List.of(CHARACTER, EQUIPMENT),
    List.of(ign, equipmentId),
    () -> { /* 비즈니스 로직 */ }
);

// EquipmentService에서도 동일하게
orderedLockManager.executeWithLocks(
    List.of(CHARACTER, EQUIPMENT),  // 순서 동일!
    List.of(characterIgn, equipmentId),
    () -> { /* 비즈니스 로직 */ }
);
```

**장점**:
- **Deadlock 완전 예방**: Lock 획득 순서가 전역적으로 일관됨
- **최소한의 Lock 경합**: 필요한 리소스만 Lock
- **확장성**: 새로운 LockType 추가 시 순서만 지정

**단점**:
- **코드 변경 필요**: 기존 Lock 호출 코드 수정
- **러닝 커브**: 개발자가 Lock Ordering 규칙을 이해해야 함

**결론**: **가장 근원적이고 확장 가능한 해결책**

---

### 3장: 결정의 근거 (Decision)

#### 3.1 선택: Lock Ordering 전역 규칙 정의 및 강제

MapleExpectation 프로젝트는 **선택지 3: Lock Ordering 전역 규칙 정의 및 강제**를 채택했습니다.

**결정 근거**:
1. **Deadlock은 P0 장애**: 서비스 중단을 초래하므로 근원적 예방 필수
2. **Lock Ordering은 Deadlock 예방의 정석**: OS/Database 분야에서 검증된 기법
3. **확장성**: 새로운 리소스 타입 추가 시에도 규칙 적용 가능

---

### 4장: 구현의 여정 (Action)

#### 4.1 LockType Enum 정의

**파일**: `maple/expectation/lock/LockType.java`

```java
package maple.expectation.lock;

import lombok.Getter;

/**
 * Lock 획득 순서 전역 규칙
 *
 * 중요: 순서(order)를 변경할 때는 기존 Lock과의 호환성 검토 필수
 *
 * 순서 규칙: 작은 숫자일수록 먼저 획득
 * 1. CHARACTER (캐릭터)
 * 2. EQUIPMENT (장비)
 * 3. CUBE (큐브)
 * 4. DONATION (후원)
 * 5. LIKE (좋아요)
 */
@Getter
public enum LockType {
    CHARACTER(1, "character"),
    EQUIPMENT(2, "equipment"),
    CUBE(3, "cube"),
    DONATION(4, "donation"),
    LIKE(5, "like");

    private final int order;
    private final String prefix;

    LockType(int order, String prefix) {
        this.order = order;
        this.prefix = prefix;
    }

    /**
     * Lock Key 생성
     * 포맷: "02:equipment:B" (순서:접두사:ID)
     */
    public String getLockKey(String id) {
        return String.format("%02d:%s:%s", order, prefix, id);
    }

    /**
     * 여러 Lock Key를 순서대로 정렬
     */
    public static List<String> sortLockKeys(List<LockKey> lockKeys) {
        return lockKeys.stream()
            .map(LockKey::getFullKey)
            .sorted()  // 문자열 정렬로 자동 순서화
            .toList();
    }

    public record LockKey(LockType type, String id) {
        public String getFullKey() {
            return type.getLockKey(id);
        }
    }
}
```

#### 4.2 OrderedLockManager 구현

**파일**: `maple/expectation/lock/OrderedLockManager.java`

```java
package maple.expectation.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderedLockManager {

    private final RedissonClient redissonClient;
    private static final long LOCK_LEASE_TIME = 10;  // 초

    /**
     * 여러 Lock을 순서대로 획득 후 작업 실행
     *
     * @param lockKeys Lock 타입과 ID 목록 (순서 무관, 자동 정렬됨)
     * @param action 실행할 작업
     */
    public void executeWithLocks(List<LockType.LockKey> lockKeys, Runnable action) {
        // Lock Key를 순서대로 정렬
        List<String> sortedKeys = LockType.sortLockKeys(lockKeys);

        List<RLock> locks = sortedKeys.stream()
            .map(redissonClient::getLock)
            .toList();

        // 모든 Lock 획득 (순서대로)
        acquireAll(locks);

        try {
            action.run();
        } finally {
            // 모든 Lock 해제
            releaseAll(locks);
        }
    }

    private void acquireAll(List<RLock> locks) {
        for (RLock lock : locks) {
            try {
                boolean acquired = lock.tryLock(1, LOCK_LEASE_TIME, TimeUnit.SECONDS);
                if (!acquired) {
                    throw new ServerTimeoutException("Lock 획득 타임아웃: " + lock.getName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServerBaseException("Lock 획득 중단", e);
            }
        }
    }

    private void releaseAll(List<RLock> locks) {
        // 역순으로 해제
        for (int i = locks.size() - 1; i >= 0; i--) {
            try {
                if (locks.get(i).isHeldByCurrentThread()) {
                    locks.get(i).unlock();
                }
            } catch (Exception e) {
                log.warn("Lock 해제 실패 (무시): {}", locks.get(i).getName(), e);
            }
        }
    }
}
```

#### 4.3 기존 코드 Lock Ordering 적용

**Before (Deadlock 위험)**:
```java
@Service
@RequiredArgsConstructor
public class CharacterService {
    private final RedissonClient redissonClient;

    public void updateCharacterWithEquipment(String ign, String equipmentId) {
        RLock characterLock = redissonClient.getLock("character:" + ign);
        RLock equipmentLock = redissonClient.getLock("equipment:" + equipmentId);

        characterLock.lock();
        equipmentLock.lock();  // Deadlock 위험!
        try {
            // 비즈니스 로직
        } finally {
            equipmentLock.unlock();
            characterLock.unlock();
        }
    }
}
```

**After (Deadlock 방지)**:
```java
@Service
@RequiredArgsConstructor
public class CharacterService {
    private final OrderedLockManager lockManager;

    public void updateCharacterWithEquipment(String ign, String equipmentId) {
        lockManager.executeWithLocks(
            List.of(
                new LockType.LockKey(CHARACTER, ign),
                new LockType.LockKey(EQUIPMENT, equipmentId)
            ),
            () -> {
                // 비즈니스 로직
            }
        );
    }
}
```

#### 4.4 단위 테스트

**파일**: `maple/expectation/lock/OrderedLockManagerTest.java`

```java
@SpringBootTest
class OrderedLockManagerTest {

    @Autowired
    private OrderedLockManager lockManager;

    @Test
    void deadlock_테스트_역순_Lock_획득() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        // Thread 1: A → B 순서
        CompletableFuture.runAsync(() -> {
            lockManager.executeWithLocks(
                List.of(
                    new LockType.LockKey(CHARACTER, "A"),
                    new LockType.LockKey(EQUIPMENT, "B")
                ),
                () -> {
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                    successCount.incrementAndGet();
                }
            );
            latch.countDown();
        });

        // Thread 2: B → A 역순 (자동 정렬되어 A → B로 변경됨)
        CompletableFuture.runAsync(() -> {
            lockManager.executeWithLocks(
                List.of(
                    new LockType.LockKey(EQUIPMENT, "B"),
                    new LockType.LockKey(CHARACTER, "A")
                ),
                () -> {
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                    successCount.incrementAndGet();
                }
            );
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(2, successCount.get());  // Deadlock 없이 둘 다 성공
    }
}
```

---

### 5장: 결과와 학습 (Result)

#### 5.1 성과

1. **Deadlock 완전 예방**: Lock 획득 순서가 전역적으로 일관됨
2. **코드 가독성 향상**: `OrderedLockManager.executeWithLocks()`로 의도가 명확함
3. **확장성**: 새로운 LockType 추가 시 `enum`에만 추가하면 됨

#### 5.2 학습한 점

1. **Deadlock은 Lock Ordering으로 예방**: 타임아웃은 해결책이 아님
2. **전역 규칙 준수的重要性**: 모든 개발자가 동일한 규칙을 따라야 함
3. **자동 정렬의 편리성**: `sorted()`로 개발자가 순서를 신경 쓰지 않아도 됨

#### 5.3 향후 개선 방향

- **Compile-time Check**: Annotation Processor로 Lock Ordering 검증
- **Deadlock Detection**: 주기적으로 Lock 대기 그래프 분석

---

## Consequences

### 긍정적 영향
- **안정성 확보**: Circular Deadlock으로 인한 서비스 중단 방지
- **예측 가능성**: Lock 획득 순서가 명확하여 동시성 로직 이해 쉬움

### 부정적 영향
- **코드 변경 비용**: 기존 Lock 호출 코드를 `OrderedLockManager`로 전환 필요
- **러닝 커브**: 신규 개발자가 Lock Ordering 개념을 학습해야 함

### 위험 완화
- **Migration 가이드**: 기존 코드 전환을 위한 가이드 문서 작성
- **코드 리뷰 강화**: Lock 사용 시 PR Review에서 Ordering 준수 확인

---

## References

- **Issue #228**: [P0][Nightmare-09] Named Lock 역순 획득으로 인한 Circular Deadlock 발생
- **Issue #221**: [P0][Nightmare-02] Lock Ordering 미적용으로 인한 Deadlock 발생
- **ADR-047**: Redisson Watchdog를 사용한 회복탄력적 분산 락과 MySQL Fallback
- **Database System Concepts - Chapter 16 (Deadlock Prevention)**
