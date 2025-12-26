package maple.expectation.service.v2.donation.event;

import lombok.RequiredArgsConstructor;
import maple.expectation.global.error.exception.DeveloperNotFoundException;
import maple.expectation.global.error.exception.InsufficientPointException;
import maple.expectation.repository.v2.MemberRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DonationProcessor {
    private final MemberRepository memberRepository;

    @Transactional(propagation = Propagation.MANDATORY) // 반드시 기존 트랜잭션 내에서 실행
    public void executeTransfer(String guestUuid, Long developerId, Long amount) {
        // 1. 잔액 차감
        if (memberRepository.decreasePoint(guestUuid, amount) == 0) {
            throw new InsufficientPointException("이체 실패: 잔액 부족 또는 유효하지 않은 게스트");
        }

        // 2. 포인트 증가
        if (memberRepository.increasePoint(developerId, amount) == 0) {
            throw new DeveloperNotFoundException(developerId.toString());
        }
    }
}