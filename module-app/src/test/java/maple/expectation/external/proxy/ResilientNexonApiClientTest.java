package maple.expectation.external.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.external.impl.ResilientNexonApiClient;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("장애 복원력이 적용된 Nexon API 클라이언트 테스트")
class ResilientNexonApiClientTest extends IntegrationTestSupport {

  @Autowired private ResilientNexonApiClient resilientNexonApiClient;
  @Autowired private ObjectMapper objectMapper;

  // ✅ CLAUDE.md Section 24: Circuit Breaker/Retry 상태 초기화를 위한 Registry 주입
  @Autowired(required = false)
  private CircuitBreakerRegistry circuitBreakerRegistry;

  @Autowired(required = false)
  private RetryRegistry retryRegistry;

  // 💡 equipmentRepository를 Mock으로 오버라이드하여 stubbing 가능하게 함
  @org.springframework.boot.test.mock.mockito.MockBean
  private maple.expectation.repository.v2.CharacterEquipmentRepository equipmentRepository;

  // 💡 realNexonApiClient Mock for testing resilience patterns
  @org.springframework.boot.test.mock.mockito.MockBean(name = "realNexonApiClient")
  private maple.expectation.external.NexonApiClient nexonApiClient;

  /**
   * CLAUDE.md Section 24: 공유 상태 해결 - 테스트 간 Resilience4j 상태 격리 Circuit Breaker와 Retry 상태를 각 테스트 전에
   * 초기화
   */
  @BeforeEach
  void resetResilience4jState() {
    // Circuit Breaker 상태 초기화 (이전 테스트에서 OPEN 상태 방지)
    if (circuitBreakerRegistry != null) {
      circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // Note: Retry는 CircuitBreaker와 달리 호출 간 지속되는 상태가 없으므로
    // reset()이 불필요합니다. (Resilience4j Retry는 상태 비저장 컴포넌트)

    // Mock 상태 초기화
    reset(nexonApiClient);
  }

  @Test
  @DisplayName("성공 시나리오: 결과값을 그대로 반환")
  void successDelegationTest() {
    String name = "메이플고수";
    // Issue #195: CompletableFuture 반환으로 변경
    given(nexonApiClient.getOcidByCharacterName(name))
        .willReturn(CompletableFuture.completedFuture(new CharacterOcidResponse("ocid-123")));

    assertThat(resilientNexonApiClient.getOcidByCharacterName(name).join().getOcid())
        .isEqualTo("ocid-123");
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

    // Issue #202: Awaitility로 Retry 완료 대기 (비동기 전환 대비 + 타이밍 안정성)
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> verify(nexonApiClient, times(3)).getOcidByCharacterName(name));
  }

  @Test
  @DisplayName("Fallback: API 실패 시 DB 캐시 반환")
  void fallbackScenarioA_Test() throws Exception {
    String ocid = "cache-exists-ocid";
    String json = objectMapper.writeValueAsString(new EquipmentResponse());

    given(nexonApiClient.getItemDataByOcid(ocid))
        .willReturn(CompletableFuture.failedFuture(new ExternalServiceException("Err")));
    given(equipmentRepository.findById(ocid))
        .willReturn(
            Optional.of(
                maple.expectation.domain.v2.CharacterEquipment.builder()
                    .ocid(ocid)
                    .jsonContent(json)
                    .build()));

    assertThat(resilientNexonApiClient.getItemDataByOcid(ocid).join()).isNotNull();
  }
}
