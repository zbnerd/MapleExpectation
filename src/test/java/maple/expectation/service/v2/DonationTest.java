package maple.expectation.service.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.service.v2.auth.AdminService;
import maple.expectation.support.EnableTimeLogging;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/**
 * DonationService 통합 테스트
 *
 * <p>Admin(개발자)에게 커피를 보내는 기능을 검증합니다. Admin은 fingerprint로 식별되며, AdminService를 통해 등록됩니다.
 *
 * <h3>테스트 시나리오:</h3>
 *
 * <ul>
 *   <li>멱등성: 같은 requestId로 중복 요청 방지
 *   <li>동시성: 잔액 부족 시 단일 성공
 *   <li>Hotspot: 다중 발신자 → 단일 수신자
 * </ul>
 *
 * <p>CLAUDE.md Section 24 준수: @Execution(SAME_THREAD)로 병렬 실행 충돌 방지
 *
 * <p>IntegrationTestSupport 상속: 격리된 Redis 컨테이너 사용
 */
@Slf4j
@EnableTimeLogging
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("concurrency")
public class DonationTest extends IntegrationTestSupport {

  @Autowired DonationService donationService;
  @Autowired MemberRepository memberRepository;
  @Autowired DonationHistoryRepository donationHistoryRepository;
  @Autowired AdminService adminService;

  private final List<Long> createdMemberIds = new ArrayList<>();

  // 테스트용 Admin fingerprint (UUID 형식)
  private String testAdminFingerprint;

  @BeforeEach
  void setUp() {
    // 테스트용 Admin fingerprint 생성 및 등록
    testAdminFingerprint = "test-admin-" + UUID.randomUUID().toString().substring(0, 8);
    adminService.addAdmin(testAdminFingerprint);
  }

  private Member saveAndTrack(Member member) {
    Member saved = memberRepository.saveAndFlush(member);
    createdMemberIds.add(saved.getId());
    return saved;
  }

  @AfterEach
  void tearDown() {
    // Admin 등록 해제
    if (testAdminFingerprint != null) {
      adminService.removeAdmin(testAdminFingerprint);
    }

    if (!createdMemberIds.isEmpty()) {
      donationHistoryRepository.deleteAll();
      memberRepository.deleteAllByIdInBatch(createdMemberIds);
      createdMemberIds.clear();
    }
  }

  @Test
  @DisplayName("멱등성(Idempotency) 검증: 같은 RequestID로 두 번 요청하면, 잔액은 한 번만 차감되어야 한다.")
  void idempotencyTest() {
    // 1. Given - Admin Member 생성 (fingerprint를 uuid로 사용)
    Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
    Member guest = saveAndTrack(Member.createGuest(1000L));

    String fixedRequestId = UUID.randomUUID().toString();

    // 2. When - 같은 requestId로 2회 요청
    donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, fixedRequestId);
    donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, fixedRequestId);

    // 3. Then - 1회만 처리됨
    Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
    Member updatedAdmin = memberRepository.findById(admin.getId()).orElseThrow();

    assertThat(updatedGuest.getPoint()).isEqualTo(0L);
    assertThat(updatedAdmin.getPoint()).isEqualTo(1000L);
    assertThat(donationHistoryRepository.existsByRequestId(fixedRequestId)).isTrue();
  }

  @Test
  @Tag("flaky")
  @DisplayName("따닥 방어: 1000원 가진 유저가 동시에 100번 요청(각기 다른 ID)해도, 잔액 부족으로 딱 1번만 성공해야 한다.")
  void concurrencyTest() throws InterruptedException {
    // 1. Given
    Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
    Member guest = saveAndTrack(Member.createGuest(1000L));

    int threadCount = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(32);
    CountDownLatch latch = new CountDownLatch(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    // 2. When
    for (int i = 0; i < threadCount; i++) {
      executorService.submit(
          () -> {
            try {
              String uniqueRequestId = UUID.randomUUID().toString();
              donationService.sendCoffee(
                  guest.getUuid(), testAdminFingerprint, 1000L, uniqueRequestId);
              successCount.incrementAndGet();
            } catch (Exception e) {
              failCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executorService.shutdown();
    executorService.awaitTermination(5, TimeUnit.SECONDS);

    // 3. Then
    Member updatedGuest = memberRepository.findById(guest.getId()).orElseThrow();
    assertThat(updatedGuest.getPoint()).isEqualTo(0L);
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failCount.get()).isEqualTo(99);
  }

  @Test
  @Tag("flaky")
  @DisplayName("Hotspot 방어: 100명의 유저가 동시에 1000원씩 보내면, Admin은 정확히 10만원을 받아야 한다.")
  void hotspotTest() throws InterruptedException {
    // 1. Given
    Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));

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
      executorService.submit(
          () -> {
            try {
              String uniqueRequestId = UUID.randomUUID().toString();
              donationService.sendCoffee(guestUuid, testAdminFingerprint, 1000L, uniqueRequestId);
              successCount.incrementAndGet();
            } catch (Exception e) {
              log.error("Donation failed: {}", e.getMessage());
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(30, TimeUnit.SECONDS);
    executorService.shutdown();
    executorService.awaitTermination(5, TimeUnit.SECONDS);

    // 3. Then
    Member updatedAdmin = memberRepository.findById(admin.getId()).orElseThrow();
    assertThat(successCount.get()).isEqualTo(100);
    assertThat(updatedAdmin.getPoint()).isEqualTo(100 * 1000L);
  }
}
