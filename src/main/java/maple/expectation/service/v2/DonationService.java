package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.global.error.exception.DeveloperNotFoundException;
import maple.expectation.global.error.exception.InsufficientPointException;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
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
    private final LogicExecutor executor; // ✅ 지능형 실행기 추가

    @Transactional
    @Locked(key = "#guestUuid")
    @ObservedTransaction("service.v2.DonationService.sendCoffee")
    public void sendCoffee(String guestUuid, Long developerId, Long amount, String requestId) {
        TaskContext context = TaskContext.of("Donation", "SendCoffee", requestId); //

        // ✅ [패턴 5] executeWithRecovery: 정상 로직과 장애 시 복구(이벤트 발행) 로직 분리
        executor.executeWithRecovery(() -> {
            // 1. 멱등성 확인
            if (donationHistoryRepository.existsByRequestId(requestId)) return null;

            // 2. 실제 이체 로직 실행
            donationProcessor.executeTransfer(guestUuid, developerId, amount);

            // 3. 이력 저장
            saveHistory(guestUuid, developerId, amount, requestId);
            return null;
        }, (e) -> {
            // [비즈니스 예외] 그대로 전파 (InsufficientPointException, DeveloperNotFoundException)
            if (e instanceof InsufficientPointException || e instanceof DeveloperNotFoundException) {
                throw (RuntimeException) e;
            }

            // [기술적 장애] 이벤트 발행 후 도메인 예외로 변환
            log.error("🚑 [Technical Failure] 도네이션 프로세스 장애 발생 -> 실패 이벤트 발행: {}", requestId);
            eventPublisher.publishEvent(new DonationFailedEvent(requestId, guestUuid, e));
            throw new CriticalTransactionFailureException("도네이션 시스템 오류 발생", e);
        }, context);
    }

    /**
     * ✅ [관측성 확보] saveHistory도 실행기로 감싸서 연산 시간 및 성공 여부 추적
     */
    private void saveHistory(String sender, Long receiver, Long amount, String reqId) {
        executor.executeVoid(() ->
                        donationHistoryRepository.save(DonationHistory.builder()
                                .senderUuid(sender)
                                .receiverId(receiver)
                                .amount(amount)
                                .requestId(reqId)
                                .build()),
                TaskContext.of("Donation", "SaveHistory", reqId) //
        );
    }
}