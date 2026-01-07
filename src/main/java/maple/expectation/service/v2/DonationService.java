package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.global.error.exception.DeveloperNotFoundException;
import maple.expectation.global.error.exception.InsufficientPointException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
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
    private final ApplicationEventPublisher eventPublisher;
    private final LogicExecutor executor;

    @Transactional
    @Locked(key = "#guestUuid")
    @ObservedTransaction("service.v2.DonationService.sendCoffee")
    public void sendCoffee(String guestUuid, Long developerId, Long amount, String requestId) {
        TaskContext context = TaskContext.of("Donation", "SendCoffee", requestId);

        // ✅ executeWithRecovery: 정상 흐름과 복구 흐름을 선언적으로 분리
        executor.executeOrCatch(() -> {
            if (donationHistoryRepository.existsByRequestId(requestId)) {
                return null;
            }

            donationProcessor.executeTransfer(guestUuid, developerId, amount);
            saveHistory(guestUuid, developerId, amount, requestId);
            return null;
        }, (e) -> {
            // 비즈니스 예외는 그대로 전파 (Locked나 Transaction에서 처리)
            if (e instanceof InsufficientPointException || e instanceof DeveloperNotFoundException) {
                throw (RuntimeException) e;
            }

            // 기술적 장애 발생 시에만 이벤트 발행 및 래핑 예외 발생
            log.error("🚑 [Technical Failure] 도네이션 장애 발생 -> 실패 이벤트 발행: {}", requestId);
            eventPublisher.publishEvent(new DonationFailedEvent(requestId, guestUuid, e));
            throw new CriticalTransactionFailureException("도네이션 시스템 오류 발생", e);
        }, context);
    }

    private void saveHistory(String sender, Long receiver, Long amount, String reqId) {
        // 내부 메서드도 별도 컨텍스트로 관측성 확보
        executor.executeVoid(() ->
                        donationHistoryRepository.save(DonationHistory.builder()
                                .senderUuid(sender)
                                .receiverId(receiver)
                                .amount(amount)
                                .requestId(reqId)
                                .build()),
                TaskContext.of("Donation", "SaveHistory", reqId)
        );
    }
}