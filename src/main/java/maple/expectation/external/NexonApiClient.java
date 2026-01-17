package maple.expectation.external;

import maple.expectation.aop.annotation.NexonDataCache;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import java.util.concurrent.CompletableFuture;

public interface NexonApiClient {
    /**
     * 캐릭터 이름으로 OCID 조회 (비동기)
     *
     * <p>Issue #195: .block() 제거 - Reactor 체인 내 블로킹 호출 anti-pattern 해결</p>
     *
     * @param characterName 캐릭터 이름
     * @return OCID 응답 Future
     */
    CompletableFuture<CharacterOcidResponse> getOcidByCharacterName(String characterName);

    @NexonDataCache
    CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid);
}