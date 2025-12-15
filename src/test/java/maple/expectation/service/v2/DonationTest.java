package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
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

    // 🔥 핵심 해결책 1: 테스트 시작 전에 기존 데이터를 싹 지워버림
    // (이전에 실패해서 남은 데이터 때문에 에러 나는 것을 방지)
    @BeforeEach
    void cleanUp() {
        memberRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("따닥 방어: 1000원 가진 유저가 동시에 100번 요청해도, 딱 1번만 성공해야 한다.")
    void concurrencyTest() throws InterruptedException {
        // 1. Given
        Member developer = memberRepository.save(new Member("00000000-0000-0000-0000-000000000000", 0L));
        Member guest = memberRepository.save(new Member(1000L));

        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 2. When
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    donationService.sendCoffee(guest.getUuid(), developer.getId(), 1000L);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    // 로그가 너무 많아질 수 있으니, 실패 로그는 debug 레벨이나 생략하는 게 깔끔합니다.
                    // log.debug("송금 실패: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // 3. Then
        Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();

        // 검증
        assertThat(updatedGuest.getPoint()).isEqualTo(0L);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(1000L);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(99);

        // 4. 결과 로그 출력 (System.out 대신 log.info 사용)
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
        // 1. Given
        // 🔥 핵심 해결책 2: UUID 충돌 방지를 위해 랜덤 UUID 사용
        String developerUuid = "00000000-0000-0000-0000-000000000000";
        Member developer = memberRepository.save(new Member(developerUuid, 0L));

        int threadCount = 100;
        List<Member> guests = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            // Guest들도 랜덤 UUID로 생성됨 (Member 생성자 로직 확인)
            guests.add(memberRepository.save(new Member(1000L)));
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

        // 검증
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failCount.get()).isEqualTo(0);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(100 * 1000L);

        log.info("==== [Hotspot 테스트 결과] ====");
        log.info("성공 횟수   : {} (예상값: 100)", successCount.get());
        log.info("개발자 잔액 : {} (예상값: 100,000)", updatedDeveloper.getPoint());
        log.info("=============================");
    }
}