package maple.expectation.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.MemberRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private static final String DEVELOPER_UUID = "00000000-0000-0000-0000-000000000000";

    @Override
    public void run(String... args) {
        initDeveloper();
    }

    private void initDeveloper() {
        if (memberRepository.findByUuid(DEVELOPER_UUID).isPresent()) {
            return;
        }

        log.info("🚀 시스템 초기 데이터 생성: 개발자 계정 ({})", DEVELOPER_UUID);

        try {
            // 💡 [수정 포인트] new 대신 정적 팩토리 메서드 사용
            // 이제 패키지가 달라도 public 메서드를 통해 안전하게 생성 가능합니다.
            Member developer = Member.createSystemAdmin(DEVELOPER_UUID, 0L);
            memberRepository.save(developer);

            log.info("✅ 개발자 계정 생성 완료");
        } catch (Exception e) {
            // Unique Constraint 등으로 인한 에러 발생 시 (이미 다른 인스턴스가 만든 경우)
            log.warn("⏭️ 초기 데이터가 이미 존재합니다. (Conflict 방지)");
        }
    }
}