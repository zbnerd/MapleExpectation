package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 순서 보장 다중 락 실행기 (Issue #221: N02-Lock Ordering Deadlock)
 *
 * <h3>목적</h3>
 * <p>Coffman Condition #4 (Circular Wait)를 방지하여 Deadlock을 예방합니다.
 * 락 키를 알파벳순으로 정렬하여 모든 스레드가 동일한 순서로 락을 획득합니다.</p>
 *
 * <h3>5-Agent Council 피드백 반영</h3>
 * <ul>
 *   <li>🔵 Blue Agent: 순차 획득 방식으로 진정한 Deadlock Prevention</li>
 *   <li>🟢 Green Agent: 반복 패턴 사용 (재귀 대신), System.nanoTime() 정밀도</li>
 *   <li>🔴 Red Agent: deadline 기반 타임아웃으로 30초 상한 보장 (P0-RED-01)</li>
 * </ul>
 *
 * <h3>CLAUDE.md 준수사항</h3>
 * <ul>
 *   <li>Section 12: Zero Try-Catch Policy - LogicExecutor 패턴 사용</li>
 *   <li>Section 6: 생성자 주입 (@RequiredArgsConstructor)</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * // 계좌 이체: A -> B 순서 보장 (알파벳순 정렬로 Deadlock 방지)
 * orderedLockExecutor.executeWithOrderedLocks(
 *     List.of("account:user1", "account:user2"),
 *     30, TimeUnit.SECONDS, 60,
 *     () -> transferService.transfer(user1, user2, amount)
 * );
 * }</pre>
 *
 * @see LockStrategy
 * @since 2026-01-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderedLockExecutor {

    private final LockStrategy lockStrategy;
    private final LogicExecutor executor;

    /**
     * 순서 보장 다중 락 실행 (반복 패턴)
     *
     * <p><b>Green Agent 권장사항 반영</b>:
     * <ul>
     *   <li>재귀 대신 반복 패턴으로 스택 오버플로우 방지</li>
     *   <li>System.nanoTime()으로 정밀한 타임아웃 계산</li>
     *   <li>단일 finally 블록에서 LIFO 순서 락 해제</li>
     * </ul>
     *
     * @param keys 락 키 목록 (내부에서 알파벳순 정렬됨)
     * @param totalTimeout 전체 타임아웃 값
     * @param timeUnit 타임아웃 단위
     * @param leaseTime 각 락의 유지 시간 (초)
     * @param task 실행할 작업
     * @return 작업 결과
     */
    public <T> T executeWithOrderedLocks(
            List<String> keys,
            long totalTimeout,
            TimeUnit timeUnit,
            long leaseTime,
            ThrowingSupplier<T> task
    ) {
        TaskContext context = TaskContext.of("OrderedLock", "Execute", String.join(",", keys));

        return executor.execute(
                () -> executeWithOrderedLocksInternal(keys, totalTimeout, timeUnit, leaseTime, task),
                context
        );
    }

    /**
     * 내부 구현: 반복 패턴으로 락 획득 및 실행
     *
     * <p><b>알고리즘</b>:
     * <ol>
     *   <li>키를 알파벳순 정렬 (Circular Wait 조건 제거)</li>
     *   <li>deadline 계산 (전체 타임아웃 상한)</li>
     *   <li>순차적으로 각 락 획득 (남은 시간 기반)</li>
     *   <li>모든 락 획득 후 작업 실행</li>
     *   <li>finally: LIFO 순서로 락 해제</li>
     * </ol>
     */
    private <T> T executeWithOrderedLocksInternal(
            List<String> keys,
            long totalTimeout,
            TimeUnit timeUnit,
            long leaseTime,
            ThrowingSupplier<T> task
    ) throws Throwable {
        // 1. 정렬하여 Circular Wait 조건 제거
        List<String> sortedKeys = keys.stream()
                .sorted()
                .toList();

        log.debug("[OrderedLock] Acquiring {} locks in order: {}", sortedKeys.size(), sortedKeys);

        // 2. [P0-RED-01] deadline 계산 (나노초 정밀도)
        long deadlineNanos = System.nanoTime() + timeUnit.toNanos(totalTimeout);

        // 3. 획득한 락 추적 (LIFO 해제용)
        List<String> acquiredLocks = new ArrayList<>(sortedKeys.size());

        try {
            // 4. 순차적 락 획득
            for (int i = 0; i < sortedKeys.size(); i++) {
                String currentKey = sortedKeys.get(i);

                // [P0-RED-01] 남은 시간 계산
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new DistributedLockException(
                            String.format("전체 락 타임아웃 초과: %d/%d 락 획득 중 [key=%s]",
                                    i, sortedKeys.size(), currentKey)
                    );
                }

                // 남은 시간을 waitTime으로 변환 (최소 1초, 최대 10초)
                long remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
                long waitTimeSec = Math.max(1, Math.min(remainingSeconds, 10));

                log.debug("[OrderedLock] Acquiring lock {}/{}: {} (remaining: {}ms)",
                        i + 1, sortedKeys.size(), currentKey,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos));

                // 5. 락 획득 시도 (tryLockImmediately 또는 executeWithLock)
                boolean acquired = tryAcquireLock(currentKey, waitTimeSec, leaseTime);
                if (!acquired) {
                    throw new DistributedLockException(
                            String.format("락 획득 실패: %s (waited %ds)", currentKey, waitTimeSec)
                    );
                }

                acquiredLocks.add(currentKey);
            }

            log.info("[OrderedLock] All {} locks acquired, executing task", sortedKeys.size());

            // 6. 작업 실행
            return task.get();

        } finally {
            // 7. LIFO 순서로 락 해제 (역순 순회)
            releaseLocksInReverseOrder(acquiredLocks);
        }
    }

    /**
     * 락 획득 시도
     *
     * <p>tryLockImmediately를 먼저 시도하고, 지원하지 않으면 executeWithLock으로 대체</p>
     */
    private boolean tryAcquireLock(String key, long waitTimeSec, long leaseTime) {
        try {
            return lockStrategy.tryLockImmediately(key, leaseTime);
        } catch (UnsupportedOperationException e) {
            // MySQL Named Lock 등 tryLockImmediately 미지원 시 executeWithLock 사용
            log.debug("[OrderedLock] tryLockImmediately not supported, using executeWithLock for: {}", key);
            try {
                lockStrategy.executeWithLock(key, waitTimeSec, leaseTime, () -> null);
                return true;
            } catch (Throwable t) {
                log.warn("[OrderedLock] Failed to acquire lock: {} - {}", key, t.getMessage());
                return false;
            }
        }
    }

    /**
     * [Green Agent] LIFO 순서로 락 해제
     *
     * <p>역순으로 해제하여 데드락 가능성 최소화</p>
     */
    private void releaseLocksInReverseOrder(List<String> acquiredLocks) {
        for (int i = acquiredLocks.size() - 1; i >= 0; i--) {
            String lockKey = acquiredLocks.get(i);
            try {
                lockStrategy.unlock(lockKey);
                log.debug("[OrderedLock] Released lock: {}", lockKey);
            } catch (Exception e) {
                // 락 해제 실패는 로그만 남기고 계속 진행
                log.warn("[OrderedLock] Failed to release lock: {} - {}", lockKey, e.getMessage());
            }
        }
    }

    /**
     * 편의 메서드: 초 단위 타임아웃
     */
    public <T> T executeWithOrderedLocks(
            List<String> keys,
            long totalTimeoutSeconds,
            long leaseTime,
            ThrowingSupplier<T> task
    ) {
        return executeWithOrderedLocks(keys, totalTimeoutSeconds, TimeUnit.SECONDS, leaseTime, task);
    }
}
