package maple.expectation.external.impl;

import lombok.RequiredArgsConstructor;
import maple.expectation.exception.CharacterNotFoundException;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class RealNexonApiClient implements NexonApiClient {

    private final WebClient mapleWebClient;

    @Value("${nexon.api.key}")
    private String apiKey;

    @Override
    public CharacterOcidResponse getOcidByCharacterName(String characterName) {
        return mapleWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/maplestory/v1/id")
                        .queryParam("character_name", characterName)
                        .build())
                .header("x-nxopen-api-key", apiKey)
                .retrieve()
                // 4xx 에러 발생 시 처리
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                    // 넥슨 API가 캐릭터가 없을 때 400을 반환하므로 이를 잡아냄
                    if (clientResponse.statusCode().value() == 400) {
                        return Mono.error(new CharacterNotFoundException(characterName));
                    }
                    return clientResponse.createException().flatMap(Mono::error);
                })
                .bodyToMono(CharacterOcidResponse.class)
                .block();
    }

    @Override
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