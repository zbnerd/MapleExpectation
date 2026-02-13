package maple.expectation.external.impl;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.TimeoutProperties;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterBasicResponse;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.CubeHistoryResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Component("realNexonApiClient")
@RequiredArgsConstructor
public class RealNexonApiClient implements NexonApiClient {

  @org.springframework.beans.factory.annotation.Qualifier("mapleWebClient") private final WebClient mapleWebClient;

  private final TimeoutProperties timeoutProperties;

  @Value("${nexon.api.key}")
  private String apiKey;

  /**
   * 캐릭터 이름으로 OCID 조회 (비동기)
   *
   * <p>Issue #195: .block() → .toFuture() 전환으로 Reactor 체인 내 블로킹 제거
   *
   * <p>Issue #196: timeout + onErrorResume 패턴으로 에러 본문 로깅
   */
  @Override
  public CompletableFuture<CharacterOcidResponse> getOcidByCharacterName(String characterName) {
    log.info("[NexonApi] OCID lookup: characterName={}", characterName);
    return mapleWebClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/maplestory/v1/id")
                    .queryParam("character_name", characterName)
                    .build())
        .header("x-nxopen-api-key", apiKey)
        .retrieve()
        .bodyToMono(CharacterOcidResponse.class)
        .onErrorResume(
            WebClientResponseException.class,
            ex -> {
              if (ex.getStatusCode().is4xxClientError()) {
                // Issue #196: 상태 코드 + 실제 에러 메시지 로깅 (디버깅 가시성)
                log.warn(
                    "[NexonApi] OCID lookup failed. Status: {}, Body: {}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString());
                return Mono.error(new CharacterNotFoundException(characterName));
              }
              // 5xx: 서킷브레이커 동작을 위해 상위 전파
              return Mono.error(ex);
            })
        .timeout(timeoutProperties.getApiCall())
        .toFuture();
  }

  /**
   * OCID로 캐릭터 기본 정보 조회 (비동기)
   *
   * <p>Nexon API /maplestory/v1/character/basic 호출
   */
  @Override
  public CompletableFuture<CharacterBasicResponse> getCharacterBasic(String ocid) {
    log.info("[NexonApi] Character basic info request: ocid={}", ocid);
    return mapleWebClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder.path("/maplestory/v1/character/basic").queryParam("ocid", ocid).build())
        .header("x-nxopen-api-key", apiKey)
        .retrieve()
        .bodyToMono(CharacterBasicResponse.class)
        .timeout(timeoutProperties.getApiCall())
        .toFuture();
  }

  @Override
  public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
    log.info("[NexonApi] Equipment data request (Cache Miss): ocid={}", ocid);
    return mapleWebClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/maplestory/v1/character/item-equipment")
                    .queryParam("ocid", ocid)
                    .build())
        .header("x-nxopen-api-key", apiKey)
        .retrieve()
        .bodyToMono(EquipmentResponse.class)
        .timeout(timeoutProperties.getApiCall())
        .toFuture();
  }

  /**
   * OCID로 큐브 사용 내역 조회 (비동기)
   *
   * <p>Nexon API /maplestory/v1/history/cube 호출
   */
  @Override
  public CompletableFuture<CubeHistoryResponse> getCubeHistory(String ocid) {
    log.info("[NexonApi] Cube history request: ocid={}", ocid);
    return mapleWebClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder.path("/maplestory/v1/history/cube").queryParam("ocid", ocid).build())
        .header("x-nxopen-api-key", apiKey)
        .retrieve()
        .bodyToMono(CubeHistoryResponse.class)
        .timeout(timeoutProperties.getApiCall())
        .toFuture();
  }
}
