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
     * Stalled PROCESSING 상태 항목 조회 (#229)
     *
     * <p>무결성 검증을 위해 개별 항목 조회 필요. LIMIT 100으로 배치 크기 제한.</p>
     *
     * @param staleTime Stale 판정 기준 시간
     * @return Stalled 상태의 Outbox 항목 목록 (최대 100건)
     */
    @Query("SELECT o FROM DonationOutbox o WHERE o.status = 'PROCESSING' AND o.lockedAt < :staleTime ORDER BY o.id")
    List<DonationOutbox> findStalledProcessing(@Param("staleTime") LocalDateTime staleTime, Pageable pageable);

    /**
     * 상태별 카운트 (메트릭용)
     */
    long countByStatusIn(List<OutboxStatus> statuses);

    /**
     * 멱등성 체크
     */
    boolean existsByRequestId(String requestId);
}
