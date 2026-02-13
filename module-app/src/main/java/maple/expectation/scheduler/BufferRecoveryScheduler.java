package maple.expectation.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.queue.strategy.RedisBufferStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Buffer 복구 스케줄러 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 *
 * <ul>
 *   <li>Retry Queue 처리: 지연 시간이 지난 메시지를 Main Queue로 복귀
 *   <li>INFLIGHT Re-drive: 타임아웃된 메시지를 Main Queue로 복귀
 * </ul>
 *
 * <h3>GPT-5 Iteration 4 반영</h3>
 *
 * <ul>
 *   <li>(C) Retry ZSET 메커니즘 - Delayed Retry 지원
 *   <li>(1) ACK/Redrive 레이스 Lua 원자화
 * </ul>
 *
 * <h3>스케줄링 주기</h3>
 *
 * <ul>
 *   <li>processRetryQueue: 5초 (Retry → Main Queue)
 *   <li>redriveExpiredInflight: 30초 (타임아웃 메시지 복구)
 * </ul>
 *
 * <h3>분산 환경 안전 (Issue #283 P1-7)</h3>
 *
 * <p>각 스케줄링 작업에 분산 락을 적용하여 Scale-out 시 중복 실행을 방지합니다.
 *
 * <ul>
 *   <li>waitTime=0: 락 획득 실패 시 즉시 스킵 (다른 인스턴스가 처리 중)
 *   <li>leaseTime: processRetryQueue(30s), redriveExpiredInflight(60s)
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Red (SRE): 타임아웃 기반 자동 복구로 메시지 유실 방지
 *   <li>Green (Performance): 배치 처리로 Redis RTT 최소화
 *   <li>Purple (Auditor): Lua Script 원자성으로 레이스 컨디션 방지
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
    matchIfMissing = true)
public class BufferRecoveryScheduler {

  private final RedisBufferStrategy<?> redisBufferStrategy;
  private final LockStrategy lockStrategy;
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
   * <p>지연 시간이 지난 재시도 메시지를 Main Queue로 복귀시킵니다.
   *
   * <h4>처리 흐름</h4>
   *
   * <pre>
   * Retry Queue (ZSET: score=nextAttemptAt)
   *     ↓ ZRANGEBYSCORE 0 ~ now LIMIT 100
   * Main Queue (List)
   * </pre>
   *
   * <h4>분산 락 (Issue #283 P1-7)</h4>
   *
   * <p>waitTime=0으로 락 획득 실패 시 즉시 스킵하여 중복 처리 방지
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 10초 대기, 재시도는 급하지 않으므로 여유 있게 처리
   */
  @Scheduled(fixedDelayString = "${scheduler.buffer-recovery.retry-rate:10000}")
  public void processRetryQueue() {
    executor.executeOrDefault(
        () ->
            lockStrategy.executeWithLock(
                "scheduler:buffer-recovery:retry",
                0,
                30,
                () -> {
                  doProcessRetryQueue();
                  return null;
                }),
        null,
        TaskContext.of("Scheduler", "Buffer.ProcessRetry"));
  }

  private void doProcessRetryQueue() {
    List<String> processed = redisBufferStrategy.processRetryQueue(batchSize);

    if (!processed.isEmpty()) {
      meterRegistry.counter("buffer.scheduler.retry.processed").increment(processed.size());
      log.info("[BufferRecovery] Processed {} retry messages", processed.size());
    }
  }

  /**
   * 만료된 INFLIGHT 메시지 Re-drive (30초)
   *
   * <p>타임아웃된 INFLIGHT 메시지를 Main Queue로 복귀시킵니다.
   *
   * <h4>처리 흐름</h4>
   *
   * <pre>
   * INFLIGHT TS (ZSET: score=timestamp)
   *     ↓ ZRANGEBYSCORE 0 ~ (now - timeout) LIMIT 100
   * INFLIGHT (List)
   *     ↓ LPOS 확인 후 LREM + RPUSH (Lua 원자성)
   * Main Queue (List)
   * </pre>
   *
   * <h4>GPT-5 Iteration 4 (1)</h4>
   *
   * <p>Lua Script로 ACK/Redrive 레이스 컨디션 방지
   *
   * <h4>분산 락 (Issue #283 P1-7)</h4>
   *
   * <p>waitTime=0으로 락 획득 실패 시 즉시 스킵하여 중복 처리 방지
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 60초 대기, DLQ 처리는 천천히 수행
   */
  @Scheduled(fixedDelayString = "${scheduler.buffer-recovery.redrive-rate:60000}")
  public void redriveExpiredInflight() {
    executor.executeOrDefault(
        () ->
            lockStrategy.executeWithLock(
                "scheduler:buffer-recovery:redrive",
                0,
                60,
                () -> {
                  doRedriveExpiredInflight();
                  return null;
                }),
        null,
        TaskContext.of("Scheduler", "Buffer.Redrive"));
  }

  private void doRedriveExpiredInflight() {
    // 만료된 INFLIGHT 메시지 조회
    List<String> expiredMsgIds =
        redisBufferStrategy.getExpiredInflightMessages(inflightTimeoutMs, batchSize);

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
        skipCount++; // 이미 ACK된 경우
      }
    }

    if (redriveCount > 0) {
      meterRegistry.counter("buffer.scheduler.redrive.success").increment(redriveCount);
      log.warn(
          "[BufferRecovery] Redriven {} expired INFLIGHT messages (skipped: {})",
          redriveCount,
          skipCount);
    }
  }
}
