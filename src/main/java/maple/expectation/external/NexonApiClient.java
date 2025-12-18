package maple.expectation.external;

import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;

/**
 * 넥슨 오픈 API와의 통신을 위한 표준 인터페이스
 */
public interface NexonApiClient {
    CharacterOcidResponse getOcidByCharacterName(String characterName);
    EquipmentResponse getItemDataByOcid(String ocid);
}