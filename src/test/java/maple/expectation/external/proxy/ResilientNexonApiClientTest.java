package maple.expectation.external.proxy;

import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
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
    private NexonApiClient cachingProxyMock;

    @Test
    @DisplayName("성공 시나리오: 캐시 프록시가 응답하면 그대로 반환한다")
    void successDelegationTest() {
        // given
        String characterName = "메이플고수";
        CharacterOcidResponse expectedResponse = new CharacterOcidResponse();

        // Mock 설정
        given(cachingProxyMock.getOcidByCharacterName(characterName)).willReturn(expectedResponse);

        // when
        CharacterOcidResponse result = resilientNexonApiClient.getOcidByCharacterName(characterName);

        // then
        assertThat(result).isEqualTo(expectedResponse);
        verify(cachingProxyMock, times(1)).getOcidByCharacterName(characterName);
    }

    @Test
    @DisplayName("실패 시나리오: 캐시 프록시 호출 실패 시 예외를 던지고 로그를 남긴다")
    void retryOnFailureTest() {
        // given
        String characterName = "에러발생캐릭";

        // Mock이 예외를 던지도록 설정
        given(cachingProxyMock.getOcidByCharacterName(characterName))
                .willThrow(new RuntimeException("API 서버 연결 실패"));

        // when & then
        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName(characterName))
                .isInstanceOf(RuntimeException.class);

        verify(cachingProxyMock, times(1)).getOcidByCharacterName(characterName);
    }

    @Test
    @DisplayName("성공 시나리오: OCID로 장비 정보 조회 시 응답 객체를 그대로 반환한다")
    void getItemDataSuccessTest() {
        // given
        String ocid = "test-ocid-123";
        EquipmentResponse expectedResponse = new EquipmentResponse();
        // 필요 시 response 내부 필드 세팅

        given(cachingProxyMock.getItemDataByOcid(ocid)).willReturn(expectedResponse);

        // when
        EquipmentResponse result = resilientNexonApiClient.getItemDataByOcid(ocid);

        // then
        assertThat(result).isEqualTo(expectedResponse);
        verify(cachingProxyMock, times(1)).getItemDataByOcid(ocid);
    }

    @Test
    @DisplayName("실패 시나리오: 장비 정보 조회 실패 시 예외가 발생한다")
    void getItemDataFailureTest() {
        // given
        String ocid = "error-ocid";
        given(cachingProxyMock.getItemDataByOcid(ocid))
                .willThrow(new RuntimeException("넥슨 API 서버 점검 중"));

        // when & then
        assertThatThrownBy(() -> resilientNexonApiClient.getItemDataByOcid(ocid))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("넥슨 API 서버 점검 중");

        verify(cachingProxyMock, times(1)).getItemDataByOcid(ocid);
    }
}