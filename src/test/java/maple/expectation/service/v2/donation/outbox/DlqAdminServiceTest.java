package maple.expectation.service.v2.donation.outbox;

import maple.expectation.controller.dto.dlq.DlqDetailResponse;
import maple.expectation.controller.dto.dlq.DlqEntryResponse;
import maple.expectation.controller.dto.dlq.DlqReprocessResult;
import maple.expectation.domain.v2.DonationDlq;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DlqNotFoundException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.repository.v2.DonationDlqRepository;
import maple.expectation.repository.v2.DonationOutboxRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * DlqAdminService 단위 테스트
 *
 * <h3>테스트 케이스</h3>
 * <ul>
 *   <li>DLQ 목록 조회 (페이징)</li>
 *   <li>DLQ 상세 조회 (성공/실패)</li>
 *   <li>DLQ 재처리 (성공/중복/실패)</li>
 *   <li>DLQ 폐기 (성공/실패)</li>
 *   <li>DLQ 총 건수 조회</li>
 * </ul>
 *
 * <p>CLAUDE.md Section 24 준수: @Execution(SAME_THREAD)로 병렬 실행 충돌 방지</p>
 * <p>LENIENT 모드: Mock 공유 시 UnnecessaryStubbingException 방지</p>
 * <p>Note: @Nested 구조 제거 - MockitoExtension과의 mock 공유 이슈 방지</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Execution(ExecutionMode.SAME_THREAD)
class DlqAdminServiceTest {

    @Mock DonationDlqRepository dlqRepository;
    @Mock DonationOutboxRepository outboxRepository;
    @Mock OutboxMetrics metrics;
    @Mock LogicExecutor executor;

    DlqAdminService dlqAdminService;

    private DonationDlq sampleDlq;

    @BeforeEach
    void setUp() throws Exception {
        // DlqAdminService 수동 생성 (Mock 주입)
        dlqAdminService = new DlqAdminService(dlqRepository, outboxRepository, metrics, executor);

        // LogicExecutor Mock - 람다 실제 실행 (Passthrough)
        when(executor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
                .thenAnswer(invocation -> {
                    ThrowingSupplier<?> task = invocation.getArgument(0);
                    return task.get();
                });

        doAnswer(invocation -> {
            ThrowingRunnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

        // Sample DLQ 생성
        sampleDlq = createSampleDlq(1L, "req-001", "DONATION_COMPLETED",
                "{\"amount\":1000}", "Max retry exceeded");
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
        assertThat(result.getContent().get(0).requestId()).isEqualTo("req-001");
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
        assertThat(result.requestId()).isEqualTo("req-001");
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
        when(outboxRepository.save(any(DonationOutbox.class))).thenAnswer(invocation -> {
            DonationOutbox outbox = invocation.getArgument(0);
            ReflectionTestUtils.setField(outbox, "id", 100L);
            return outbox;
        });
        when(outboxRepository.findByRequestId(anyString())).thenReturn(
                Optional.of(DonationOutbox.create("req-001", "DONATION_COMPLETED", "{\"amount\":1000}")));

        // When
        DlqReprocessResult result = dlqAdminService.reprocess(1L);

        // Then
        assertThat(result.dlqId()).isEqualTo(1L);
        assertThat(result.requestId()).isEqualTo("req-001");

        verify(dlqRepository).delete(sampleDlq);
        verify(metrics).incrementDlqReprocessed();
    }

    @Test
    @DisplayName("reprocess - 중복 requestId가 있으면 Outbox 생성을 스킵하고 DLQ만 삭제한다")
    void reprocessWithDuplicateRequestId() {
        // Given
        given(dlqRepository.findById(1L)).willReturn(Optional.of(sampleDlq));
        given(outboxRepository.existsByRequestId(anyString())).willReturn(true);
        when(outboxRepository.findByRequestId(anyString())).thenReturn(
                Optional.of(DonationOutbox.create("req-001", "DONATION_COMPLETED", "{}")));

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

    // ========== Helper Methods ==========

    private DonationDlq createSampleDlq(Long id, String requestId, String eventType,
                                         String payload, String failureReason) {
        DonationOutbox outbox = DonationOutbox.create(requestId, eventType, payload);
        ReflectionTestUtils.setField(outbox, "id", 1L);

        DonationDlq dlq = DonationDlq.from(outbox, failureReason);
        ReflectionTestUtils.setField(dlq, "id", id);

        return dlq;
    }
}
