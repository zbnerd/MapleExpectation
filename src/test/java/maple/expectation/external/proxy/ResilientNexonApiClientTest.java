package maple.expectation.external.proxy;

import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.ExternalServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest
class ResilientNexonApiClientTest {

    @Autowired
    private ResilientNexonApiClient resilientNexonApiClient;

    @MockitoBean(name = "nexonApiCachingProxy")
    private NexonApiCachingProxy delegate;

    @Test
    @DisplayName("성공 시나리오: 외부 API가 정상 응답하면 결과값을 그대로 반환한다")
    void successDelegationTest() {
        String characterName = "메이플고수";
        CharacterOcidResponse expectedResponse = new CharacterOcidResponse("ocid-123");
        given(delegate.getOcidByCharacterName(characterName)).willReturn(expectedResponse);

        CharacterOcidResponse result = resilientNexonApiClient.getOcidByCharacterName(characterName);

        assertThat(result.getOcid()).isEqualTo("ocid-123");
    }

    @Test
    @DisplayName("재시도 시나리오: API 호출 실패 시 설정에 따라 3번 재시도(Retry)를 수행한다")
    void retryLogicTest() {
        // [Given]
        // 💡 RuntimeException 대신 recordExceptions에 등록된 ExternalServiceException 발동
        String characterName = "네트워크불안정";
        given(delegate.getOcidByCharacterName(characterName))
                .willThrow(new ExternalServiceException("Nexon API Connection Failed"));

        // [When & Then]
        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName(characterName))
                .isInstanceOf(ExternalServiceException.class);

        // Retry 3번 호출 확인
        verify(delegate, times(3)).getOcidByCharacterName(characterName);
    }

    @Test
    @DisplayName("Fallback 시나리오 [Scenario A]: API 실패 시 캐시가 있으면 캐시를 반환한다")
    void fallbackScenarioA_Test() {
        // [Given]
        String ocid = "cache-exists-ocid";
        EquipmentResponse cachedResponse = new EquipmentResponse();
        cachedResponse.setCharacterClass("Hero");

        // 💡 비동기 실패 시에도 ExternalServiceException을 담아서 반환
        given(delegate.getItemDataByOcid(ocid))
                .willReturn(CompletableFuture.failedFuture(new ExternalServiceException("Nexon API Error")));

        // Scenario A 상황: 만료된 캐시가 존재함
        given(delegate.getExpiredCache(ocid)).willReturn(cachedResponse);

        // [When]
        CompletableFuture<EquipmentResponse> result = resilientNexonApiClient.getItemDataByOcid(ocid);

        // [Then]
        assertThat(result.join().getCharacterClass()).isEqualTo("Hero");
    }

    @Test
    @DisplayName("Fallback 시나리오 [Scenario B]: 장애가 지속되고 캐시도 없으면 예외를 반환한다")
    void fallbackScenarioB_Test() {
        // [Given]
        String ocid = "no-cache-ocid";
        // 💡 ExternalServiceException 발동
        given(delegate.getItemDataByOcid(ocid))
                .willReturn(CompletableFuture.failedFuture(new ExternalServiceException("Nexon API Down")));

        // Scenario B 상황: 캐시도 없음
        given(delegate.getExpiredCache(ocid)).willReturn(null);

        // [When & Then]
        assertThatThrownBy(() -> resilientNexonApiClient.getItemDataByOcid(ocid).join())
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(ExternalServiceException.class);
    }

    @Test
    @DisplayName("서킷 브레이커 테스트: 연속 실패 시 호출을 차단해야 한다")
    void circuitBreakerOpenTest() {
        // [Given]
        // 💡 서킷 브레이커를 Open 시키기 위해 ExternalServiceException 발생 설정
        given(delegate.getOcidByCharacterName(anyString()))
                .willThrow(new ExternalServiceException("Critical Nexon API Error"));

        // [When] 서킷을 열기 위해 충분히 호출 (yml 설정된 slidingWindowSize 이상 호출)
        for (int i = 0; i < 20; i++) {
            try {
                resilientNexonApiClient.getOcidByCharacterName("테스트캐릭터");
            } catch (Exception ignored) {}
        }

        // [Then] 서킷이 OPEN 되었으므로 마지막 요청도 예외가 발생해야 함
        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName("마지막요청"))
                .isInstanceOf(ExternalServiceException.class);

        // 중요: 서킷이 작동했다면 실제 delegate(Mock) 호출 횟수는 시도 횟수보다 훨씬 적어야 함
        verify(delegate, atMost(30)).getOcidByCharacterName(anyString());
    }
}