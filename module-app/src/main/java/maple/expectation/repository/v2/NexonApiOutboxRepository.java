package maple.expectation.repository.v2;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.domain.v2.NexonApiOutbox.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Nexon API Outbox Repository (N19)
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
 *   <li>@Version 낙관적 락 병행하여 강한 일관성 보장
 * </ul>
 *
 * @see maple.expectation.domain.v2.NexonApiOutbox
 * @see <a
 *     href="../../../../docs/01_Chaos_Engineering/06_Nightmare/Scenarios/N19-outbox-replay.md">N19
 *     Scenario</a>
 */
public interface NexonApiOutboxRepository extends JpaRepository<NexonApiOutbox, Long> {

  Optional<NexonApiOutbox> findByRequestId(String requestId);

  /**
   * SKIP LOCKED 조회 (분산 배치 처리용)
   *
   * <p><b>락 전략</b>: PESSIMISTIC_WRITE + SKIP LOCKED
   *
   * <p><b>QueryHint -2</b>: Hibernate 6.x에서 SKIP LOCKED 지정
   *
   * <p><b>선택 사유</b>: 분산 환경에서 여러 인스턴스가 동시에 Outbox를 폴링할 때, 잠긴 행을 건너뛰어 병렬 처리 가능. 대기 없이 즉시 다음 레코드 처리.
   *
   * <h4>인덱스 활용</h4>
   *
   * <p>idx_pending_poll (status, next_retry_at, id) 복합 인덱스 활용.
   *
   * @param statuses 조회할 상태 목록 (PENDING, FAILED)
   * @param now 현재 시간 (nextRetryAt 기준)
   * @param pageable 페이지 크기 제한
   * @return 락이 걸린 Outbox 항목 목록
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "SELECT o FROM NexonApiOutbox o WHERE o.status IN :statuses "
          + "AND o.nextRetryAt <= :now ORDER BY o.id")
  List<NexonApiOutbox> findPendingWithLock(
      @Param("statuses") List<OutboxStatus> statuses,
      @Param("now") LocalDateTime now,
      Pageable pageable);

  /**
   * Stalled 상태 복구 (JVM 크래시 대응)
   *
   * <p>lockedAt이 staleTime보다 오래된 PROCESSING 상태를 PENDING으로 복원.
   *
   * <h4>Scale-out 고려사항</h4>
   *
   * <p>복수 인스턴스가 동시 실행 가능하므로 {@link #findStalledProcessing(LocalDateTime, Pageable)} 와 함께 사용하여 SKIP
   * LOCKED로 중복 방지.
   *
   * @param staleTime Stale 판정 기준 시간
   * @return 업데이트된 행 수
   */
  @Modifying
  @Query(
      "UPDATE NexonApiOutbox o SET o.status = 'PENDING', o.lockedBy = NULL, o.lockedAt = NULL "
          + "WHERE o.status = 'PROCESSING' AND o.lockedAt < :staleTime")
  int resetStalledProcessing(@Param("staleTime") LocalDateTime staleTime);

  /**
   * Stalled PROCESSING 상태 항목 조회
   *
   * <p>무결성 검증을 위해 개별 항목 조회 필요. LIMIT 100으로 배치 크기 제한.
   *
   * <h4>SKIP LOCKED 필요성</h4>
   *
   * <p>Scale-out 시 복수 인스턴스가 동시 복구 실행 → SKIP LOCKED으로 중복 방지. 기존에는 @Lock 없이 조회하여
   * OptimisticLockException 발생 가능.
   *
   * <h4>인덱스 활용</h4>
   *
   * <p>idx_locked (locked_by, locked_at) 인덱스 활용.
   *
   * @param staleTime Stale 판정 기준 시간
   * @param pageable 페이지 크기 제한 (권장: 100)
   * @return Stalled 상태의 Outbox 항목 목록
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "SELECT o FROM NexonApiOutbox o WHERE o.status = 'PROCESSING' AND o.lockedAt < :staleTime ORDER BY o.id")
  List<NexonApiOutbox> findStalledProcessing(
      @Param("staleTime") LocalDateTime staleTime, Pageable pageable);

  /**
   * 상태별 카운트 (메트릭용)
   *
   * @param statuses 카운트할 상태 목록
   * @return 해당 상태의 레코드 수
   */
  long countByStatusIn(List<OutboxStatus> statuses);

  /**
   * 멱등성 체크
   *
   * @param requestId 요청 ID
   * @return 중복 여부
   */
  boolean existsByRequestId(String requestId);
}
