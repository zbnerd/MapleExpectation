package maple.expectation.external.proxy;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Primary
@Component("resilientNexonApiClient")
public class ResilientNexonApiClient implements NexonApiClient {

    private final NexonApiClient delegate;
    private final DiscordAlertService discordAlertService;
    private static final String NEXON_API = "nexonApi";

    public ResilientNexonApiClient(@Qualifier("nexonApiCachingProxy") NexonApiClient delegate,
                                   DiscordAlertService discordAlertService) {
        this.delegate = delegate;
        this.discordAlertService = discordAlertService;
    }

    @Override
    @ObservedTransaction("external.api.nexon.ocid")
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getOcidFallback")
    public CharacterOcidResponse getOcidByCharacterName(String name) {
        return delegate.getOcidByCharacterName(name);
    }

    @Override
    @ObservedTransaction("external.api.nexon.itemdata")
    @TimeLimiter(name = NEXON_API)
    @CircuitBreaker(name = NEXON_API) // 💡 fallbackMethod 제거
    @Retry(name = NEXON_API, fallbackMethod = "getItemDataFallback")
    public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
        return delegate.getItemDataByOcid(ocid);
    }

    // --- Fallback Methods ---

    /**
     * [OCID 조회 전용 Fallback]
     */
    public CharacterOcidResponse getOcidFallback(String name, Throwable t) {
        handleIgnoreMarker(t);
        log.error("🚩 [Resilience] OCID 최종 조회 실패 (재시도 완료). 사유: {}", t.getMessage());
        throw new ExternalServiceException("넥슨 캐릭터 정보 조회 서비스");
    }

    /**
     * [장비 데이터 전용 Fallback]
     */
    public CompletableFuture<EquipmentResponse> getItemDataFallback(String ocid, Throwable t) {
        handleIgnoreMarker(t);
        log.warn("🚩 [Resilience] 장비 데이터 최종 장애 감지 (재시도 완료). 시나리오 판단 시작...");

        // 캐스팅을 통해 Scenario A 지원 메서드 호출
        EquipmentResponse cachedData = ((NexonApiCachingProxy) delegate).getExpiredCache(ocid);

        if (cachedData != null) {
            log.warn("[Scenario A] 만료된 캐시 데이터 반환 (Degrade)");
            return CompletableFuture.completedFuture(cachedData);
        }

        log.error("[Scenario B] 캐시 부재. 알림 발송 및 에러 반환");
        discordAlertService.sendCriticalAlert("외부 API 장애", "OCID: " + ocid, new Exception(t));
        throw new ExternalServiceException("넥슨 API 서비스 불가");
    }

    private void handleIgnoreMarker(Throwable t) {
        if (t instanceof CircuitBreakerIgnoreMarker) {
            throw (RuntimeException) t;
        }
    }
}