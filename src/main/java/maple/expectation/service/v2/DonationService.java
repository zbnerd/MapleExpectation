package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import maple.expectation.aop.annotation.LogExecutionTime;
import maple.expectation.repository.v2.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DonationService {

    private final MemberRepository memberRepository;

    @Transactional
    @LogExecutionTime
    public void sendCoffee(String guestUuid, Long developerId, Long amount) {

        // 1. [Guest] 포인트 차감 시도
        // 쿼리 한 방으로 '잔액 확인' + '차감'을 동시에 수행합니다.
        int updatedCount = memberRepository.decreasePoint(guestUuid, amount);

        // 업데이트된 행이 0개라면? -> 조건(잔액 부족 or 유저 없음) 불만족
        if (updatedCount == 0) {
            throw new IllegalStateException("이체 실패: 잔액이 부족하거나 게스트가 존재하지 않습니다.");
        }

        // 2. [Developer] 포인트 증가
        // Guest 차감이 성공했을 때만 실행됩니다.
        int developerUpdated = memberRepository.increasePoint(developerId, amount);

        // 만약 개발자 ID가 잘못되어 업데이트가 안 됐다면? -> 롤백해야 함
        if (developerUpdated == 0) {
            throw new IllegalArgumentException("이체 실패: 개발자 계정이 존재하지 않습니다.");
        }
    }
}