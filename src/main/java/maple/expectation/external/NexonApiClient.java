package maple.expectation.external;

import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import java.util.concurrent.CompletableFuture;

public interface NexonApiClient {
    CharacterOcidResponse getOcidByCharacterName(String characterName);
    CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid);
}