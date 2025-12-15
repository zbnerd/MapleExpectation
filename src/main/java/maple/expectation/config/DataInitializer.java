package maple.expectation.config;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.MemberRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final MemberRepository memberRepository;

    @Override
    // @Transactional // save 자체가 트랜잭션이라 초기화엔 굳이 필요 없음
    public void run(String... args) {
        String developerUuid = "00000000-0000-0000-0000-000000000000";

        // 1. 락 없이 그냥 조회 (Repository에 findByUuid 메서드가 필요함)
        // 없으면 생성
        if (memberRepository.findByUuid(developerUuid).isEmpty()) {
            try {
                memberRepository.save(new Member(developerUuid, 0L));
            } catch (Exception e) {
                // 혹시라도 아주 찰나의 순간에 다른 인스턴스가 먼저 만들었으면
                // Unique Constraint 에러가 날 텐데, 쿨하게 무시하면 됩니다.
                // (이미 존재한다는 뜻이니까요)
            }
        }
    }
}