package maple.expectation.service.v2;

import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.service.v2.donation.event.DonationProcessor;
import maple.expectation.service.v2.donation.listener.DonationFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow; // 💡 void 메서드 에러 발생용
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class DonationServiceFailureTest {

    @Mock
    DonationHistoryRepository donationHistoryRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    DonationProcessor donationProcessor;

    @InjectMocks
    DonationService donationService;

    @Test
    @DisplayName("치명적인 시스템 예외 발생 시, 실패 이벤트를 발행하고 커스텀 예외를 던진다.")
    void criticalErrorAlertTest() {
        // 1. Given
        String guestUuid = "guest-123";
        Long developerId = 999L;
        Long amount = 1000L;
        String requestId = "req-123";

        given(donationHistoryRepository.existsByRequestId(requestId)).willReturn(false);

        willThrow(new RuntimeException("DB Connection Refused"))
                .given(donationProcessor).executeTransfer(anyString(), anyLong(), anyLong());

        // 2. When & Then
        assertThatThrownBy(() ->
                donationService.sendCoffee(guestUuid, developerId, amount, requestId)
        )
                .isInstanceOf(CriticalTransactionFailureException.class);

        // 3. 검증: 이벤트가 정말 발행되었는가?
        verify(eventPublisher, times(1)).publishEvent(any(DonationFailedEvent.class));
    }
}