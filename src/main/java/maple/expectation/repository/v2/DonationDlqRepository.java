package maple.expectation.repository.v2;

import maple.expectation.domain.v2.DonationDlq;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * Dead Letter Queue Repository (Issue #80)
 *
 * <h3>Triple Safety Net 1차 안전망</h3>
 * <p>Outbox 처리 실패 시 데이터를 저장하여 영구 손실 방지.</p>
 *
 * @see maple.expectation.domain.v2.DonationDlq
 * @see maple.expectation.service.v2.donation.outbox.application.DlqHandler
 */
public interface DonationDlqRepository extends JpaRepository<DonationDlq, Long> {

    /**
     * DLQ 목록 조회 (최신순 정렬, 페이징)
     */
    Page<DonationDlq> findAllByOrderByMovedAtDesc(Pageable pageable);

    /**
     * requestId로 DLQ 조회
     */
    Optional<DonationDlq> findByRequestId(String requestId);

    /**
     * DLQ 총 건수
     */
    @Query("SELECT COUNT(d) FROM DonationDlq d")
    long countAll();
}
