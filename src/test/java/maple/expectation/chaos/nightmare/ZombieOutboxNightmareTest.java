package maple.expectation.chaos.nightmare;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.DonationOutboxRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.service.v2.DonationService;
import maple.expectation.service.v2.auth.AdminService;
import maple.expectation.service.v2.donation.outbox.OutboxProcessor;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightmare 13: Zombie Outbox - 영원히 처리되지 않는 메시지
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - JVM 크래시 시뮬레이션으로 PROCESSING 상태 고착</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - Outbox 상태 전이 및 복구 로직</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - Stalled 메시지 수, 복구 시간</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 메시지 손실 없음 확인</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Zombie 메시지 발견 시 P0 Issue 생성</li>
 * </ul>
 *
 * <h4>실제 서비스 사용</h4>
 * <p>이 테스트는 실제 DonationService.sendCoffee()를 통해 Outbox를 생성합니다.
 * 임의로 SQL INSERT를 사용하지 않고, 실제 운영 환경과 동일한 흐름을 검증합니다.</p>
 *
 * <h4>예상 결과: CONDITIONAL PASS</h4>
 * <p>PROCESSING 상태에서 JVM 크래시 시 메시지가 Zombie화.
 * recoverStalled() 메서드가 정상 작동하면 복구됨.</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Outbox Pattern: 분산 트랜잭션 대안</li>
 *   <li>Idempotency: 중복 처리 방지</li>
 *   <li>At-Least-Once Delivery: 최소 1회 전달 보장</li>
 *   <li>Stale Processing: 오래된 작업 상태 감지</li>
 *   <li>Heartbeat: 작업 생존 신호</li>
 * </ul>
 *
 * @see DonationService#sendCoffee(String, String, Long, String)
 * @see OutboxProcessor
 * @see DonationOutboxRepository
 */
@Slf4j
@Tag("nightmare")
@DisplayName("Nightmare 13: Zombie Outbox - 영원히 처리되지 않는 메시지")
class ZombieOutboxNightmareTest extends IntegrationTestSupport {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DonationService donationService;

    @Autowired
    private DonationOutboxRepository outboxRepository;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private AdminService adminService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private DonationHistoryRepository donationHistoryRepository;

