package maple.expectation.external.proxy;

import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.ExternalServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest
class ResilientNexonApiClientTest {

    @Autowired
    private ResilientNexonApiClient resilientNexonApiClient;

    @MockitoBean(name = "nexonApiCachingProxy")
    private NexonApiClient delegate;

    @Test
    @DisplayName("성공 시나리오: 외부 API가 정상 응답하면 결과값을 그대로 반환한다")
    void successDelegationTest() {
        String characterName = "메이플고수";
        CharacterOcidResponse expectedResponse = new CharacterOcidResponse("ocid-123");
        given(delegate.getOcidByCharacterName(characterName)).willReturn(expectedResponse);

        CharacterOcidResponse result = resilientNexonApiClient.getOcidByCharacterName(characterName);

        assertThat(result.getOcid()).isEqualTo("ocid-123");
        verify(delegate, times(1)).getOcidByCharacterName(characterName);
    }

    @Test
    @DisplayName("재시도 시나리오: API 호출 실패 시 설정에 따라 3번 재시도(Retry)를 수행한다")
    void retryLogicTest() {
        String characterName = "네트워크불안정";
        given(delegate.getOcidByCharacterName(characterName))
                .willThrow(new RuntimeException("연결 실패"));

        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName(characterName))
                .isInstanceOf(ExternalServiceException.class);

        // Retry 3번 호출 확인
        verify(delegate, times(3)).getOcidByCharacterName(characterName);
    }

    @Test
    @DisplayName("Fallback 시나리오: 장애가 지속되면 정의된 ExternalServiceException(503)을 반환한다")
    void fallbackExceptionTest() {
        String ocid = "error-ocid";
        given(delegate.getItemDataByOcid(ocid)).willThrow(new RuntimeException("점검 중"));

        assertThatThrownBy(() -> resilientNexonApiClient.getItemDataByOcid(ocid))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    @DisplayName("서킷 브레이커 테스트: 연속 실패 시 호출을 차단해야 한다")
    void circuitBreakerOpenTest() {
        // given: 무조건 에러 발생
        given(delegate.getOcidByCharacterName(anyString()))
                .willThrow(new RuntimeException("장애 발생"));

        // when: 서킷을 열기 위해 충분히 호출 (재시도가 포함되므로 호출 횟수가 급증함)
        for (int i = 0; i < 20; i++) {
            try {
                resilientNexonApiClient.getOcidByCharacterName("테스트캐릭터");
            } catch (Exception ignored) {}
        }

        // then: 서킷이 OPEN 되었으므로 마지막 요청도 예외가 발생해야 함
        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName("마지막요청"))
                .isInstanceOf(ExternalServiceException.class);

        // 중요: 서킷이 작동했다면 실제 delegate(Mock) 호출 횟수는
        // 전체 시도 횟수(20 * 3 = 60회 이상)보다 훨씬 적어야 함
        verify(delegate, atMost(30)).getOcidByCharacterName(anyString());
    }
}