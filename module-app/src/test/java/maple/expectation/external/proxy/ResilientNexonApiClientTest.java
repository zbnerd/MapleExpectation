package maple.expectation.external.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.concurrent.CompletableFuture;
import maple.expectation.error.exception.ExternalServiceException;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.impl.ResilientNexonApiClient;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ResilientNexonApiClient 통합 테스트
 *
 * <p>Resilience4j 서킷브레이커와 재시도 패턴을 검증합니다.
 */
@DisplayName("장애 복원력이 적용된 Nexon API 클라이언트 테스트")
class ResilientNexonApiClientTest extends IntegrationTestSupport {

  @Autowired private ResilientNexonApiClient resilientNexonApiClient;

  @Autowired private ObjectMapper objectMapper;

  @Autowired(required = false)
  private CircuitBreakerRegistry circuitBreakerRegistry;

  @Autowired(required = false)
  private RetryRegistry retryRegistry;

  @org.springframework.boot.test.mock.mockito.MockBean(name = "realNexonApiClient")
  private maple.expectation.external.NexonApiClient nexonApiClient;

  @BeforeEach
  void resetResilience4jState() {
    // Circuit Breaker 상태 초기화 (이전 테스트에서 OPEN 상태 방지)
    if (circuitBreakerRegistry != null) {
      circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // Mock 상태 초기화
    org.mockito.Mockito.reset(nexonApiClient);
  }

  @Test
  @DisplayName("성공 시나리오: 결과값을 그대로 반환")
  void successDelegationTest() {
    String name = "메이플고수";
    CharacterOcidResponse expectedResponse = new CharacterOcidResponse("ocid-123");
    BDDMockito.given(nexonApiClient.getOcidByCharacterName(name))
        .willReturn(CompletableFuture.completedFuture(expectedResponse));

    assertThat(resilientNexonApiClient.getOcidByCharacterName(name).join()).isEqualTo(expectedResponse);
  }

  @Test
  @DisplayName("재시도 시나리오: 외부 서비스 예외 시 재시도 후 실패")
  void retryLogicTest() {
    String name = "네트워크불안정";
    BDDMockito.given(nexonApiClient.getOcidByCharacterName(name))
        .willReturn(CompletableFuture.failedFuture(new ExternalServiceException("Error")));

    assertThatThrownBy(() -> resilientNexonApiClient.getOcidByCharacterName(name).join())
        .hasCauseInstanceOf(ExternalServiceException.class);
  }
}