    // 테스트 데이터 관리
    private String testAdminFingerprint;
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<String> createdRequestIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 테스트용 Admin fingerprint 생성 및 등록
        testAdminFingerprint = "zombie-admin-" + UUID.randomUUID().toString().substring(0, 8);
        adminService.addAdmin(testAdminFingerprint);
        log.info("[Setup] Created admin fingerprint: {}", testAdminFingerprint);
    }

    @AfterEach
    void tearDown() {
        // Admin 등록 해제
        if (testAdminFingerprint != null) {
            adminService.removeAdmin(testAdminFingerprint);
        }

        // 테스트 데이터 정리
        try {
            // Outbox 정리
            for (String requestId : createdRequestIds) {
                outboxRepository.findByRequestId(requestId)
                        .ifPresent(outboxRepository::delete);
            }
            createdRequestIds.clear();

            // DonationHistory 정리
            donationHistoryRepository.deleteAll();

            // Member 정리
            if (!createdMemberIds.isEmpty()) {
                memberRepository.deleteAllByIdInBatch(createdMemberIds);
                createdMemberIds.clear();
            }
        } catch (Exception e) {
            log.warn("[Cleanup] Error during cleanup: {}", e.getMessage());
        }
    }

    /**
     * 🔴 Red's Test 1: 실제 DonationService로 Outbox 생성 후 Zombie 복구 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>DonationService.sendCoffee()로 실제 Outbox 생성</li>
     *   <li>Outbox 상태를 PROCESSING으로 변경하고 processed_at을 10분 전으로 설정 (JVM 크래시 시뮬레이션)</li>
     *   <li>recoverStalled() 호출</li>
     *   <li>상태가 PENDING 또는 FAILED로 복구되는지 확인</li>
     * </ol>
     *
     * <p><b>성공 기준</b>: PROCESSING 상태가 복구됨</p>
     * <p><b>실패 조건</b>: PROCESSING 상태 유지 → Zombie 메시지</p>
     */
    @Test
    @DisplayName("실제 DonationService로 Outbox 생성 후 Zombie 복구 검증")
    void shouldRecoverZombieOutbox_createdByActualDonationService() throws Exception {
        // Given: 실제 DonationService를 통해 Outbox 생성
        Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
        Member guest = saveAndTrack(Member.createGuest(10000L));

        String requestId = "ZOMBIE-" + UUID.randomUUID();
        createdRequestIds.add(requestId);

        log.info("[Red] Creating real Outbox via DonationService.sendCoffee()...");
        donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, requestId);

        // Outbox가 생성되었는지 확인
        DonationOutbox createdOutbox = outboxRepository.findByRequestId(requestId)
                .orElseThrow(() -> new AssertionError("Outbox not created by DonationService"));
        log.info("[Red] Outbox created: requestId={}, status={}", requestId, createdOutbox.getStatus());

        // JVM 크래시 시뮬레이션: PROCESSING 상태로 변경 + 10분 전 시간 설정
        simulateJvmCrash(requestId, 10);

        // When: 복구 실행
        log.info("[Red] Executing recoverStalled()...");
        outboxProcessor.recoverStalled();

        // Then: 상태 확인
        Thread.sleep(500); // 복구 완료 대기

        DonationOutbox recovered = outboxRepository.findByRequestId(requestId).orElse(null);

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│   Nightmare 13: Zombie Outbox Recovery (Real Service)      │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Service Used: DonationService.sendCoffee()                 │");
        log.info("│ Request ID: {}...                                          │", requestId.substring(0, 20));

        if (recovered != null) {
            log.info("│ Initial Status: PENDING → Forced to PROCESSING            │");
            log.info("│ Final Status: {}                                           │", recovered.getStatus());
            log.info("│ Retry Count: {}                                            │", recovered.getRetryCount());

            boolean isRecovered = recovered.getStatus() != OutboxStatus.PROCESSING;

            if (isRecovered) {
                log.info("│ ✅ Zombie successfully recovered                           │");
            } else {
                log.info("│ ❌ ZOMBIE STILL ALIVE! Status: {}                          │", recovered.getStatus());
            }
        } else {
            log.info("│ ⚠️ Entry not found (may have been processed)               │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");

        // 검증: PROCESSING이 아니어야 함
        assertThat(recovered).isNotNull();
        assertThat(recovered.getStatus())
                .as("[Nightmare] Stalled entry should be recovered from PROCESSING")
                .isNotEqualTo(OutboxStatus.PROCESSING);
    }

    /**
     * 🔵 Blue's Test 2: 다중 Donation으로 생성된 Zombie 동시 복구
     *
     * <p>여러 인스턴스에서 크래시된 상황 시뮬레이션</p>
     */
    @Test
    @DisplayName("다중 Donation으로 생성된 여러 Zombie 동시 복구")
    void shouldRecoverMultipleZombies_createdByRealDonations() throws Exception {
        // Given: Admin과 Guest 생성
        Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
        Member guest = saveAndTrack(Member.createGuest(50000L)); // 충분한 포인트

        int zombieCount = 5;
        List<String> requestIds = new ArrayList<>();

        // 여러 Donation 실행
        for (int i = 0; i < zombieCount; i++) {
            String requestId = "ZOMBIE-MULTI-" + i + "-" + UUID.randomUUID();
            requestIds.add(requestId);
            createdRequestIds.add(requestId);

            donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, requestId);
            log.info("[Blue] Created Outbox {}: {}...", i + 1, requestId.substring(0, 25));
        }

        // 모든 Outbox를 Zombie로 변환
        for (String requestId : requestIds) {
            simulateJvmCrash(requestId, 7);
        }
        log.info("[Blue] Simulated JVM crash for {} outbox entries", zombieCount);

        // When: 복구 실행
        outboxProcessor.recoverStalled();
        Thread.sleep(500);

        // Then: 복구 확인
        int recoveredCount = 0;
        for (String requestId : requestIds) {
            var entry = outboxRepository.findByRequestId(requestId).orElse(null);
            if (entry != null && entry.getStatus() != OutboxStatus.PROCESSING) {
                recoveredCount++;
            }
        }

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│         Multi-Instance Crash Recovery (Real)               │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Total Zombies Created: {}                                  │", zombieCount);
        log.info("│ Recovered: {}                                              │", recoveredCount);
        log.info("│ Still Zombies: {}                                          │", zombieCount - recoveredCount);
        log.info("└────────────────────────────────────────────────────────────┘");

        assertThat(recoveredCount)
                .as("All stalled entries should be recovered")
                .isEqualTo(zombieCount);
    }

    /**
     * 🟢 Green's Test 3: Zombie 감지 임계값 검증 (5분 기준)
     *
     * <p>STALE_THRESHOLD(5분)이 적절한지 검증</p>
     */
    @Test
    @DisplayName("Zombie 감지 임계값 검증 (5분 기준)")
    void shouldDetectZombiesBasedOnThreshold() throws Exception {
        // Given: Admin과 Guest 생성
        Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
        Member guest = saveAndTrack(Member.createGuest(20000L));

        // 2개의 Outbox 생성
        String recentRequestId = "ZOMBIE-RECENT-" + UUID.randomUUID();
        String staleRequestId = "ZOMBIE-STALE-" + UUID.randomUUID();
        createdRequestIds.add(recentRequestId);
        createdRequestIds.add(staleRequestId);

        donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, recentRequestId);
        donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, staleRequestId);

        log.info("[Green] Created recent and stale outbox entries");

        // Recent: 2분 전 (임계값 미만 - 복구되면 안 됨)
        simulateJvmCrash(recentRequestId, 2);

        // Stale: 10분 전 (임계값 초과 - 복구되어야 함)
        simulateJvmCrash(staleRequestId, 10);

        // When: 복구 실행
        outboxProcessor.recoverStalled();
        Thread.sleep(500);

        // Then: 결과 확인
        var recent = outboxRepository.findByRequestId(recentRequestId).orElse(null);
        var stale = outboxRepository.findByRequestId(staleRequestId).orElse(null);

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│         Zombie Detection Threshold Analysis                │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Threshold: 5 minutes                                       │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Recent (2min):                                             │");
        log.info("│   Status: {}                                               │",
                recent != null ? recent.getStatus() : "NOT FOUND");
        log.info("│   Expected: May remain PROCESSING (below threshold)        │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Stale (10min):                                             │");
        log.info("│   Status: {}                                               │",
                stale != null ? stale.getStatus() : "NOT FOUND");
        log.info("│   Expected: PENDING/FAILED (should be recovered)           │");
        log.info("└────────────────────────────────────────────────────────────┘");

        // Stale은 반드시 복구되어야 함
        assertThat(stale).isNotNull();
        assertThat(stale.getStatus())
                .as("Stale entry (10min) should be recovered")
                .isNotEqualTo(OutboxStatus.PROCESSING);
    }

    /**
     * 🟣 Purple's Test 4: Zombie 복구 후 데이터 무결성 검증
     *
     * <p>복구 후 원본 데이터가 손상되지 않았는지 검증</p>
     */
    @Test
    @DisplayName("Zombie 복구 후 데이터 무결성 검증")
    void shouldMaintainDataIntegrity_afterZombieRecovery() throws Exception {
        // Given: Admin과 Guest 생성
        Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
        Member guest = saveAndTrack(Member.createGuest(10000L));

        String requestId = "ZOMBIE-INTEGRITY-" + UUID.randomUUID();
        createdRequestIds.add(requestId);

        // 실제 Donation 실행
        donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 5000L, requestId);

        // 원본 Outbox 데이터 저장
        DonationOutbox original = outboxRepository.findByRequestId(requestId)
                .orElseThrow(() -> new AssertionError("Outbox not created"));
        String originalPayload = original.getPayload();
        String originalEventType = original.getEventType();

        log.info("[Purple] Original payload: {}", originalPayload);

        // JVM 크래시 시뮬레이션
        simulateJvmCrash(requestId, 10);

        // When: 복구 실행
        outboxProcessor.recoverStalled();
        Thread.sleep(500);

        // Then: 데이터 무결성 확인
        DonationOutbox recovered = outboxRepository.findByRequestId(requestId).orElse(null);

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│         Data Integrity After Zombie Recovery               │");
        log.info("├────────────────────────────────────────────────────────────┤");

        if (recovered != null) {
            boolean payloadIntact = originalPayload.equals(recovered.getPayload());
            boolean eventTypeIntact = originalEventType.equals(recovered.getEventType());
            boolean hashValid = recovered.verifyIntegrity();

            log.info("│ Payload Intact: {}                                         │", payloadIntact ? "✅ YES" : "❌ NO");
            log.info("│ Event Type Intact: {}                                      │", eventTypeIntact ? "✅ YES" : "❌ NO");
            log.info("│ Content Hash Valid: {}                                     │", hashValid ? "✅ YES" : "❌ NO");
            log.info("│ Recovery Status: {}                                        │", recovered.getStatus());

            assertThat(payloadIntact)
                    .as("Payload should remain intact after recovery")
                    .isTrue();
            assertThat(eventTypeIntact)
                    .as("Event type should remain intact after recovery")
                    .isTrue();
        } else {
            log.info("│ Entry not found                                            │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");
    }

    // ========== Helper Methods ==========

    private Member saveAndTrack(Member member) {
        Member saved = memberRepository.save(member);
        createdMemberIds.add(saved.getId());
        return saved;
    }

    /**
     * JVM 크래시 시뮬레이션: Outbox 상태를 PROCESSING으로 변경하고 시간을 과거로 설정
     *
     * <p>실제 JVM 크래시 시 OutboxProcessor가 PROCESSING 상태로 변경한 후
     * 완료 처리 전에 크래시되는 상황을 재현합니다.</p>
     */
    private void simulateJvmCrash(String requestId, int minutesAgo) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // DonationOutbox 실제 컬럼명: locked_by, locked_at (JPA 스네이크 케이스 변환)
            String updateSql = """
                UPDATE donation_outbox
                SET status = 'PROCESSING',
                    locked_by = 'crashed-instance',
                    locked_at = ?
                WHERE request_id = ?
                """;

            LocalDateTime stalledTime = LocalDateTime.now().minusMinutes(minutesAgo);

            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setTimestamp(1, Timestamp.valueOf(stalledTime));
                ps.setString(2, requestId);
                int updated = ps.executeUpdate();
                log.debug("[SimulateCrash] Updated {} rows for requestId={}", updated, requestId);
            }
        }
    }
}
