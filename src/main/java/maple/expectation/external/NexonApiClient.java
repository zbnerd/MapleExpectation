package maple.expectation.external;

import maple.expectation.aop.annotation.NexonDataCache;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import java.util.concurrent.CompletableFuture;

public interface NexonApiClient {
    CharacterOcidResponse getOcidByCharacterName(String characterName);

    @NexonDataCache
    CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid);
}