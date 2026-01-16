package maple.expectation.repository.v2;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Outbox Repository (Issue #80)
 *
 * <h3>SKIP LOCKED 쿼리</h3>
 * <p>분산 환경에서 중복 처리를 방지하는 DB 레벨 락.</p>
 * <ul>
 *   <li>Redis 분산 락 대비 장점: Redis 장애 시에도 독립 동작</li>
 *   <li>레코드 단위 병렬 처리 가능</li>
 * </ul>
 *
 * @see maple.expectation.domain.v2.DonationOutbox
 */
public interface DonationOutboxRepository extends JpaRepository<DonationOutbox, Long> {

    Optional<DonationOutbox> findByRequestId(String requestId);

    /**
     * SKIP LOCKED: 분산 환경 중복 처리 방지
     *
     * <p>QueryHint -2 = SKIP LOCKED (Hibernate 6.x)</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses " +
            "AND o.nextRetryAt <= :now ORDER BY o.id")
    List<DonationOutbox> findPendingWithLock(
            @Param("statuses") List<OutboxStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /**
     * Stalled 상태 복구 (JVM 크래시 대응)
     *
     * <p>lockedAt이 staleTime보다 오래된 PROCESSING 상태를 PENDING으로 복원</p>
     */
    @Modifying
    @Query("UPDATE DonationOutbox o SET o.status = 'PENDING', o.lockedBy = NULL, o.lockedAt = NULL " +
            "WHERE o.status = 'PROCESSING' AND o.lockedAt < :staleTime")
    int resetStalledProcessing(@Param("staleTime") LocalDateTime staleTime);

    /**
     * 상태별 카운트 (메트릭용)
     */
    long countByStatusIn(List<OutboxStatus> statuses);

    /**
     * 멱등성 체크
     */
    boolean existsByRequestId(String requestId);
}
