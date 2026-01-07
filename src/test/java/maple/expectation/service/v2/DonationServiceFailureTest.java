package maple.expectation.service.v2;

import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.service.v2.donation.event.DonationProcessor;
import maple.expectation.service.v2.donation.listener.DonationFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DonationServiceFailureTest {

    @Mock DonationHistoryRepository donationHistoryRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock DonationProcessor donationProcessor;
    @Mock LogicExecutor executor; // 🚀 잊지 말고 Mock 추가

    @InjectMocks
    DonationService donationService;

    @BeforeEach
    void setUp() {
        // 🚀 [핵심] LogicExecutor Mock이 내부 람다를 실제로 '호출'하게 만듭니다.
        // Ambiguous call 방지를 위해 정확한 타입을 지정합니다.
        lenient().when(executor.executeOrCatch(
                any(ThrowingSupplier.class),
                any(Function.class),
                any(TaskContext.class))
        ).thenAnswer(invocation -> {
            ThrowingSupplier<?> task = invocation.getArgument(0);
            Function<Throwable, ?> recovery = invocation.getArgument(1);
            try {
                return task.get(); // 1. 우선 정상 로직 실행 시도
            } catch (Throwable e) {
                return recovery.apply(e); // 2. 에러 나면 복구 로직 실행
            }
        });

        // saveHistory 등에서 사용하는 executeVoid도 대응
        lenient().doAnswer(invocation -> {
            ((maple.expectation.global.executor.function.ThrowingRunnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).executeVoid((ThrowingRunnable) any(), (TaskContext) any());
    }

    @Test
    @DisplayName("치명적인 시스템 예외 발생 시, 실패 이벤트를 발행하고 커스텀 예외를 던진다.")
    void criticalErrorAlertTest() {
        // 1. Given
        String guestUuid = "guest-123";
        String requestId = "req-123";

        given(donationHistoryRepository.existsByRequestId(requestId)).willReturn(false);

        // Processor에서 런타임 예외 발생 유도
        willThrow(new RuntimeException("DB Connection Refused"))
                .given(donationProcessor).executeTransfer(anyString(), anyLong(), anyLong());

        // 2. When & Then
        assertThatThrownBy(() ->
                donationService.sendCoffee(guestUuid, 999L, 1000L, requestId)
        ).isInstanceOf(CriticalTransactionFailureException.class);

        // 3. 검증: 이벤트 발행 확인
        verify(eventPublisher, times(1)).publishEvent(any(DonationFailedEvent.class));
    }
}