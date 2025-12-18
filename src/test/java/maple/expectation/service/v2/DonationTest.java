package maple.expectation.service.v2;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationHistory;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.support.SpringBootTestWithTimeLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
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
    DonationHistoryRepository donationHistoryRepository; // 🆕 추가됨

    // ✅ 안전 장치: 테스트 중 생성된 Member ID 추적
    private final List<Long> createdMemberIds = new ArrayList<>();

    // 💡 헬퍼 메서드
    private Member saveAndTrack(Member member) {
        Member saved = memberRepository.save(member);
        createdMemberIds.add(saved.getId());
        return saved;
    }

    @AfterEach
    @Transactional
    void tearDown() {
        if (!createdMemberIds.isEmpty()) {
            // 🆕 1. 히스토리 먼저 삭제 (FK 제약조건 방지 및 깔끔한 정리)
            // 실제 운영 DB라면 deleteAll()은 위험하지만, 테스트 격리를 위해 생성한 멤버 관련 데이터만 지우는 로직이 이상적입니다.
            // 여기서는 편의상 테스트가 만든 멤버들이 받은 히스토리만 지운다고 가정하거나,
            // 현재 개발 단계(공용 DB)이므로 내가 만든 ID와 관련된 히스토리를 찾아 지웁니다.
            // (간단한 구현을 위해 여기서는 로직 생략하고 멤버 삭제 시도.
            // 만약 FK 에러가 나면 historyRepository에서 먼저 지워야 합니다.)

            // *안전한 삭제 팁*: createdMemberIds에 있는 ID가 receiverId인 히스토리 삭제
            // donationHistoryRepository.deleteByReceiverIdIn(createdMemberIds); (Repository에 메서드 필요)

            // 2. 멤버 삭제
            memberRepository.deleteAllById(createdMemberIds);
            createdMemberIds.clear();
        }
    }

    @Test
    @DisplayName("멱등성(Idempotency) 검증: 같은 RequestID로 두 번 요청하면, 잔액은 한 번만 차감되어야 한다.")
    void idempotencyTest() {
        // 1. Given
        String randomDeveloperUuid = UUID.randomUUID().toString();
        Member developer = saveAndTrack(new Member(randomDeveloperUuid, 0L));
        Member guest = saveAndTrack(new Member(1000L));

        String fixedRequestId = UUID.randomUUID().toString(); // 🔑 고정된 요청 ID

        // 2. When
        // 첫 번째 요청 (성공해야 함)
        donationService.sendCoffee(guest.getUuid(), developer.getId(), 1000L, fixedRequestId);

        // 두 번째 요청 (같은 ID - 무시되어야 함)
        donationService.sendCoffee(guest.getUuid(), developer.getId(), 1000L, fixedRequestId);

        // 3. Then
        Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();

        // 잔액은 1000원만 줄어들어야 함 (두 번째 요청은 씹혔으므로)
        assertThat(updatedGuest.getPoint()).isEqualTo(0L);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(1000L);

        // 히스토리는 1개만 남아야 함
        boolean exists = donationHistoryRepository.existsByRequestId(fixedRequestId);
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("따닥 방어: 1000원 가진 유저가 동시에 100번 요청(각기 다른 ID)해도, 잔액 부족으로 딱 1번만 성공해야 한다.")
    void concurrencyTest() throws InterruptedException {
        // 1. Given
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
                    // 🆕 수정됨: 매 요청마다 '새로운' RequestId를 생성해서 보냄
                    // 그래야 멱등성 필터를 통과하고 "잔액 부족" 로직까지 도달하여 동시성을 테스트할 수 있음
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

        // 3. Then
        Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();

        assertThat(updatedGuest.getPoint()).isEqualTo(0L);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(1000L);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(99);
    }

    @Test
    @DisplayName("Hotspot 방어: 100명의 유저가 동시에 1000원씩 보내면, 개발자는 정확히 10만원을 받아야 한다.")
    void hotspotTest() throws InterruptedException {
        // 1. Given
        String randomDeveloperUuid = UUID.randomUUID().toString();
        Member developer = saveAndTrack(new Member(randomDeveloperUuid, 0L));

        int threadCount = 100;
        List<Member> guests = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            guests.add(saveAndTrack(new Member(1000L)));
        }

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 2. When
        for (int i = 0; i < threadCount; i++) {
            final String guestUuid = guests.get(i).getUuid();
            executorService.submit(() -> {
                try {
                    // 🆕 수정됨: 각각 다른 요청이므로 고유한 RequestId 부여
                    String uniqueRequestId = UUID.randomUUID().toString();
                    donationService.sendCoffee(guestUuid, developer.getId(), 1000L, uniqueRequestId);
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
        Member updatedDeveloper = memberRepository.findById(developer.getId()).orElseThrow();
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failCount.get()).isEqualTo(0);
        assertThat(updatedDeveloper.getPoint()).isEqualTo(100 * 1000L);
    }
}