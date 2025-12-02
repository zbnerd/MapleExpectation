package maple.expectation.external;

import lombok.RequiredArgsConstructor;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class MaplestoryApiClient {

    private final WebClient mapleWebClient;

    @Value("${nexon.api.key}")
    private String apiKey;

    public CharacterOcidResponse getOcidByCharacterName(String characterName) {
        return mapleWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/maplestory/v1/id")
                        .queryParam("character_name", characterName)
                        .build())
                .header("x-nxopen-api-key", apiKey)
                .retrieve()
                .bodyToMono(CharacterOcidResponse.class)
                .block();
    }

    public EquipmentResponse getItemDataByOcid(String ocid) {
        return mapleWebClient.get()
                .uri(urlBuilder -> urlBuilder
                        .path("/maplestory/v1/character/item-equipment")
                        .queryParam("ocid", ocid)
                        .build())
                .header("x-nxopen-api-key", apiKey)
                .retrieve()
                .bodyToMono(EquipmentResponse.class)
                .block();
    }

}
