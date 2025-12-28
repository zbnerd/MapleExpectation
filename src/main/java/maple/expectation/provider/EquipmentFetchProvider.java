package maple.expectation.provider;

import lombok.RequiredArgsConstructor;
import maple.expectation.aop.annotation.NexonDataCache;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.EquipmentResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class EquipmentFetchProvider {

    private final NexonApiClient nexonApiClient;

    @NexonDataCache
    @Cacheable(value = "equipment", key = "#ocid")
    public EquipmentResponse fetchWithCache(String ocid) {
        // 비동기 결과를 동기로 전환해서 캐시에 저장 (Spring 6의 호환성 문제 해결)
        return nexonApiClient.getItemDataByOcid(ocid).join();
    }
}