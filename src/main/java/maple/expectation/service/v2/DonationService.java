package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.global.error.exception.AdminMemberNotFoundException;
import maple.expectation.global.error.exception.AdminNotFoundException;
import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.global.error.exception.InsufficientPointException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.DonationOutboxRepository;
import maple.expectation.service.v2.auth.AdminService;
import maple.expectation.service.v2.donation.event.DonationProcessor;
import maple.expectation.service.v2.donation.listener.DonationFailedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도네이션(커피 후원) 서비스
 *
 * <p>게스트가 Admin(개발자)에게 커피를 사주는 기능입니다.
 * Admin은 fingerprint로 식별되며, ADMIN_FINGERPRINTS에 등록된 사용자만
 * 후원을 받을 수 있습니다.</p>
 *
 * <p>보안 고려사항:
 * <ul>
 *   <li>Admin fingerprint 검증 (AdminService.isAdmin())</li>
 *   <li>멱등성 보장 (requestId 중복 체크)</li>
 *   <li>분산 락 (guestUuid 기준)</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

    private final DonationHistoryRepository donationHistoryRepository;
    private final DonationOutboxRepository donationOutboxRepository;
    private final DonationProcessor donationProcessor;
    private final AdminService adminService;
    private final ApplicationEventPublisher eventPublisher;
    private final LogicExecutor executor;

    /**
     * Admin(개발자)에게 커피 보내기
     *
     * <p>Issue #198: 금융 거래 데이터 일관성을 위해 READ_COMMITTED isolation level 명시.
     * MySQL InnoDB 기본값과 동일하나 명시적 선언으로 의도를 표현합니다.</p>
     *
     * @param guestUuid        발신자 UUID
     * @param adminFingerprint 수신자 Admin fingerprint
     * @param amount           후원 금액
     * @param requestId        멱등성 키
     * @throws AdminNotFoundException       유효하지 않은 Admin fingerprint
     * @throws AdminMemberNotFoundException Admin의 Member 계정이 없음
     * @throws InsufficientPointException   잔액 부족
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Locked(key = "#guestUuid")
    @ObservedTransaction("service.v2.DonationService.sendCoffee")
    public void sendCoffee(String guestUuid, String adminFingerprint, Long amount, String requestId) {
        TaskContext context = TaskContext.of("Donation", "SendCoffee", requestId);

        // Admin 권한 검증 (ADMIN_FINGERPRINTS에 등록된 사용자만 가능)
        validateAdmin(adminFingerprint);

        executor.executeOrCatch(() -> {
            // 멱등성 체크
            if (donationHistoryRepository.existsByRequestId(requestId)) {
                log.info("[Donation] Duplicate request ignored: requestId={}", requestId);
                return null;
            }

            donationProcessor.executeTransferToAdmin(guestUuid, adminFingerprint, amount);
            saveHistory(guestUuid, adminFingerprint, amount, requestId);
            saveOutbox(guestUuid, adminFingerprint, amount, requestId);
            return null;
        }, (e) -> {
            // 비즈니스 예외는 그대로 전파
            if (e instanceof InsufficientPointException || e instanceof AdminMemberNotFoundException) {
                throw (RuntimeException) e;
            }

            // 기술적 장애 발생 시에만 이벤트 발행
            log.error("[Donation] Technical failure -> publishing failed event: requestId={}", requestId);
            eventPublisher.publishEvent(new DonationFailedEvent(requestId, guestUuid, e));
            throw new CriticalTransactionFailureException("도네이션 시스템 오류 발생", e);
        }, context);
    }

    private void validateAdmin(String adminFingerprint) {
        if (!adminService.isAdmin(adminFingerprint)) {
            throw new AdminNotFoundException();
        }
    }

    private void saveHistory(String sender, String receiverFingerprint, Long amount, String reqId) {
        executor.executeVoid(() ->
                        donationHistoryRepository.save(DonationHistory.builder()
                                .senderUuid(sender)
                                .receiverFingerprint(receiverFingerprint)
                                .amount(amount)
                                .requestId(reqId)
                                .build()),
                TaskContext.of("Donation", "SaveHistory", reqId)
        );
    }

    /**
     * Outbox 저장 (같은 트랜잭션에서 원자적으로 저장)
     *
     * <p>Issue #80: Transactional Outbox Pattern</p>
     */
    private void saveOutbox(String sender, String receiverFingerprint, Long amount, String reqId) {
        executor.executeVoid(() -> {
            String payload = createPayload(sender, receiverFingerprint, amount);
            DonationOutbox outbox = DonationOutbox.create(reqId, "DONATION_COMPLETED", payload);
            donationOutboxRepository.save(outbox);
        }, TaskContext.of("Donation", "SaveOutbox", reqId));
    }

    private String createPayload(String sender, String receiverFingerprint, Long amount) {
        // 보안: fingerprint 마스킹하여 저장
        String maskedFingerprint = maskFingerprint(receiverFingerprint);
        return String.format(
                "{\"senderUuid\":\"%s\",\"receiverFingerprint\":\"%s\",\"amount\":%d}",
                sender, maskedFingerprint, amount
        );
    }

    private String maskFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 8) {
            return "****";
        }
        return fingerprint.substring(0, 4) + "****";
    }
}