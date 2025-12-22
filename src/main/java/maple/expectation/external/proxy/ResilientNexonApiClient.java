package maple.expectation.external.proxy;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * [Resilience Boundary] 
 * 외부 API 의존성에 대한 SLA 정책(Timeout, Fail-fast, Fallback)을 관리하는 프록시입니다.
 * 서비스 레이어는 이 클래스를 통해 외부 시스템과 통신하며 장애 격리(Bulkhead) 효과를 얻습니다.
 */
@Slf4j
@Primary // 서비스에서 주입 시 최우선순위
@Component
public class ResilientNexonApiClient implements NexonApiClient {

    private final NexonApiClient delegate;

    // 캐시 프록시(NexonApiCachingProxy)를 주입받아 Resilience 정책을 입힙니다.
    public ResilientNexonApiClient(@Qualifier("nexonApiCachingProxy") NexonApiClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public CharacterOcidResponse getOcidByCharacterName(String characterName) {
        log.debug("[Resilience] 외부 OCID 조회 경계 진입: {}", characterName);
        
        // TODO (PR #2): 명시적 Timeout(Fail-fast) 적용 예정
        // TODO (PR #3): Micrometer 메트릭(Observability) 측정 예정
        try {
            return delegate.getOcidByCharacterName(characterName);
        } catch (Exception e) {
            log.error("[Resilience] 외부 호출 실패 - Fallback 전략 검토 필요: {}", characterName);
            throw e;
        }
    }

    @Override
    public EquipmentResponse getItemDataByOcid(String ocid) {
        log.debug("[Resilience] 외부 장비 데이터 조회 경계 진입: {}", ocid);
        
        // TODO (PR #2): Circuit Breaker 도입 예정
        return delegate.getItemDataByOcid(ocid);
    }
}