package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked; // 1. 어노테이션 추가
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.global.error.exception.DeveloperNotFoundException;
import maple.expectation.global.error.exception.InsufficientPointException;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

    private final MemberRepository memberRepository;
    private final DonationHistoryRepository donationHistoryRepository;
    private final DiscordAlertService discordAlertService;

    /**
     * 리팩토링 포인트: @Locked 적용
     * - Key: guestUuid (송금하는 유저별로 락을 걸어 '따닥' 요청 방지)
     */
    @Transactional
    @Locked(key = "#guestUuid") // 2. 전략 패턴 기반 락 적용
    @ObservedTransaction("service.v2.DonationService.sendCoffee")
    public void sendCoffee(String guestUuid, Long developerId, Long amount, String requestId) {
        try {
            // 1️⃣ 멱등성 방어 (락 안에서 보호받으므로 훨씬 안전함)
            if (donationHistoryRepository.existsByRequestId(requestId)) {
                log.info("[Idempotency] 이미 처리된 요청입니다. RequestId={}", requestId);
                return;
            }

            // 2️⃣ 잔액 차감 (Atomic Update 쿼리는 그대로 유지하여 '2중 안전장치' 확보)
            int updatedCount = memberRepository.decreasePoint(guestUuid, amount);
            if (updatedCount == 0) {
                log.warn("[Donation Failed] 잔액 부족 또는 게스트 없음. Guest={}", guestUuid);
                throw new InsufficientPointException("이체 실패: 잔액 부족 또는 유효하지 않은 게스트");
            }

            // 3️⃣ 개발자 포인트 증가
            int developerUpdated = memberRepository.increasePoint(developerId, amount);
            if (developerUpdated == 0) {
                log.warn("[Donation Failed] 존재하지 않는 개발자 ID. DevId={}", developerId);
                throw new DeveloperNotFoundException("이체 실패: 존재하지 않는 개발자 ID(" + developerId + ")");
            }

            // 4️⃣ 이력 저장
            DonationHistory history = DonationHistory.builder()
                    .senderUuid(guestUuid)
                    .receiverId(developerId)
                    .amount(amount)
                    .requestId(requestId)
                    .build();

            donationHistoryRepository.save(history);

            log.info("[Donation Success] {} -> {} ({}원)", guestUuid, developerId, amount);

        } catch (InsufficientPointException | DeveloperNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("💥 Critical Failure in Donation Transaction. RequestId={}", requestId, e);
            discordAlertService.sendCriticalAlert("DONATION TRANSACTION FAILED", "RequestId: " + requestId, e);
            throw new CriticalTransactionFailureException("도네이션 시스템 오류 발생", e);
        }
    }
}