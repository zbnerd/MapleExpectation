package maple.expectation.external.proxy;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.external.dto.v2.EquipmentResponse;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.util.GzipUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Primary
@Component("resilientNexonApiClient")
public class ResilientNexonApiClient implements NexonApiClient {

    private final NexonApiClient delegate;
    private final DiscordAlertService discordAlertService;
    private final CharacterEquipmentRepository equipmentRepository; // 💡 직접 주입
    private final ObjectMapper objectMapper; // 💡 직접 주입

    private static final String NEXON_API = "nexonApi";

    public ResilientNexonApiClient(
            @Qualifier("realNexonApiClient") NexonApiClient delegate, // 💡 real로 변경
            DiscordAlertService discordAlertService,
            CharacterEquipmentRepository equipmentRepository,
            ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.discordAlertService = discordAlertService;
        this.equipmentRepository = equipmentRepository;
        this.objectMapper = objectMapper;
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
    @CircuitBreaker(name = NEXON_API)
    @Retry(name = NEXON_API, fallbackMethod = "getItemDataFallback")
    public CompletableFuture<EquipmentResponse> getItemDataByOcid(String ocid) {
        return delegate.getItemDataByOcid(ocid);
    }

    // --- Fallback Methods ---

    public CharacterOcidResponse getOcidFallback(String name, Throwable t) {
        handleIgnoreMarker(t);
        log.error("🚩 [Resilience] OCID 최종 조회 실패. 사유: {}", t.getMessage());
        throw new ExternalServiceException("넥슨 캐릭터 정보 조회 서비스");
    }

    public CompletableFuture<EquipmentResponse> getItemDataFallback(String ocid, Throwable t) {
        handleIgnoreMarker(t);
        log.warn("🚩 [Resilience] 장애 대응 시나리오 가동. 사유: {}", t.getMessage());

        // 💡 프록시가 사라졌으므로 직접 DB에서 꺼내옵니다 (Scenario A)
        EquipmentResponse cachedData = equipmentRepository.findById(ocid)
                .map(this::convertToResponse)
                .orElse(null);

        if (cachedData != null) {
            log.warn("[Scenario A] 만료된 캐시 데이터 반환 (Degrade)");
            return CompletableFuture.completedFuture(cachedData);
        }

        log.error("[Scenario B] 캐시 부재. 알림 발송");
        discordAlertService.sendCriticalAlert("외부 API 장애", "OCID: " + ocid, new Exception(t));
        throw new ExternalServiceException("넥슨 API 서비스 불가");
    }

    private void handleIgnoreMarker(Throwable t) {
        if (t instanceof CircuitBreakerIgnoreMarker) {
            throw (RuntimeException) t;
        }
    }

    // 💡 Fallback에서 쓸 수 있도록 캐시 변환 로직 추가
    private EquipmentResponse convertToResponse(CharacterEquipment entity) {
        try {
            byte[] data = entity.getRawData();
            String json = (data.length > 2 && data[0] == (byte) 0x1F)
                    ? GzipUtils.decompress(data)
                    : new String(data, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, EquipmentResponse.class);
        } catch (Exception e) {
            return null;
        }
    }
}