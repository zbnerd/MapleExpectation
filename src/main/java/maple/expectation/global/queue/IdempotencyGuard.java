package maple.expectation.global.queue;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 멱등성 보장 가드 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 * <p>Redis SETNX 패턴을 사용하여 메시지 중복 처리를 방지합니다.</p>
 *
 * <h3>GPT-5 Iteration 4 반영</h3>
 * <ul>
 *   <li>(A) ACK는 msgId 기반 - 멱등성 키로 msgId 사용</li>
 *   <li>At-Least-Once 환경에서 Consumer 레벨 멱등성 보장</li>
 * </ul>
 *
 * <h3>사용 패턴</h3>
 * <pre>{@code
 * // Consumer 코드에서:
 * if (idempotencyGuard.tryAcquire(msgId)) {
 *     try {
 *         // 비즈니스 로직 처리
 *         buffer.ack(msgId);
 *         idempotencyGuard.markCompleted(msgId);
 *     } catch (Exception e) {
 *         buffer.nack(msgId, retryCount);
 *         idempotencyGuard.release(msgId);  // 재시도 허용
 *     }
 * } else {
 *     // 이미 처리 중이거나 완료됨 - 스킵
 *     buffer.ack(msgId);
 * }
 * }</pre>
 *
 * <h3>키 구조</h3>
 * <pre>
 * {idempotency}:job:{jobType}:{msgId} = "PROCESSING" | "COMPLETED"
 * TTL: 24시간 (완료 후 자동 삭제)
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Purple (Auditor): SETNX 원자성으로 중복 처리 방지</li>
 *   <li>Red (SRE): TTL로 키 누적 방지</li>
 *   <li>Green (Performance): 단일 Redis 호출로 판정</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    /** 멱등성 키 TTL (기본값: 24시간) */
    @Value("${idempotency.ttl-hours:24}")
    private int ttlHours;

    /**
     * 처리 시작 시도 (SETNX 패턴)
     *
     * <p>키가 없으면 PROCESSING으로 설정하고 true 반환,
     * 키가 있으면 false 반환 (이미 처리 중 또는 완료)</p>
     *
     * @param jobType 작업 유형 (예: "expectation", "like")
     * @param msgId 메시지 ID
     * @return true: 처리 가능, false: 이미 처리 중 또는 완료
     */
    public boolean tryAcquire(String jobType, String msgId) {
        String key = buildKey(jobType, msgId);

        return executor.executeOrDefault(
                () -> {
                    RBucket<String> bucket = redissonClient.getBucket(key);
                    boolean acquired = bucket.setIfAbsent(STATUS_PROCESSING, Duration.ofHours(ttlHours));

                    if (acquired) {
                        meterRegistry.counter("idempotency.acquire.success", "job", jobType).increment();
                        log.debug("[IdempotencyGuard] Acquired: {} -> {}", jobType, msgId);
                    } else {
                        String currentStatus = bucket.get();
                        meterRegistry.counter("idempotency.acquire.skip", "job", jobType, "status", currentStatus)
                                .increment();
                        log.debug("[IdempotencyGuard] Skip ({}): {} -> {}", currentStatus, jobType, msgId);
                    }

                    return acquired;
                },
                false,
                TaskContext.of("Idempotency", "TryAcquire", msgId)
        );
    }

    /**
     * 처리 완료 마킹
     *
     * <p>PROCESSING → COMPLETED로 상태 변경 (TTL 유지)</p>
     *
     * @param jobType 작업 유형
     * @param msgId 메시지 ID
     */
    public void markCompleted(String jobType, String msgId) {
        String key = buildKey(jobType, msgId);

        executor.executeVoid(
                () -> {
                    RBucket<String> bucket = redissonClient.getBucket(key);
                    bucket.set(STATUS_COMPLETED, ttlHours, TimeUnit.HOURS);

                    meterRegistry.counter("idempotency.completed", "job", jobType).increment();
                    log.debug("[IdempotencyGuard] Completed: {} -> {}", jobType, msgId);
                },
                TaskContext.of("Idempotency", "MarkCompleted", msgId)
        );
    }

    /**
     * 처리 포기/재시도 허용 (키 삭제)
     *
     * <p>처리 실패 시 호출하여 재시도 가능하도록 키 삭제</p>
     *
     * @param jobType 작업 유형
     * @param msgId 메시지 ID
     */
    public void release(String jobType, String msgId) {
        String key = buildKey(jobType, msgId);

        executor.executeVoid(
                () -> {
                    boolean deleted = redissonClient.getBucket(key).delete();

                    if (deleted) {
                        meterRegistry.counter("idempotency.release.success", "job", jobType).increment();
                        log.debug("[IdempotencyGuard] Released: {} -> {}", jobType, msgId);
                    } else {
                        meterRegistry.counter("idempotency.release.not_found", "job", jobType).increment();
                        log.debug("[IdempotencyGuard] Release not found: {} -> {}", jobType, msgId);
                    }
                },
                TaskContext.of("Idempotency", "Release", msgId)
        );
    }

    /**
     * 상태 조회
     *
     * @param jobType 작업 유형
     * @param msgId 메시지 ID
     * @return 상태 (PROCESSING, COMPLETED, 또는 null)
     */
    public String getStatus(String jobType, String msgId) {
        String key = buildKey(jobType, msgId);

        return executor.executeOrDefault(
                () -> redissonClient.<String>getBucket(key).get(),
                null,
                TaskContext.of("Idempotency", "GetStatus", msgId)
        );
    }

    /**
     * 이미 완료된 메시지인지 확인
     *
     * @param jobType 작업 유형
     * @param msgId 메시지 ID
     * @return true: 완료됨
     */
    public boolean isCompleted(String jobType, String msgId) {
        return STATUS_COMPLETED.equals(getStatus(jobType, msgId));
    }

    /**
     * Redis 키 빌드
     *
     * <p>Hash Tag 패턴: {idempotency}:job:{jobType}:{msgId}</p>
     *
     * @param jobType 작업 유형
     * @param msgId 메시지 ID
     * @return Redis 키
     */
    private String buildKey(String jobType, String msgId) {
        return RedisKey.IDEMPOTENCY_PREFIX.getKey() + "job:" + jobType + ":" + msgId;
    }
}
