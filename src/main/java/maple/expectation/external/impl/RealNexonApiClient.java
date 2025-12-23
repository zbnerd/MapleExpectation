package maple.expectation.external.impl;

import lombok.RequiredArgsConstructor;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.concurrent.CompletableFuture;

@Component("realNexonApiClient")
@RequiredArgsConstructor
public class RealNexonApiClient implements NexonApiClient {

    private final WebClient mapleWebClient;

    @Value("${nexon.api.key}")
    private String apiKey;

    @Override
    public CharacterOcidResponse getOcidByCharacterName(String characterName) {
        return mapleWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/maplestory/v1/id").queryParam("character_name", characterName).build())
                .header("x-nxopen-api-key", apiKey)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, res -> Mono.error(new CharacterNotFoundException(characterName)))
                .bodyToMono(CharacterOcidResponse.class)
                .block();
    }

    @Override
    public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
        return mapleWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/maplestory/v1/character/item-equipment").queryParam("ocid", ocid).build())
                .header("x-nxopen-api-key", apiKey)
                .retrieve()
                .bodyToMono(EquipmentResponse.class)
                .toFuture();
    }
}