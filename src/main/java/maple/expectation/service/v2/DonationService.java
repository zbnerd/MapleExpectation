package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.global.error.exception.DeveloperNotFoundException;
import maple.expectation.global.error.exception.InsufficientPointException;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.service.v2.donation.event.DonationProcessor;
import maple.expectation.service.v2.donation.listener.DonationFailedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

    private final DonationHistoryRepository donationHistoryRepository;
    private final DonationProcessor donationProcessor;
    private final ApplicationEventPublisher eventPublisher; // 💡 이벤트 발행자

    @Transactional
    @Locked(key = "#guestUuid")
    @ObservedTransaction("service.v2.DonationService.sendCoffee")
    public void sendCoffee(String guestUuid, Long developerId, Long amount, String requestId) {
        try {
            // 1. 멱등성 확인
            if (donationHistoryRepository.existsByRequestId(requestId)) return;

            // 2. 실제 이체 로직 실행 (전용 프로세서에 위임)
            donationProcessor.executeTransfer(guestUuid, developerId, amount);

            // 3. 이력 저장
            saveHistory(guestUuid, developerId, amount, requestId);

        } catch (InsufficientPointException | DeveloperNotFoundException e) {
            throw e; // 비즈니스 예외는 그대로 던짐
        } catch (Exception e) {
            // 4. 기술적 장애 시 이벤트 발행 (Discord 서비스와 결합 해제!)
            eventPublisher.publishEvent(new DonationFailedEvent(requestId, guestUuid, e));
            throw new CriticalTransactionFailureException("도네이션 시스템 오류 발생", e);
        }
    }

    private void saveHistory(String sender, Long receiver, Long amount, String reqId) {
        donationHistoryRepository.save(DonationHistory.builder()
                .senderUuid(sender)
                .receiverId(receiver)
                .amount(amount)
                .requestId(reqId)
                .build());
    }
}