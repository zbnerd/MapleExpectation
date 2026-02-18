package maple.expectation.infrastructure.external;

import java.util.concurrent.CompletableFuture;
import maple.expectation.infrastructure.external.dto.v2.CharacterBasicResponse;
import maple.expectation.infrastructure.external.dto.v2.CharacterOcidResponse;
import maple.expectation.infrastructure.external.dto.v2.CubeHistoryResponse;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Mock Nexon API Client for chaos/nightmare tests.
 *
 * <p>Provides a no-op implementation of NexonApiClient for chaos profile testing. This allows
 * Spring context to load successfully without requiring real API calls.
 *
 * <p>N19 tests use @MockitoBean for more control, but other chaos tests need a bean.
 */
@Profile("chaos")
@Component("nexonApiClient")
public class MockNexonApiClient implements NexonApiClient {

  @Override
  public CompletableFuture<CharacterOcidResponse> getOcidByCharacterName(String characterName) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("MockNexonApiClient: Not implemented in chaos profile"));
  }

  @Override
  public CompletableFuture<CharacterBasicResponse> getCharacterBasic(String ocid) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("MockNexonApiClient: Not implemented in chaos profile"));
  }

  @Override
  public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("MockNexonApiClient: Not implemented in chaos profile"));
  }

  @Override
  public CompletableFuture<CubeHistoryResponse> getCubeHistory(String ocid) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("MockNexonApiClient: Not implemented in chaos profile"));
  }
}
