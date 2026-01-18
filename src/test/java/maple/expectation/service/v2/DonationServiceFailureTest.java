package maple.expectation.service.v2;

import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.AdminNotFoundException;
import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.DonationOutboxRepository;
import maple.expectation.service.v2.auth.AdminService;
import maple.expectation.service.v2.donation.event.DonationProcessor;
import maple.expectation.service.v2.donation.listener.DonationFailedEvent;
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
import org.springframework.context.ApplicationEventPublisher;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/**
 * DonationService 실패 시나리오 단위 테스트
 *
 * <p>치명적인 시스템 예외 발생 시 DonationFailedEvent가 발행되고
 * CriticalTransactionFailureException이 던져지는지 검증합니다.</p>
 *
 * <p>CLAUDE.md Section 24 준수: @Execution(SAME_THREAD)로 병렬 실행 충돌 방지</p>
 * <p>LENIENT 모드: Mock 공유 시 UnnecessaryStubbingException 방지</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Execution(ExecutionMode.SAME_THREAD)
class DonationServiceFailureTest {

    @Mock DonationHistoryRepository donationHistoryRepository;
    @Mock DonationOutboxRepository donationOutboxRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock DonationProcessor donationProcessor;
    @Mock AdminService adminService;
    @Mock LogicExecutor executor;

    DonationService donationService;

    private static final String VALID_ADMIN_FINGERPRINT = "test-admin-fingerprint";
    private static final String INVALID_ADMIN_FINGERPRINT = "invalid-fingerprint";

    @BeforeEach
    void setUp() {
        // 수동으로 서비스 생성 (Mock 주입)
        donationService = new DonationService(
                donationHistoryRepository,
                donationOutboxRepository,
                donationProcessor,
                adminService,
                eventPublisher,
                executor
        );

        // LogicExecutor Mock - 람다 실제 실행
        when(executor.executeOrCatch(
                any(ThrowingSupplier.class),
                any(Function.class),
                any(TaskContext.class))
        ).thenAnswer(invocation -> {
            ThrowingSupplier<?> task = invocation.getArgument(0);
            Function<Throwable, ?> recovery = invocation.getArgument(1);
            try {
                return task.get();
            } catch (Throwable e) {
                return recovery.apply(e);
            }
        });

        doAnswer(invocation -> {
            ((ThrowingRunnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));
    }

    @Test
    @DisplayName("치명적인 시스템 예외 발생 시, 실패 이벤트를 발행하고 커스텀 예외를 던진다.")
    void criticalErrorAlertTest() {
        // 1. Given
        String guestUuid = "guest-123";
        String requestId = "req-123";

        given(adminService.isAdmin(VALID_ADMIN_FINGERPRINT)).willReturn(true);
        given(donationHistoryRepository.existsByRequestId(requestId)).willReturn(false);

        // Processor에서 런타임 예외 발생 유도
        willThrow(new RuntimeException("DB Connection Refused"))
                .given(donationProcessor).executeTransferToAdmin(anyString(), anyString(), anyLong());

        // 2. When & Then
        assertThatThrownBy(() ->
                donationService.sendCoffee(guestUuid, VALID_ADMIN_FINGERPRINT, 1000L, requestId)
        ).isInstanceOf(CriticalTransactionFailureException.class);

        // 3. 검증: 이벤트 발행 확인
        verify(eventPublisher, times(1)).publishEvent(any(DonationFailedEvent.class));
    }

    @Test
    @DisplayName("유효하지 않은 Admin fingerprint로 요청 시 AdminNotFoundException이 발생한다.")
    void invalidAdminFingerprintTest() {
        // 1. Given
        String guestUuid = "guest-123";
        String requestId = "req-456";

        given(adminService.isAdmin(INVALID_ADMIN_FINGERPRINT)).willReturn(false);

        // 2. When & Then
        assertThatThrownBy(() ->
                donationService.sendCoffee(guestUuid, INVALID_ADMIN_FINGERPRINT, 1000L, requestId)
        ).isInstanceOf(AdminNotFoundException.class);

        // 3. 검증: Processor가 호출되지 않음
        verify(donationProcessor, never()).executeTransferToAdmin(anyString(), anyString(), anyLong());
    }
}
