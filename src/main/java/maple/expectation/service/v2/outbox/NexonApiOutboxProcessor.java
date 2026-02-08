package maple.expectation.service.v2.outbox;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.config.OutboxProperties;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.domain.v2.NexonApiOutbox.OutboxStatus;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.NexonApiOutboxRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nexon API Outbox 처리 서비스 (N19: Outbox Replay Pattern)
 *
 * <h3>2-Phase 트랜잭션 패턴</h3>
 *
 * <p>분산 환경에서의 중복 처리 방지와 격리성 보장을 위해 2단계 트랜잭션을 사용합니다.
 *
 * <h4>Phase 1: fetchAndLock() (단일 트랜잭션)</h4>
 *
 * <ul>
 *   <li>SKIP LOCKED로 PENDING/FAILED 항목 조회
 *   <li>즉시 PROCESSING 상태로 변경하여 중복 폴링 방지
 *   <li>트랜잭션 종료 시 락 해제되지만 상태가 유지되어 안전함
 * </ul>
 *
 * <h4>Phase 2: processBatch() (항목별 독립 트랜잭션)</h4>
 *
 * <ul>
 *   <li>각 항목을 TransactionTemplate으로 독립 트랜잭션에서 처리
 *   <li>개별 실패가 전체 배치에 영향을 주지 않음 (P0-2 Fix)
 *   <li>실패 시 handleFailure()로 retryCount 증가 -> DLQ 이동 (P0-1 Fix)
 * </ul>
 *
 * <h3>Financial-Grade 특성</h3>
 *
 * <ul>
 *   <li>SKIP LOCKED: 분산 환경 중복 처리 방지
 *   <li>Exponential Backoff: 재시도 간격 증가 (최대 1시간)
 *   <li>Stalled Recovery: JVM 크래시로 Zombie 상태 복구
 *   <li>Integrity Verification: Content Hash 기반 무결성 검증
 * </ul>
 *
 * <h3>Nexon API 특화</h3>
 *
 * <ul>
 *   <li>외부 API 장애 시 Outbox 적재 -> 재시도
 *   <li>6시간 장애 대응 (N19 시나리오)
 *   <li>NexonApiRetryClient로 실제 API 호출 위임
 * </ul>
 *
 * @see NexonApiOutboxRepository
 * @see NexonApiRetryClient
 * @see NexonApiOutboxMetrics
 */
@Slf4j
@Service
@EnableConfigurationProperties(OutboxProperties.class)
@RequiredArgsConstructor
public class NexonApiOutboxProcessor {

  private final NexonApiOutboxRepository outboxRepository;
  private final NexonApiRetryClient retryClient;
  private final NexonApiOutboxMetrics metrics;
  private final LogicExecutor executor;
  private final TransactionTemplate transactionTemplate;
  private final OutboxProperties properties;
  private final NexonApiDlqHandler dlqHandler;

  /**
   * Pending 항목 폴링 및 처리
   *
   * <h4>2-Phase 처리</h4>
   *
   * <ol>
   *   <li>Phase 1 (TX): SKIP LOCKED 조회 + markProcessing + save
   *   <li>Phase 2 (항목별 TX): 개별 처리 (실패 시 다른 항목에 영향 없음)
   * </ol>
   */
  @ObservedTransaction("scheduler.nexon_api_outbox.poll")
  public void pollAndProcess() {
    TaskContext context =
        TaskContext.of("NexonApiOutbox", "PollAndProcess", properties.getInstanceId());

    executor.executeOrCatch(
        () -> {
          List<NexonApiOutbox> locked = fetchAndLock();
          if (locked.isEmpty()) {
            return null;
          }

          log.info("[NexonApiOutbox] 처리 시작: {}건", locked.size());
          processBatch(locked);
          return null;
        },
        e -> {
          log.error("[NexonApiOutbox] 폴링 실패", e);
          metrics.incrementPollFailure();
          return null;
        },
        context);
  }

  /**
   * Phase 1: SKIP LOCKED 조회 + markProcessing (단일 트랜잭션)
   *
   * <p>트랜잭션 종료와 함께 SKIP LOCKED 해제되지만, 상태가 PROCESSING으로 변경되어 다른 인스턴스가 재조회하지 않음
   *
   * <h4>인덱스 활용</h4>
   *
   * <p>idx_pending_poll (status, next_retry_at, id) 복합 인덱스 사용
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public List<NexonApiOutbox> fetchAndLock() {
    List<NexonApiOutbox> pending =
        outboxRepository.findPendingWithLock(
            List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
            LocalDateTime.now(),
            PageRequest.of(0, properties.getBatchSize()));

    for (NexonApiOutbox entry : pending) {
      entry.markProcessing(properties.getInstanceId());
    }

    return outboxRepository.saveAll(pending);
  }

  /**
   * Phase 2: 배치 처리 (항목별 독립 트랜잭션)
   *
   * <p>개별 항목 실패가 전체 배치에 영향을 주지 않음
   */
  private void processBatch(List<NexonApiOutbox> locked) {
    int success = 0;
    int failed = 0;

    for (NexonApiOutbox entry : locked) {
      boolean result = processEntryInTransaction(entry.getId());
      if (result) success++;
      else failed++;
    }

    log.info("[NexonApiOutbox] 처리 완료: 성공={}, 실패={}", success, failed);
  }

