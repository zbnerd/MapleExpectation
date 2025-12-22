package maple.expectation.external.proxy;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Primary
@Component
public class ResilientNexonApiClient implements NexonApiClient {

    private final NexonApiClient delegate;
    private static final String NEXON_API = "nexonApi";

    public ResilientNexonApiClient(@Qualifier("nexonApiCachingProxy") NexonApiClient delegate) {
        this.delegate = delegate;
    }

    @Override
    @ObservedTransaction("external.api.nexon.ocid")
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getOcidFallback")
    public CharacterOcidResponse getOcidByCharacterName(String characterName) {
        log.debug("[External API] 넥슨 OCID 조회 요청: {}", characterName);
        return delegate.getOcidByCharacterName(characterName);
    }

    @Override
    @ObservedTransaction("external.api.nexon.equipment")
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getEquipmentFallback")
    public EquipmentResponse getItemDataByOcid(String ocid) {
        log.debug("[External API] 넥슨 장비 데이터 조회 요청: {}", ocid);
        return delegate.getItemDataByOcid(ocid);
    }

    // --- Fallback Methods ---

    public CharacterOcidResponse getOcidFallback(String characterName, Throwable t) {
        handleIgnoreMarker(t);
        log.error("[Resilience] 넥슨 서버 응답 없음. OCID 조회 실패 - 사유: {}, 대상: {}", t.getMessage(), characterName);
        throw new ExternalServiceException("넥슨 캐릭터 정보 조회 서비스");
    }

    public EquipmentResponse getEquipmentFallback(String ocid, Throwable t) {
        handleIgnoreMarker(t);
        log.error("[Resilience] 넥슨 서버 응답 없음. 장비 데이터 조회 실패 - 사유: {}, 대상: {}", t.getMessage(), ocid);
        throw new ExternalServiceException("넥슨 장비 데이터 조회 서비스");
    }

    private void handleIgnoreMarker(Throwable t) {
        if (t instanceof CircuitBreakerIgnoreMarker) {
            log.debug("[Resilience] 비즈니스 예외 통과: {}", t.getMessage());
            throw (RuntimeException) t;
        }
    }
}