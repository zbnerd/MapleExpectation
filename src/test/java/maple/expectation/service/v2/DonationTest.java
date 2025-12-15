package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional; // AfterEach에만 사용

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTestWithTimeLogging
@TestPropertySource(properties = "app.optimization.use-compression=false")
public class DonationTest {

    @Autowired
    DonationService donationService;
    @Autowired
    MemberRepository memberRepository;

    // ✅ 안전 장치 1: 테스트 중 생성된 Member ID를 추적하는 리스트
    private final List<Long> createdMemberIds = new ArrayList<>();

    // 💡 헬퍼 메서드: 저장 후, 삭제를 위해 ID를 추적 리스트에 추가 (랜덤 UUID 사용 권장)
    private Member saveAndTrack(Member member) {
        Member saved = memberRepository.save(member);
        createdMemberIds.add(saved.getId());
        return saved;
    }

    // @BeforeEach는 공용 DB 보호를 위해 사용하지 않습니다.

    @AfterEach
    @Transactional // ✅ 안전 장치 2: 삭제는 트랜잭션이 필요하므로 여기에만 @Transactional을 붙입니다.
    void tearDown() {
        if (!createdMemberIds.isEmpty()) {
            // 내가 만든 ID만 골라서 삭제 (공용 DB 보호)
            memberRepository.deleteAllById(createdMemberIds);
            createdMemberIds.clear();
        }
    }

    @Test
    @DisplayName("따닥 방어: 1000원 가진 유저가 동시에 100번 요청해도, 딱 1번만 성공해야 한다.")
    void concurrencyTest() throws InterruptedException {
        // 1. Given: 개발자 UUID를 매번 랜덤 생성하여 테스트 간 충돌 방지
        String randomDeveloperUuid = UUID.randomUUID().toString();
        Member developer = saveAndTrack(new Member(randomDeveloperUuid, 0L));
        Member guest = saveAndTrack(new Member(1000L));

        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 2. When
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    // Note: DonationService의 파라미터에 requestId도 추가해야 완벽한 멱등성 검증이 됩니다.
                    donationService.sendCoffee(guest.getUuid(), developer.getId(), 1000L);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // 3. Then
        // 데이터가 워커 스레드에서 커밋되었기 때문에, 메인 스레드에서 조회할 때는 EntityManager를 Clear할 필요가 없습니다.
        Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();

        // 검증 (Atomic Update 덕분에 1번만 성공, 99번은 잔액 부족으로 실패)
        assertThat(updatedGuest.getPoint()).isEqualTo(0L);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(1000L);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(99);

        // 4. 결과 로그 출력
        log.info("================ [테스트 결과] ================");
        log.info("총 시도 횟수 : {}", threadCount);
        log.info("성공 횟수   : {} (예상값: 1)", successCount.get());
        log.info("실패 횟수   : {} (예상값: 99)", failCount.get());
        log.info("개발자 잔액 : {} (예상값: 1000)", updatedDeveloper.getPoint());
        log.info("게스트 잔액 : {} (예상값: 0)", updatedGuest.getPoint());
        log.info("=============================================");
    }

    @Test
    @DisplayName("Hotspot 방어: 100명의 유저가 동시에 1000원씩 보내면, 개발자는 정확히 10만원을 받아야 한다.")
    void hotspotTest() throws InterruptedException {
        // 1. Given: 개발자 UUID를 매번 랜덤 생성하여 테스트 간 충돌 방지
        String randomDeveloperUuid = UUID.randomUUID().toString();
        Member developer = saveAndTrack(new Member(randomDeveloperUuid, 0L));

        int threadCount = 100;
        List<Member> guests = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            // Guest들도 랜덤 UUID로 생성 및 추적
            guests.add(saveAndTrack(new Member(1000L)));
        }

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 2. When: 100명이 동시에 개발자에게 송금
        for (int i = 0; i < threadCount; i++) {
            final String guestUuid = guests.get(i).getUuid();
            executorService.submit(() -> {
                try {
                    // DonationService 호출
                    donationService.sendCoffee(guestUuid, developer.getId(), 1000L);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("이체 실패: ", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // 3. Then
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();

        // 검증 (모든 Atomic Update 쿼리가 독립적으로 성공했는지 확인)
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failCount.get()).isEqualTo(0);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(100 * 1000L);

        log.info("==== [Hotspot 테스트 결과] ====");
        log.info("성공 횟수   : {} (예상값: 100)", successCount.get());
        log.info("개발자 잔액 : {} (예상값: 100,000)", updatedDeveloper.getPoint());
        log.info("=============================");
    }
}