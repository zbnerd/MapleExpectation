package maple.expectation.infrastructure.persistence.repository;

import java.util.Optional;
import maple.expectation.domain.v2.DonationDlq;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Dead Letter Queue Repository (Issue #80)
 *
 * <h3>Triple Safety Net 1차 안전망</h3>
 *
 * <p>Outbox 처리 실패 시 데이터를 저장하여 영구 손실 방지.
 *
 * @see maple.expectation.domain.v2.DonationDlq
 * @see maple.expectation.service.v2.donation.outbox.DlqHandler
 */
public interface DonationDlqRepository extends JpaRepository<DonationDlq, Long> {

  /** DLQ 목록 조회 (최신순 정렬, 페이징) */
  Page<DonationDlq> findAllByOrderByMovedAtDesc(Pageable pageable);

  /** requestId로 DLQ 조회 */
  Optional<DonationDlq> findByRequestId(String requestId);

  /** DLQ 총 건수 */
  @Query("SELECT COUNT(d) FROM DonationDlq d")
  long countAll();

  // ========== Cursor-based Pagination (#233) ==========

  /**
   * Cursor 기반 조회 - 첫 페이지
   *
   * <p>ID 순으로 정렬하여 Keyset Pagination 지원
   */
  @Query("SELECT d FROM DonationDlq d ORDER BY d.id")
  Slice<DonationDlq> findFirstPage(Pageable pageable);

  /**
   * Cursor 기반 조회 - 다음 페이지
   *
   * <p>WHERE id > cursor 로 인덱스 활용, O(1) 성능
   *
   * @param cursor 이전 페이지의 마지막 ID
   */
  @Query("SELECT d FROM DonationDlq d WHERE d.id > :cursor ORDER BY d.id")
  Slice<DonationDlq> findByCursorGreaterThan(@Param("cursor") Long cursor, Pageable pageable);
}
