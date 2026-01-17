package maple.expectation.external.proxy;

import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.ResilientNexonApiClient;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@DisplayName("장애 복원력이 적용된 Nexon API 클라이언트 테스트")
class ResilientNexonApiClientTest extends IntegrationTestSupport {

    @Autowired private ResilientNexonApiClient resilientNexonApiClient;
    @Autowired private ObjectMapper objectMapper;

    // 💡 equipmentRepository를 Mock으로 오버라이드하여 stubbing 가능하게 함
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private maple.expectation.repository.v2.CharacterEquipmentRepository equipmentRepository;

    // 💡 nexonApiClient는 부모(IntegrationTestSupport)에서 상속받은 Mock 사용

    @Test
    @DisplayName("성공 시나리오: 결과값을 그대로 반환")
    void successDelegationTest() {
        String name = "메이플고수";
        // Issue #195: CompletableFuture 반환으로 변경
        given(nexonApiClient.getOcidByCharacterName(name))
                .willReturn(CompletableFuture.completedFuture(new CharacterOcidResponse("ocid-123")));

        assertThat(resilientNexonApiClient.getOcidByCharacterName(name).join().getOcid()).isEqualTo("ocid-123");
    }

    @Test
    @DisplayName("재시도 시나리오: 실패 시 3번 재시도 수행")
    void retryLogicTest() {
        String name = "네트워크불안정";
        // Issue #195: CompletableFuture.failedFuture 반환으로 변경
        given(nexonApiClient.getOcidByCharacterName(name))
                .willReturn(CompletableFuture.failedFuture(new ExternalServiceException("Error")));

        assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName(name).join())
                .hasCauseInstanceOf(ExternalServiceException.class);

        verify(nexonApiClient, times(3)).getOcidByCharacterName(name);
    }

    @Test
    @DisplayName("Fallback: API 실패 시 DB 캐시 반환")
    void fallbackScenarioA_Test() throws Exception {
        String ocid = "cache-exists-ocid";
        String json = objectMapper.writeValueAsString(new EquipmentResponse());

        given(nexonApiClient.getItemDataByOcid(ocid)).willReturn(CompletableFuture.failedFuture(new ExternalServiceException("Err")));
        given(equipmentRepository.findById(ocid)).willReturn(Optional.of(maple.expectation.domain.v2.CharacterEquipment.builder().ocid(ocid).jsonContent(json).build()));

        assertThat(resilientNexonApiClient.getItemDataByOcid(ocid).join()).isNotNull();
    }
}