package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

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
public class DonationTest {

    @Autowired
    DonationService donationService;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    DonationHistoryRepository donationHistoryRepository;

    private final List<Long> createdMemberIds = new ArrayList<>();

    private Member saveAndTrack(Member member) {
        Member saved = memberRepository.save(member);
        createdMemberIds.add(saved.getId());
        return saved;
    }

    @AfterEach
    @Transactional
    void tearDown() {
        if (!createdMemberIds.isEmpty()) {
            // 💡 [데이터 정리 순서] 자식(History)을 먼저 지워야 외래키 제약조건 에러가 안 납니다.
            // 테스트에서 생성한 멤버들이 관여된 히스토리를 먼저 싹 비웁니다.
            donationHistoryRepository.deleteAll();

            // 그 다음 생성했던 멤버들을 삭제합니다.
            memberRepository.deleteAllByIdInBatch(createdMemberIds);
            createdMemberIds.clear();
        }
    }

    @Test
    @DisplayName("멱등성(Idempotency) 검증: 같은 RequestID로 두 번 요청하면, 잔액은 한 번만 차감되어야 한다.")
    void idempotencyTest() {
        // 1. Given
        String randomDeveloperUuid = UUID.randomUUID().toString();
        // 💡 [수정] new 대신 정적 팩토리 메서드 사용
        Member developer = saveAndTrack(Member.createSystemAdmin(randomDeveloperUuid, 0L));
        Member guest = saveAndTrack(Member.createGuest(1000L));

        String fixedRequestId = UUID.randomUUID().toString();

        // 2. When
        donationService.sendCoffee(guest.getUuid(), developer.getId(), 1000L, fixedRequestId);
        donationService.sendCoffee(guest.getUuid(), developer.getId(), 1000L, fixedRequestId);

        // 3. Then
        Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();

        assertThat(updatedGuest.getPoint()).isEqualTo(0L);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(1000L);
        assertThat(donationHistoryRepository.existsByRequestId(fixedRequestId)).isTrue();
    }

    @Test
    @DisplayName("따닥 방어: 1000원 가진 유저가 동시에 100번 요청(각기 다른 ID)해도, 잔액 부족으로 딱 1번만 성공해야 한다.")
    void concurrencyTest() throws InterruptedException {
        // 1. Given
        String randomDeveloperUuid = UUID.randomUUID().toString();
        // 💡 [수정] new 대신 정적 팩토리 메서드 사용
        Member developer = saveAndTrack(Member.createSystemAdmin(randomDeveloperUuid, 0L));
        Member guest = saveAndTrack(Member.createGuest(1000L));

        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 2. When
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    String uniqueRequestId = UUID.randomUUID().toString();
                    donationService.sendCoffee(guest.getUuid(), developer.getId(), 1000L, uniqueRequestId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 3. Then
        Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
        assertThat(updatedGuest.getPoint()).isEqualTo(0L);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(99);
    }

    @Test
    @DisplayName("Hotspot 방어: 100명의 유저가 동시에 1000원씩 보내면, 개발자는 정확히 10만원을 받아야 한다.")
    void hotspotTest() throws InterruptedException {
        // 1. Given
        String randomDeveloperUuid = UUID.randomUUID().toString();
        // 💡 [수정] new 대신 정적 팩토리 메서드 사용
        Member developer = saveAndTrack(Member.createSystemAdmin(randomDeveloperUuid, 0L));

        int threadCount = 100;
        List<Member> guests = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            guests.add(saveAndTrack(Member.createGuest(1000L)));
        }

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // 2. When
        for (int i = 0; i < threadCount; i++) {
            final String guestUuid = guests.get(i).getUuid();
            executorService.submit(() -> {
                try {
                    String uniqueRequestId = UUID.randomUUID().toString();
                    donationService.sendCoffee(guestUuid, developer.getId(), 1000L, uniqueRequestId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Donation failed: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 3. Then
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(100 * 1000L);
    }
}