  /**
   * 개별 Outbox 항목 처리 (독립 트랜잭션)
   *
   * <h4>Zombie Loop 방지 (P0-1 Fix)</h4>
   *
   * <p>executeOrCatch로 실패 시 반드시 handleFailure() 호출 -> retryCount 증가 -> DLQ 이동
   */
  private boolean processEntryInTransaction(Long entryId) {
    TaskContext context = TaskContext.of("NexonApiOutbox", "ProcessEntry", String.valueOf(entryId));

    return executor.executeOrCatch(
        () -> {
          Boolean result =
              transactionTemplate.execute(
                  status -> {
                    NexonApiOutbox entry = outboxRepository.findById(entryId).orElse(null);
                    if (entry == null) {
                      return false;
                    }

                    return processEntry(entry);
                  });
          return Boolean.TRUE.equals(result);
        },
        e -> {
          log.error("[NexonApiOutbox] 항목 처리 실패: id={}", entryId, e);
          recoverFailedEntry(entryId, e.getMessage());
          return false;
        },
        context);
  }

  /**
   * 개별 항목 처리 로직 (트랜잭션 내부)
   *
   * <h4>처리 흐름</h4>
   *
   * <ol>
   *   <li>무결성 검증 (Content Hash)
   *   <li>NexonApiRetryClient로 실제 API 호출
   *   <li>성공 시 markCompleted()
   *   <li>실패 시 예외 발생 -> executeOrCatch가 handleFailure() 호출
   * </ol>
   */
  private boolean processEntry(NexonApiOutbox entry) {
    if (!verifyIntegrity(entry)) {
      handleIntegrityFailure(entry);
      return false;
    }

    // 실제 Nexon API 호출 (재시도 로직)
    boolean apiSuccess = retryClient.processOutboxEntry(entry);

    if (!apiSuccess) {
      throw new RuntimeException("Nexon API call failed: " + entry.getRequestId());
    }

    entry.markCompleted();
    outboxRepository.save(entry);
    metrics.incrementProcessed();
    return true;
  }

  /**
   * 무결성 검증
   *
   * <p>Content Hash를 재계산하여 데이터 위변조 검증
   */
  private boolean verifyIntegrity(NexonApiOutbox entry) {
    return entry.verifyIntegrity();
  }

  /**
   * 실패 항목 복구 (별도 트랜잭션)
   *
   * <p>processEntry 예외 발생 시 반드시 retryCount를 증가시켜 Zombie Loop 방지
   */
  private void recoverFailedEntry(Long entryId, String errorMessage) {
    TaskContext context =
        TaskContext.of("NexonApiOutbox", "RecoverFailed", String.valueOf(entryId));

    executor.executeOrDefault(
        () -> {
          transactionTemplate.executeWithoutResult(
              status -> {
                NexonApiOutbox entry = outboxRepository.findById(entryId).orElse(null);
                if (entry == null) {
                  return;
                }
                handleFailure(entry, errorMessage);
              });
          return null;
        },
        null,
        context);
  }

  /**
   * 무결성 검증 실패 처리
   *
   * <p>재시도 무의미 -> 즉시 DLQ 이동
   */
  private void handleIntegrityFailure(NexonApiOutbox entry) {
    log.error("[NexonApiOutbox] 무결성 검증 실패 -> 즉시 DLQ 이동: {}", entry.getRequestId());
    metrics.incrementIntegrityFailure();

    String reason = "Integrity verification failed - data tampering detected";
    entry.markFailed(reason);
    entry.forceDeadLetter();
    outboxRepository.save(entry);

    // Triple Safety Net: DLQ 핸들러 연동 (Issue #333)
    dlqHandler.handleDeadLetter(entry, reason);
  }

  /**
   * 처리 실패 핸들링
   *
   * <p>retryCount 증가 -> maxRetries 도달 시 DLQ 이동
   */
  public void handleFailure(NexonApiOutbox entry, String error) {
    entry.markFailed(error);
    outboxRepository.save(entry);
    metrics.incrementFailed();

    if (entry.shouldMoveToDlq()) {
      log.warn(
          "[NexonApiOutbox] DLQ 이동: requestId={}, retryCount={}",
          entry.getRequestId(),
          entry.getRetryCount());
      metrics.incrementDlq();

      // Triple Safety Net: DLQ 핸들러 연동 (Issue #333)
      dlqHandler.handleDeadLetter(entry, error);
    }
  }

  /**
   * Stalled 상태 복구 (JVM 크래시 대응)
   *
   * <p>복구 전 Content Hash 기반 무결성 검증 수행
   *
   * <h4>인덱스 활용</h4>
   *
   * <p>idx_locked (locked_by, locked_at) 인덱스 사용
   */
  @ObservedTransaction("scheduler.nexon_api_outbox.recover_stalled")
  @Transactional
  public void recoverStalled() {
    LocalDateTime staleTime = LocalDateTime.now().minus(properties.getStaleThreshold());
    List<NexonApiOutbox> stalledEntries =
        outboxRepository.findStalledProcessing(
            staleTime, PageRequest.of(0, properties.getBatchSize()));

    if (stalledEntries.isEmpty()) {
      return;
    }

    log.info("[NexonApiOutbox] Stalled 상태 발견: {}건, 무결성 검증 시작", stalledEntries.size());

    int recovered = 0;
    int integrityFailed = 0;

    for (NexonApiOutbox entry : stalledEntries) {
      if (!verifyIntegrity(entry)) {
        log.error(
            "[NexonApiOutbox] 무결성 검증 실패 - Zombie 복구 중단, DLQ 이동: requestId={}",
            entry.getRequestId());
        handleIntegrityFailure(entry);
        integrityFailed++;
        continue;
      }

      entry.resetToRetry();
      outboxRepository.save(entry);
      recovered++;
    }

    if (recovered > 0) {
      log.warn("[NexonApiOutbox] Stalled 상태 복구 완료: 성공={}, 무결성실패={}", recovered, integrityFailed);
      metrics.incrementStalledRecovered(recovered);
    }

    if (integrityFailed > 0) {
      log.error("[NexonApiOutbox] Stalled 복구 중 무결성 검증 실패: {}건", integrityFailed);
    }
  }
}
