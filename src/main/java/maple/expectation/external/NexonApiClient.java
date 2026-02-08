package maple.expectation.external;

import java.util.concurrent.CompletableFuture;
import maple.expectation.aop.annotation.NexonDataCache;
import maple.expectation.external.dto.v2.CharacterBasicResponse;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.CubeHistoryResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;

public interface NexonApiClient {
  /**
   * 캐릭터 이름으로 OCID 조회 (비동기)
   *
   * <p>Issue #195: .block() 제거 - Reactor 체인 내 블로킹 호출 anti-pattern 해결
   *
   * @param characterName 캐릭터 이름
   * @return OCID 응답 Future
   */
  CompletableFuture<CharacterOcidResponse> getOcidByCharacterName(String characterName);

  /**
   * OCID로 캐릭터 기본 정보 조회 (비동기)
   *
   * <p>Nexon API /maplestory/v1/character/basic 호출
   *
   * <p>world_name, character_class, character_image 등 반환
   *
   * @param ocid 캐릭터 고유 ID
   * @return 캐릭터 기본 정보 응답 Future
   */
  CompletableFuture<CharacterBasicResponse> getCharacterBasic(String ocid);

  @NexonDataCache
  CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid);

  /**
   * OCID로 큐브 사용 내역 조회 (비동기)
   *
   * <p>Nexon API /maplestory/v1/history/cube 호출
   *
   * <p>큐브 사용 결과, 잠재능력 등급, 옵션 값 등 반환
   *
   * @param ocid 캐릭터 고유 ID
   * @return 큐브 사용 내역 응답 Future
   */
  CompletableFuture<CubeHistoryResponse> getCubeHistory(String ocid);
}
