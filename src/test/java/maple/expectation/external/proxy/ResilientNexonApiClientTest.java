package maple.expectation.external.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.ResilientNexonApiClient;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest
class ResilientNexonApiClientTest {

    @Autowired
    private ResilientNexonApiClient resilientNexonApiClient;

    @MockitoBean(name = "realNexonApiClient")
    private NexonApiClient delegate;

    @MockitoBean
    private CharacterEquipmentRepository equipmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

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
        String characterName = "네트워크불안정";
        given(delegate.getOcidByCharacterName(characterName))
                .willThrow(new ExternalServiceException("Nexon API Connection Failed"));

        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName(characterName))
                .isInstanceOf(ExternalServiceException.class);

        verify(delegate, times(3)).getOcidByCharacterName(characterName);
    }

    @Test
    @DisplayName("Fallback 시나리오 [Scenario A]: API 실패 시 캐시가 있으면 캐시를 반환한다")
    void fallbackScenarioA_Test() throws Exception {
        // [Given]
        String ocid = "cache-exists-ocid";
        EquipmentResponse expectedResponse = new EquipmentResponse();
        expectedResponse.setCharacterClass("Hero");

        // 💡 리팩토링 포인트 1: byte[] 대신 JSON String으로 변환
        String jsonContent = objectMapper.writeValueAsString(expectedResponse);

        // 💡 리팩토링 포인트 2: 변경된 엔티티 구조(String 필드) 및 빌더 사용
        CharacterEquipment entity = CharacterEquipment.builder()
                .ocid(ocid)
                .jsonContent(jsonContent)
                .build();

        // 1. API 호출은 실패하도록 설정
        given(delegate.getItemDataByOcid(ocid))
                .willReturn(CompletableFuture.failedFuture(new ExternalServiceException("API Error")));

        // 2. 리포지토리가 캐시 엔티티를 반환하도록 Mocking
        given(equipmentRepository.findById(ocid)).willReturn(Optional.of(entity));

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

        // 1. API 실패
        given(delegate.getItemDataByOcid(ocid))
                .willReturn(CompletableFuture.failedFuture(new ExternalServiceException("Nexon API Down")));

        // 2. DB에도 데이터가 없음
        given(equipmentRepository.findById(ocid)).willReturn(Optional.empty());

        // [When & Then]
        assertThatThrownBy(() -> resilientNexonApiClient.getItemDataByOcid(ocid).join())
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(ExternalServiceException.class);
    }

    @Test
    @DisplayName("서킷 브레이커 테스트: 연속 실패 시 호출을 차단해야 한다")
    void circuitBreakerOpenTest() {
        given(delegate.getOcidByCharacterName(anyString()))
                .willThrow(new ExternalServiceException("Critical Nexon API Error"));

        for (int i = 0; i < 20; i++) {
            try {
                resilientNexonApiClient.getOcidByCharacterName("테스트캐릭터");
            } catch (Exception ignored) {}
        }

        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName("마지막요청"))
                .isInstanceOf(ExternalServiceException.class);

        verify(delegate, atMost(30)).getOcidByCharacterName(anyString());
    }
}