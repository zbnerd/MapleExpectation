package maple.expectation.service.v2.donation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import maple.expectation.controller.dto.common.CursorPageRequest;
import maple.expectation.controller.dto.common.CursorPageResponse;
import maple.expectation.controller.dto.dlq.DlqDetailResponse;
import maple.expectation.controller.dto.dlq.DlqEntryResponse;
import maple.expectation.controller.dto.dlq.DlqReprocessResult;
import maple.expectation.domain.v2.DonationDlq;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.error.exception.DlqNotFoundException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.persistence.repository.DonationDlqRepository;
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DlqAdminService 단위 테스트
 *
 * <h3>테스트 케이스</h3>
 *
 * <ul>
 *   <li>DLQ 목록 조회 (페이징)
 *   <li>DLQ 상세 조회 (성공/실패)
 *   <li>DLQ 재처리 (성공/중복/실패)
 *   <li>DLQ 폐기 (성공/실패)
 *   <li>DLQ 총 건수 조회
 * </ul>
 *
 * <p>CLAUDE.md Section 24 준수: @Execution(SAME_THREAD)로 병렬 실행 충돌 방지
 *
 * <p>LENIENT 모드: Mock 공유 시 UnnecessaryStubbingException 방지
 *
 * <p>Note: @Nested 구조 제거 - MockitoExtension과의 mock 공유 이슈 방지
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Execution(ExecutionMode.SAME_THREAD)
class DlqAdminServiceTest {

  @Mock DonationDlqRepository dlqRepository;
  @Mock DonationOutboxRepository outboxRepository;
  @Mock OutboxMetrics metrics;

  private LogicExecutor executor;

  DlqAdminService dlqAdminService;

  private DonationDlq sampleDlq;

  @BeforeEach
  void setUp() throws Exception {
    // Use TestLogicExecutors for pass-through behavior
    executor = TestLogicExecutors.passThrough();

    // DlqAdminService 수동 생성 (Mock 주입)
    dlqAdminService = new DlqAdminService(dlqRepository, outboxRepository, metrics, executor);

    // Sample DLQ 생성
    sampleDlq =
        createSampleDlq(
            1L, "req-001", "DONATION_COMPLETED", "{\"amount\":1000}", "Max retry exceeded");
  }

  // ========== findAll Tests ==========

