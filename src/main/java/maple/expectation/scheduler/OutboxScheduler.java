package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.donation.outbox.OutboxMetrics;
import maple.expectation.service.v2.donation.outbox.OutboxProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 폴링 스케줄러 (Issue #80)
 *
 * <h3>스케줄링 주기</h3>
 * <ul>
 *   <li>pollAndProcess: 10초 (Pending -> Processing -> Completed)</li>
 *   <li>recoverStalled: 5분 (JVM 크래시 대응)</li>
 * </ul>
 *
 * <h3>P1-7 Fix: updatePendingCount() 스케줄러 레벨로 이동</h3>
 * <p>기존: OutboxProcessor.pollAndProcess() 내부에서 호출 (배치 트랜잭션 내 추가 쿼리)</p>
 * <p>수정: 스케줄러에서 독립적으로 호출 (배치 처리 시간 단축)</p>
 *
 * <h3>분산 환경 안전 (Issue #283 P1-9)</h3>
 * <p><b>분산 락 미적용 사유</b>: {@link DonationOutboxRepository}의
 * {@code findPendingWithLock()} 및 {@code findStalledProcessing()} 메서드는
 * {@code PESSIMISTIC_WRITE} + {@code SKIP LOCKED} 쿼리를 사용하여 DB 레벨에서
 * 중복 처리를 방지합니다.</p>
 *
 * <p><b>SKIP LOCKED 동작 원리</b>:</p>
 * <ul>
 *   <li>다중 인스턴스가 동시에 폴링 시, 잠긴 행은 건너뛰고 다음 행 처리</li>
 *   <li>대기 없이 병렬 처리 가능 (높은 처리량 보장)</li>
 *   <li>Redis 분산 락 대비 장점: Redis 장애 시에도 독립 동작</li>
 * </ul>
 *
 * @see OutboxProcessor
 * @see DonationOutboxRepository#findPendingWithLock
 * @see DonationOutboxRepository#findStalledProcessing
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.outbox.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxScheduler {

    private final OutboxProcessor outboxProcessor;
    private final OutboxMetrics outboxMetrics;
    private final LogicExecutor executor;

    /**
     * Outbox 폴링 및 처리 (10초)
     */
    @Scheduled(fixedRate = 10000)
    public void pollAndProcess() {
        executor.executeVoid(
                () -> {
                    outboxProcessor.pollAndProcess();
                    outboxMetrics.updatePendingCount();
                },
                TaskContext.of("Scheduler", "Outbox.Poll")
        );
    }

    /**
     * Stalled 상태 복구 (5분)
     *
     * <p>JVM 크래시로 PROCESSING 상태에서 멈춘 항목을 PENDING으로 복원</p>
     */
    @Scheduled(fixedRate = 300000)
    public void recoverStalled() {
        executor.executeVoid(
                outboxProcessor::recoverStalled,
                TaskContext.of("Scheduler", "Outbox.RecoverStalled")
        );
    }
}
