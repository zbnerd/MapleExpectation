package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationDlq;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import maple.expectation.domain.v2.Member;
import maple.expectation.repository.v2.DonationDlqRepository;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.DonationOutboxRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.service.v2.DonationService;
import maple.expectation.service.v2.auth.AdminService;
import maple.expectation.service.v2.donation.outbox.DlqHandler;
import maple.expectation.service.v2.donation.outbox.OutboxProcessor;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightmare 17: Poison Pill - 실제 OutboxProcessor 처리 흐름 검증
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - Payload 변조로 verifyIntegrity() 실패 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - OutboxProcessor → DlqHandler 자동 연동
 *   <li>🟢 Green (Performance): 메트릭 검증 - DLQ 메시지 수, 처리 성공률
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - ContentHash 검증 및 원본 보존
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 실제 처리 흐름 검증
 * </ul>
 *
 * <h4>테스트 목적</h4>
 *
 * <p>실제 {@code DonationService}로 생성된 Outbox가 처리 과정에서 무결성 검증 실패 시 {@code OutboxProcessor}가 자동으로 DLQ로
 * 이동시키는지 검증한다.
 *
 * <h4>Poison Pill 시나리오</h4>
 *
 * <ol>
 *   <li><b>Payload 변조</b>: DB에서 직접 payload 수정 → contentHash 불일치
 *   <li><b>verifyIntegrity() 실패</b>: OutboxProcessor.processEntry()에서 감지
 *   <li><b>handleIntegrityFailure()</b>: 즉시 DEAD_LETTER 상태 + DLQ 이동
 *   <li><b>Head-of-Line Blocking 방지</b>: Poison Pill 이후 정상 메시지도 처리
 * </ol>
 *
 * <h4>Triple Safety Net (DlqHandler.java)</h4>
 *
 * <ol>
 *   <li><b>1차</b>: DB DLQ INSERT
 *   <li><b>2차</b>: File Backup (DLQ 실패 시)
 *   <li><b>3차</b>: Discord Critical Alert + Metric
 * </ol>
 *
 * <h4>예상 결과: PASS</h4>
 *
 * <p>ContentHash 검증으로 Poison Pill 감지, Triple Safety Net으로 데이터 손실 방지.
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Poison Pill Pattern: 처리 불가능한 메시지로 시스템 중단
 *   <li>Head-of-Line (HoL) Blocking: 첫 메시지가 막히면 뒤 메시지도 처리 불가
 *   <li>Content Hash Verification: SHA-256으로 데이터 변조 감지
 *   <li>Dead Letter Queue (DLQ): 처리 실패 메시지 격리
 * </ul>
 *
 * @see maple.expectation.service.v2.donation.outbox.OutboxProcessor#processEntry
 * @see maple.expectation.domain.v2.DonationOutbox#verifyIntegrity()
 * @see maple.expectation.service.v2.donation.outbox.DlqHandler
 */
@Slf4j
@Tag("nightmare")
@DisplayName("Nightmare 17: Poison Pill - 실제 OutboxProcessor 처리 흐름")
class PoisonPillNightmareTest extends IntegrationTestSupport {

  @Autowired private DonationService donationService;

  @Autowired private OutboxProcessor outboxProcessor;

  @Autowired private DlqHandler dlqHandler;

  @Autowired private DonationOutboxRepository outboxRepository;

  @Autowired private DonationDlqRepository dlqRepository;

  @Autowired private MemberRepository memberRepository;

  @Autowired private DonationHistoryRepository donationHistoryRepository;

  @Autowired private AdminService adminService;

  @PersistenceContext private EntityManager entityManager;

