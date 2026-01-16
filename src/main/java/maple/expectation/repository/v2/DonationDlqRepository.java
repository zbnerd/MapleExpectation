package maple.expectation.repository.v2;

import maple.expectation.domain.v2.DonationDlq;
import org.springframework.data.jpa.repository.JpaRepository;

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
    // 기본 CRUD만 사용
}