  @Test
  @DisplayName("findAll - 페이징으로 DLQ 목록을 조회한다")
  void findAllWithPaging() {
    // Given
    DonationDlq dlq1 = createSampleDlq(1L, "req-001", "DONATION_COMPLETED", "{}", "error1");
    DonationDlq dlq2 = createSampleDlq(2L, "req-002", "DONATION_COMPLETED", "{}", "error2");
    Page<DonationDlq> page = new PageImpl<>(List.of(dlq1, dlq2), PageRequest.of(0, 20), 2);

    given(dlqRepository.findAllByOrderByMovedAtDesc(any(Pageable.class))).willReturn(page);

    // When
    Page<DlqEntryResponse> result = dlqAdminService.findAll(0, 20);

    // Then
    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent().get(0).getRequestId()).isEqualTo("req-001");
  }

  // ========== findById Tests ==========

  @Test
  @DisplayName("findById - 존재하는 DLQ를 조회한다")
  void findByIdSuccess() {
    // Given
    given(dlqRepository.findById(1L)).willReturn(Optional.of(sampleDlq));

    // When
    DlqDetailResponse result = dlqAdminService.findById(1L);

    // Then
    assertThat(result.id()).isEqualTo(1L);
    assertThat(result.getRequestId()).isEqualTo("req-001");
    assertThat(result.payload()).isEqualTo("{\"amount\":1000}");
  }

  @Test
  @DisplayName("findById - 존재하지 않는 DLQ 조회 시 예외가 발생한다")
  void findByIdNotFound() {
    // Given: Mockito defaults Optional return to Optional.empty()

    // When & Then
    assertThatThrownBy(() -> dlqAdminService.findById(999L))
        .isInstanceOf(DlqNotFoundException.class);
  }

  // ========== reprocess Tests ==========

  @Test
  @DisplayName("reprocess - DLQ를 Outbox로 복원하여 재처리한다")
  void reprocessSuccess() {
    // Given
    given(dlqRepository.findById(1L)).willReturn(Optional.of(sampleDlq));
    given(outboxRepository.existsByRequestId(anyString())).willReturn(false);
    when(outboxRepository.save(any(DonationOutbox.class)))
        .thenAnswer(
            invocation -> {
              DonationOutbox outbox = invocation.getArgument(0);
              ReflectionTestUtils.setField(outbox, "id", 100L);
              return outbox;
            });
    when(outboxRepository.findByRequestId(anyString()))
        .thenReturn(
            Optional.of(
                DonationOutbox.create("req-001", "DONATION_COMPLETED", "{\"amount\":1000}")));

    // When
    DlqReprocessResult result = dlqAdminService.reprocess(1L);

    // Then
    assertThat(result.dlqId()).isEqualTo(1L);
    assertThat(result.getRequestId()).isEqualTo("req-001");

    verify(dlqRepository).delete(sampleDlq);
    verify(metrics).incrementDlqReprocessed();
  }

  @Test
  @DisplayName("reprocess - 중복 requestId가 있으면 Outbox 생성을 스킵하고 DLQ만 삭제한다")
  void reprocessWithDuplicateRequestId() {
    // Given
    given(dlqRepository.findById(1L)).willReturn(Optional.of(sampleDlq));
    given(outboxRepository.existsByRequestId(anyString())).willReturn(true);
    when(outboxRepository.findByRequestId(anyString()))
        .thenReturn(Optional.of(DonationOutbox.create("req-001", "DONATION_COMPLETED", "{}")));

    // When
    DlqReprocessResult result = dlqAdminService.reprocess(1L);

    // Then
    verify(outboxRepository, never()).save(any(DonationOutbox.class));
    verify(dlqRepository).delete(sampleDlq);
  }

  @Test
  @DisplayName("reprocess - 존재하지 않는 DLQ 재처리 시 예외가 발생한다")
  void reprocessNotFound() {
    // Given: Mockito defaults Optional return to Optional.empty()

    // When & Then
    assertThatThrownBy(() -> dlqAdminService.reprocess(999L))
        .isInstanceOf(DlqNotFoundException.class);
  }

  // ========== discard Tests ==========

  @Test
  @DisplayName("discard - DLQ를 폐기(삭제)한다")
  void discardSuccess() {
    // Given
    given(dlqRepository.findById(1L)).willReturn(Optional.of(sampleDlq));

    // When
    dlqAdminService.discard(1L);

    // Then
    verify(dlqRepository).delete(sampleDlq);
    verify(metrics).incrementDlqDiscarded();
  }

  @Test
  @DisplayName("discard - 존재하지 않는 DLQ 폐기 시 예외가 발생한다")
  void discardNotFound() {
    // Given: Mockito defaults Optional return to Optional.empty()

    // When & Then
    assertThatThrownBy(() -> dlqAdminService.discard(999L))
        .isInstanceOf(DlqNotFoundException.class);
  }

  // ========== count Tests ==========

  @Test
  @DisplayName("count - DLQ 총 건수를 조회한다")
  void countSuccess() {
    // Given
    given(dlqRepository.countAll()).willReturn(42L);

    // When
    long count = dlqAdminService.count();

    // Then
    assertThat(count).isEqualTo(42L);
  }

  // ========== findAllByCursor Tests (#233 Cursor Pagination) ==========

  @Test
  @DisplayName("findAllByCursor - 첫 페이지 조회 (cursor=null)")
  void findAllByCursorFirstPage() {
    // Given: DLQ 항목 5개 준비
    DonationDlq dlq1 = createSampleDlq(1L, "req-001", "DONATION_COMPLETED", "{}", "error1");
    DonationDlq dlq2 = createSampleDlq(2L, "req-002", "DONATION_COMPLETED", "{}", "error2");
    Slice<DonationDlq> slice = new SliceImpl<>(List.of(dlq1, dlq2), PageRequest.of(0, 2), true);

    given(dlqRepository.findFirstPage(any(Pageable.class))).willReturn(slice);

    CursorPageRequest request = CursorPageRequest.of(null, 2);

    // When
    CursorPageResponse<DlqEntryResponse> result = dlqAdminService.findAllByCursor(request);

    // Then
    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getHasNext()).isTrue();
    assertThat(result.getNextCursor()).isEqualTo(2L);
    assertThat(result.getContent().get(0).getRequestId()).isEqualTo("req-001");

    verify(dlqRepository).findFirstPage(any(Pageable.class));
    verify(dlqRepository, never()).findByCursorGreaterThan(any(), any());
  }

  @Test
  @DisplayName("findAllByCursor - 다음 페이지 조회 (cursor=2)")
  void findAllByCursorNextPage() {
    // Given: cursor 이후 항목 조회
    DonationDlq dlq3 = createSampleDlq(3L, "req-003", "DONATION_COMPLETED", "{}", "error3");
    DonationDlq dlq4 = createSampleDlq(4L, "req-004", "DONATION_COMPLETED", "{}", "error4");
    Slice<DonationDlq> slice = new SliceImpl<>(List.of(dlq3, dlq4), PageRequest.of(0, 2), true);

    given(dlqRepository.findByCursorGreaterThan(eq(2L), any(Pageable.class))).willReturn(slice);

    CursorPageRequest request = CursorPageRequest.of(2L, 2);

    // When
    CursorPageResponse<DlqEntryResponse> result = dlqAdminService.findAllByCursor(request);

    // Then
    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getHasNext()).isTrue();
    assertThat(result.getNextCursor()).isEqualTo(4L);
    assertThat(result.getContent().get(0).getRequestId()).isEqualTo("req-003");

    verify(dlqRepository, never()).findFirstPage(any());
    verify(dlqRepository).findByCursorGreaterThan(eq(2L), any(Pageable.class));
  }

  @Test
  @DisplayName("findAllByCursor - 마지막 페이지 도달 (hasNext=false)")
  void findAllByCursorLastPage() {
    // Given: 마지막 항목만 남음
    DonationDlq dlq5 = createSampleDlq(5L, "req-005", "DONATION_COMPLETED", "{}", "error5");
    Slice<DonationDlq> slice = new SliceImpl<>(List.of(dlq5), PageRequest.of(0, 10), false);

    given(dlqRepository.findByCursorGreaterThan(eq(4L), any(Pageable.class))).willReturn(slice);

    CursorPageRequest request = CursorPageRequest.of(4L, 10);

    // When
    CursorPageResponse<DlqEntryResponse> result = dlqAdminService.findAllByCursor(request);

    // Then
    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getHasNext()).isFalse();
    assertThat(result.getNextCursor()).isEqualTo(5L);
    assertThat(result.getContent().get(0).getRequestId()).isEqualTo("req-005");
  }

  @Test
  @DisplayName("findAllByCursor - Size 유효성 검사 (MAX_SIZE=100 초과 시 자동 조정)")
  void findAllByCursorSizeValidation() {
    // Given: size=200으로 요청 (MAX_SIZE=100 초과)
    DonationDlq dlq1 = createSampleDlq(1L, "req-001", "DONATION_COMPLETED", "{}", "error1");
    Slice<DonationDlq> slice = new SliceImpl<>(List.of(dlq1), PageRequest.of(0, 100), false);

    given(dlqRepository.findFirstPage(any(Pageable.class))).willReturn(slice);

    // CursorPageRequest.of()에서 MAX_SIZE로 자동 조정됨
    CursorPageRequest request = CursorPageRequest.of(null, 200);

    // When
    CursorPageResponse<DlqEntryResponse> result = dlqAdminService.findAllByCursor(request);

    // Then: size가 100으로 조정되어 요청됨
    assertThat(request.size()).isEqualTo(100);
    assertThat(result.getContent()).hasSize(1);
  }

  // ========== Helper Methods ==========

  private DonationDlq createSampleDlq(
      Long id, String requestId, String eventType, String payload, String failureReason) {
    DonationOutbox outbox = DonationOutbox.create(requestId, eventType, payload);
    ReflectionTestUtils.setField(outbox, "id", 1L);

    DonationDlq dlq = DonationDlq.from(outbox, failureReason);
    ReflectionTestUtils.setField(dlq, "id", id);

    return dlq;
  }
}
