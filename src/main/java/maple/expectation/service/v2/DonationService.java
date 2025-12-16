package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.exception.DeveloperNotFoundException;
import maple.expectation.exception.InsufficientPointException;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

    private final MemberRepository memberRepository;
    private final DonationHistoryRepository donationHistoryRepository;

    @Transactional
    public void sendCoffee(String guestUuid, Long developerId, Long amount, String requestId) {

        // 1️⃣ [INFO] 멱등성 방어 로그
        if (donationHistoryRepository.existsByRequestId(requestId)) {
            log.info("[Idempotency] 이미 처리된 요청입니다. RequestId={}, Guest={}", requestId, guestUuid);
            return;
        }

        // 2️⃣ [WARN] 잔액 부족 로그 추가
        int updatedCount = memberRepository.decreasePoint(guestUuid, amount);
        if (updatedCount == 0) {
            log.warn("[Donation Failed] 잔액 부족 또는 게스트 없음. Guest={}, Amount={}, RequestId={}",
                    guestUuid, amount, requestId);
            throw new InsufficientPointException("이체 실패: 잔액이 부족하거나 유효하지 않은 게스트입니다.");
        }

        // 3️⃣ [WARN] 개발자 계정 오류 로그 추가
        int developerUpdated = memberRepository.increasePoint(developerId, amount);
        if (developerUpdated == 0) {
            log.warn("[Donation Failed] 존재하지 않는 개발자 ID. DevId={}, Guest={}, RequestId={}",
                    developerId, guestUuid, requestId);
            throw new DeveloperNotFoundException("이체 실패: 존재하지 않는 개발자 ID(" + developerId + ")입니다.");
        }

        // 4️⃣ [INFO] 성공 로그 (이력 저장)
        DonationHistory history = DonationHistory.builder()
                .senderUuid(guestUuid)
                .receiverId(developerId)
                .amount(amount)
                .requestId(requestId)
                .build();

        donationHistoryRepository.save(history);

        log.info("[Donation Success] 이체 성공. Guest={} -> Dev={}, Amount={}, RequestId={}",
                guestUuid, developerId, amount, requestId);
    }
}