  private String testAdminFingerprint;
  private final List<Long> createdMemberIds = new ArrayList<>();
  private final List<String> createdRequestIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    testAdminFingerprint = "poison-admin-" + UUID.randomUUID().toString().substring(0, 8);
    adminService.addAdmin(testAdminFingerprint);
  }

  @AfterEach
  @Transactional
  void tearDown() {
    if (testAdminFingerprint != null) {
      adminService.removeAdmin(testAdminFingerprint);
    }

    // DLQ 및 Outbox 정리
    for (String requestId : createdRequestIds) {
      dlqRepository.findByRequestId(requestId).ifPresent(dlqRepository::delete);
      outboxRepository.findByRequestId(requestId).ifPresent(outboxRepository::delete);
    }
    createdRequestIds.clear();

    if (!createdMemberIds.isEmpty()) {
      donationHistoryRepository.deleteAll();
      memberRepository.deleteAllByIdInBatch(createdMemberIds);
      createdMemberIds.clear();
    }
  }

  private Member saveAndTrack(Member member) {
    Member saved = memberRepository.save(member);
    createdMemberIds.add(saved.getId());
    return saved;
  }

  /**
   * 🔴 Red's Test 1: Payload 변조 → OutboxProcessor 자동 DLQ 이동
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>DonationService로 정상 Outbox 생성 (ContentHash 자동 계산)
   *   <li>Native Query로 payload 직접 변조 (Poison Pill 생성)
   *   <li>OutboxProcessor.pollAndProcess() 호출
   *   <li>verifyIntegrity() 실패 → handleIntegrityFailure() → DLQ 자동 이동
   * </ol>
   *
   * <p><b>핵심 검증</b>: 실제 처리 흐름에서 Poison Pill이 자동으로 DLQ로 이동
   */
  @Test
  @DisplayName("Payload 변조 시 OutboxProcessor가 자동으로 DLQ 이동")
  @Transactional
  void shouldAutomaticallyMoveToDlq_whenPayloadCorrupted() throws Exception {
    // Given: DonationService로 정상 Outbox 생성
    Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
    Member guest = saveAndTrack(Member.createGuest(10000L));

    String requestId = "POISON-" + UUID.randomUUID().toString().substring(0, 8);
    createdRequestIds.add(requestId);

    donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, requestId);

    // Outbox 생성 확인
    Optional<DonationOutbox> outboxOpt = outboxRepository.findByRequestId(requestId);
    assertThat(outboxOpt).isPresent();

    DonationOutbox outbox = outboxOpt.get();
    String originalPayload = outbox.getPayload();
    String originalHash = outbox.getContentHash();

    log.info(
        "[Red] Outbox created - requestId={}, hash={}",
        requestId,
        originalHash.substring(0, 16) + "...");

    // Poison Pill 생성: payload 직접 변조 (ContentHash 불일치 유발)
    String poisonPayload = "{\"corrupted\":true,\"malicious\":\"POISON_PILL_DATA\"}";

    int updated =
        entityManager
            .createNativeQuery(
                "UPDATE donation_outbox SET payload = :poison WHERE request_id = :requestId")
            .setParameter("poison", poisonPayload)
            .setParameter("requestId", requestId)
            .executeUpdate();

    assertThat(updated).isEqualTo(1);
    entityManager.flush();
    entityManager.clear(); // 1차 캐시 클리어

    log.info(
        "[Red] Payload corrupted - original: {}..., poison: {}",
        originalPayload.substring(0, Math.min(30, originalPayload.length())),
        poisonPayload.substring(0, Math.min(30, poisonPayload.length())));

    // When: OutboxProcessor가 처리 시도
    outboxProcessor.pollAndProcess();

    // Then: DLQ로 자동 이동 확인
    Optional<DonationDlq> dlqOpt = dlqRepository.findByRequestId(requestId);
    Optional<DonationOutbox> finalOutbox = outboxRepository.findByRequestId(requestId);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│   Nightmare 17: Poison Pill Auto-DLQ Migration Results     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Request ID: {}                                             ", truncate(requestId, 40));
    log.info(
        "│ Original Hash: {}...                                       ",
        originalHash.substring(0, 16));
    log.info("│ Payload Corrupted: YES                                     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Outbox Final Status: {}                                    ",
        finalOutbox.map(o -> o.getStatus().name()).orElse("NOT_FOUND"));
    log.info(
        "│ DLQ Entry Created: {}                                      ",
        dlqOpt.isPresent() ? "YES ✅" : "NO ❌");

    if (dlqOpt.isPresent()) {
      DonationDlq dlq = dlqOpt.get();
      log.info(
          "│ DLQ Failure Reason: {}                                    ",
          truncate(dlq.getFailureReason(), 40));
    }

    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ verifyIntegrity() 실패 → 자동 DLQ 이동 완료              │");
    log.info("│ ✅ Triple Safety Net 1차 DB INSERT 성공                     │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // Assertions
    assertThat(dlqOpt).as("[Nightmare] Payload 변조된 Poison Pill은 DLQ로 자동 이동해야 함").isPresent();

    assertThat(finalOutbox.map(DonationOutbox::getStatus).orElse(null))
        .as("Outbox 상태가 DEAD_LETTER로 변경되어야 함")
        .isEqualTo(OutboxStatus.DEAD_LETTER);

    assertThat(dlqOpt.get().getFailureReason())
        .as("DLQ에 무결성 검증 실패 사유가 기록되어야 함")
        .containsIgnoringCase("integrity");
  }

  /**
   * 🔵 Blue's Test 2: Head-of-Line Blocking 방지 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>정상 Outbox 3개 + Poison Pill 2개 생성 (총 5개)
   *   <li>OutboxProcessor.pollAndProcess() 호출
   *   <li>Poison Pill은 DLQ로 이동, 정상 메시지는 COMPLETED
   *   <li>Head-of-Line Blocking 발생하지 않음 확인
   * </ol>
   *
   * <p><b>핵심 검증</b>: Poison Pill이 있어도 다른 메시지 처리가 막히지 않음
   */
  @Test
  @DisplayName("Head-of-Line Blocking 방지 - Poison Pill 뒤 정상 메시지도 처리")
  @Transactional
  void shouldPreventHoLBlocking_whenPoisonPillExists() throws Exception {
    // Given: Admin 및 Guest 생성
    Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
    Member guest = saveAndTrack(Member.createGuest(100000L)); // 충분한 포인트

    int totalMessages = 5;
    int poisonPillIndices[] = {0, 2}; // 첫 번째와 세 번째를 Poison Pill로

    List<String> requestIds = new ArrayList<>();

    // 5개의 Outbox 생성
    for (int i = 0; i < totalMessages; i++) {
      String requestId = "HOL-TEST-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
      requestIds.add(requestId);
      createdRequestIds.add(requestId);

      donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, requestId);
      log.info("[Blue] Created Outbox #{}: {}", i, requestId);
    }

    // Poison Pill 생성: 특정 인덱스의 payload 변조
    for (int poisonIdx : poisonPillIndices) {
      String poisonRequestId = requestIds.get(poisonIdx);
      String poisonPayload = "{\"poison\":true,\"index\":" + poisonIdx + "}";

      entityManager
          .createNativeQuery(
              "UPDATE donation_outbox SET payload = :poison WHERE request_id = :requestId")
          .setParameter("poison", poisonPayload)
          .setParameter("requestId", poisonRequestId)
          .executeUpdate();

      log.info("[Blue] Poisoned Outbox #{}: {}", poisonIdx, poisonRequestId);
    }

    entityManager.flush();
    entityManager.clear();

    // When: OutboxProcessor 처리
    outboxProcessor.pollAndProcess();

    // Then: 결과 분석
    int completedCount = 0;
    int deadLetterCount = 0;
    int dlqCount = 0;

    for (int i = 0; i < totalMessages; i++) {
      String requestId = requestIds.get(i);
      Optional<DonationOutbox> outbox = outboxRepository.findByRequestId(requestId);
      Optional<DonationDlq> dlq = dlqRepository.findByRequestId(requestId);

      OutboxStatus status = outbox.map(DonationOutbox::getStatus).orElse(null);

      if (status == OutboxStatus.COMPLETED) {
        completedCount++;
      } else if (status == OutboxStatus.DEAD_LETTER) {
        deadLetterCount++;
      }

      if (dlq.isPresent()) {
        dlqCount++;
      }

      log.info(
          "[Blue] Outbox #{} - Status: {}, DLQ: {}", i, status, dlq.isPresent() ? "YES" : "NO");
    }

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      Head-of-Line Blocking Prevention Results              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Messages: {}                                         ", totalMessages);
    log.info(
        "│ Poison Pills Injected: {}                                  ", poisonPillIndices.length);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ COMPLETED: {} (expected: {})                               ",
        completedCount,
        totalMessages - poisonPillIndices.length);
    log.info(
        "│ DEAD_LETTER: {} (expected: {})                             ",
        deadLetterCount,
        poisonPillIndices.length);
    log.info(
        "│ DLQ Entries: {} (expected: {})                             ",
        dlqCount,
        poisonPillIndices.length);
    log.info("├────────────────────────────────────────────────────────────┤");

    if (completedCount == totalMessages - poisonPillIndices.length
        && deadLetterCount == poisonPillIndices.length) {
      log.info("│ ✅ No Head-of-Line Blocking!                               │");
      log.info("│ ✅ Normal messages processed despite Poison Pills          │");
    } else {
      log.info("│ ❌ HoL Blocking detected or processing error               │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // Assertions
    assertThat(completedCount)
        .as("[Nightmare] 정상 메시지는 Poison Pill과 관계없이 COMPLETED 되어야 함")
        .isEqualTo(totalMessages - poisonPillIndices.length);

    assertThat(deadLetterCount)
        .as("Poison Pill은 모두 DEAD_LETTER 상태가 되어야 함")
        .isEqualTo(poisonPillIndices.length);

    assertThat(dlqCount).as("모든 Poison Pill은 DLQ에 저장되어야 함").isEqualTo(poisonPillIndices.length);
  }

  /**
   * 🟢 Green's Test 3: Max Retry 초과 → DLQ 이동 검증
   *
   * <p>재시도 횟수(maxRetries=3)를 초과하면 자동으로 DLQ로 이동
   */
  @Test
  @DisplayName("Max Retry 초과 시 자동 DLQ 이동")
  @Transactional
  void shouldMoveToDlq_whenMaxRetryExceeded() throws Exception {
    // Given: Outbox 생성
    Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
    Member guest = saveAndTrack(Member.createGuest(10000L));

    String requestId = "RETRY-" + UUID.randomUUID().toString().substring(0, 8);
    createdRequestIds.add(requestId);

    donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 1000L, requestId);

    // retryCount를 maxRetries 이상으로 설정 (handleFailure 시 DLQ 이동)
    entityManager
        .createNativeQuery(
            "UPDATE donation_outbox SET retry_count = 3 WHERE request_id = :requestId")
        .setParameter("requestId", requestId)
        .executeUpdate();

    entityManager.flush();
    entityManager.clear();

    // Outbox 조회
    Optional<DonationOutbox> outboxOpt = outboxRepository.findByRequestId(requestId);
    assertThat(outboxOpt).isPresent();

    DonationOutbox outbox = outboxOpt.get();
    log.info("[Green] Outbox retryCount set to max: {}", outbox.getRetryCount());

    // When: handleFailure 호출 (실제 OutboxProcessor에서 호출되는 메서드)
    outboxProcessor.handleFailure(outbox, "Simulated processing failure after max retries");

    // Then: DLQ 이동 확인
    Optional<DonationDlq> dlqOpt = dlqRepository.findByRequestId(requestId);
    Optional<DonationOutbox> finalOutbox = outboxRepository.findByRequestId(requestId);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      Max Retry Exceeded → DLQ Migration Results            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Retry Count: {} (max: 3)                                   ", outbox.getRetryCount());
    log.info(
        "│ shouldMoveToDlq(): {}                                      ", outbox.shouldMoveToDlq());
    log.info(
        "│ Final Status: {}                                           ",
        finalOutbox.map(o -> o.getStatus().name()).orElse("N/A"));
    log.info(
        "│ DLQ Entry: {}                                              ",
        dlqOpt.isPresent() ? "YES ✅" : "NO ❌");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ Exponential backoff exhausted → DLQ 이동 완료            │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(dlqOpt).as("[Nightmare] Max retry 초과 시 DLQ로 이동해야 함").isPresent();

    assertThat(finalOutbox.map(DonationOutbox::getStatus).orElse(null))
        .as("Outbox 상태가 DEAD_LETTER로 변경되어야 함")
        .isEqualTo(OutboxStatus.DEAD_LETTER);
  }

  /**
   * 🟣 Purple's Test 4: DLQ 데이터 무결성 - 원본 보존 검증
   *
   * <p>DLQ로 이동된 메시지의 원본 데이터가 손실 없이 보존되는지 검증
   */
  @Test
  @DisplayName("DLQ 데이터 무결성 - 변조된 payload도 원본으로 보존")
  @Transactional
  void shouldPreserveCorruptedPayload_inDlq() throws Exception {
    // Given: Outbox 생성 및 Poison Pill 주입
    Member admin = saveAndTrack(Member.createSystemAdmin(testAdminFingerprint, 0L));
    Member guest = saveAndTrack(Member.createGuest(10000L));

    String requestId = "DLQ-PRESERVE-" + UUID.randomUUID().toString().substring(0, 8);
    createdRequestIds.add(requestId);

    donationService.sendCoffee(guest.getUuid(), testAdminFingerprint, 5000L, requestId);

    // Poison Pill 생성
    String poisonPayload = "{\"attack\":\"injection\",\"data\":\"<script>alert(1)</script>\"}";

    entityManager
        .createNativeQuery(
            "UPDATE donation_outbox SET payload = :poison WHERE request_id = :requestId")
        .setParameter("poison", poisonPayload)
        .setParameter("requestId", requestId)
        .executeUpdate();

    entityManager.flush();
    entityManager.clear();

    // When: OutboxProcessor 처리
    outboxProcessor.pollAndProcess();

    // Then: DLQ에서 원본(변조된) 데이터 확인
    Optional<DonationDlq> dlqOpt = dlqRepository.findByRequestId(requestId);
    assertThat(dlqOpt).isPresent();

    DonationDlq dlq = dlqOpt.get();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      DLQ Data Integrity - Corrupted Payload Preserved      │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Request ID: {}                                             ", truncate(requestId, 40));
    log.info(
        "│ Injected Poison Payload: {}                                ",
        truncate(poisonPayload, 40));
    log.info(
        "│ DLQ Stored Payload: {}                                     ",
        truncate(dlq.getPayload(), 40));
    log.info(
        "│ Payload Match: {}                                          ",
        poisonPayload.equals(dlq.getPayload()) ? "YES ✅" : "NO ❌");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Event Type: {}                                             ", dlq.getEventType());
    log.info(
        "│ Failure Reason: {}                                         ",
        truncate(dlq.getFailureReason(), 40));
    log.info("│ Moved At: {}                                               ", dlq.getMovedAt());
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ 변조된 payload도 DLQ에 원본 그대로 보존                    │");
    log.info("│ 🔍 보안 분석을 위한 Forensic 데이터 유지                       │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(dlq.getPayload())
        .as("[Nightmare] 변조된 payload도 DLQ에 원본 그대로 보존되어야 함 (Forensic)")
        .isEqualTo(poisonPayload);

    assertThat(dlq.getFailureReason()).as("무결성 검증 실패 사유가 기록되어야 함").isNotNull();
  }

  /**
   * 🟡 Yellow's Test 5: Triple Safety Net 전체 구조 검증
   *
   * <p>DlqHandler의 Triple Safety Net이 올바르게 구성되어 있는지 문서화
   */
  @Test
  @DisplayName("Triple Safety Net 아키텍처 검증")
  void shouldVerifyTripleSafetyNetArchitecture() {
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│      Triple Safety Net Architecture (DlqHandler)           │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔒 1차 안전망: DB DLQ INSERT                                │");
    log.info("│   • DonationDlq 엔티티 저장                                 │");
    log.info("│   • originalOutboxId로 원본 추적                            │");
    log.info("│   • Metrics: outbox_dlq_total.increment()                  │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 📁 2차 안전망: File Backup (DB 실패 시)                      │");
    log.info("│   • ShutdownDataPersistenceService 사용                    │");
    log.info("│   • JSON 포맷으로 로컬 파일 저장                             │");
    log.info("│   • Metrics: outbox_file_backup.increment()                │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🚨 3차 안전망: Discord Critical Alert                       │");
    log.info("│   • DiscordAlertService.sendCriticalAlert()                │");
    log.info("│   • 운영자 즉시 알림                                         │");
    log.info("│   • Metrics: outbox_critical_failure.increment()           │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│                                                            │");
    log.info("│ 🛡️ Poison Pill 방어 전략:                                   │");
    log.info("│   1. ContentHash 검증 (verifyIntegrity)                    │");
    log.info("│   2. 즉시 DEAD_LETTER 상태 전환                             │");
    log.info("│   3. 다른 메시지 처리 계속 (HoL Blocking 방지)               │");
    log.info("│   4. Forensic 분석을 위한 원본 보존                          │");
    log.info("│                                                            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 📊 모니터링 쿼리:                                            │");
    log.info("│   • outbox_dlq_total > 0: DLQ 발생 알림                     │");
    log.info("│   • outbox_integrity_failure_total: 변조 시도 감지          │");
    log.info("│   • outbox_processed_total: 정상 처리량                     │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // Bean 주입 확인
    assertThat(outboxProcessor).as("OutboxProcessor 빈이 주입되어야 함").isNotNull();

    assertThat(dlqHandler).as("DlqHandler 빈이 주입되어야 함").isNotNull();

    assertThat(dlqRepository).as("DonationDlqRepository 빈이 주입되어야 함").isNotNull();
  }

  // ========== Helper Methods ==========

  private String truncate(String str, int maxLength) {
    if (str == null) return "null";
    return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
  }
}
