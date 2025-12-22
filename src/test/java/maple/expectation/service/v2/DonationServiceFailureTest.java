package maple.expectation.service.v2;

import maple.expectation.global.error.exception.CriticalTransactionFailureException;
import maple.expectation.repository.v2.DonationHistoryRepository;
import maple.expectation.repository.v2.MemberRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class) // 스프링을 띄우지 않고 가볍게 테스트
class DonationServiceFailureTest {

    @Mock // 가짜 객체 생성
    MemberRepository memberRepository;
    @Mock
    DonationHistoryRepository donationHistoryRepository;
    @Mock
    DiscordAlertService discordAlertService;

    @InjectMocks // 가짜 객체들을 주입받는 진짜 서비스
    DonationService donationService;

    @Test
    @DisplayName("치명적인 시스템 예외 발생 시, 디스코드 알림을 발송하고 커스텀 예외를 던진다.")
    void criticalErrorAlertTest() {
        // 1. Given (상황 설정)
        String guestUuid = "guest-123";
        Long developerId = 999L;
        Long amount = 1000L;
        String requestId = "req-123";

        // 멱등성 검사는 통과했다고 가정
        given(donationHistoryRepository.existsByRequestId(requestId)).willReturn(false);

        // 💥 강제로 DB 에러 발생시키기 (예: RuntimeException)
        given(memberRepository.decreasePoint(guestUuid, amount))
                .willThrow(new RuntimeException("DB Connection Refused"));

        // 2. When & Then (검증)
        // 우리가 만든 CriticalTransactionFailureException이 터지는지 확인
        assertThatThrownBy(() -> 
            donationService.sendCoffee(guestUuid, developerId, amount, requestId)
        )
        .isInstanceOf(CriticalTransactionFailureException.class)
        .hasMessageContaining("도네이션 시스템 오류 발생");

        // ✅ 핵심: DiscordAlertService의 sendCriticalAlert 메서드가 호출되었는지 감시(Verify)
        verify(discordAlertService, times(1))
                .sendCriticalAlert(any(), any(), any());
    }
}