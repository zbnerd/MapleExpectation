package maple.expectation.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.strategy.RedisBufferStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Buffer 복구 스케줄러 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 * <ul>
 *   <li>Retry Queue 처리: 지연 시간이 지난 메시지를 Main Queue로 복귀</li>
 *   <li>INFLIGHT Re-drive: 타임아웃된 메시지를 Main Queue로 복귀</li>
 * </ul>
 *
 * <h3>GPT-5 Iteration 4 반영</h3>
 * <ul>
 *   <li>(C) Retry ZSET 메커니즘 - Delayed Retry 지원</li>
 *   <li>(1) ACK/Redrive 레이스 Lua 원자화</li>
 * </ul>
 *
 * <h3>스케줄링 주기</h3>
 * <ul>
 *   <li>processRetryQueue: 5초 (Retry → Main Queue)</li>
 *   <li>redriveExpiredInflight: 30초 (타임아웃 메시지 복구)</li>
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Red (SRE): 타임아웃 기반 자동 복구로 메시지 유실 방지</li>
 *   <li>Green (Performance): 배치 처리로 Redis RTT 최소화</li>
 *   <li>Purple (Auditor): Lua Script 원자성으로 레이스 컨디션 방지</li>
 * </ul>
 *
 * @see RedisBufferStrategy Redis 버퍼 전략
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(RedisBufferStrategy.class)
@ConditionalOnProperty(
        name = "scheduler.buffer-recovery.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class BufferRecoveryScheduler {

    private final RedisBufferStrategy<?> redisBufferStrategy;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    /** INFLIGHT 타임아웃 (기본값: 60초) */
    @Value("${buffer.inflight.timeout-ms:60000}")
    private long inflightTimeoutMs;

    /** 배치 처리 크기 (기본값: 100) */
    @Value("${buffer.recovery.batch-size:100}")
    private int batchSize;

    /**
     * Retry Queue 처리 (5초)
     *
     * <p>지연 시간이 지난 재시도 메시지를 Main Queue로 복귀시킵니다.</p>
     *
     * <h4>처리 흐름</h4>
     * <pre>
     * Retry Queue (ZSET: score=nextAttemptAt)
     *     ↓ ZRANGEBYSCORE 0 ~ now LIMIT 100
     * Main Queue (List)
     * </pre>
     */
    @Scheduled(fixedRateString = "${scheduler.buffer-recovery.retry-rate:5000}")
    public void processRetryQueue() {
        executor.executeVoid(
                this::doProcessRetryQueue,
                TaskContext.of("Scheduler", "Buffer.ProcessRetry")
        );
    }

    private void doProcessRetryQueue() {
        List<String> processed = redisBufferStrategy.processRetryQueue(batchSize);

        if (!processed.isEmpty()) {
            meterRegistry.counter("buffer.scheduler.retry.processed")
                    .increment(processed.size());
            log.info("[BufferRecovery] Processed {} retry messages", processed.size());
        }
    }

    /**
     * 만료된 INFLIGHT 메시지 Re-drive (30초)
     *
     * <p>타임아웃된 INFLIGHT 메시지를 Main Queue로 복귀시킵니다.</p>
     *
     * <h4>처리 흐름</h4>
     * <pre>
     * INFLIGHT TS (ZSET: score=timestamp)
     *     ↓ ZRANGEBYSCORE 0 ~ (now - timeout) LIMIT 100
     * INFLIGHT (List)
     *     ↓ LPOS 확인 후 LREM + RPUSH (Lua 원자성)
     * Main Queue (List)
     * </pre>
     *
     * <h4>GPT-5 Iteration 4 (1)</h4>
     * <p>Lua Script로 ACK/Redrive 레이스 컨디션 방지</p>
     */
    @Scheduled(fixedRateString = "${scheduler.buffer-recovery.redrive-rate:30000}")
    public void redriveExpiredInflight() {
        executor.executeVoid(
                this::doRedriveExpiredInflight,
                TaskContext.of("Scheduler", "Buffer.Redrive")
        );
    }

    private void doRedriveExpiredInflight() {
        // 만료된 INFLIGHT 메시지 조회
        List<String> expiredMsgIds = redisBufferStrategy.getExpiredInflightMessages(inflightTimeoutMs, batchSize);

        if (expiredMsgIds.isEmpty()) {
            return;
        }

        int redriveCount = 0;
        int skipCount = 0;

        for (String msgId : expiredMsgIds) {
            boolean redriven = redisBufferStrategy.redrive(msgId);
            if (redriven) {
                redriveCount++;
            } else {
                skipCount++;  // 이미 ACK된 경우
            }
        }

        if (redriveCount > 0) {
            meterRegistry.counter("buffer.scheduler.redrive.success")
                    .increment(redriveCount);
            log.warn("[BufferRecovery] Redriven {} expired INFLIGHT messages (skipped: {})",
                    redriveCount, skipCount);
        }
    }
}
