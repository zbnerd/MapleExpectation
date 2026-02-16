package maple.expectation.infrastructure.persistence.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Outbox Repository (Issue #80)
 *
 * <h3>락 전략 (Issue #28)</h3>
 *
 * <p><b>SKIP LOCKED</b>: 분산 환경에서 중복 처리를 방지하는 DB 레벨 락.
 *
 * <h4>선택 사유</h4>
 *
 * <ul>
 *   <li>일반 Pessimistic Lock은 대기 발생 → 처리량 저하
 *   <li>SKIP LOCKED은 잠긴 행 건너뛰기 → 병렬 처리 가능
 *   <li>Redis 분산 락 대비 장점: Redis 장애 시에도 독립 동작
 *   <li>금융 트랜잭션이므로 @Version 낙관적 락 병행하여 강한 일관성 보장
 * </ul>
 *
 * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide - 후원 도메인</a>
 * @see maple.expectation.domain.v2.DonationOutbox
 */
public interface DonationOutboxRepository extends JpaRepository<DonationOutbox, Long> {

  Optional<DonationOutbox> findByRequestId(String requestId);

  /**
   * SKIP LOCKED 조회 (분산 배치 처리용)
   *
   * <p><b>락 전략</b>: PESSIMISTIC_WRITE + SKIP LOCKED
   *
   * <p><b>QueryHint -2</b>: Hibernate 6.x에서 SKIP LOCKED 지정
   *
   * <p><b>선택 사유</b>: 분산 환경에서 여러 인스턴스가 동시에 Outbox를 폴링할 때, 잠긴 행을 건너뛰어 병렬 처리 가능. 대기 없이 즉시 다음 레코드 처리.
   *
   * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide - SKIP LOCKED</a>
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "SELECT o FROM DonationOutbox o WHERE o.status IN :statuses "
          + "AND o.nextRetryAt <= :now ORDER BY o.id")
  List<DonationOutbox> findPendingWithLock(
      @Param("statuses") List<OutboxStatus> statuses,
      @Param("now") LocalDateTime now,
      Pageable pageable);

  /**
   * Stalled 상태 복구 (JVM 크래시 대응)
   *
   * <p>lockedAt이 staleTime보다 오래된 PROCESSING 상태를 PENDING으로 복원
   */
  @Modifying
  @Query(
      "UPDATE DonationOutbox o SET o.status = 'PENDING', o.lockedBy = NULL, o.lockedAt = NULL "
          + "WHERE o.status = 'PROCESSING' AND o.lockedAt < :staleTime")
  int resetStalledProcessing(@Param("staleTime") LocalDateTime staleTime);

  /**
   * Stalled PROCESSING 상태 항목 조회 (#229)
   *
   * <p>무결성 검증을 위해 개별 항목 조회 필요. LIMIT 100으로 배치 크기 제한.
   *
   * <h4>P1-4 Fix: SKIP LOCKED 추가</h4>
   *
   * <p>Scale-out 시 복수 인스턴스가 동시 복구 실행 -> SKIP LOCKED으로 중복 방지. 기존에는 @Lock 없이 조회하여
   * OptimisticLockException 발생 가능.
   *
   * @param staleTime Stale 판정 기준 시간
   * @return Stalled 상태의 Outbox 항목 목록 (최대 100건)
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "SELECT o FROM DonationOutbox o WHERE o.status = 'PROCESSING' AND o.lockedAt < :staleTime ORDER BY o.id")
  List<DonationOutbox> findStalledProcessing(
      @Param("staleTime") LocalDateTime staleTime, Pageable pageable);

  /** 상태별 카운트 (메트릭용) */
  long countByStatusIn(List<OutboxStatus> statuses);

  /** 멱등성 체크 */
  boolean existsByRequestId(String requestId);
}
